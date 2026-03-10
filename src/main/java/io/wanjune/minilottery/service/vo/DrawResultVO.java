package io.wanjune.minilottery.service.vo;

import lombok.Data;

/**
 * 抽奖结果
 *
 * @author zjh
 * @since 2026/3/10 19:25
 */
@Data
public class DrawResultVO {
    /** 奖品ID */
    private String awardId;
    /** 奖品名称 */
    private String awardName;
    /** 1-优惠券 2-实物 3-谢谢参与 */
    private Integer awardType;
}