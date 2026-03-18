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
 * - 秒杀接口 QPS 限流读取 degradation.strategy 配置
 * - 秒杀接口按 voucherId 热点参数限流（默认单券 QPS 1000）
 * - 商铺查询 QPS 限流读取 degradation.strategy 配置
 * - 熔断：异常比例 > 50% 时触发降级，持续 10s
 * - 熔断：异常数超过阈值时触发降级
 * <p>
 * L3 熔断保护：
 * - DB 压力过大或异常比例超过 50% 时自动熔断
 * - 返回友好提示："当前抢购人数过多，请稍后重试"
 * <p>
 * 配置一致性说明：
 * - SentinelConfig 与 DegradationService 共用 degradation.strategy
 *
 * @author Nisson
 */
@Configuration
public class SentinelConfig {

    @Resource
    private DegradationStrategyProperties degradationStrategyProperties;

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
     * 动态更新限流规则。
     *
     * @param seckillQps 秒杀接口当前允许的 QPS
     */
    public void updateFlowRules(int seckillQps) {
        List<FlowRule> rules = new ArrayList<>();

        // 秒杀接口 QPS 限流
        FlowRule seckillRule = new FlowRule();
        seckillRule.setResource("seckillVoucher");
        seckillRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        seckillRule.setCount(Math.max(1, seckillQps));
        seckillRule.setLimitApp("default");
        rules.add(seckillRule);

        // 商铺查询接口 QPS 限流
        FlowRule shopRule = new FlowRule();
        shopRule.setResource("queryShopById");
        shopRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        shopRule.setCount(degradationStrategyProperties.getShopQueryQps());
        shopRule.setLimitApp("default");
        rules.add(shopRule);

        FlowRuleManager.loadRules(rules);

        System.out.println("【Sentinel】秒杀接口 QPS 限制已调整为 " + Math.max(1, seckillQps)
                + "，商铺查询 QPS=" + degradationStrategyProperties.getShopQueryQps());
    }

    /** QPS 限流规则 */
    private void initFlowRules() {
        updateFlowRules(degradationStrategyProperties.getNormalQps());
    }

    /** 热点参数限流：按 voucherId 维度限流 */
    private void initParamFlowRules() {
        ParamFlowRule rule = new ParamFlowRule("seckillVoucher")
                .setParamIdx(0)                          // 第 0 个参数（voucherId）
                .setGrade(RuleConstant.FLOW_GRADE_QPS)
                .setCount(degradationStrategyProperties.getHotspotDefaultQps());

        // 可针对特定参数值设定例外阈值
        ParamFlowItem hotItem = new ParamFlowItem()
                .setClassType(Long.class.getName())
                .setObject(String.valueOf(degradationStrategyProperties.getHotspotSpecialVoucherId()))
                .setCount(degradationStrategyProperties.getHotspotSpecialQps());
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
