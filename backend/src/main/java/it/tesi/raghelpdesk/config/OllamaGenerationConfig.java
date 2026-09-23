package it.tesi.raghelpdesk.config;

import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Opzioni di generazione condivise da tutte le richieste RAG. */
@Configuration
public class OllamaGenerationConfig {

    @Bean
    public OllamaChatOptions ollamaGenerationOptions(
            @Value("${rag.generation.model}") String model,
            @Value("${rag.generation.max-output-tokens}") int maxOutputTokens,
            @Value("${rag.generation.thinking-enabled}") boolean thinkingEnabled,
            @Value("${rag.generation.keep-alive}") String keepAlive,
            @Value("${rag.generation.temperature}") double temperature,
            @Value("${rag.generation.context-window}") int contextWindow) {
        var builder = OllamaChatOptions.builder()
                .model(model)
                .numPredict(maxOutputTokens)
                .keepAlive(keepAlive)
                .temperature(temperature)
                .numCtx(contextWindow);

        return thinkingEnabled
                ? builder.enableThinking().build()
                : builder.disableThinking().build();
    }
}
