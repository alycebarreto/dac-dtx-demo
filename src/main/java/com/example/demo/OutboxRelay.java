package com.example.demo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Relay/Poller do Outbox — implementa "at-least-once delivery" do H2 pro Mongo.
 *
 * A cada 5 segundos:
 *  1) Le ate N eventos PENDING da tabela outbox_events (H2).
 *  2) Para cada um, aplica a mudanca equivalente no Mongo.
 *  3) Se conseguir, marca como PROCESSED. Se falhar, incrementa attempts e
 *     deixa PENDING para retentar no proximo tick.
 *
 * Por que isso garante consistencia eventual:
 *  - A escrita na outbox eh ATOMICA com o save() do User no H2 (mesma TX local).
 *  - Mesmo se a aplicacao cair ou o Mongo estiver fora do ar, a linha PENDING
 *    fica persistida e sera replicada quando der.
 *  - Idempotencia eh chave: se a entrega no Mongo for processada 2x por engano,
 *    o resultado final deve ser o mesmo (por isso usamos upsert no INSERT).
 */
@Component
public class OutboxRelay {

    private static final int  BATCH_SIZE   = 20;
    private static final int  MAX_ATTEMPTS = 5;

    private final OutboxRepository outbox;
    private final MongoCollection<Document> users;

    public OutboxRelay(OutboxRepository outbox,
                       MongoClient mongoClient,
                       @Value("${app.mongo.database}") String database) {
        this.outbox = outbox;
        this.users  = mongoClient.getDatabase(database).getCollection("users");
    }

    /**
     * Executa a cada 5s (configuravel). O Spring @Scheduled exige
     * @EnableScheduling na classe principal (DemoApplication).
     */
    @Scheduled(fixedDelay = 5000)
    public void drain() {
        List<OutboxEvent> pending = outbox.findPending(BATCH_SIZE);
        if (pending.isEmpty()) return;

        log("Drenando outbox: " + pending.size() + " evento(s) PENDING");

        for (OutboxEvent ev : pending) {
            try {
                applyToMongo(ev);
                outbox.markProcessed(ev.getId());
                log("  ✓ " + ev + " replicado para Mongo");
            } catch (Exception e) {
                outbox.markAttempted(ev.getId());
                log("  ✗ " + ev + " falhou: " + e.getMessage());
                if (ev.getAttempts() + 1 >= MAX_ATTEMPTS) {
                    outbox.markFailed(ev.getId());
                    log("  ! " + ev + " excedeu " + MAX_ATTEMPTS + " tentativas — marcado FAILED");
                }
            }
        }
    }

    /**
     * Aplica o evento no Mongo. Operacoes sao idempotentes:
     *  - USER_CREATED: upsert (insere se nao existe, atualiza se existe)
     *  - USER_UPDATED: update (no-op se nao existe)
     *  - USER_DELETED: delete (no-op se nao existe)
     */
    private void applyToMongo(OutboxEvent ev) {
        // Parse simples do payload: name|email (sem dependencia de Jackson)
        String[] parts = ev.getPayload().split("\\|", -1);
        String name  = parts.length > 0 ? parts[0] : null;
        String email = parts.length > 1 ? parts[1] : null;

        switch (ev.getEventType()) {
            case USER_CREATED, USER_UPDATED -> {
                users.updateOne(
                        Filters.eq("id", ev.getAggregateId()),
                        Updates.combine(
                                Updates.setOnInsert("id", ev.getAggregateId()),
                                Updates.set("name",  name),
                                Updates.set("email", email)
                        ),
                        new com.mongodb.client.model.UpdateOptions().upsert(true)
                );
            }
            case USER_DELETED -> {
                users.deleteOne(Filters.eq("id", ev.getAggregateId()));
            }
        }
    }

    private void log(String msg) {
        System.out.println("[OUTBOX-RELAY] " + msg);
    }
}
