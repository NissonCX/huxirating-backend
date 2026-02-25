package com.hmdp.config;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Sentinel 限流降级配置
 * <p>
 * 规则说明：
 * - 秒杀接口 QPS 限流 200
 * - 秒杀接口按 voucherId 热点参数限流（单券 QPS 50）
 * - 商户查询 QPS 限流 1000
 * - 熔断：异常比例 > 50% 时触发降级，持续 10s
 *
 * @author Nisson
 */
@Configuration
public class SentinelConfig {

    @Bean
    public SentinelResourceAspect sentinelResourceAspect() {
        return new SentinelResourceAspect();
    }

    @PostConstruct
    public void initRules() {
        initFlowRules();
        initParamFlowRules();
        initDegradeRules();
    }

    /** QPS 限流规则 */
    private void initFlowRules() {
        List<FlowRule> rules = new ArrayList<>();

        // 秒杀接口 QPS 限流
        FlowRule seckillRule = new FlowRule();
        seckillRule.setResource("seckillVoucher");
        seckillRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        seckillRule.setCount(200);
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

    /** 熔断降级规则：异常比例 > 50% 时降级 10s */
    private void initDegradeRules() {
        DegradeRule rule = new DegradeRule("seckillVoucher");
        rule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO);
        rule.setCount(0.5);         // 异常比例阈值 50%
        rule.setTimeWindow(10);     // 降级持续时间 10s
        rule.setMinRequestAmount(10); // 最少请求数
        rule.setStatIntervalMs(10000); // 统计窗口 10s

        DegradeRuleManager.loadRules(Collections.singletonList(rule));
    }
}
