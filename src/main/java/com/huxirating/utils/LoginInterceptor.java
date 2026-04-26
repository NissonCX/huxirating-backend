package com.huxirating.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huxirating.dto.Result;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;

/**
 * 登录校验拦截器（第二层）
 * 校验 ThreadLocal 中是否有用户信息，无则返回 401
 *
 * @author Nisson
 */
public class LoginInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    public LoginInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // CORS 预检请求直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if (UserHolder.getUser() == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(Result.fail("UNAUTHORIZED", "未登录")));
            return false;
        }
        return true;
    }
}
