package com.huxirating.exception;

/**
 * 权限不足异常（越权操作）
 * 由 WebExceptionAdvice 捕获并返回 HTTP 403
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
