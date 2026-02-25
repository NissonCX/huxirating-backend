package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
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
}
