package it.tesi.raghelpdesk.web;

import it.tesi.raghelpdesk.dto.ChatRequest;
import it.tesi.raghelpdesk.dto.ChatResponse;
import it.tesi.raghelpdesk.dto.ChatStreamEvent;
import it.tesi.raghelpdesk.rag.RagService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * API REST consumata dal frontend Angular.
 * POST /api/chat         { "question": "..." }  ->  risposta + fonti + tempi
 * POST /api/chat/stream  { "question": "..." }  ->  stessi dati come Server-Sent
 *                        Events: la risposta arriva token per token (eventi
 *                        "sources", "delta", "done"; "error" in caso di guasto
 *                        a stream già aperto).
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final RagService ragService;

    public ChatController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ResponseEntity.ok(ragService.answer(request.question()));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> chatStream(@Valid @RequestBody ChatRequest request) {
        return ragService.answerStream(request.question())
                .map(ChatController::toServerSentEvent)
                // A stream avviato lo status 200 è già partito: l'errore non può più
                // passare dal GlobalExceptionHandler e viene inviato come evento SSE.
                .onErrorResume(exception -> {
                    log.error("Errore durante lo streaming della risposta", exception);
                    return Flux.just(toServerSentEvent(new ChatStreamEvent.Error(
                            "Errore interno. Verificare che Ollama sia in esecuzione.")));
                });
    }

    private static ServerSentEvent<ChatStreamEvent> toServerSentEvent(ChatStreamEvent event) {
        String name = switch (event) {
            case ChatStreamEvent.Sources ignored -> "sources";
            case ChatStreamEvent.Delta ignored -> "delta";
            case ChatStreamEvent.Done ignored -> "done";
            case ChatStreamEvent.Error ignored -> "error";
        };
        return ServerSentEvent.builder(event).event(name).build();
    }
}
