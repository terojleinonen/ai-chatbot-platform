package com.demo.ai.controller;

import com.demo.ai.dto.AiRequest;
import com.demo.ai.dto.AiResponse;
import com.demo.ai.dto.ChatExchange;
import com.demo.ai.dto.TrainFaqDto;
import com.demo.ai.entity.TenantFaqEntity;
import com.demo.ai.service.MultiTenantAiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Internal API for the backend only (API key required); never called from browsers, so no CORS. */
@RestController
@RequestMapping("/ai")
public class AiController {
    static final int MAX_MESSAGE_LENGTH = 4000;
    static final int MAX_FAQS = 5000;
    static final int MAX_QUESTION_LENGTH = 1000;
    static final int MAX_ANSWER_LENGTH = 3000;
    static final int MAX_HISTORY = 20;
    /** Earlier replies are Claude's, so they can be longer than an FAQ answer. */
    static final int MAX_HISTORY_ANSWER_LENGTH = 20000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final MultiTenantAiService aiService;

    public AiController(MultiTenantAiService aiService) {
        this.aiService = aiService;
    }

    @PostMapping("/reply")
    public AiResponse reply(@RequestBody AiRequest req) {
        validate(req);
        return new AiResponse(aiService.reply(req.getTenantId(), req.getMessage(), req.getHistory()));
    }

    /**
     * The reply as newline-delimited JSON, streamed while Claude writes it: any number of {"delta": text} lines, then
     * {"reply": complete text, "done": true}, which replaces the deltas (they differ if Claude failed part-way).
     */
    @PostMapping("/reply/stream")
    public void replyStream(@RequestBody AiRequest req, HttpServletResponse response) throws IOException {
        validate(req);
        response.setContentType("application/x-ndjson");
        response.setCharacterEncoding("UTF-8");
        OutputStream out = response.getOutputStream();
        String reply = aiService.reply(req.getTenantId(), req.getMessage(), req.getHistory(),
                text -> writeLine(out, Map.of("delta", text)));
        writeLine(out, Map.of("reply", reply, "done", true));
    }

    /** Writes and flushes one JSON line; a disconnected client aborts the reply (and with it the Claude stream). */
    private static void writeLine(OutputStream out, Map<String, Object> line) {
        try {
            out.write(JSON.writeValueAsBytes(line));
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void validate(AiRequest req) {
        if (req.getTenantId() == null) throw badRequest("tenantId is required");
        if (req.getMessage() == null || req.getMessage().isBlank()) throw badRequest("message is required");
        if (req.getMessage().length() > MAX_MESSAGE_LENGTH) throw badRequest("message is too long");
        List<ChatExchange> history = req.getHistory();
        if (history.size() > MAX_HISTORY) throw badRequest("at most " + MAX_HISTORY + " history entries");
        for (ChatExchange ex : history) {
            if (ex == null || ex.question() == null || ex.question().isBlank() || ex.answer() == null || ex.answer().isBlank()
                    || ex.question().length() > MAX_MESSAGE_LENGTH || ex.answer().length() > MAX_HISTORY_ANSWER_LENGTH) {
                throw badRequest("each history entry needs a question (max " + MAX_MESSAGE_LENGTH + ") and answer (max "
                        + MAX_HISTORY_ANSWER_LENGTH + ")");
            }
        }
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
