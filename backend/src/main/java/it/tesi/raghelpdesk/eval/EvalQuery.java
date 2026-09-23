package it.tesi.raghelpdesk.eval;

import java.util.List;

/**
 * Una query di test del "gold standard": la domanda e gli ID dei ticket
 * che un esperto umano considera pertinenti per quella domanda.
 */
public record EvalQuery(String question, List<String> relevantTicketIds) {
}
