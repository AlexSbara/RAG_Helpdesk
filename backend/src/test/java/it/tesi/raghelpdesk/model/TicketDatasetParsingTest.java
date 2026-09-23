package it.tesi.raghelpdesk.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

class TicketDatasetParsingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesMetadataAndLoadsAllOriginalTickets() throws Exception {
        var resource = new ClassPathResource("data/tickets_dataset.json");
        TicketDataset dataset = objectMapper.readValue(
                resource.getInputStream(), TicketDataset.class);

        assertThat(dataset.meta().path("totale_ticket").asInt()).isEqualTo(38);
        assertThat(dataset.tickets()).hasSize(38);
        assertThat(new HashSet<>(dataset.tickets().stream().map(Ticket::id).toList()))
                .hasSize(38);
        assertThat(dataset.tickets().getFirst().tags()).contains("password");
        assertThat(dataset.tickets().getFirst().dataChiusura()).isEqualTo("2025-02-11");
        assertThat(dataset.tickets().getFirst().stato()).isEqualTo("Risolto");
    }
}
