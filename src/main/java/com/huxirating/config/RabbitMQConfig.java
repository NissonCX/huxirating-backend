package com.huxirating.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 秒杀订单消息队列配置
 * 拓扑: 主队列 → 重试队列(TTL延迟) → 死信队列
 */
@Configuration
public class RabbitMQConfig {

    public static final String ORDER_EXCHANGE = "seckill.order.exchange";
    public static final String ORDER_QUEUE = "seckill.order.queue";
    public static final String ORDER_ROUTING_KEY = "seckill.order";

    public static final String RETRY_EXCHANGE = "seckill.order.retry.exchange";
    public static final String RETRY_QUEUE = "seckill.order.retry.queue";
    public static final String RETRY_ROUTING_KEY = "seckill.order.retry";

    public static final String DLX_EXCHANGE = "seckill.order.dlx.exchange";
    public static final String DLQ_QUEUE = "seckill.order.dlq.queue";
    public static final String DLQ_ROUTING_KEY = "seckill.order.dlq";

    /** 最大重试次数 */
    public static final int MAX_RETRY_COUNT = 3;
    /** 重试延迟（毫秒） */
    public static final int RETRY_TTL = 5000;
    /** 死信队列消息保留时间（毫秒），7天 */
    public static final int DLQ_MESSAGE_TTL = 7 * 24 * 60 * 60 * 1000;
    /** 死信队列最大长度，防止磁盘溢出 */
    public static final int DLQ_MAX_LENGTH = 10000;

    // ====== 主交换机 & 队列 ======

    @Bean
    public DirectExchange orderExchange() {
        return ExchangeBuilder.directExchange(ORDER_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue orderQueue() {
        return QueueBuilder.durable(ORDER_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(DLQ_ROUTING_KEY)
                .lazy()  // lazy 模式：消息尽早落盘，减少内存占用（大流量场景必备）
                .build();
    }

    @Bean
    public Binding orderBinding() {
        return BindingBuilder.bind(orderQueue()).to(orderExchange()).with(ORDER_ROUTING_KEY);
    }

    // ====== 重试交换机 & 队列（TTL 到期后自动转发回主队列） ======

    @Bean
    public DirectExchange retryExchange() {
        return ExchangeBuilder.directExchange(RETRY_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue retryQueue() {
        return QueueBuilder.durable(RETRY_QUEUE)
                .ttl(RETRY_TTL)
                .deadLetterExchange(ORDER_EXCHANGE)
                .deadLetterRoutingKey(ORDER_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding retryBinding() {
        return BindingBuilder.bind(retryQueue()).to(retryExchange()).with(RETRY_ROUTING_KEY);
    }

    // ====== 死信交换机 & 队列 ======

    @Bean
    public DirectExchange dlxExchange() {
        return ExchangeBuilder.directExchange(DLX_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue dlqQueue() {
        return QueueBuilder.durable(DLQ_QUEUE)
                .ttl(DLQ_MESSAGE_TTL)          // 消息最多保留 7 天
                .maxLength(DLQ_MAX_LENGTH)     // 最多积压 10000 条，超出后丢弃最早的
                .lazy()                        // lazy 模式落盘，防止 RAM 溢出
                .build();
    }

    @Bean
    public Binding dlqBinding() {
        return BindingBuilder.bind(dlqQueue()).to(dlxExchange()).with(DLQ_ROUTING_KEY);
    }
}
