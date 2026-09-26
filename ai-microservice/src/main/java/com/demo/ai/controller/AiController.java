package com.demo.ai.controller;

import com.demo.ai.dto.AiRequest;
import com.demo.ai.dto.AiResponse;
import com.demo.ai.dto.TrainFaqDto;
import com.demo.ai.entity.TenantFaqEntity;
import com.demo.ai.service.MultiTenantAiService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/** Internal API for the backend only (API key required); never called from browsers, so no CORS. */
@RestController
@RequestMapping("/ai")
public class AiController {
    static final int MAX_MESSAGE_LENGTH = 4000;
    static final int MAX_FAQS = 5000;
    static final int MAX_QUESTION_LENGTH = 1000;
    static final int MAX_ANSWER_LENGTH = 3000;

    private final MultiTenantAiService aiService;

    public AiController(MultiTenantAiService aiService) {
        this.aiService = aiService;
    }

    @PostMapping("/reply")
    public AiResponse reply(@RequestBody AiRequest req) {
        if (req.getTenantId() == null) throw badRequest("tenantId is required");
        if (req.getMessage() == null || req.getMessage().isBlank()) throw badRequest("message is required");
        if (req.getMessage().length() > MAX_MESSAGE_LENGTH) throw badRequest("message is too long");
        String answer = aiService.reply(req.getTenantId(), req.getMessage());
        return new AiResponse(answer);
    }

    @PostMapping("/train/{tenantId}")
    public String train(@PathVariable Long tenantId, @RequestBody List<TrainFaqDto> faqs) {
        if (faqs == null || faqs.size() > MAX_FAQS) throw badRequest("between 0 and " + MAX_FAQS + " FAQs");
        List<TenantFaqEntity> entities = new ArrayList<>();
        for (TrainFaqDto dto : faqs) {
            if (dto.getQuestion() == null || dto.getAnswer() == null
                    || dto.getQuestion().length() > MAX_QUESTION_LENGTH || dto.getAnswer().length() > MAX_ANSWER_LENGTH) {
                throw badRequest("each FAQ needs a question (max " + MAX_QUESTION_LENGTH + ") and answer (max "
                        + MAX_ANSWER_LENGTH + ")");
            }
            entities.add(new TenantFaqEntity(tenantId, dto.getQuestion(), dto.getAnswer()));
        }
        aiService.replaceFaqsAndTrain(tenantId, entities);
        return "Trained model for tenant " + tenantId + " with " + entities.size() + " FAQs.";
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
