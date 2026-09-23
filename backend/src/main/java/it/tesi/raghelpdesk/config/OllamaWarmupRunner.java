package it.tesi.raghelpdesk.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * Warm-up di Ollama all'avvio dell'applicazione.
 *
 * Il caricamento del modello generativo da disco è la componente più lenta e
 * variabile della latenza (decine di secondi a freddo). Precaricandolo qui,
 * la prima domanda dell'utente trova sempre il modello già residente in
 * memoria e paga solo il tempo di generazione.
 *
 * Il precaricamento avviene in un thread separato per non ritardare l'avvio
 * (in parallelo all'indicizzazione dei ticket) e non è bloccante: se Ollama
 * non è raggiungibile l'applicazione parte comunque e il modello verrà
 * caricato alla prima richiesta.
 */
@Configuration
public class OllamaWarmupRunner {

    private static final Logger log = LoggerFactory.getLogger(OllamaWarmupRunner.class);

    @Bean
    ApplicationRunner preloadGenerationModel(
            @Value("${spring.ai.ollama.base-url}") String baseUrl,
            @Value("${rag.generation.model}") String model,
            @Value("${rag.generation.keep-alive}") String keepAlive,
            @Value("${rag.generation.context-window}") int contextWindow) {
        return args -> {
            Thread warmup = new Thread(
                    () -> preload(baseUrl, model, keepAlive, contextWindow), "ollama-warmup");
            warmup.setDaemon(true);
            warmup.start();
        };
    }

    private void preload(String baseUrl, String model, String keepAlive, int contextWindow) {
        long start = System.currentTimeMillis();
        try {
            var requestFactory = new JdkClientHttpRequestFactory();
            // Il caricamento a freddo può superare il minuto se la cache disco è vuota
            requestFactory.setReadTimeout(Duration.ofMinutes(3));

            RestClient.builder()
                    .baseUrl(baseUrl)
                    .requestFactory(requestFactory)
                    .build()
                    .post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    // Una richiesta senza prompt fa solo caricare il modello in memoria.
                    // num_ctx deve combaciare con le richieste reali: un valore diverso
                    // farebbe ricaricare il runner alla prima domanda (cold start nascosto).
                    .body(Map.of(
                            "model", model,
                            "keep_alive", keepAlive,
                            "options", Map.of("num_ctx", contextWindow)))
                    .retrieve()
                    .toBodilessEntity();

            log.info("Warm-up Ollama completato: modello {} residente in {} ms",
                    model, System.currentTimeMillis() - start);
        }
        catch (Exception exception) {
            log.warn("Warm-up Ollama non riuscito ({}): il modello verrà caricato alla prima richiesta",
                    exception.getMessage());
        }
    }
}
