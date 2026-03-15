package io.wanjune.minilottery.service.rule.tree.impl;

import io.wanjune.minilottery.mapper.UserParticipateCountMapper;
import io.wanjune.minilottery.service.rule.tree.ILogicTreeNode;
import io.wanjune.minilottery.service.rule.tree.TreeFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 抽奖次数锁节点（Phase 3）
 *
 * 对应简历：「规则树后置决策 — 用户抽奖次数不足时拦截大奖」
 *
 * ruleValue = 阈值数字（如 "2"），表示用户需要参与 >= 2 次才能获得此奖品
 *
 * 执行逻辑：
 * - 查询用户参与次数
 * - >= ruleValue → ALLOW（放行，继续查库存）
 * - < ruleValue → TAKE_OVER（拦截，走兜底奖品）
 *
 * 使用场景：
 * - 防止新用户第一次就抽中大奖（如 iPhone），提高用户粘性
 * - 运营可通过修改 DB 中的 ruleValue 调整解锁门槛
 *
 * 参考 big-market: RuleLockLogicTreeNode.java
 */
@Slf4j
@Component("rule_lock")
@RequiredArgsConstructor
public class RuleLockLogicTreeNode implements ILogicTreeNode {

    private final UserParticipateCountMapper userParticipateCountMapper;

    @Override
    public TreeFactory.TreeActionEntity logic(String userId, String activityId, String awardId, String ruleValue) {
        log.info("规则树 — 次数锁 userId={}, awardId={}, ruleValue={}", userId, awardId, ruleValue);

        // 1. 解析阈值
        int threshold = 0;
        if (ruleValue != null && !ruleValue.isBlank()) {
            try {
                threshold = Integer.parseInt(ruleValue.trim());
            } catch (NumberFormatException e) {
                log.warn("次数锁阈值解析失败，默认放行 ruleValue={}", ruleValue);
                return new TreeFactory.TreeActionEntity("ALLOW", null);
            }
        }

        // 2. 查询用户参与次数
        int participateCount = userParticipateCountMapper.queryByUserIdAndActivityId(userId, activityId);

        // 3. 判断是否达标
        if (participateCount >= threshold) {
            // 次数够，放行 → 引擎根据 ALLOW 边跳转到 rule_stock
            log.info("次数锁通过 participateCount={} >= threshold={}", participateCount, threshold);
            return new TreeFactory.TreeActionEntity("ALLOW", null);
        } else {
            // 次数不够，拦截 → 引擎根据 TAKE_OVER 边跳转到 rule_luck_award
            log.info("次数锁拦截 participateCount={} < threshold={}", participateCount, threshold);
            return new TreeFactory.TreeActionEntity("TAKE_OVER", null);
        }
    }
}
