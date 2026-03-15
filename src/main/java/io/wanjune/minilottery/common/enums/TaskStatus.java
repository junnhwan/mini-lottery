package io.wanjune.minilottery.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 发奖任务状态枚举
 */
@Getter
@AllArgsConstructor
public enum TaskStatus {
    PENDING(0, "待处理"),
    PROCESSING(1, "发送中"),
    COMPLETED(2, "已完成"),
    FAILED(3, "失败");

    private final int code;
    private final String desc;
}
