package it.tesi.raghelpdesk.rag;

import it.tesi.raghelpdesk.dto.ChatResponse;
import it.tesi.raghelpdesk.dto.ChatStreamEvent;
import it.tesi.raghelpdesk.dto.RetrievedTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Cuore della pipeline RAG: RETRIEVAL + GENERATION.
 *
 * Per ogni domanda dell'utente:
 *  1. RETRIEVAL  - la domanda viene trasformata in embedding e confrontata
 *                  con i ticket indicizzati; si prendono i top-K più simili;
 *  2. AUGMENT    - i ticket recuperati vengono inseriti nel prompt come
 *                  contesto, con istruzioni precise per il modello;
 *  3. GENERATION - l'LLM (Ollama in locale, o API cloud) genera la risposta
 *                  basandosi SOLO sul contesto fornito.
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private static final String SYSTEM_PROMPT = """
            Sei un assistente virtuale di helpdesk tecnico aziendale.
            Rispondi in italiano alla domanda dell'utente basandoti ESCLUSIVAMENTE
            sui ticket risolti forniti nel contesto qui sotto.

            Regole:
            - Se il contesto contiene una soluzione pertinente, spiegala in modo
              chiaro, passo per passo.
            - Cita sempre gli ID dei ticket da cui hai tratto la risposta
              (es. "come nel ticket TCK-012").
            - Se il contesto NON contiene informazioni pertinenti, dillo
              esplicitamente e suggerisci di aprire un nuovo ticket:
              NON inventare soluzioni.
            - Sii sintetico: al massimo 5-6 frasi complessive oppure un breve
              elenco di passi; non ripetere la domanda e non aggiungere
              premesse o conclusioni superflue.

            CONTESTO (ticket risolti):
            {context}
            """;

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    @Value("${rag.top-k}")
    private int topK;

    @Value("${rag.similarity-threshold}")
    private double similarityThreshold;

    public RagService(
            VectorStore vectorStore,
            ChatClient.Builder chatClientBuilder,
            OllamaChatOptions ollamaGenerationOptions) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder
                .defaultOptions(ollamaGenerationOptions)
                .build();
    }

    public ChatResponse answer(String question) {
        // ---- 1. RETRIEVAL ----
        long t0 = System.currentTimeMillis();
        List<Document> retrieved = retrieve(question, topK);
        long retrievalMillis = System.currentTimeMillis() - t0;

        // ---- 2. AUGMENT: costruzione del contesto ----
        String context = buildContext(retrieved);

        // ---- 3. GENERATION ----
        long t1 = System.currentTimeMillis();
        String answer = chatClient.prompt()
                .system(s -> s.text(SYSTEM_PROMPT).param("context", context))
                .user(question)
                .call()
                .content();
        long generationMillis = System.currentTimeMillis() - t1;

        log.info("query answered: retrieved={} retrievalMs={} generationMs={}",
                retrieved.size(), retrievalMillis, generationMillis);

        return new ChatResponse(answer, toSources(retrieved), retrievalMillis, generationMillis);
    }

    /**
     * Variante in streaming di {@link #answer(String)}: stesso retrieval e
     * stesso prompt, ma la risposta viene emessa token per token man mano che
     * il modello la genera. Riduce drasticamente la latenza percepita: il
     * primo frammento arriva dopo la sola lettura del prompt, senza attendere
     * l'intera generazione.
     */
    public Flux<ChatStreamEvent> answerStream(String question) {
        // ---- 1. RETRIEVAL (sincrono, è la parte rapida della pipeline) ----
        long t0 = System.currentTimeMillis();
        List<Document> retrieved = retrieve(question, topK);
        long retrievalMillis = System.currentTimeMillis() - t0;

        // ---- 2. AUGMENT ----
        String context = buildContext(retrieved);

        // ---- 3. GENERATION in streaming ----
        long t1 = System.currentTimeMillis();
        Flux<ChatStreamEvent> deltas = chatClient.prompt()
                .system(s -> s.text(SYSTEM_PROMPT).param("context", context))
                .user(question)
                .stream()
                .content()
                .map(ChatStreamEvent.Delta::new);

        return Flux.concat(
                Flux.just(new ChatStreamEvent.Sources(toSources(retrieved), retrievalMillis)),
                deltas,
                // defer: il tempo di generazione va misurato quando i delta sono finiti
                Flux.defer(() -> {
                    long generationMillis = System.currentTimeMillis() - t1;
                    log.info("query answered (stream): retrieved={} retrievalMs={} generationMs={}",
                            retrieved.size(), retrievalMillis, generationMillis);
                    return Flux.just(new ChatStreamEvent.Done(generationMillis));
                }));
    }

    private static String buildContext(List<Document> retrieved) {
        return retrieved.isEmpty()
                ? "(nessun ticket pertinente trovato)"
                : retrieved.stream()
                        .map(d -> "--- Ticket " + d.getMetadata().get("ticketId") + " ---\n" + d.getText())
                        .collect(Collectors.joining("\n\n"));
    }

    private static List<RetrievedTicket> toSources(List<Document> retrieved) {
        return retrieved.stream()
                .map(d -> new RetrievedTicket(
                        metadata(d, "ticketId"),
                        metadata(d, "titolo"),
                        metadata(d, "categoria"),
                        metadata(d, "priorita"),
                        d.getScore() != null ? d.getScore() : 0.0,
                        d.getText()))
                .toList();
    }

    /** Metadato del documento come stringa, vuota se assente (mai il letterale "null"). */
    private static String metadata(Document document, String key) {
        Object value = document.getMetadata().get(key);
        return value != null ? String.valueOf(value) : "";
    }

    /**
     * Solo retrieval, senza generazione: riusato dal modulo di valutazione
     * per calcolare Precision@k, Recall@k e MRR.
     */
    public List<Document> retrieve(String question, int k) {
        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(k)
                        .similarityThreshold(similarityThreshold)
                        .build());
    }
}
