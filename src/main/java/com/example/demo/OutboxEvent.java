package com.example.demo;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * Entidade Outbox — implementa o padrao Transactional Outbox.
 *
 * Ideia central:
 *  - Quando salvamos um User no H2, escrevemos NA MESMA transacao local
 *    uma linha aqui na outbox descrevendo o evento ("USER_CREATED ...").
 *  - Como as duas escritas (users + outbox_events) acontecem na mesma
 *    transacao local do H2, ou as duas acontecem ou nenhuma acontece —
 *    sem precisar de transacao distribuida pra isso.
 *  - Depois um processo separado (OutboxRelay) le as linhas PENDING
 *    e replica pro MongoDB, garantindo "at-least-once delivery".
 *
 * Por que isso resolve o problema:
 *  Sem outbox, voce poderia salvar no H2 e tentar mandar pro Mongo;
 *  se a aplicacao caisse entre as duas chamadas, o Mongo nunca veria
 *  o evento. Com outbox, a linha fica gravada e sera entregue assim
 *  que a aplicacao subir de novo.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    public enum Status { PENDING, PROCESSED, FAILED }

    public enum EventType { USER_CREATED, USER_UPDATED, USER_DELETED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private EventType eventType;

    /** ID da entidade alvo no H2 (User.id) */
    @Column(nullable = false)
    private Long aggregateId;

    /** Carga util do evento em JSON simples (nome, email, etc.) */
    @Column(nullable = false, length = 2000)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime processedAt;

    /** Numero de tentativas de entrega (para backoff) */
    @Column(nullable = false)
    private int attempts;

    public OutboxEvent() {}

    public OutboxEvent(EventType eventType, Long aggregateId, String payload) {
        this.eventType   = eventType;
        this.aggregateId = aggregateId;
        this.payload     = payload;
        this.status      = Status.PENDING;
        this.createdAt   = LocalDateTime.now();
        this.attempts    = 0;
    }

    public Long getId()                     { return id; }
    public EventType getEventType()         { return eventType; }
    public Long getAggregateId()            { return aggregateId; }
    public String getPayload()              { return payload; }
    public Status getStatus()               { return status; }
    public LocalDateTime getCreatedAt()     { return createdAt; }
    public LocalDateTime getProcessedAt()   { return processedAt; }
    public int getAttempts()                { return attempts; }

    public void setStatus(Status status)            { this.status = status; }
    public void setProcessedAt(LocalDateTime t)     { this.processedAt = t; }
    public void incrementAttempts()                 { this.attempts++; }

    @Override
    public String toString() {
        return "OutboxEvent[id=" + id + ", type=" + eventType
                + ", aggId=" + aggregateId + ", status=" + status
                + ", attempts=" + attempts + "]";
    }
}
