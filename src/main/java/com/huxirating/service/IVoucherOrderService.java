package com.huxirating.service;

import com.huxirating.dto.Result;
import com.huxirating.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

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
}
