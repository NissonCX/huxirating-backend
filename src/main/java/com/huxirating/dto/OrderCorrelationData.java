package com.huxirating.dto;

import lombok.Getter;
import org.springframework.amqp.rabbit.connection.CorrelationData;

/**
 * 秒杀订单专用 CorrelationData
 * 携带消息体，供 confirm NACK 时写入 Outbox 兜底
 */
@Getter
public class OrderCorrelationData extends CorrelationData {
    private final Long orderId;
    private final String messageBody;

    public OrderCorrelationData(Long orderId, String messageBody) {
        super("order:" + orderId);
        this.orderId = orderId;
        this.messageBody = messageBody;
    }
}
