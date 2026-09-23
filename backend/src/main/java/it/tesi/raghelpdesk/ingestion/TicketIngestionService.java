package it.tesi.raghelpdesk.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.tesi.raghelpdesk.model.Ticket;
import it.tesi.raghelpdesk.model.TicketDataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Fase di INGESTION della pipeline RAG.
 *
 * All'avvio dell'applicazione:
 *  1. legge i ticket dal file JSON (la base di conoscenza);
 *  2. trasforma ogni ticket in un Document di Spring AI
 *     (testo indicizzabile + metadati);
 *  3. lo aggiunge al vector store: qui avviene il calcolo dell'embedding;
 *  4. salva il vector store su file, così ai riavvii successivi
 *     gli embedding non vengono ricalcolati.
 */
@Service
public class TicketIngestionService {

    private static final Logger log = LoggerFactory.getLogger(TicketIngestionService.class);

    private final SimpleVectorStore vectorStore;
    private final ObjectMapper objectMapper;

    @Value("${rag.dataset-path}")
    private Resource datasetResource;

    @Value("${rag.vector-store-file}")
    private String vectorStoreFile;

    public TicketIngestionService(SimpleVectorStore vectorStore, ObjectMapper objectMapper) {
        this.vectorStore = vectorStore;
        this.objectMapper = objectMapper;
    }

    @Bean
    ApplicationRunner ingestOnStartup() {
        return args -> {
            File persisted = new File(vectorStoreFile);
            String datasetFingerprint;
            try (InputStream datasetStream = datasetResource.getInputStream()) {
                datasetFingerprint = fingerprint(datasetStream);
            }

            if (canReusePersistedStore(persisted, datasetFingerprint)) {
                long start = System.currentTimeMillis();
                vectorStore.load(persisted);
                log.info("Vector store caricato da {} in {} ms",
                        vectorStoreFile, System.currentTimeMillis() - start);
                return;
            }

            long start = System.currentTimeMillis();
            TicketDataset dataset;
            try (InputStream datasetStream = datasetResource.getInputStream()) {
                dataset = objectMapper.readValue(datasetStream, TicketDataset.class);
            }
            List<Document> documents = createDocuments(dataset);

            // Qui Spring AI calcola l'embedding di ogni documento (ONNX, in-process)
            vectorStore.add(documents);
            vectorStore.save(persisted);
            Files.writeString(fingerprintSidecar(persisted).toPath(), datasetFingerprint,
                    StandardCharsets.UTF_8);

            // TODO tesi: questo tempo è il "tempo di indicizzazione" del Capitolo 5
            log.info("Indicizzati {} ticket in {} ms (embedding inclusi)",
                    documents.size(), System.currentTimeMillis() - start);
        };
    }

    static List<Document> createDocuments(TicketDataset dataset) {
        if (dataset.tickets() == null || dataset.tickets().isEmpty()) {
            throw new IllegalStateException("Il dataset non contiene ticket");
        }

        return dataset.tickets().stream()
                .map(t -> new Document(
                        t.toIndexableText(),
                        Map.of(
                                "ticketId", t.id(),
                                "titolo", t.titolo(),
                                "categoria", t.categoria(),
                                "priorita", t.priorita(),
                                "stato", t.stato(),
                                "tags", String.join(", ", t.tags()))))
                .toList();
    }

    static String fingerprint(InputStream inputStream) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 non disponibile", exception);
        }

        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            digest.update(buffer, 0, bytesRead);
        }
        return HexFormat.of().withUpperCase().formatHex(digest.digest());
    }

    static boolean canReusePersistedStore(File persistedStore, String datasetFingerprint) throws IOException {
        File fingerprintFile = fingerprintSidecar(persistedStore);
        return persistedStore.exists()
                && fingerprintFile.isFile()
                && Files.readString(fingerprintFile.toPath(), StandardCharsets.UTF_8)
                        .trim()
                        .equals(datasetFingerprint);
    }

    private static File fingerprintSidecar(File persistedStore) {
        return new File(persistedStore.getPath() + ".sha256");
    }
}
