package com.huxirating.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huxirating.entity.MessageOutbox;
import com.huxirating.mapper.MessageOutboxMapper;
import com.huxirating.service.IMessageOutboxService;
import org.springframework.stereotype.Service;

@Service
public class MessageOutboxServiceImpl extends ServiceImpl<MessageOutboxMapper, MessageOutbox>
        implements IMessageOutboxService {
}
