package io.wanjune.minilottery.service.rule.tree;

import io.wanjune.minilottery.mapper.RuleTreeMapper;
import io.wanjune.minilottery.mapper.RuleTreeNodeLineMapper;
import io.wanjune.minilottery.mapper.RuleTreeNodeMapper;
import io.wanjune.minilottery.mapper.po.RuleTreeNode;
import io.wanjune.minilottery.mapper.po.RuleTreeNodeLine;
import io.wanjune.minilottery.service.rule.tree.vo.RuleTreeNodeLineVO;
import io.wanjune.minilottery.service.rule.tree.vo.RuleTreeNodeVO;
import io.wanjune.minilottery.service.rule.tree.vo.RuleTreeVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则树工厂 — 从 DB 加载规则树 → 创建决策树引擎（Phase 3）
 *
 * 职责：
 * 1. 从 DB 的 3 张表（rule_tree + rule_tree_node + rule_tree_node_line）读取规则树定义
 * 2. 组装为 RuleTreeVO 内存对象
 * 3. 创建 DecisionTreeEngine 执行决策
 * 4. RuleTreeVO 缓存在 ConcurrentHashMap 中，避免每次都查 DB
 *
 * 参考 big-market: DefaultTreeFactory.java
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TreeFactory {

    private final RuleTreeMapper ruleTreeMapper;
    private final RuleTreeNodeMapper ruleTreeNodeMapper;
    private final RuleTreeNodeLineMapper ruleTreeNodeLineMapper;

    /** Spring 自动注入所有 ILogicTreeNode 实现（key=beanName, 如 "rule_lock"） */
    private final Map<String, ILogicTreeNode> logicTreeNodeGroup;

    /** 缓存已加载的规则树 VO */
    private final ConcurrentHashMap<String, RuleTreeVO> treeCache = new ConcurrentHashMap<>();

    /**
     * 执行决策树
     *
     * @param treeId     规则树 ID
     * @param userId     用户 ID
     * @param activityId 活动 ID
     * @param awardId    链阶段抽中的奖品 ID
     * @return 决策后的最终奖品 ID
     */
    public String process(String treeId, String userId, String activityId, String awardId) {
        RuleTreeVO ruleTreeVO = loadRuleTree(treeId);
        if (ruleTreeVO == null) {
            log.warn("规则树不存在，直接返回原 awardId treeId={}", treeId);
            return awardId;
        }
        DecisionTreeEngine engine = new DecisionTreeEngine(logicTreeNodeGroup, ruleTreeVO);
        return engine.process(userId, activityId, awardId);
    }

    /**
     * 加载规则树（有缓存）
     * 从 DB 3 张表组装为 RuleTreeVO
     */
    private RuleTreeVO loadRuleTree(String treeId) {
        RuleTreeVO cached = treeCache.get(treeId);
        if (cached != null) {
            return cached;
        }

        // 1. 查询树定义
        var ruleTree = ruleTreeMapper.queryByTreeId(treeId);
        if (ruleTree == null) {
            return null;
        }

        // 2. 查询所有节点
        List<RuleTreeNode> nodes = ruleTreeNodeMapper.queryByTreeId(treeId);

        // 3. 查询所有连线
        List<io.wanjune.minilottery.mapper.po.RuleTreeNodeLine> lines = ruleTreeNodeLineMapper.queryByTreeId(treeId);

        // 4. 按 ruleNodeFrom 分组连线（方便后续挂到对应节点上）
        Map<String, List<RuleTreeNodeLineVO>> lineMap = new HashMap<>();
        for (RuleTreeNodeLine line : lines) {
            lineMap.computeIfAbsent(line.getRuleNodeFrom(), k -> new ArrayList<>())
                    .add(RuleTreeNodeLineVO.builder()
                            .ruleNodeFrom(line.getRuleNodeFrom())
                            .ruleNodeTo(line.getRuleNodeTo())
                            .ruleLimitType(line.getRuleLimitType())
                            .ruleLimitValue(line.getRuleLimitValue())
                            .build());
        }

        // 5. 组装节点 VO Map
        Map<String, RuleTreeNodeVO> treeNodeMap = new HashMap<>();
        for (RuleTreeNode node : nodes) {
            treeNodeMap.put(node.getRuleKey(), RuleTreeNodeVO.builder()
                    .treeId(node.getTreeId())
                    .ruleKey(node.getRuleKey())
                    .ruleDesc(node.getRuleDesc())
                    .ruleValue(node.getRuleValue())
                    .treeNodeLineList(lineMap.getOrDefault(node.getRuleKey(), List.of()))
                    .build());
        }

        // 6. 组装树 VO
        RuleTreeVO ruleTreeVO = RuleTreeVO.builder()
                .treeId(ruleTree.getTreeId())
                .treeName(ruleTree.getTreeName())
                .treeDesc(ruleTree.getTreeDesc())
                .treeRootRuleKey(ruleTree.getTreeRootRuleKey())
                .treeNodeMap(treeNodeMap)
                .build();

        treeCache.put(treeId, ruleTreeVO);
        log.info("规则树加载完成 treeId={}, nodeCount={}", treeId, nodes.size());
        return ruleTreeVO;
    }

    // ==================== 内部类 ====================

    /**
     * 树节点执行结果
     *
     * @param checkType ALLOW / TAKE_OVER
     * @param awardId   节点决策的奖品ID（可为 null，表示不改变 awardId）
     */
    public record TreeActionEntity(String checkType, String awardId) {
    }
}
