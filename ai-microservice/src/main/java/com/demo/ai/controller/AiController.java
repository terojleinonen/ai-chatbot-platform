package com.demo.ai.controller;

import com.demo.ai.dto.AiRequest;
import com.demo.ai.dto.AiResponse;
import com.demo.ai.dto.TrainFaqDto;
import com.demo.ai.entity.TenantFaqEntity;
import com.demo.ai.service.MultiTenantAiService;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/ai")
@CrossOrigin
public class AiController {
    private final MultiTenantAiService aiService;

    public AiController(MultiTenantAiService aiService) {
        this.aiService = aiService;
    }

    @PostMapping("/reply")
    public AiResponse reply(@RequestBody AiRequest req) {
        String answer = aiService.reply(req.getTenantId(), req.getMessage());
        return new AiResponse(answer);
    }

    @PostMapping("/train/{tenantId}")
    public String train(@PathVariable Long tenantId, @RequestBody List<TrainFaqDto> faqs) {
        List<TenantFaqEntity> entities = new ArrayList<>();
        for (TrainFaqDto dto : faqs) {
            entities.add(new TenantFaqEntity(tenantId, dto.getQuestion(), dto.getAnswer()));
        }
        aiService.replaceFaqsAndTrain(tenantId, entities);
        return "Trained model for tenant " + tenantId + " with " + entities.size() + " FAQs.";
    }
}
