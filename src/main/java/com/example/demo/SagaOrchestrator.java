package com.example.demo;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Orquestrador SAGA — executa passos em sequencia e, se algum falhar,
 * compensa os passos ja executados na ORDEM INVERSA.
 *
 * Caracteristicas:
 *  - Cada passo eh transacao local independente (commit imediato).
 *  - Sem bloqueio de recursos (diferente de 2PC).
 *  - Estados intermediarios podem ser visiveis para outros leitores.
 *  - Em troca: alta disponibilidade, sem locks distribuidos.
 *
 * Fluxo:
 *
 *   passo1.execute() OK
 *   passo2.execute() OK
 *   passo3.execute() FALHA
 *      → compensar passo2 (rollback semantico)
 *      → compensar passo1 (rollback semantico)
 *      → propaga excecao original
 *
 * Se uma compensacao falhar, log para recuperacao manual (real-world).
 */
@Component
public class SagaOrchestrator {

    public enum SagaResult { COMPLETED, COMPENSATED, COMPENSATION_FAILED }

    public SagaResult run(List<SagaStep> steps) {
        String sagaId = UUID.randomUUID().toString().substring(0, 8);
        log(sagaId, "=== INICIO SAGA ===  " + steps.size() + " passos");

        List<SagaStep> executed = new ArrayList<>();

        for (SagaStep step : steps) {
            try {
                log(sagaId, "→ executando: " + step.name());
                step.execute();
                executed.add(step);
                log(sagaId, "  [" + step.name() + "] OK");
            } catch (Exception e) {
                log(sagaId, "  [" + step.name() + "] FALHOU: " + e.getMessage());
                log(sagaId, "── INICIANDO COMPENSACOES (ordem inversa) ──");

                // Compensar do mais recente para o mais antigo
                Collections.reverse(executed);
                boolean compensationFailed = false;
                for (SagaStep done : executed) {
                    try {
                        log(sagaId, "← compensando: " + done.name());
                        done.compensate();
                        log(sagaId, "  [" + done.name() + "] COMPENSADO");
                    } catch (Exception ce) {
                        log(sagaId, "  [" + done.name() + "] COMPENSACAO FALHOU: " + ce.getMessage()
                                + " — REQUER INTERVENCAO MANUAL");
                        compensationFailed = true;
                    }
                }
                log(sagaId, "=== FIM SAGA ("
                        + (compensationFailed ? "COMPENSATION_FAILED" : "COMPENSATED")
                        + ") ===");
                return compensationFailed
                        ? SagaResult.COMPENSATION_FAILED
                        : SagaResult.COMPENSATED;
            }
        }

        log(sagaId, "=== FIM SAGA (COMPLETED) ===");
        return SagaResult.COMPLETED;
    }

    private void log(String sagaId, String msg) {
        System.out.println("[SAGA " + sagaId + "] " + msg);
    }
}
