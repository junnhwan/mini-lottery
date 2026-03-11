package io.wanjune.minilottery.common;

import lombok.Getter;

/**
 * 业务异常
 *
 * Service 层抛出，GlobalExceptionHandler 统一捕获并返回 Result
 *
 * @author zjh
 * @since 2026/3/11
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
