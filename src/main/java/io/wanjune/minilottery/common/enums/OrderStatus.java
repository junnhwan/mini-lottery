package io.wanjune.minilottery.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 订单状态枚举
 */
@Getter
@AllArgsConstructor
public enum OrderStatus {
    PENDING(0, "待处理"),
    COMPLETED(1, "已完成"),
    TIMEOUT(2, "已超时");

    private final int code;
    private final String desc;
}
