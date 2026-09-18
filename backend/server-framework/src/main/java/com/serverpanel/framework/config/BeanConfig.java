package com.serverpanel.framework.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 通用 Bean：密码哈希（BCrypt）。
 *
 * <p>仅取 spring-security-crypto 的 BCrypt 实现，不引入完整 Spring Security 过滤链。
 */
@Configuration
public class BeanConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
