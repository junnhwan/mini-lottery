package io.wanjune.minilottery.service.rule.chain.impl;

import io.wanjune.minilottery.service.armory.StrategyArmory;
import io.wanjune.minilottery.service.rule.chain.AbstractLogicChain;
import io.wanjune.minilottery.service.rule.chain.ChainFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * 默认抽奖节点 — 责任链的终端节点（Phase 3）
 *
 * 对应简历：「责任链前置过滤 — 默认走 O(1) 算法抽奖」
 *
 * 这是链的"兜底"，前面的节点（黑名单、权重）都未拦截时，
 * 最终会到这里，调用 StrategyArmory.draw(activityId) 正常抽奖。
 *
 * 注意：DefaultLogicChain 永远不调 next()，它是链的终端。
 *
 * 参考 big-market: DefaultLogicChain.java
 */
@Slf4j
@Component("rule_default")
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
@RequiredArgsConstructor
public class DefaultLogicChain extends AbstractLogicChain {

    private final StrategyArmory strategyArmory;

    @Override
    protected String ruleModel() {
        return "rule_default";
    }

    @Override
    public ChainFactory.ChainResult logic(String userId, String activityId) {
        // 终端节点：直接调用策略装配的 draw 方法，使用默认奖池
        String awardId = strategyArmory.draw(activityId);
        log.info("责任链 — 默认抽奖 userId={}, activityId={}, awardId={}", userId, activityId, awardId);
        return new ChainFactory.ChainResult(awardId, ChainFactory.LogicModel.RULE_DEFAULT);
    }
}
