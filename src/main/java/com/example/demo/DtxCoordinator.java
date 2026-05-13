package com.example.demo;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;


@Component
public class DtxCoordinator {

    public enum DtxResult { COMMITTED, ROLLED_BACK, PARTIAL_FAILURE }

    public DtxResult run(List<DtxParticipant> participants) {
        String txId = UUID.randomUUID().toString().substring(0, 8);
        log(txId, "=== INICIO DA TRANSACAO DISTRIBUIDA ===");
        log(txId, "Participantes: " + participants.size());

        // ─────────── FASE 1: PREPARE ───────────
        log(txId, "──────── FASE 1: PREPARE (votacao) ────────");
        List<DtxParticipant> prepared = new ArrayList<>();
        boolean allOk = true;

        for (DtxParticipant p : participants) {
            try {
                boolean vote = p.prepare();
                if (vote) {
                    log(txId, "  [" + p.name() + "] VOTOU: YES");
                    prepared.add(p);
                } else {
                    log(txId, "  [" + p.name() + "] VOTOU: NO");
                    allOk = false;
                    break;
                }
            } catch (Exception e) {
                log(txId, "  [" + p.name() + "] FALHOU NO PREPARE: " + e.getMessage());
                allOk = false;
                break;
            }
        }

        // ─────────── FASE 2: COMMIT ou ROLLBACK ───────────
        if (allOk) {
            log(txId, "──────── FASE 2: COMMIT (todos votaram YES) ────────");
            boolean partialFailure = false;
            for (DtxParticipant p : participants) {
                try {
                    p.commit();
                    log(txId, "  [" + p.name() + "] COMMIT OK");
                } catch (Exception e) {
                    // Estado heuristico — alguns ja commitaram, este falhou.
                    // Em 2PC real, eh registrado no log para recuperacao manual.
                    log(txId, "  [" + p.name() + "] COMMIT FALHOU: " + e.getMessage()
                            + "  (estado heuristico — log para reconciliacao)");
                    partialFailure = true;
                }
            }
            log(txId, "=== FIM (" + (partialFailure ? "PARTIAL_FAILURE" : "COMMITTED") + ") ===");
            return partialFailure ? DtxResult.PARTIAL_FAILURE : DtxResult.COMMITTED;
        } else {
            log(txId, "──────── FASE 2: ROLLBACK (decisao: abortar) ────────");
            // Rollback so nos que ja prepararam, na ordem inversa (boa pratica)
            Collections.reverse(prepared);
            for (DtxParticipant p : prepared) {
                try {
                    p.rollback();
                    log(txId, "  [" + p.name() + "] ROLLBACK OK");
                } catch (Exception e) {
                    log(txId, "  [" + p.name() + "] ROLLBACK FALHOU: " + e.getMessage());
                }
            }
            log(txId, "=== FIM (ROLLED_BACK) ===");
            return DtxResult.ROLLED_BACK;
        }
    }

    private void log(String txId, String msg) {
        System.out.println("[DTX " + txId + "] " + msg);
    }
}
