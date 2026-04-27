package com.huxirating.config;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.huxirating.dto.Result;
import com.huxirating.exception.ForbiddenException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.servlet.http.HttpServletRequest;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler({IllegalArgumentException.class})
    public ResponseEntity<Result> handleIllegalArgument(IllegalArgumentException e, HttpServletRequest request) {
        log.warn("400 {} {} - {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail("BAD_REQUEST", e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<Result> handleValidation(Exception e, HttpServletRequest request) {
        String msg = "参数校验失败";
        if (e instanceof MethodArgumentNotValidException) {
            MethodArgumentNotValidException ex = (MethodArgumentNotValidException) e;
            if (ex.getBindingResult().hasErrors() && ex.getBindingResult().getAllErrors().get(0) != null) {
                msg = ex.getBindingResult().getAllErrors().get(0).getDefaultMessage();
            }
        } else if (e instanceof BindException) {
            BindException ex = (BindException) e;
            if (ex.getBindingResult().hasErrors() && ex.getBindingResult().getAllErrors().get(0) != null) {
                msg = ex.getBindingResult().getAllErrors().get(0).getDefaultMessage();
            }
        }
        log.warn("400 {} {} - {}", request.getMethod(), request.getRequestURI(), msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail("VALIDATION_ERROR", msg));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result> handleBodyNotReadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        log.warn("400 {} {} - body not readable", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail("BAD_REQUEST", "请求体格式错误"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result> handleMissingParam(MissingServletRequestParameterException e, HttpServletRequest request) {
        log.warn("400 {} {} - missing parameter: {}", request.getMethod(), request.getRequestURI(), e.getParameterName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail("BAD_REQUEST", "缺少必需参数: " + e.getParameterName()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        log.warn("415 {} {} - unsupported media type: {}", request.getMethod(), request.getRequestURI(), e.getContentType());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(Result.fail("UNSUPPORTED_MEDIA_TYPE", "不支持的Content-Type: " + e.getContentType()));
    }

    @ExceptionHandler(BlockException.class)
    public ResponseEntity<Result> handleSentinelBlock(BlockException e, HttpServletRequest request) {
        log.warn("429 {} {} - sentinel blocked: {}", request.getMethod(), request.getRequestURI(), e.getRule());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Result.fail("RATE_LIMITED", "请求过于频繁，请稍后再试"));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Result> handleForbidden(ForbiddenException e, HttpServletRequest request) {
        log.warn("403 {} {} - {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.fail("FORBIDDEN", e.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Result> handleRuntimeException(RuntimeException e, HttpServletRequest request) {
        log.error("500 {} {} - {}", request.getMethod(), request.getRequestURI(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail("INTERNAL_ERROR", "服务器异常"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result> handleException(Exception e, HttpServletRequest request) {
        log.error("500 {} {} - {}", request.getMethod(), request.getRequestURI(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail("INTERNAL_ERROR", "服务器异常"));
    }
}
