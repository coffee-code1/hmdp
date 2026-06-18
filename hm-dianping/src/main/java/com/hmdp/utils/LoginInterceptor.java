package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class LoginInterceptor implements HandlerInterceptor {

    
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        UserHolder.removeUser();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获取session
        HttpSession session = request.getSession(false);
        if (session == null) {
            response.setStatus(401);
            return false;
        }

        //获取session用户
        Object user = session.getAttribute("user");

        //3.判断用户是否存在
        if(user == null){
            //4不存在拦截
            response.setStatus(401);
            return false;

        }
        UserHolder.saveUser((UserDTO)user);
        return true;
    }
}
