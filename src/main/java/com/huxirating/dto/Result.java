package com.huxirating.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一响应结果封装类
 * 前后端交互的统一数据格式
 *
 * 字段说明：
 * - success：请求是否成功
 * - errorMsg：错误信息（失败时返回）
 * - data：响应数据（成功时返回）
 * - total：总数（分页查询时返回）
 *
 * @author Nisson
 */
@Data
@NoArgsConstructor
public class Result {
    private Boolean success;
    /** 业务错误码（可选，便于前端稳定处理） */
    private String errorCode;
    private String errorMsg;
    private Object data;
    private Long total;

    public Result(Boolean success, String errorMsg, Object data, Long total) {
        this.success = success;
        this.errorMsg = errorMsg;
        this.data = data;
        this.total = total;
    }

    public Result(Boolean success, String errorCode, String errorMsg, Object data, Long total) {
        this.success = success;
        this.errorCode = errorCode;
        this.errorMsg = errorMsg;
        this.data = data;
        this.total = total;
    }

    /**
     * 成功响应（无数据）
     */
    public static Result ok(){
        return new Result(true, null, null, null);
    }

    /**
     * 成功响应（带数据）
     */
    public static Result ok(Object data){
        return new Result(true, null, data, null);
    }

    /**
     * 成功响应（分页数据）
     */
    public static Result ok(List<?> data, Long total){
        return new Result(true, null, data, total);
    }

    /**
     * 失败响应
     */
    public static Result fail(String errorMsg){
        return new Result(false, null, errorMsg, null, null);
    }

    public static Result fail(String errorCode, String errorMsg) {
        return new Result(false, errorCode, errorMsg, null, null);
    }
}
