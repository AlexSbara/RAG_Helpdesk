package it.tesi.raghelpdesk.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record TicketDataset(JsonNode meta, List<Ticket> tickets) {
}
