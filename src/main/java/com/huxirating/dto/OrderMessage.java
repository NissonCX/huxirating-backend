package com.huxirating.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 秒杀订单 MQ 消息体
 */
@Data
@NoArgsConstructor
public class OrderMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long orderId;
    private Long userId;
    private Long voucherId;

    /** 重试次数（嵌入消息体，避免 DLX 转发时 Header 丢失） */
    private Integer retryCount = 0;

    public OrderMessage(Long orderId, Long userId, Long voucherId) {
        this.orderId = orderId;
        this.userId = userId;
        this.voucherId = voucherId;
    }
}
