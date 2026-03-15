package io.wanjune.minilottery.service.rule.tree.impl;

import io.wanjune.minilottery.service.rule.tree.ILogicTreeNode;
import io.wanjune.minilottery.service.rule.tree.TreeFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 兜底奖品节点 — 规则树的终端节点（Phase 3）
 *
 * 对应简历：「规则树后置决策 — 兜底奖品保底用户体验」
 *
 * ruleValue = 兜底奖品ID（如 "R004"），表示"谢谢参与"
 *
 * 当 rule_lock 拦截（次数不够）或 rule_stock 库存不足时，
 * 树引擎会跳转到此节点，返回兜底奖品。
 *
 * 这是树的终端节点，始终返回 TAKE_OVER（接管），没有出边。
 *
 * 参考 big-market: RuleLuckAwardLogicTreeNode.java
 */
@Slf4j
@Component("rule_luck_award")
public class RuleLuckAwardLogicTreeNode implements ILogicTreeNode {

    @Override
    public TreeFactory.TreeActionEntity logic(String userId, String activityId, String awardId, String ruleValue) {
        // 兜底奖品ID 从 ruleValue 中获取（DB 配置）
        String fallbackAwardId = (ruleValue != null && !ruleValue.isBlank()) ? ruleValue.trim() : awardId;
        log.info("规则树 — 兜底奖品 userId={}, fallbackAwardId={}", userId, fallbackAwardId);
        // TAKE_OVER = 接管，返回兜底奖品（终端节点，无出边）
        return new TreeFactory.TreeActionEntity("TAKE_OVER", fallbackAwardId);
    }
}
