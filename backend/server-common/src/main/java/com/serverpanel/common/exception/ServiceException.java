package com.serverpanel.common.exception;

import lombok.Getter;

/**
 * 业务异常：携带 {@link ErrorCode} 业务码，由全局异常处理器统一转成 R 响应。
 */
@Getter
public class ServiceException extends RuntimeException {

    private final int code;

    public ServiceException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    /** 使用错误码的 code，但替换为更具体的消息 */
    public ServiceException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    public ServiceException(int code, String message) {
        super(message);
        this.code = code;
    }

    public ServiceException(String message) {
        super(message);
        this.code = ErrorCode.ERROR.getCode();
    }
}
