package io.wanjune.minilottery.service.rule.chain.impl;

import io.wanjune.minilottery.mapper.StrategyRuleMapper;
import io.wanjune.minilottery.mapper.UserParticipateCountMapper;
import io.wanjune.minilottery.mapper.po.StrategyRule;
import io.wanjune.minilottery.service.armory.StrategyArmory;
import io.wanjune.minilottery.service.rule.chain.AbstractLogicChain;
import io.wanjune.minilottery.service.rule.chain.ChainFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 权重过滤节点（Phase 3）
 *
 * 对应简历：「责任链前置过滤 — 用户累计抽奖次数达到阈值，切换到更高概率的奖池」
 *
 * DB 配置格式（strategy_rule.rule_value）：
 *   "2:R001,R002,R003,R004 3:R001,R002,R004"
 *   含义：空格分隔多个阈值组，冒号左边是参与次数阈值，右边是该阈值下可选的奖品ID列表
 *
 * 执行逻辑：
 * - 查询用户参与次数
 * - 遍历阈值组（从大到小），找到用户满足的最高阈值
 * - 命中 → 从该阈值对应的权重子奖池抽奖（key = activityId_threshold）
 * - 未命中任何阈值 → 调 next().logic() 传递给 DefaultLogicChain
 *
 * 权重子奖池装配：
 * - StrategyArmory.armory() 中会为每个阈值组单独构建概率表
 *   （key = "A20260310001_3"，只包含该阈值组的奖品，概率重新归一化）
 * - 抽奖时调用 strategyArmory.draw("A20260310001_3") 从子奖池抽
 *
 * 参考 big-market: RuleWeightLogicChain.java
 */
@Slf4j
@Component("rule_weight")
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
@RequiredArgsConstructor
public class RuleWeightLogicChain extends AbstractLogicChain {

    private final StrategyRuleMapper strategyRuleMapper;
    private final UserParticipateCountMapper userParticipateCountMapper;
    private final StrategyArmory strategyArmory;

    @Override
    protected String ruleModel() {
        return "rule_weight";
    }

    @Override
    public ChainFactory.ChainResult logic(String userId, String activityId) {
        log.info("责任链 — 权重校验 userId={}, activityId={}", userId, activityId);

        // 1. 从 DB 读取权重配置
        StrategyRule rule = strategyRuleMapper.queryByActivityIdAndRuleModel(activityId, ruleModel());
        if (rule == null || rule.getRuleValue() == null) {
            return next().logic(userId, activityId);
        }

        // 2. 解析权重配置
        //    格式："2:R001,R002,R003,R004 3:R001,R002,R004"
        //    解析为 TreeMap<threshold, awardIds>（按阈值从大到小匹配）
        String ruleValue = rule.getRuleValue();
        TreeMap<Integer, String> weightMap = parseWeightConfig(ruleValue);
        if (weightMap.isEmpty()) {
            return next().logic(userId, activityId);
        }

        // 3. 查询用户参与次数
        int participateCount = userParticipateCountMapper.queryByUserIdAndActivityId(userId, activityId);

        // 4. 从大到小匹配阈值（用 descendingMap 保证先匹配高阈值）
        //    例：阈值 3 和 2，用户参与了 3 次 → 命中阈值 3（更高的奖池）
        for (Map.Entry<Integer, String> entry : weightMap.descendingMap().entrySet()) {
            int threshold = entry.getKey();
            if (participateCount >= threshold) {
                // 命中权重阈值，从权重子奖池抽奖
                String weightKey = activityId + "_" + threshold;
                String awardId = strategyArmory.draw(weightKey);
                log.info("用户命中权重规则 userId={}, threshold={}, awardId={}", userId, threshold, awardId);
                return new ChainFactory.ChainResult(awardId, ChainFactory.LogicModel.RULE_WEIGHT);
            }
        }

        // 5. 未命中任何阈值，传递给下一个节点
        return next().logic(userId, activityId);
    }

    /**
     * 解析权重配置字符串
     *
     * @param ruleValue 格式："2:R001,R002,R003,R004 3:R001,R002,R004"
     * @return TreeMap<阈值, 原始奖品ID列表字符串>（方便 StrategyArmory 装配时解析）
     */
    private TreeMap<Integer, String> parseWeightConfig(String ruleValue) {
        TreeMap<Integer, String> weightMap = new TreeMap<>();
        String[] groups = ruleValue.split("\\s+");
        for (String group : groups) {
            String[] parts = group.split(":");
            if (parts.length == 2) {
                try {
                    int threshold = Integer.parseInt(parts[0]);
                    weightMap.put(threshold, parts[1]);
                } catch (NumberFormatException e) {
                    log.warn("权重阈值解析失败，跳过 group={}", group);
                }
            }
        }
        return weightMap;
    }
}
