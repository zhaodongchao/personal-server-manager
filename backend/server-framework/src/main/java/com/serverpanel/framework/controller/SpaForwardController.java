package com.serverpanel.framework.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA 前端路由回退。
 *
 * <p>生产包将 Vben 构建产物打进 classpath:/static，本控制器把
 * 非 API/WS 且不含文件扩展名的 GET 请求转发到 index.html，
 * 交给前端路由处理（刷新 /dashboard 等深层链接仍可访问）。
 * 真实静态资源（.js/.css/图片等）由 Spring 资源处理器优先命中。
 */
@Controller
public class SpaForwardController {

    @GetMapping(value = {
        "/",
        "/{path:^(?!api|ws|error)[^.]*$}",
        "/{path:^(?!api|ws|error)[^.]*$}/**"
    })
    public String forward() {
        return "forward:/index.html";
    }
}
