package com.demo.backend.service;

import com.demo.backend.entity.Faq;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class FaqValidationTest {
    private static Faq faq(String q, String a) {
        Faq f = new Faq();
        f.setQuestion(q);
        f.setAnswer(a);
        return f;
    }

    private static String reason(Faq f) {
        return assertThrows(ResponseStatusException.class, () -> FaqService.validate(f)).getReason();
    }

    @Test
    void trimsAndAcceptsValidFaqs() {
        Faq f = faq("  Q?  ", " A. ");
        FaqService.validate(f);
        assertEquals("Q?", f.getQuestion());
        assertEquals("A.", f.getAnswer());
        assertDoesNotThrow(() -> FaqService.validate(faq("q".repeat(1000), "a".repeat(3000))));
    }

    @Test
    void rejectsMissingOrOversizedFields() {
        assertEquals("Question and answer are required", reason(faq(null, "a")));
        assertEquals("Question and answer are required", reason(faq("q", "  ")));
        assertEquals("Question is too long (at most 1000 characters)", reason(faq("q".repeat(1001), "a")));
        assertEquals("Answer is too long (at most 3000 characters)", reason(faq("q", "a".repeat(3001))));
    }
}
