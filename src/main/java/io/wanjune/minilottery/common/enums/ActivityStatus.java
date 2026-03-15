package io.wanjune.minilottery.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 活动状态枚举
 */
@Getter
@AllArgsConstructor
public enum ActivityStatus {
    INACTIVE(0, "未开始"),
    ACTIVE(1, "进行中"),
    ENDED(2, "已结束");

    private final int code;
    private final String desc;
}
