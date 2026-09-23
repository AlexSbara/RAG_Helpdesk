package it.tesi.raghelpdesk.dto;

import java.util.List;

/**
 * Eventi inviati al frontend sul canale SSE di POST /api/chat/stream.
 *
 * Sequenza tipica: un evento {@link Sources} (fonti e tempo di retrieval),
 * una serie di {@link Delta} (frammenti incrementali della risposta) e un
 * evento {@link Done} conclusivo con il tempo di generazione. In caso di
 * fallimento a stream già avviato viene emesso un {@link Error}.
 */
public sealed interface ChatStreamEvent {

    /** Primo evento: ticket recuperati e durata della fase di retrieval. */
    record Sources(List<RetrievedTicket> sources, long retrievalMillis) implements ChatStreamEvent {
    }

    /** Frammento incrementale del testo generato dal modello. */
    record Delta(String text) implements ChatStreamEvent {
    }

    /** Evento conclusivo: durata complessiva della generazione. */
    record Done(long generationMillis) implements ChatStreamEvent {
    }

    /** Errore avvenuto quando lo stream era già aperto (HTTP 200 già inviato). */
    record Error(String message) implements ChatStreamEvent {
    }
}
