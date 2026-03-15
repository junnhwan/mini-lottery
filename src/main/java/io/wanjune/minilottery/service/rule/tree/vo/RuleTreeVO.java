package io.wanjune.minilottery.service.rule.tree.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 规则树 VO — 整棵树的内存表示（Phase 3）
 *
 * 从 DB 的 3 张表（rule_tree + rule_tree_node + rule_tree_node_line）组装而成
 * 组装逻辑在 TreeFactory 中
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleTreeVO {
    /** 规则树ID */
    private String treeId;
    /** 规则树名称 */
    private String treeName;
    /** 规则树描述 */
    private String treeDesc;
    /** 根节点的 ruleKey（如 "rule_lock"） */
    private String treeRootRuleKey;
    /** 所有节点，key = ruleKey（如 "rule_lock", "rule_stock", "rule_luck_award"） */
    private Map<String, RuleTreeNodeVO> treeNodeMap;
}
