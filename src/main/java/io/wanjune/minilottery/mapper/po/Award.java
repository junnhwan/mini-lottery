package io.wanjune.minilottery.mapper.po;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 奖品表
 */
@Data
public class Award {
    private Long id;
    /** 奖品ID */
    private String awardId;
    /** 所属活动ID */
    private String activityId;
    /** 奖品名称 */
    private String awardName;
    /** 1-优惠券 2-实物 3-谢谢参与 */
    private Integer awardType;
    /** 中奖概率，如 0.1000 表示 10% */
    private BigDecimal awardRate;
    /** 奖品库存 */
    private Integer stock;
    /** 排序，越小越靠前 */
    private Integer sort;
    private LocalDateTime createTime;
    /** 关联的规则树 ID，如 "tree_lock_1"（Phase 3 新增） */
    private String ruleModels;
}
