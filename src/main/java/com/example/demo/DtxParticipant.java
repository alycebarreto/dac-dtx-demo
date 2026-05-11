package com.example.demo;

/**
 * Participante de uma transacao distribuida no protocolo 2PC.
 *
 * Cada Resource Manager (banco) que entra numa transacao distribuida
 * implementa esta interface. O Coordinator chama:
 *
 *   1) prepare()   — fase 1: "voce consegue commitar?" — true=YES / false=NO
 *   2) commit()    — fase 2a: aplique definitivamente
 *      OU
 *      rollback() — fase 2b: desfaca o que preparou
 *
 * Diferente do XAResource padrao, esta interface eh mais simples — proposital,
 * para tornar o protocolo visivel no codigo do trabalho sem depender de
 * driver XA. Mongo, por exemplo, nao tem XA, e aqui simulamos com um
 * "staging" no participante.
 */
public interface DtxParticipant {

    /** Nome legivel para os logs. */
    String name();

    /**
     * Fase 1 — vota se pode commitar.
     *
     * O participante deve:
     *  - Validar a operacao (constraints, schema, conectividade)
     *  - Reservar recursos (locks, escrita em log durable)
     *  - Retornar TRUE se garante que um commit() subsequente vai funcionar
     *  - Retornar FALSE (ou lancar excecao) para forcar rollback global
     */
    boolean prepare() throws Exception;

    /**
     * Fase 2a — aplica definitivamente o trabalho preparado.
     * So eh chamado se TODOS os participantes votaram TRUE no prepare.
     */
    void commit() throws Exception;

    /**
     * Fase 2b — desfaz o que foi preparado.
     * Chamado se algum participante votou FALSE ou se houve erro na fase 1.
     */
    void rollback() throws Exception;
}
