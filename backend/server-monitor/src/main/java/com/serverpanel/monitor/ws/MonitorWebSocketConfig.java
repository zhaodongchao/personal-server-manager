package com.serverpanel.monitor.ws;

import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * WebSocket 配置：/ws/monitor（query 参数 token 认证）。
 *
 * <p>浏览器 WS 无法携带 Authorization 头，token 通过 URL 参数传递，
 * 握手时用 Sa-Token 校验有效性。
 */
@Slf4j
@Configuration
@EnableWebSocket
public class MonitorWebSocketConfig implements WebSocketConfigurer {

    private final MonitorWebSocketHandler monitorWebSocketHandler;

    public MonitorWebSocketConfig(MonitorWebSocketHandler monitorWebSocketHandler) {
        this.monitorWebSocketHandler = monitorWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(monitorWebSocketHandler, "/ws/monitor")
                .addInterceptors(new TokenHandshakeInterceptor())
                .setAllowedOrigins("*");
    }

    /** 握手 token 校验 */
    static class TokenHandshakeInterceptor implements HandshakeInterceptor {

        @Override
        public boolean beforeHandshake(
                @NonNull ServerHttpRequest request,
                @NonNull ServerHttpResponse response,
                @NonNull WebSocketHandler wsHandler,
                @NonNull Map<String, Object> attributes) {
            String token = null;
            String query = request.getURI().getQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length == 2 && "token".equals(kv[0])) {
                        token = kv[1];
                        break;
                    }
                }
            }
            Object loginId = token == null ? null : StpUtil.getLoginIdByToken(token);
            if (loginId == null) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                log.warn("Monitor WS handshake rejected: invalid token");
                return false;
            }
            attributes.put("userId", loginId);
            return true;
        }

        @Override
        public void afterHandshake(
                @NonNull ServerHttpRequest request,
                @NonNull ServerHttpResponse response,
                @NonNull WebSocketHandler wsHandler,
                Exception exception) {
            // no-op
        }
    }
}
