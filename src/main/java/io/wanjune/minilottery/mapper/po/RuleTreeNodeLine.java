package io.wanjune.minilottery.mapper.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 规则树节点连线表
 * Phase 3 新增
 */
@Data
public class RuleTreeNodeLine {
    private Long id;
    /** 规则树ID */
    private String treeId;
    /** 起始节点 rule_key */
    private String ruleNodeFrom;
    /** 目标节点 rule_key */
    private String ruleNodeTo;
    /** 限定类型：EQUAL */
    private String ruleLimitType;
    /** 限定值：ALLOW / TAKE_OVER */
    private String ruleLimitValue;
    private LocalDateTime createTime;
}
