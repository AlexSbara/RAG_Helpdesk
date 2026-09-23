package it.tesi.raghelpdesk.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configura il "database vettoriale simulato": SimpleVectorStore.
 *
 * È un archivio in memoria che confronta i vettori con la similarità
 * del coseno. Grazie all'astrazione VectorStore di Spring AI, in futuro
 * basterebbe cambiare questo bean (es. pgvector, Qdrant) senza toccare
 * il resto del codice.
 */
@Configuration
public class VectorStoreConfig {

    @Bean
    public SimpleVectorStore vectorStore(EmbeddingModel embeddingModel) {
        // L'EmbeddingModel (ONNX, in-process) è auto-configurato da Spring AI
        // in base alle proprietà spring.ai.embedding.transformer.* in application.yml
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
