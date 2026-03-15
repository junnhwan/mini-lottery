package io.wanjune.minilottery.mapper.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 策略规则表（责任链节点的配置值）
 * Phase 3 新增
 */
@Data
public class StrategyRule {
    private Long id;
    /** 活动ID */
    private String activityId;
    /** 规则模型：rule_blacklist / rule_weight */
    private String ruleModel;
    /** 规则值（格式因 rule_model 而异） */
    private String ruleValue;
    private LocalDateTime createTime;
}
