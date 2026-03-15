package io.wanjune.minilottery.mapper.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 规则树节点表
 * Phase 3 新增
 */
@Data
public class RuleTreeNode {
    private Long id;
    /** 规则树ID */
    private String treeId;
    /** 节点标识：rule_lock / rule_stock / rule_luck_award */
    private String ruleKey;
    /** 节点描述 */
    private String ruleDesc;
    /** 节点配置值（lock 阈值 / 兜底奖品 ID 等） */
    private String ruleValue;
    private LocalDateTime createTime;
}
