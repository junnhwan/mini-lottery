package io.wanjune.minilottery.mapper.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 规则树定义表
 * Phase 3 新增
 */
@Data
public class RuleTree {
    private Long id;
    /** 规则树ID */
    private String treeId;
    /** 规则树名称 */
    private String treeName;
    /** 规则树描述 */
    private String treeDesc;
    /** 根节点 rule_key */
    private String treeRootRuleKey;
    private LocalDateTime createTime;
}
