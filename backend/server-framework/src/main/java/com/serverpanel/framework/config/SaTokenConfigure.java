package com.serverpanel.framework.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;

/**
 * Web MVC 配置：Sa-Token 登录拦截 + CORS。
 *
 * <p>鉴权模型：/api/** 默认要求登录，白名单放行登录接口与文档资源；
 * 细粒度权限（perms）由各 Controller 上的 @SaCheckPermission 声明。
 * 认证走 Authorization 头而非 Cookie，天然免疫 CSRF。
 */
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/v1/auth/login",
                        "/error",
                        // 邮箱验证码（登录 / 注册 / 重置密码）：enabled / code / login / register / reset-password
                        // 免登录放行；场景与限额校验在 Service 内（冷却 / 日限 / 一次性消费）
                        "/api/v1/auth/mail/enabled",
                        "/api/v1/auth/mail/code",
                        "/api/v1/auth/mail/login",
                        "/api/v1/auth/register",
                        "/api/v1/auth/reset-password",
                        // 点选人机校验：click 下发与 verify 均免登录放行
                        "/api/v1/auth/captcha/**",
                        // 第三方登录：providers/authorize/login 免登录放行
                        //（authorize 的 intent=bind 在 OAuthService 内显式 checkLogin）；
                        // bind/bindings/binding 不放行，保持登录拦截。
                        "/api/v1/auth/oauth/providers",
                        "/api/v1/auth/oauth/*/authorize",
                        "/api/v1/auth/oauth/*/login");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Authorization", "Content-Disposition")
                .maxAge(3600);
    }
}
