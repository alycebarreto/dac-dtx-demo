package com.example.demo;

/**
 * Passo de uma Saga.
 *
 * Cada passo eh uma transacao LOCAL e tem uma operacao de compensacao
 * (rollback semantico) que deve ser executavel mesmo depois que o passo
 * foi commitado.
 *
 * Diferente do 2PC, a Saga nao trava recursos — aceita que estados
 * intermediarios fiquem visiveis (read committed para outros leitores)
 * em troca de nao bloquear.
 */
public interface SagaStep {

    String name();

    /** Executa o passo (transacao local commitada). */
    void execute() throws Exception;

    /** Compensa o passo ja executado (reverte semanticamente). */
    void compensate() throws Exception;
}
