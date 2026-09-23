package it.tesi.raghelpdesk.dto;

/**
 * Un ticket recuperato dal vector store, con il punteggio di similarità.
 *
 * {@code estratto} è il testo indicizzato del ticket (titolo, categoria,
 * problema, soluzione): il frontend ne mostra la sola parte "Soluzione:"
 * nella scheda del ticket, così l'utente vede da dove viene la risposta.
 */
public record RetrievedTicket(
        String id,
        String titolo,
        String categoria,
        String priorita,
        double score,
        String estratto) {
}
