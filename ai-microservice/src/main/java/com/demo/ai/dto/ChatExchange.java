package com.demo.ai.dto;

/** An earlier question in the same chat and the answer the customer was shown. */
public record ChatExchange(String question, String answer) {}
