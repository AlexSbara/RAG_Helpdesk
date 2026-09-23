package it.tesi.raghelpdesk.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.tesi.raghelpdesk.rag.RagService;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calcola le metriche di retrieval richieste dal Capitolo 5 della tesi:
 *
 *  - Precision@k : dei k ticket recuperati, quanti sono davvero pertinenti?
 *  - Recall@k    : dei ticket pertinenti esistenti, quanti compaiono nei primi k?
 *  - MRR         : in media, quanto in alto compare il PRIMO risultato
 *                  pertinente? (1 = sempre in prima posizione)
 *
 * Le metriche vengono calcolate su un insieme di query di test con
 * giudizi di rilevanza annotati a mano (eval_queries.json).
 */
@Service
public class EvaluationService {

    private static final int[] K_VALUES = {1, 3, 5};

    private final RagService ragService;
    private final ObjectMapper objectMapper;

    @Value("${rag.eval-path}")
    private Resource evalResource;

    public EvaluationService(RagService ragService, ObjectMapper objectMapper) {
        this.ragService = ragService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> evaluate() throws IOException {
        List<EvalQuery> queries = objectMapper.readValue(
                evalResource.getInputStream(), new TypeReference<>() {});

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("numQueries", queries.size());

        double mrrSum = 0.0;
        Map<Integer, Double> precisionSum = new LinkedHashMap<>();
        Map<Integer, Double> recallSum = new LinkedHashMap<>();
        for (int k : K_VALUES) {
            precisionSum.put(k, 0.0);
            recallSum.put(k, 0.0);
        }

        int maxK = K_VALUES[K_VALUES.length - 1];
        for (EvalQuery q : queries) {
            List<String> retrievedIds = ragService.retrieve(q.question(), maxK).stream()
                    .map(d -> String.valueOf(d.getMetadata().get("ticketId")))
                    .toList();

            // MRR: reciproco della posizione del primo risultato pertinente
            double rr = 0.0;
            for (int i = 0; i < retrievedIds.size(); i++) {
                if (q.relevantTicketIds().contains(retrievedIds.get(i))) {
                    rr = 1.0 / (i + 1);
                    break;
                }
            }
            mrrSum += rr;

            for (int k : K_VALUES) {
                List<String> topK = retrievedIds.subList(0, Math.min(k, retrievedIds.size()));
                long hits = topK.stream().filter(q.relevantTicketIds()::contains).count();
                precisionSum.merge(k, (double) hits / k, Double::sum);
                recallSum.merge(k, (double) hits / q.relevantTicketIds().size(), Double::sum);
            }
        }

        int n = queries.size();
        for (int k : K_VALUES) {
            report.put("precision@" + k, round(precisionSum.get(k) / n));
            report.put("recall@" + k, round(recallSum.get(k) / n));
        }
        report.put("MRR", round(mrrSum / n));
        return report;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
