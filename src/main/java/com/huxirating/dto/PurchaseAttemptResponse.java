package com.huxirating.dto;

import lombok.Data;

/**
 * 统一的“购买尝试”响应体（放在 Result.data 内）
 * 用于秒杀/排队/降级直写等多种模式下的统一交互。
 */
@Data
public class PurchaseAttemptResponse {

    /**
     * 用户侧状态
     * @see PurchaseState
     */
    private String state;

    /**
     * 稳定引用（后续查询/取消/等待都使用它）
     * 建议：正常路径可直接使用 orderId；排队路径使用 ticketId（如 T123）。
     */
    private String purchaseToken;

    /**
     * 订单 ID（可能为空，表示尚未创建订单）
     */
    private String orderId;

    /**
     * 排队票据 ID（仅排队模式返回）
     */
    private String queueTicketId;

    /**
     * token 过期时间（epochMillis，可选）
     */
    private Long expiresAt;

    /**
     * 推荐的下一步动作（可选）
     */
    private NextAction nextAction;

    /**
     * 面向用户的提示文案（可选）
     */
    private String message;

    /**
     * 面向前端/埋点的错误信息（可选；失败也可以直接用 Result.fail）
     */
    private ErrorInfo error;

    @Data
    public static class NextAction {
        /** POLL / REDIRECT_TO_PAY / NONE */
        private String type;
        private String url;
        private Long retryAfterMs;
    }

    @Data
    public static class ErrorInfo {
        /** SOLD_OUT / DUPLICATE_PURCHASE / IN_FLIGHT / QUEUE_FULL / SYSTEM_BUSY / NOT_FOUND / FORBIDDEN */
        private String code;
        private Boolean retryable;
        private String detail;
    }
}

