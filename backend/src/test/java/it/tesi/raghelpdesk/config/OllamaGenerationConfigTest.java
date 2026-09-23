package it.tesi.raghelpdesk.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.ThinkOption;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaGenerationConfigTest {

    @Test
    void buildsBoundedNonThinkingOptionsForTheConfiguredModel() {
        var options = new OllamaGenerationConfig().ollamaGenerationOptions(
                "gemma4:e4b", 384, false, "10m", 0.2, 4096);

        assertThat(options.getModel()).isEqualTo("gemma4:e4b");
        assertThat(options.getNumPredict()).isEqualTo(384);
        assertThat(options.getKeepAlive()).isEqualTo("10m");
        assertThat(options.getTemperature()).isEqualTo(0.2);
        assertThat(options.getNumCtx()).isEqualTo(4096);
        assertThat(options.getThinkOption())
                .isInstanceOfSatisfying(ThinkOption.ThinkBoolean.class,
                        thinking -> assertThat(thinking.enabled()).isFalse());
    }
}
