package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

public interface IUserService extends IService<User> {

    Result sendcode(String phone);

    Result login(LoginFormDTO loginFormDTO);

    Result sign();

    Result signCount();
}
