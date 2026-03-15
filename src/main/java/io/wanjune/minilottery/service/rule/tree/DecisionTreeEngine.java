package io.wanjune.minilottery.service.rule.tree;

import io.wanjune.minilottery.service.rule.tree.vo.RuleTreeNodeLineVO;
import io.wanjune.minilottery.service.rule.tree.vo.RuleTreeNodeVO;
import io.wanjune.minilottery.service.rule.tree.vo.RuleTreeVO;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 决策树引擎 — 遍历规则树，依次执行节点逻辑（Phase 3）
 *
 * 遍历算法：
 * 1. 从根节点开始（treeRootRuleKey）
 * 2. 获取当前节点对应的 ILogicTreeNode Bean → 调用 logic()
 * 3. logic() 返回 ALLOW 或 TAKE_OVER
 * 4. 根据返回值匹配该节点的出边（RuleTreeNodeLineVO），找到下一个节点
 * 5. 跳转到下一个节点，重复 2-4
 * 6. 没有匹配的边 → 终止，返回当前结果
 *
 * 参考 big-market: DecisionTreeEngine.java
 */
@Slf4j
public class DecisionTreeEngine {

    /** 所有树节点 Bean（Spring 注入的 Map<beanName, ILogicTreeNode>） */
    private final Map<String, ILogicTreeNode> logicTreeNodeGroup;
    /** 当前规则树的 VO（包含节点和边的完整定义） */
    private final RuleTreeVO ruleTreeVO;

    public DecisionTreeEngine(Map<String, ILogicTreeNode> logicTreeNodeGroup, RuleTreeVO ruleTreeVO) {
        this.logicTreeNodeGroup = logicTreeNodeGroup;
        this.ruleTreeVO = ruleTreeVO;
    }

    /**
     * 执行决策树
     *
     * @param userId     用户ID
     * @param activityId 活动ID
     * @param awardId    链阶段抽中的奖品ID
     * @return 最终决策的奖品ID
     */
    public String process(String userId, String activityId, String awardId) {
        log.info("决策树开始执行 treeId={}, userId={}, awardId={}", ruleTreeVO.getTreeId(), userId, awardId);

        // 当前遍历到的节点 ruleKey
        String currentRuleKey = ruleTreeVO.getTreeRootRuleKey();
        // 最终结果的 awardId（初始为链传入的 awardId）
        String resultAwardId = awardId;

        while (currentRuleKey != null) {
            // 1. 获取当前节点定义
            RuleTreeNodeVO currentNode = ruleTreeVO.getTreeNodeMap().get(currentRuleKey);
            if (currentNode == null) {
                log.warn("决策树节点不存在 ruleKey={}", currentRuleKey);
                break;
            }

            // 2. 获取对应的逻辑处理 Bean
            ILogicTreeNode logicTreeNode = logicTreeNodeGroup.get(currentRuleKey);
            if (logicTreeNode == null) {
                log.warn("决策树节点 Bean 不存在 ruleKey={}", currentRuleKey);
                break;
            }

            // 3. 执行节点逻辑
            TreeFactory.TreeActionEntity actionEntity = logicTreeNode.logic(
                    userId, activityId, resultAwardId, currentNode.getRuleValue());

            // 4. 更新结果（节点可能改变 awardId，如兜底节点）
            if (actionEntity.awardId() != null) {
                resultAwardId = actionEntity.awardId();
            }

            String checkType = actionEntity.checkType();
            log.info("决策树节点执行完成 ruleKey={}, checkType={}, awardId={}", currentRuleKey, checkType, resultAwardId);

            // 5. 根据 checkType 匹配出边，找到下一个节点
            currentRuleKey = nextNode(checkType, currentNode.getTreeNodeLineList());
        }

        log.info("决策树执行完毕 treeId={}, finalAwardId={}", ruleTreeVO.getTreeId(), resultAwardId);
        return resultAwardId;
    }

    /**
     * 根据当前节点的返回值（ALLOW/TAKE_OVER），匹配出边，返回下一个节点的 ruleKey
     *
     * @param checkType 当前节点返回的检查类型
     * @param lineList  当前节点的出边列表
     * @return 下一个节点的 ruleKey，null 表示终止
     */
    private String nextNode(String checkType, List<RuleTreeNodeLineVO> lineList) {
        if (lineList == null || lineList.isEmpty()) {
            return null; // 无出边，终端节点
        }
        for (RuleTreeNodeLineVO line : lineList) {
            // 目前只支持 EQUAL 匹配
            if ("EQUAL".equals(line.getRuleLimitType()) && checkType.equals(line.getRuleLimitValue())) {
                return line.getRuleNodeTo();
            }
        }
        return null; // 未匹配到任何边
    }
}
