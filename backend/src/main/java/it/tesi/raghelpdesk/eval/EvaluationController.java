package it.tesi.raghelpdesk.eval;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * GET /api/eval -> esegue la valutazione del retrieval e restituisce
 * le metriche pronte da copiare nelle tabelle del Capitolo 5.
 */
@RestController
public class EvaluationController {

    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @GetMapping("/api/eval")
    public Map<String, Object> evaluate() throws IOException {
        return evaluationService.evaluate();
    }
}
