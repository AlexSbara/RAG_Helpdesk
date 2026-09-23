package it.tesi.raghelpdesk.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Rappresenta un ticket risolto della base di conoscenza.
 * I campi rispecchiano la struttura del dataset originale dei ticket.
 */
public record Ticket(
        String id,
        String categoria,
        String titolo,
        String descrizione,
        String soluzione,
        String stato,
        String priorita,
        @JsonProperty("tag") List<String> tags,
        @JsonProperty("data_chiusura") String dataChiusura) {

    /**
     * Testo che verrà trasformato in embedding e indicizzato.
     * Concatena titolo, descrizione e soluzione: è su questo testo
     * che avviene la ricerca semantica.
     */
    public String toIndexableText() {
        return "Titolo: " + titolo + "\n"
             + "Categoria: " + categoria + "\n"
             + "Problema: " + descrizione + "\n"
             + "Soluzione: " + soluzione;
    }
}
