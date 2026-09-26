package com.demo.ai.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class ApiKeyFilterTest {
    private static final String KEY = "a-real-ai-key-that-is-long-enough-123";

    private int status(ApiKeyFilter filter, String header) throws Exception {
        var request = new MockHttpServletRequest("POST", "/ai/reply");
        if (header != null) request.addHeader("X-API-KEY", header);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void acceptsOnlyTheConfiguredKey() throws Exception {
        var filter = new ApiKeyFilter(KEY, false);
        assertEquals(200, status(filter, KEY));
        assertEquals(401, status(filter, null));
        assertEquals(401, status(filter, KEY + "x"));
        assertEquals(401, status(filter, KEY.substring(0, 10)));
    }

    @Test
    void refusesToStartWithWeakOrDevKeys() {
        assertThrows(IllegalStateException.class, () -> new ApiKeyFilter("", false));
        assertThrows(IllegalStateException.class, () -> new ApiKeyFilter("MY_INTERNAL_AI_KEY", false));
        String dev = "dev-only-insecure-ai-key-do-not-use-in-production";
        assertThrows(IllegalStateException.class, () -> new ApiKeyFilter(dev, false));
        assertDoesNotThrow(() -> new ApiKeyFilter(dev, true));
    }
}
