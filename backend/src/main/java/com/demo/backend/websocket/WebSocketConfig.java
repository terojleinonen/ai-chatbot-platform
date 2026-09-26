package com.demo.backend.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Any website may connect (the widget is embedded on customer sites); which websites may use a
        // given tenant's chat is checked per message against the tenant's allowed origins.
        // Native WebSocket endpoint (admin panel, @stomp/stompjs): ws://host/ws
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*")
                .addInterceptors(new ChatHandshakeInterceptor());
        // SockJS endpoint (embeddable widget): http://host/ws-chat
        registry.addEndpoint("/ws-chat").setAllowedOriginPatterns("*")
                .addInterceptors(new ChatHandshakeInterceptor()).withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }
}
