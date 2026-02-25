package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 消息发件箱（Outbox 模式）
 * MQ 投递失败时写入此表，由定时任务补偿重发
 */
@Data
@TableName("tb_message_outbox")
public class MessageOutbox implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 关联订单ID */
    private Long orderId;

    /** 消息内容（JSON） */
    private String messageBody;

    /** 状态: 0-待发送 1-已发送 2-发送失败 */
    private Integer status;

    /** 重试次数 */
    private Integer retryCount;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
