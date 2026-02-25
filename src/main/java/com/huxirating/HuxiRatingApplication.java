package com.huxirating;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 虎溪锐评平台启动类
 *
 * @author Nisson
 */
@MapperScan("com.huxirating.mapper")
@SpringBootApplication
@EnableAspectJAutoProxy(exposeProxy = true)
@EnableScheduling
public class HuxiRatingApplication {

    public static void main(String[] args) {
        SpringApplication.run(HuxiRatingApplication.class, args);
    }
}
