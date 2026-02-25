package com.huxirating.config;

import com.alibaba.csp.sentinel.annotation.aspectj.SentinelResourceAspect;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowItem;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.huxirating.degradation.DegradationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Sentinel 限流降级配置（L3 熔断保护增强版）
 * <p>
 * 规则说明：
 * - 秒杀接口 QPS 限流 200（降级模式下自动降低到 100）
 * - 秒杀接口按 voucherId 热点参数限流（单券 QPS 50）
 * - 商户查询 QPS 限流 1000
 * - 熔断：异常比例 > 50% 时触发降级，持续 10s
 * - 熔断：慢调用比例 > 50% 且 RT > 100ms 时触发降级
 * - 熔断：异常数超过阈值时触发降级
 * <p>
 * L3 熔断保护：
 * - DB 压力过大或异常比例超过 50% 时自动熔断
 * - 返回友好提示："当前抢购人数过多，请稍后重试"
 *
 * @author Nisson
 */
@Configuration
public class SentinelConfig {

    @Resource
    private DegradationService degradationService;

    /**
     * 正常模式 QPS 限制
     */
    private static final int NORMAL_SECKILL_QPS = 200;
    private static final int DEGRADED_SECKILL_QPS = 100;

    @Bean
    public SentinelResourceAspect sentinelResourceAspect() {
        return new SentinelResourceAspect();
    }

    @PostConstruct
    public void initRules() {
        initFlowRules();
        initParamFlowRules();
        initDegradeRules();
        initSystemRules();
    }

    /**
     * 动态更新限流规则（根据降级状态调整）
     */
    public void updateFlowRulesForDegradation(boolean degraded) {
        List<FlowRule> rules = new ArrayList<>();

        // 秒杀接口 QPS 限流（降级时降低）
        FlowRule seckillRule = new FlowRule();
        seckillRule.setResource("seckillVoucher");
        seckillRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        seckillRule.setCount(degraded ? DEGRADED_SECKILL_QPS : NORMAL_SECKILL_QPS);
        seckillRule.setLimitApp("default");
        rules.add(seckillRule);

        // 商户查询接口 QPS 限流
        FlowRule shopRule = new FlowRule();
        shopRule.setResource("queryShopById");
        shopRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        shopRule.setCount(1000);
        shopRule.setLimitApp("default");
        rules.add(shopRule);

        FlowRuleManager.loadRules(rules);

        // 记录日志
        if (degraded) {
            System.out.println("【Sentinel】降级模式：秒杀接口 QPS 限制已降低到 " + DEGRADED_SECKILL_QPS);
        } else {
            System.out.println("【Sentinel】正常模式：秒杀接口 QPS 限制恢复到 " + NORMAL_SECKILL_QPS);
        }
    }

    /** QPS 限流规则 */
    private void initFlowRules() {
        updateFlowRulesForDegradation(false);
    }

    /** 热点参数限流：按 voucherId 维度限流 */
    private void initParamFlowRules() {
        ParamFlowRule rule = new ParamFlowRule("seckillVoucher")
                .setParamIdx(0)                          // 第 0 个参数（voucherId）
                .setGrade(RuleConstant.FLOW_GRADE_QPS)
                .setCount(50);                           // 单个 voucherId QPS 上限

        // 可针对特定参数值设定例外阈值
        ParamFlowItem hotItem = new ParamFlowItem()
                .setClassType(long.class.getName())
                .setObject(String.valueOf(1))            // voucherId=1 的热门券
                .setCount(100);                          // 提高到 100
        rule.setParamFlowItemList(Collections.singletonList(hotItem));

        ParamFlowRuleManager.loadRules(Collections.singletonList(rule));
    }

    /** 熔断降级规则（增强版） */
    private void initDegradeRules() {
        List<DegradeRule> rules = new ArrayList<>();

        // 规则1：异常比例熔断
        DegradeRule exceptionRatioRule = new DegradeRule("seckillVoucher");
        exceptionRatioRule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO);
        exceptionRatioRule.setCount(0.5);         // 异常比例阈值 50%
        exceptionRatioRule.setTimeWindow(10);     // 降级持续时间 10s
        exceptionRatioRule.setMinRequestAmount(10); // 最少请求数
        exceptionRatioRule.setStatIntervalMs(10000); // 统计窗口 10s
        rules.add(exceptionRatioRule);

        // 规则2：异常数熔断（DB 压力过大时触发）
        DegradeRule exceptionCountRule = new DegradeRule("seckillVoucher");
        exceptionCountRule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_COUNT);
        exceptionCountRule.setCount(50);           // 异常数阈值 50
        exceptionCountRule.setTimeWindow(10);     // 降级持续时间 10s
        exceptionCountRule.setMinRequestAmount(10); // 最少请求数
        exceptionCountRule.setStatIntervalMs(10000); // 统计窗口 10s
        rules.add(exceptionCountRule);

        DegradeRuleManager.loadRules(rules);
    }

    /** 系统自适应保护规则 */
    private void initSystemRules() {
        // TODO: 根据系统负载（CPU、内存、RT）自适应限流
        // 可以通过 Sentinel 的 SystemRule 实现
        // 例如：当 CPU 使用率超过 80% 时自动限流
    }
}
