package it.tesi.raghelpdesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Punto di ingresso dell'applicazione.
 * Avvia il backend Spring Boot che espone le API REST dell'assistente virtuale.
 */
@SpringBootApplication
public class RagHelpdeskApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagHelpdeskApplication.class, args);
    }
}
