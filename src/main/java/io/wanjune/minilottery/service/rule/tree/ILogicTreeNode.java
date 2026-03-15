package io.wanjune.minilottery.service.rule.tree;

/**
 * 规则树节点接口（Phase 3）
 *
 * 对应简历：「规则树后置动态决策（锁→库存→兜底），树结构存 DB 支持热插拔」
 *
 * 每个节点执行一种校验逻辑，返回 ALLOW 或 TAKE_OVER：
 * - ALLOW：当前节点"放行"，引擎根据边跳转到 ALLOW 对应的下一个节点
 * - TAKE_OVER：当前节点"接管"，引擎根据边跳转到 TAKE_OVER 对应的下一个节点
 *
 * 注意：ALLOW/TAKE_OVER 只是语义标签，具体含义因节点而异：
 * - rule_lock: ALLOW = 用户次数够（继续） / TAKE_OVER = 次数不够（拦截）
 * - rule_stock: TAKE_OVER = 库存扣成功（确认发奖） / ALLOW = 库存不足（走兜底）
 *
 * 参考 big-market: ILogicTreeNode.java
 */
public interface ILogicTreeNode {

    /**
     * 执行节点逻辑
     *
     * @param userId     用户ID
     * @param activityId 活动ID
     * @param awardId    链阶段抽中的奖品ID
     * @param ruleValue  节点配置值（从 DB rule_tree_node.rule_value 读取）
     * @return 执行结果（checkType + 可能的 awardId）
     */
    TreeFactory.TreeActionEntity logic(String userId, String activityId, String awardId, String ruleValue);
}
