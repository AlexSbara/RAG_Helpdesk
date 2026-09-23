package it.tesi.raghelpdesk.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Corpo della richiesta inviata dal frontend: la domanda dell'utente. */
public record ChatRequest(
        @NotBlank @Size(max = 2000) String question) {
}
