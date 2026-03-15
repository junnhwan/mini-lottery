package io.wanjune.minilottery.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 奖品类型枚举
 */
@Getter
@AllArgsConstructor
public enum AwardType {
    COUPON(1, "优惠券"),
    PHYSICAL(2, "实物奖品"),
    THANKS(3, "谢谢参与");

    private final int code;
    private final String desc;
}
