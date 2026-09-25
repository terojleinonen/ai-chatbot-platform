package com.demo.ai.model;

import com.demo.ai.entity.TenantFaqEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TenantModelTest {
    private final TenantModel model = new TenantModel(List.of(
            new TenantFaqEntity(1L, "What are your opening hours?", "9-17 on weekdays."),
            new TenantFaqEntity(1L, "How do I reset my password?", "Use the 'Forgot password' link."),
            new TenantFaqEntity(1L, "Do you ship internationally?", "Yes, to most countries.")
    ));

    @Test
    void picksMostSimilarFaq() {
        assertEquals("Use the 'Forgot password' link.", model.getBestAnswer("I forgot my password, how to reset it"));
        assertEquals("9-17 on weekdays.", model.getBestAnswer("opening hours?"));
    }

    @Test
    void matchesWordForms() {
        assertEquals("9-17 on weekdays.", model.getBestAnswer("when are you open"));
    }

    @Test
    void doesNotMatchOnStopWordsAlone() {
        assertEquals("I'm not sure yet. Could you rephrase your question?", model.getBestAnswer("do you have what I need"));
    }

    @Test
    void fallsBackWhenNothingMatches() {
        assertEquals("I'm not sure yet. Could you rephrase your question?", model.getBestAnswer("banana"));
        assertEquals("I'm not sure yet. Could you rephrase your question?", model.getBestAnswer("   "));
    }

    @Test
    void handlesEmptyFaqList() {
        assertEquals("No FAQ data available for this tenant yet.", new TenantModel(List.of()).getBestAnswer("hi"));
    }
}
