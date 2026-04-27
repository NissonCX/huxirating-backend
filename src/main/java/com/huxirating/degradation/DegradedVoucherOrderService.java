package com.huxirating.degradation;

import com.huxirating.dto.Result;
import com.huxirating.entity.VoucherOrder;
import com.huxirating.service.IVoucherOrderService;
import com.huxirating.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * 降级模式订单服务（DB 直写）
 * <p>
 * 当 Redis 不可用时，使用此服务处理秒杀订单：
 * - 库存查询：改查 MySQL
 * - 一人一单：改查 MySQL
 * - ID 生成：改用本地 Snowflake 算法
 * - 乐观锁扣库存 + 同步创建订单
 *
 * @author Nisson
 */
@Slf4j
@Service
public class DegradedVoucherOrderService {

    @Resource
    @Lazy
    private DegradationService degradationService;

    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 降级模式：处理秒杀订单
     * <p>
     * 流程：
     * 1. 生成订单 ID（Snowflake）
     * 2. 检查库存（DB）
     * 3. 检查一人一单（DB）
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

            // 2. 快速预检库存（非事务内，仅做提前拦截）
            Integer stock = degradationService.getStock(voucherId);
            if (stock == null || stock <= 0) {
                log.warn("[降级模式] 库存不足: voucherId={}, stock={}", voucherId, stock);
                return Result.fail("库存不足");
            }

            // 3. 事务内完成：一人一单检查 + 乐观锁扣库存 + 创建订单（原子操作）
            VoucherOrder order = createDegradedOrder(orderId, userId, voucherId);
            if (order == null) {
                // 一人一单校验失败或库存扣减失败
                return Result.fail("不能重复下单");
            }

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
            return Result.fail("系统繁忙，请稍后重试");
        }
    }

    /**
     * 降级模式：事务内原子完成一人一单检查 + 扣库存 + 创建订单。
     * 三步在同一事务内，任何一步失败则整体回滚，避免库存扣减但订单未创建的不一致。
     *
     * @return 订单对象；null 表示一人一单校验失败或库存扣减失败
     */
    @Transactional(rollbackFor = Exception.class)
    public VoucherOrder createDegradedOrder(Long orderId, Long userId, Long voucherId) {
        // 一人一单检查（事务内，与后续写入保证原子性）
        boolean hasPurchased = degradationService.hasUserPurchased(userId, voucherId);
        if (hasPurchased) {
            log.warn("[降级模式] 重复下单: userId={}, voucherId={}", userId, voucherId);
            return null;
        }

        // 乐观锁扣库存
        boolean deductSuccess = degradationService.deductStock(voucherId);
        if (!deductSuccess) {
            log.warn("[降级模式] 库存扣减失败（并发）: voucherId={}", voucherId);
            return null;
        }

        // 创建订单（DuplicateKeyException 会触发事务回滚，包括上面的库存扣减）
        return degradationService.createOrder(orderId, userId, voucherId);
    }

    /**
     * 获取降级状态信息
     */
    public Map<String, Object> getDegradationStatus() {
        DegradationService.DegradationStatus status = degradationService.getStatus();

        Map<String, Object> result = new HashMap<>();
        result.put("degraded", status.isDegraded());
        result.put("stockDecrementLogSize", status.getStockDecrementLogSize());
        result.put("purchaseRecordLogSize", status.getPurchaseRecordLogSize());
        result.put("currentQpsLimit", status.getCurrentQpsLimit());
        result.put("message", status.isDegraded()
                ? "系统处于降级模式，使用 DB 直写"
                : "系统正常运行");

        return result;
    }
}
