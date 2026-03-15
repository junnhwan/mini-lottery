package io.wanjune.minilottery.service.rule.tree.impl;

import io.wanjune.minilottery.service.rule.tree.ILogicTreeNode;
import io.wanjune.minilottery.service.rule.tree.TreeFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * 奖品库存校验节点（Phase 3）
 *
 * 对应简历：「规则树后置决策 — 校验奖品级别库存，扣减成功则确认发奖」
 *
 * 与 Phase 2 的 activity 级 DECR 不同，这里是 per-award 级别的库存：
 * - Phase 2 StockService：DECR stock:{activityId} → 控制活动总共还能抽几次
 * - 本节点：DECR award_stock:{activityId}_{awardId} → 控制某个具体奖品还能发几个
 *
 * 执行逻辑：
 * - Redis DECR award_stock:{activityId}_{awardId}
 * - surplus >= 0 → TAKE_OVER（库存扣成功，确认发奖，终止树遍历）
 * - surplus < 0 → 重置为 0，ALLOW（库存不足，走兜底）
 *
 * 注意语义和 rule_lock 是反的：
 * - rule_lock: ALLOW = 放行（好事）, TAKE_OVER = 拦截（坏事）
 * - rule_stock: TAKE_OVER = 扣成功确认发奖（好事）, ALLOW = 库存没了走兜底（坏事）
 * 这是参考 big-market 的设计，利用边（line）的方向来决定下一步走哪
 *
 * 参考 big-market: RuleStockLogicTreeNode.java
 */
@Slf4j
@Component("rule_stock")
@RequiredArgsConstructor
public class RuleStockLogicTreeNode implements ILogicTreeNode {

    private final RedissonClient redissonClient;

    /** Redis key 前缀：per-award 库存 */
    private static final String AWARD_STOCK_KEY_PREFIX = "award_stock:";

    @Override
    public TreeFactory.TreeActionEntity logic(String userId, String activityId, String awardId, String ruleValue) {
        log.info("规则树 — 奖品库存校验 userId={}, awardId={}", userId, awardId);

        // 1. Redis DECR per-award 库存
        String stockKey = AWARD_STOCK_KEY_PREFIX + activityId + "_" + awardId;
        RAtomicLong atomicStock = redissonClient.getAtomicLong(stockKey);
        long surplus = atomicStock.decrementAndGet();

        // 2. 判断扣减结果
        if (surplus >= 0) {
            // 库存扣成功，确认发奖
            log.info("奖品库存扣减成功 awardId={}, surplus={}", awardId, surplus);
            // TAKE_OVER = 确认发奖，树遍历中 TAKE_OVER 没有出边 → 终止
            return new TreeFactory.TreeActionEntity("TAKE_OVER", awardId);
        } else {
            // 库存不足，重置为 0 防止负数
            atomicStock.set(0);
            log.info("奖品库存不足 awardId={}, 重置为 0", awardId);
            // ALLOW = 走兜底（rule_stock → ALLOW → rule_luck_award）
            return new TreeFactory.TreeActionEntity("ALLOW", null);
        }
    }
}
