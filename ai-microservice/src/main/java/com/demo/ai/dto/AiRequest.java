package com.demo.ai.dto;

public class AiRequest {
    private Long tenantId;
    private String message;

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
