package cz.cuni.mff.d3s.autodebugger.model.common.technique;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestTechniqueConfigTest {

    @Test
    void givenIdOnly_whenBuild_thenStoresId() {
        var cfg = TestTechniqueConfig.ofId("trace-based-basic");
        assertEquals("trace-based-basic", cfg.getId());
        assertTrue(cfg.getParameters().isEmpty());
        assertNull(cfg.getApiKey());
    }

    @Test
    void givenParamsAndApiKey_whenBuild_thenAccessibleSafely() {
        var cfg = TestTechniqueConfig.builder()
                .id("ai-assisted")
                .param("maxTokens", "2048")
                .apiKey("secret")
                .build();
        assertEquals("2048", cfg.getParameterOrDefault("maxTokens", "512"));
        assertEquals("x", cfg.getParameterOrDefault("missing", "x"));
        assertEquals("secret", cfg.getApiKey());
        assertThrows(UnsupportedOperationException.class, () -> cfg.getParameters().put("k","v"));
    }
}

