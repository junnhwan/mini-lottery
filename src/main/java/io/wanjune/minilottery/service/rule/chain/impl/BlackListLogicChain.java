package io.wanjune.minilottery.service.rule.chain.impl;

import io.wanjune.minilottery.mapper.StrategyRuleMapper;
import io.wanjune.minilottery.mapper.po.StrategyRule;
import io.wanjune.minilottery.service.rule.chain.AbstractLogicChain;
import io.wanjune.minilottery.service.rule.chain.ChainFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 黑名单过滤节点（Phase 3）
 *
 * 对应简历：「责任链前置过滤 — 黑名单用户直接返回兜底奖品」
 *
 * DB 配置格式（strategy_rule.rule_value）：
 *   "R004:user_black_001,user_black_002"
 *   含义：冒号左边是兜底奖品ID，右边是逗号分隔的黑名单用户ID列表
 *
 * 执行逻辑：
 * - 解析 rule_value → 提取用户黑名单集合
 * - 当前用户在黑名单中 → 返回兜底奖品，TAKE_OVER（不调 next）
 * - 不在黑名单 → 调 next().logic() 传递给下一个节点
 *
 * 使用场景：
 * - 运营发现刷单用户，加入黑名单后只能抽到"谢谢参与"
 * - 内部测试用户，固定返回特定奖品
 *
 * @Scope(PROTOTYPE)：每次 getBean 返回新实例
 * 因为链节点持有 next 指针（可变状态），不同活动的链顺序不同，
 * 如果用单例，多个活动共享同一实例的 next 指针会互相覆盖
 *
 * 参考 big-market: BlackListLogicChain.java
 */
@Slf4j
@Component("rule_blacklist")
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
@RequiredArgsConstructor
public class BlackListLogicChain extends AbstractLogicChain {

    private final StrategyRuleMapper strategyRuleMapper;

    @Override
    protected String ruleModel() {
        return "rule_blacklist";
    }

    @Override
    public ChainFactory.ChainResult logic(String userId, String activityId) {
        log.info("责任链 — 黑名单校验 userId={}, activityId={}", userId, activityId);

        // 1. 从 DB 读取黑名单配置
        StrategyRule rule = strategyRuleMapper.queryByActivityIdAndRuleModel(activityId, ruleModel());
        if (rule == null || rule.getRuleValue() == null) {
            // 没有配置黑名单规则，直接放行到下一个节点
            return next().logic(userId, activityId);
        }

        // 2. 解析 rule_value：格式 "R004:user_black_001,user_black_002"
        String ruleValue = rule.getRuleValue();
        String[] parts = ruleValue.split(":");
        if (parts.length != 2) {
            log.warn("黑名单规则格式错误，放行 ruleValue={}", ruleValue);
            return next().logic(userId, activityId);
        }

        String fallbackAwardId = parts[0];  // 兜底奖品ID
        Set<String> blacklistUsers = Arrays.stream(parts[1].split(","))
                .map(String::trim)
                .collect(Collectors.toSet());

        // 3. 判断当前用户是否在黑名单中
        if (blacklistUsers.contains(userId)) {
            log.info("用户命中黑名单，返回兜底奖品 userId={}, awardId={}", userId, fallbackAwardId);
            return new ChainFactory.ChainResult(fallbackAwardId, ChainFactory.LogicModel.RULE_BLACKLIST);
        }

        // 4. 未命中，传递给下一个节点
        return next().logic(userId, activityId);
    }
}
