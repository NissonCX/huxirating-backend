package com.huxirating.dto;

/**
 * 购买尝试状态机常量
 *
 * 状态转换：
 *   ACCEPTED → PROCESSING → ORDER_CREATED → REFUNDING → REFUNDED
 *                       ↘ FAILED            ↘ CANCELED
 *   QUEUED → PROCESSING → (同上)
 *
 * 终态：ORDER_CREATED, FAILED, CANCELED, REFUNDING, REFUNDED
 */
public final class PurchaseState {
    private PurchaseState() {}

    /** 已受理，正在创建订单 */
    public static final String ACCEPTED = "ACCEPTED";
    /** 排队中（限流/降级场景） */
    public static final String QUEUED = "QUEUED";
    /** 处理中（MQ 飞行中 / 排队处理中） */
    public static final String PROCESSING = "PROCESSING";
    /** 订单已创建（终态：可支付/已支付/已核销） */
    public static final String ORDER_CREATED = "ORDER_CREATED";
    /** 购买失败（终态：库存不足/重复购买等） */
    public static final String FAILED = "FAILED";
    /** 已取消（终态） */
    public static final String CANCELED = "CANCELED";
    /** 退款中（终态：订单存在但退款进行中） */
    public static final String REFUNDING = "REFUNDING";
    /** 已退款（终态：退款完成） */
    public static final String REFUNDED = "REFUNDED";
}
