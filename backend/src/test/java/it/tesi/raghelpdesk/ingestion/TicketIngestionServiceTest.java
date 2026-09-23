package it.tesi.raghelpdesk.ingestion;

import it.tesi.raghelpdesk.model.Ticket;
import it.tesi.raghelpdesk.model.TicketDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketIngestionServiceTest {

    @Test
    void rejectsAnEmptyDataset() {
        assertThatThrownBy(() -> TicketIngestionService.createDocuments(
                new TicketDataset(null, List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Il dataset non contiene ticket");
    }

    @Test
    void createsDocumentMetadataFromTicketStatusAndTags() {
        Ticket ticket = new Ticket(
                "T-1001", "Account", "Password scaduta", "Accesso negato",
                "Reimpostare la password", "Risolto", "Media",
                List.of("password", "dominio"), "2025-02-11");

        var documents = TicketIngestionService.createDocuments(
                new TicketDataset(null, List.of(ticket)));

        assertThat(documents).singleElement().satisfies(document -> {
            assertThat(document.getMetadata())
                    .containsEntry("ticketId", "T-1001")
                    .containsEntry("stato", "Risolto")
                    .containsEntry("tags", "password, dominio");
        });
    }

    @Test
    void reusesPersistedStoreOnlyWhenItsFingerprintMatchesDataset(@TempDir Path temporaryDirectory)
            throws Exception {
        Path persistedStore = temporaryDirectory.resolve("vector-store.json");
        Files.writeString(persistedStore, "persisted store");
        String fingerprint = TicketIngestionService.fingerprint(
                new ByteArrayInputStream("dataset".getBytes(StandardCharsets.UTF_8)));
        Path sidecar = temporaryDirectory.resolve("vector-store.json.sha256");

        Files.writeString(sidecar, fingerprint);
        assertThat(TicketIngestionService.canReusePersistedStore(persistedStore.toFile(), fingerprint))
                .isTrue();

        Files.writeString(sidecar, "different fingerprint");
        assertThat(TicketIngestionService.canReusePersistedStore(persistedStore.toFile(), fingerprint))
                .isFalse();

        Files.delete(sidecar);
        assertThat(TicketIngestionService.canReusePersistedStore(persistedStore.toFile(), fingerprint))
                .isFalse();
    }

    @Test
    void fingerprintsDatasetBytesWithSha256() throws Exception {
        assertThat(TicketIngestionService.fingerprint(
                new ByteArrayInputStream("dataset".getBytes(StandardCharsets.UTF_8))))
                .isEqualTo("B277FD623676A525C29B9EB155AFC8C9010681814CEAFB2D7627F47B9A232576");
    }
}
