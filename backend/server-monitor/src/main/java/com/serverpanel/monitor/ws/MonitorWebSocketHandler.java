package com.serverpanel.monitor.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 监控 WebSocket：向所有已认证订阅者广播最新帧。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonitorWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("Monitor WS connected: {} (total {})", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("Monitor WS closed: {} (total {})", session.getId(), sessions.size());
    }

    /** 广播一帧（JSON）；单连接失败不影响其他订阅者 */
    public void broadcast(Object frame) {
        if (sessions.isEmpty()) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(frame);
            TextMessage message = new TextMessage(payload);
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    try {
                        synchronized (session) {
                            session.sendMessage(message);
                        }
                    } catch (IOException e) {
                        log.warn("Monitor WS send failed to {}: {}", session.getId(), e.getMessage());
                        sessions.remove(session);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Monitor WS broadcast failed: {}", e.getMessage());
        }
    }
}
