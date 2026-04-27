package com.huxirating.service;

import com.huxirating.dto.Result;
import com.huxirating.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author Nisson
 * @since 2025-10-01
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    /**
     * 查询订单状态（兼容异步处理中 / 已完成 / 已失败）
     * @param orderId 订单ID
     * @return 订单状态信息
     */
    Result queryOrderStatus(Long orderId);

    /**
     * 在单个数据库事务内完成：一人一单校验 + 扣减库存 + 写入订单。
     * <p>
     * 供 MQ 消费者调用。将三步 DB 操作放入同一事务，保证原子性：
     * - 若消费者在事务提交前宕机，MySQL 自动回滚，消息 requeue 后重试时
     *   查重 count=0，可安全再次执行，不会重复扣库存。
     * - 若消费者在事务提交后、ACK 前宕机，消息 requeue 后重试时
     *   查重 count>0，直接幂等跳过，同样不会重复扣库存。
     *
     * @param orderMsg MQ 消息体，包含 orderId / userId / voucherId
     */
    void createVoucherOrderTx(com.huxirating.dto.OrderMessage orderMsg);

    /**
     * 支付订单
     * @param orderId 订单ID
     * @param payType 支付方式：1余额 2支付宝 3微信
     * @return 支付结果
     */
    Result payOrder(Long orderId, Integer payType);

    /**
     * 查询用户订单列表
     * @param current 当前页
     * @param status 订单状态（可选）
     * @return 分页订单列表
     */
    Result queryUserOrders(Integer current, Integer status);

    /**
     * 取消订单
     * @param orderId 订单ID
     * @return 取消结果
     */
    Result cancelOrder(Long orderId);

    /**
     * 核销优惠券
     * @param orderId 订单ID
     * @return 核销结果
     */
    Result useVoucher(Long orderId);

    /**
     * 申请退款（用户侧）
     * 订单状态：2(已支付) -> 5(退款中)
     */
    Result applyRefund(Long orderId);

    /**
     * 确认退款成功（后台/支付回调模拟）
     * 订单状态：5(退款中) -> 6(已退款)
     */
    Result confirmRefund(Long orderId);

    /**
     * 秒杀下单（用户体验增强版）：返回统一 PurchaseAttemptResponse（放在 Result.data 内）
     */
    Result seckillVoucherPurchase(Long voucherId);

    /**
     * 根据 purchaseToken 查询购买进度（聚合订单/排队状态）
     */
    Result queryPurchase(String token);

    /**
     * 长轮询等待购买状态变化（异步 DeferredResult，不阻塞 Tomcat 线程）
     */
    DeferredResult<Result> waitPurchase(String token, Long timeoutMs);

    /**
     * 取消购买（支持 pending 取消）
     */
    Result cancelPurchase(String token);
}
