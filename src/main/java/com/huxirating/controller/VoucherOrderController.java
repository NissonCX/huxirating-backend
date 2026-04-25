package com.huxirating.controller;


import com.huxirating.degradation.SeckillQueueService;
import com.huxirating.dto.Result;
import com.huxirating.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  优惠券订单控制器
 *  提供优惠券订单的创建、查询、支付、取消、核销等功能
 * </p>
 *
 * @author Nisson
 * @since 2025-10-01
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private SeckillQueueService seckillQueueService;

    /**
     * 秒杀优惠券下单
     * @param voucherId 优惠券id
     * @return 订单id
     */
    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    /**
     * 查询订单状态
     * 支持异步下单场景：处理中 / 成功 / 已取消（失败）
     * @param orderId 订单id
     * @return 订单状态信息
     */
    @GetMapping("/{orderId}")
    public Result queryOrderStatus(@PathVariable("orderId") Long orderId) {
        return voucherOrderService.queryOrderStatus(orderId);
    }

    /**
     * 查询排队状态
     * @param ticketId 排队票据ID
     * @return 排队状态信息
     */
    @GetMapping("/queue/status/{ticketId}")
    public Result queryQueueStatus(@PathVariable("ticketId") String ticketId) {
        return seckillQueueService.queryStatus(ticketId);
    }

    /**
     * 支付订单
     * @param orderId 订单ID
     * @param payType 支付方式：1余额 2支付宝 3微信
     * @return 支付结果
     */
    @PostMapping("/pay/{orderId}")
    public Result payOrder(
            @PathVariable("orderId") Long orderId,
            @RequestParam("payType") Integer payType) {
        return voucherOrderService.payOrder(orderId, payType);
    }

    /**
     * 查询我的订单列表
     * @param current 当前页
     * @param status 订单状态（可选）
     * @return 订单列表
     */
    @GetMapping("/my")
    public Result queryMyOrders(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "status", required = false) Integer status) {
        return voucherOrderService.queryUserOrders(current, status);
    }

    /**
     * 取消订单
     * @param orderId 订单ID
     * @return 取消结果
     */
    @PutMapping("/cancel/{orderId}")
    public Result cancelOrder(@PathVariable("orderId") Long orderId) {
        return voucherOrderService.cancelOrder(orderId);
    }

    /**
     * 核销优惠券
     * @param orderId 订单ID
     * @return 核销结果
     */
    @PostMapping("/use/{orderId}")
    public Result useVoucher(@PathVariable("orderId") Long orderId) {
        return voucherOrderService.useVoucher(orderId);
    }
}
