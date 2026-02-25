package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.MessageOutbox;
import com.hmdp.mapper.MessageOutboxMapper;
import com.hmdp.service.IMessageOutboxService;
import org.springframework.stereotype.Service;

@Service
public class MessageOutboxServiceImpl extends ServiceImpl<MessageOutboxMapper, MessageOutbox>
        implements IMessageOutboxService {
}
