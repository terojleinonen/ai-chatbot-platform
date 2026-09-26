package com.demo.backend.websocket;

public class ChatMessage {
    public String sessionId;
    /** The tenant's widget key (see Tenants page in the admin panel). */
    public String widgetKey;
    public String content;
}
