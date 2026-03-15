package io.wanjune.minilottery.service.rule.tree.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 规则树节点 VO（Phase 3）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleTreeNodeVO {
    /** 规则树ID */
    private String treeId;
    /** 节点标识 */
    private String ruleKey;
    /** 节点描述 */
    private String ruleDesc;
    /** 节点配置值（lock 阈值、兜底奖品ID 等） */
    private String ruleValue;
    /** 该节点的出边列表（ALLOW → 下一节点, TAKE_OVER → 下一节点） */
    private List<RuleTreeNodeLineVO> treeNodeLineList;
}
