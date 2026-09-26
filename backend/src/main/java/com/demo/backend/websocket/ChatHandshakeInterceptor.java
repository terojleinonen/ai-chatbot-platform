package com.demo.backend.websocket;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.InetSocketAddress;
import java.util.Map;

/**
 * Records where a chat connection came from, for per-tenant origin checks and per-IP rate limiting.
 * Behind a reverse proxy, server.forward-headers-strategy must be set so the remote address is the client's.
 */
public class ChatHandshakeInterceptor implements HandshakeInterceptor {
    public static final String ORIGIN = "chat.origin";
    public static final String CLIENT_IP = "chat.clientIp";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String origin = request.getHeaders().getOrigin();
        if (origin != null) attributes.put(ORIGIN, origin.toLowerCase());
        InetSocketAddress remote = request.getRemoteAddress();
        attributes.put(CLIENT_IP, remote != null && remote.getAddress() != null
                ? remote.getAddress().getHostAddress() : "unknown");
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
