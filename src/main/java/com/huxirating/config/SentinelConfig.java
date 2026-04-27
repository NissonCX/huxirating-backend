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
import com.alibaba.csp.sentinel.slots.system.SystemRule;
import com.alibaba.csp.sentinel.slots.system.SystemRuleManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
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
@Slf4j
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

        // 秒杀接口 QPS 限流（旧版 API）
        FlowRule seckillRule = new FlowRule();
        seckillRule.setResource("seckillVoucher");
        seckillRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        seckillRule.setCount(Math.max(1, seckillQps));
        seckillRule.setLimitApp("default");
        rules.add(seckillRule);

        // 秒杀接口 QPS 限流（新版 Purchase API）
        FlowRule purchaseRule = new FlowRule();
        purchaseRule.setResource("seckillVoucherPurchase");
        purchaseRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        purchaseRule.setCount(Math.max(1, seckillQps));
        purchaseRule.setLimitApp("default");
        rules.add(purchaseRule);

        // 商铺查询接口 QPS 限流
        FlowRule shopRule = new FlowRule();
        shopRule.setResource("queryShopById");
        shopRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        shopRule.setCount(degradationStrategyProperties.getShopQueryQps());
        shopRule.setLimitApp("default");
        rules.add(shopRule);

        FlowRuleManager.loadRules(rules);

        log.info("【Sentinel】秒杀接口 QPS 限制已调整为 {}（seckillVoucher + seckillVoucherPurchase），商铺查询 QPS={}",
                Math.max(1, seckillQps), degradationStrategyProperties.getShopQueryQps());
    }

    /** QPS 限流规则 */
    private void initFlowRules() {
        updateFlowRules(degradationStrategyProperties.getNormalQps());
    }

    /** 热点参数限流：按 voucherId 维度限流 */
    private void initParamFlowRules() {
        ParamFlowRule seckillRule = new ParamFlowRule("seckillVoucher")
                .setParamIdx(0)                          // 第 0 个参数（voucherId）
                .setGrade(RuleConstant.FLOW_GRADE_QPS)
                .setCount(degradationStrategyProperties.getHotspotDefaultQps());

        // 可针对特定参数值设定例外阈值
        ParamFlowItem hotItem = new ParamFlowItem()
                .setClassType(Long.class.getName())
                .setObject(String.valueOf(degradationStrategyProperties.getHotspotSpecialVoucherId()))
                .setCount(degradationStrategyProperties.getHotspotSpecialQps());
        seckillRule.setParamFlowItemList(Collections.singletonList(hotItem));

        // 新版 Purchase API 热点参数限流（与旧版对等）
        ParamFlowRule purchaseRule = new ParamFlowRule("seckillVoucherPurchase")
                .setParamIdx(0)
                .setGrade(RuleConstant.FLOW_GRADE_QPS)
                .setCount(degradationStrategyProperties.getHotspotDefaultQps());
        ParamFlowItem purchaseHotItem = new ParamFlowItem()
                .setClassType(Long.class.getName())
                .setObject(String.valueOf(degradationStrategyProperties.getHotspotSpecialVoucherId()))
                .setCount(degradationStrategyProperties.getHotspotSpecialQps());
        purchaseRule.setParamFlowItemList(Collections.singletonList(purchaseHotItem));

        ParamFlowRuleManager.loadRules(Arrays.asList(seckillRule, purchaseRule));
    }

    /** 熔断降级规则（增强版） */
    private void initDegradeRules() {
        List<DegradeRule> rules = new ArrayList<>();

        // === seckillVoucher 熔断规则 ===

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

        // === seckillVoucherPurchase 熔断规则（与旧版对等） ===

        DegradeRule purchaseRatioRule = new DegradeRule("seckillVoucherPurchase");
        purchaseRatioRule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO);
        purchaseRatioRule.setCount(0.5);
        purchaseRatioRule.setTimeWindow(10);
        purchaseRatioRule.setMinRequestAmount(10);
        purchaseRatioRule.setStatIntervalMs(10000);
        rules.add(purchaseRatioRule);

        DegradeRule purchaseCountRule = new DegradeRule("seckillVoucherPurchase");
        purchaseCountRule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_COUNT);
        purchaseCountRule.setCount(50);
        purchaseCountRule.setTimeWindow(10);
        purchaseCountRule.setMinRequestAmount(10);
        purchaseCountRule.setStatIntervalMs(10000);
        rules.add(purchaseCountRule);

        // === queryShopById 熔断规则 ===

        DegradeRule shopRatioRule = new DegradeRule("queryShopById");
        shopRatioRule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO);
        shopRatioRule.setCount(0.5);
        shopRatioRule.setTimeWindow(10);
        shopRatioRule.setMinRequestAmount(10);
        shopRatioRule.setStatIntervalMs(10000);
        rules.add(shopRatioRule);

        DegradeRuleManager.loadRules(rules);
    }

    /** 系统自适应保护规则 */
    private void initSystemRules() {
        List<SystemRule> rules = new ArrayList<>();

        // Load1 超过 CPU 核数 * 2 时触发
        SystemRule loadRule = new SystemRule();
        loadRule.setHighestCpuUsage(0.8);          // CPU 使用率 > 80% 触发
        rules.add(loadRule);

        SystemRule rtRule = new SystemRule();
        rtRule.setAvgRt(1000);                     // 平均 RT > 1s 触发
        rules.add(rtRule);

        SystemRule threadRule = new SystemRule();
        threadRule.setMaxThread(500);               // 并发线程数 > 500 触发
        rules.add(threadRule);

        SystemRule entryRule = new SystemRule();
        entryRule.setQps(20000);                    // 入口总 QPS > 20000 触发
        rules.add(entryRule);

        SystemRuleManager.loadRules(rules);
        log.info("【Sentinel】系统自适应保护规则已加载：CPU>80%, RT>1s, Thread>500, QPS>20000");
    }
}
