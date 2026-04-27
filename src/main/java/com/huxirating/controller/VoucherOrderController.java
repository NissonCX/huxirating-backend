package com.huxirating.controller;


import com.huxirating.degradation.SeckillQueueService;
import com.huxirating.dto.Result;
import com.huxirating.dto.UserDTO;
import com.huxirating.service.IVoucherOrderService;
import com.huxirating.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.util.Set;

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

    /**
     * 管理员白名单（与 DegradationController 保持一致）
     * 生产环境建议从配置或 RBAC 系统读取
     */
    private static final Set<Long> ADMIN_USER_IDS = Set.of(1L);

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private SeckillQueueService seckillQueueService;

    /**
     * 秒杀优惠券下单
     * @deprecated 使用 {@link #seckillVoucherPurchase(Long)} 替代
     *             迁移路径：POST /voucher-order/purchase/seckill/{id}
     */
    @Deprecated
    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId, HttpServletResponse response) {
        response.setHeader("Deprecation", "true");
        response.setHeader("Link", "</voucher-order/purchase/seckill/" + voucherId + ">; rel=\"successor-version\"");
        return voucherOrderService.seckillVoucher(voucherId);
    }

    /**
     * 秒杀优惠券下单（用户体验增强版）
     * 返回统一 purchaseToken，前端可用 /voucher-order/purchase/{token} 查询进度。
     */
    @PostMapping("purchase/seckill/{id}")
    public Result seckillVoucherPurchase(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucherPurchase(voucherId);
    }

    /**
     * 查询订单状态
     * @deprecated 使用 {@link #queryPurchase(String)} 替代
     *             迁移路径：GET /voucher-order/purchase/{token}
     */
    @Deprecated
    @GetMapping("/{orderId}")
    public Result queryOrderStatus(@PathVariable("orderId") Long orderId, HttpServletResponse response) {
        response.setHeader("Deprecation", "true");
        response.setHeader("Link", "</voucher-order/purchase/" + orderId + ">; rel=\"successor-version\"");
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
     * 统一查询购买进度（purchaseToken 可以是 orderId 或 ticketId）
     */
    @GetMapping("/purchase/{token}")
    public Result queryPurchase(@PathVariable("token") String token) {
        return voucherOrderService.queryPurchase(token);
    }

    /**
     * 购买进度长轮询（减少前端频繁轮询）
     */
    @GetMapping("/purchase/{token}/wait")
    public DeferredResult<Result> waitPurchase(
            @PathVariable("token") String token,
            @RequestParam(value = "timeoutMs", defaultValue = "25000") Long timeoutMs
    ) {
        return voucherOrderService.waitPurchase(token, timeoutMs);
    }

    /**
     * 取消购买（支持 pending 取消；token 可以是 orderId 或 ticketId）
     */
    @PutMapping("/purchase/{token}/cancel")
    public Result cancelPurchase(@PathVariable("token") String token) {
        return voucherOrderService.cancelPurchase(token);
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
     * @deprecated 使用 {@link #cancelPurchase(String)} 替代
     *             迁移路径：PUT /voucher-order/purchase/{token}/cancel
     */
    @Deprecated
    @PutMapping("/cancel/{orderId}")
    public Result cancelOrder(@PathVariable("orderId") Long orderId, HttpServletResponse response) {
        response.setHeader("Deprecation", "true");
        response.setHeader("Link", "</voucher-order/purchase/" + orderId + "/cancel>; rel=\"successor-version\"");
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

    /**
     * 申请退款
     * @param orderId 订单ID
     */
    @PostMapping("/refund/apply/{orderId}")
    public Result applyRefund(@PathVariable("orderId") Long orderId) {
        return voucherOrderService.applyRefund(orderId);
    }

    /**
     * 确认退款成功（后台/模拟支付回调）
     * 注意：该接口需要管理员权限
     * @param orderId 订单ID
     */
    @PostMapping("/refund/confirm/{orderId}")
    public Result confirmRefund(@PathVariable("orderId") Long orderId) {
        if (!isAdmin()) {
            return Result.fail("无权限访问");
        }
        return voucherOrderService.confirmRefund(orderId);
    }

    private boolean isAdmin() {
        UserDTO user = UserHolder.getUser();
        return user != null && ADMIN_USER_IDS.contains(user.getId());
    }
}
