package com.demo.ai.dto;

import java.util.List;

public class AiRequest {
    private Long tenantId;
    private String message;
    /** Earlier exchanges of this chat, oldest first (optional). */
    private List<ChatExchange> history;

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public List<ChatExchange> getHistory() { return history == null ? List.of() : history; }
    public void setHistory(List<ChatExchange> history) { this.history = history; }
}
