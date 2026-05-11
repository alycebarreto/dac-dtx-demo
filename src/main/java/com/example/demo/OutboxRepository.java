package com.example.demo;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Repositorio Outbox usando EntityManager.
 *
 * Operacoes sao escritas para participar da MESMA transacao do save do User,
 * por isso aqui usamos REQUIRED (junta na transacao existente).
 */
@Repository
public class OutboxRepository {

    @PersistenceContext
    private EntityManager em;

    /**
     * Persiste um evento na outbox dentro da transacao corrente.
     * Se nao houver transacao ativa, cria uma — mas a ideia eh chamar
     * de dentro de outro @Transactional para casar com o save() da entidade.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public OutboxEvent append(OutboxEvent event) {
        em.persist(event);
        return event;
    }

    /** Lista eventos pendentes em ordem de criacao (FIFO). */
    @Transactional(readOnly = true)
    public List<OutboxEvent> findPending(int limit) {
        return em.createQuery(
                "SELECT o FROM OutboxEvent o WHERE o.status = :s ORDER BY o.createdAt ASC",
                OutboxEvent.class)
                .setParameter("s", OutboxEvent.Status.PENDING)
                .setMaxResults(limit)
                .getResultList();
    }

    /** Marca como processado (replica entregue ao Mongo com sucesso). */
    @Transactional
    public void markProcessed(Long id) {
        OutboxEvent e = em.find(OutboxEvent.class, id);
        if (e != null) {
            e.setStatus(OutboxEvent.Status.PROCESSED);
            e.setProcessedAt(LocalDateTime.now());
            em.merge(e);
        }
    }

    /** Incrementa contador de tentativa mantendo PENDING (sera retentado). */
    @Transactional
    public void markAttempted(Long id) {
        OutboxEvent e = em.find(OutboxEvent.class, id);
        if (e != null) {
            e.incrementAttempts();
            em.merge(e);
        }
    }

    /** Marca como falha definitiva (apos N tentativas). */
    @Transactional
    public void markFailed(Long id) {
        OutboxEvent e = em.find(OutboxEvent.class, id);
        if (e != null) {
            e.setStatus(OutboxEvent.Status.FAILED);
            em.merge(e);
        }
    }
}
