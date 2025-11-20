package com.demo.ai.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "tenant_faqs")
public class TenantFaqEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long tenantId;

    @Column(length = 1000)
    private String question;

    @Column(length = 3000)
    private String answer;

    public TenantFaqEntity() {}

    public TenantFaqEntity(Long tenantId, String question, String answer) {
        this.tenantId = tenantId;
        this.question = question;
        this.answer = answer;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
}
