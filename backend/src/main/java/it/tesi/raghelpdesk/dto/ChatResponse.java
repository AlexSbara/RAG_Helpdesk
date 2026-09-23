package it.tesi.raghelpdesk.dto;

import java.util.List;

/**
 * Risposta dell'assistente: testo generato, ticket usati come fonti
 * e tempi di esecuzione delle fasi (utili per il capitolo dei risultati).
 */
public record ChatResponse(
        String answer,
        List<RetrievedTicket> sources,
        long retrievalMillis,
        long generationMillis) {
}
