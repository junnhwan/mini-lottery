package io.wanjune.minilottery.service.rule.tree.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 规则树边（节点间连线）VO（Phase 3）
 *
 * 表达"如果 from 节点返回 ruleLimitValue，则跳转到 to 节点"
 * 例：rule_lock 返回 ALLOW → 跳转 rule_stock
 *    rule_lock 返回 TAKE_OVER → 跳转 rule_luck_award
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleTreeNodeLineVO {
    /** 起始节点 ruleKey */
    private String ruleNodeFrom;
    /** 目标节点 ruleKey */
    private String ruleNodeTo;
    /** 限定类型（目前只用 EQUAL） */
    private String ruleLimitType;
    /** 限定值：ALLOW / TAKE_OVER */
    private String ruleLimitValue;
}
