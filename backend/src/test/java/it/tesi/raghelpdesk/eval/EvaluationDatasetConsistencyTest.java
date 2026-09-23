package it.tesi.raghelpdesk.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationDatasetConsistencyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void evaluationQueriesReferenceExistingTicketsAndContainRequiredFields() throws Exception {
        JsonNode dataset = readJson("data/tickets_dataset.json");
        JsonNode queries = readJson("data/eval_queries.json");
        Set<String> ticketIds = new HashSet<>();

        dataset.path("tickets").forEach(ticket -> ticketIds.add(ticket.path("id").asText()));

        assertThat(ticketIds).hasSize(38);
        assertThat(queries).hasSize(8);
        queries.forEach(query -> {
            assertThat(query.path("question").asText()).isNotBlank();
            assertThat(query.path("relevantTicketIds")).isNotEmpty();
            query.path("relevantTicketIds").forEach(id ->
                    assertThat(ticketIds).contains(id.asText()));
        });
    }

    private JsonNode readJson(String path) throws Exception {
        return objectMapper.readTree(new ClassPathResource(path).getInputStream());
    }
}
