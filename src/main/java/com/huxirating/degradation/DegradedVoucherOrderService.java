package com.huxirating.degradation;

import com.huxirating.dto.Result;
import com.huxirating.entity.VoucherOrder;
import com.huxirating.service.IVoucherOrderService;
import com.huxirating.utils.UserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * 降级模式订单服务（DB 直写 + 本地缓存）
 * <p>
 * 当 Redis 不可用时，使用此服务处理秒杀订单：
 * - 库存查询：改查 MySQL（带本地缓存）
 * - 一人一单：改查 MySQL（带本地缓存）
 * - ID 生成：改用本地 Snowflake 算法
 * - 乐观锁扣库存 + 同步创建订单
 *
 * @author Nisson
 */
@Service
public class DegradedVoucherOrderService {
    private static final Logger log = LoggerFactory.getLogger(DegradedVoucherOrderService.class);

    @Resource
    private DegradationService degradationService;

    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 降级模式：处理秒杀订单
     * <p>
     * 流程：
     * 1. 生成订单 ID（Snowflake）
     * 2. 检查库存（DB + 本地缓存）
     * 3. 检查一人一单（DB + 本地缓存）
     * 4. 乐观锁扣库存
     * 5. 创建订单
     *
     * @param voucherId 优惠券 ID
     * @return 订单 ID 或错误信息
     */
    public Result handleSeckill(Long voucherId) {
        if (!degradationService.isDegraded()) {
            return Result.fail("系统正常，请使用常规秒杀流程");
        }

        Long userId = UserHolder.getUser().getId();

        try {
            // 1. 生成订单 ID（使用 Snowflake）
            Long orderId = degradationService.generateOrderId();

            // 2. 检查库存（带本地缓存）
            Integer stock = degradationService.getStockWithCache(voucherId);
            if (stock == null || stock <= 0) {
                log.warn("[降级模式] 库存不足: voucherId={}, stock={}", voucherId, stock);
                return Result.fail("库存不足");
            }

            // 3. 检查一人一单（带本地缓存）
            boolean hasPurchased = degradationService.hasUserPurchased(userId, voucherId);
            if (hasPurchased) {
                log.warn("[降级模式] 重复下单: userId={}, voucherId={}", userId, voucherId);
                return Result.fail("不能重复下单");
            }

            // 4. 乐观锁扣库存
            boolean deductSuccess = degradationService.deductStock(voucherId);
            if (!deductSuccess) {
                log.warn("[降级模式] 库存扣减失败（并发）: voucherId={}", voucherId);
                return Result.fail("库存不足");
            }

            // 5. 创建订单
            VoucherOrder order = degradationService.createOrder(orderId, userId, voucherId);

            log.info("[降级模式] 订单创建成功: orderId={}, userId={}, voucherId={}",
                    orderId, userId, voucherId);

            Map<String, Object> result = new HashMap<>(4);
            result.put("orderId", orderId.toString());
            result.put("status", 1); // 未支付
            result.put("message", "订单创建成功（降级模式）");

            return Result.ok(result);

        } catch (DuplicateKeyException e) {
            log.warn("[降级模式] 重复下单（唯一键冲突）: userId={}, voucherId={}", userId, voucherId);
            return Result.fail("不能重复下单");
        } catch (Exception e) {
            log.error("[降级模式] 订单处理异常: voucherId={}", voucherId, e);
            throw new RuntimeException("降级模式订单处理失败", e);
        }
    }

    /**
     * 获取降级状态信息
     */
    public Map<String, Object> getDegradationStatus() {
        DegradationService.DegradationStatus status = degradationService.getStatus();

        Map<String, Object> result = new HashMap<>();
        result.put("degraded", status.isDegraded());
        result.put("stockCacheSize", status.getStockCacheSize());
        result.put("purchaseCacheSize", status.getPurchaseCacheSize());
        result.put("stockDecrementLogSize", status.getStockDecrementLogSize());
        result.put("currentQpsLimit", status.getCurrentQpsLimit());
        result.put("message", status.isDegraded()
                ? "系统处于降级模式，使用 DB 直写 + 本地缓存"
                : "系统正常运行");

        return result;
    }
}
