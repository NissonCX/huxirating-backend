package com.huxirating.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.huxirating.dto.LoginFormDTO;
import com.huxirating.dto.Result;
import com.huxirating.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author Nisson
 * @since 2025-10-01
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    Result sign();

    Result signCount();

}
