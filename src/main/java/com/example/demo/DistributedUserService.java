package com.example.demo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.sql.DataSource;
import java.util.List;

/**
 * Servico que orquestra os tres padroes de transacao distribuida:
 *
 *   1) saveWith2PC   — Two-Phase Commit manual sobre H2 + Mongo
 *   2) saveWithOutbox — Outbox Pattern (escrita local + relay assincrono)
 *   3) saveWithSaga   — SAGA com compensacoes
 *
 * Cada metodo expoe um caminho diferente para a MESMA operacao
 * (salvar User em dois bancos) — facilitando a apresentacao.
 *
 * Tambem expoe flags para simular falhas (failOnMongoPrepare, failOnMongoCommit)
 * que permitem demonstrar o rollback global e as compensacoes do SAGA.
 */
@Service
public class DistributedUserService {

    private final DataSource dataSource;                    // H2
    private final MongoCollection<Document> mongoUsers;     // Mongo: colecao final
    private final MongoCollection<Document> mongoStaging;   // Mongo: colecao staging (2PC)
    private final DtxCoordinator coordinator;
    private final SagaOrchestrator saga;
    private final OutboxRepository outboxRepo;

    @PersistenceContext
    private EntityManager em;

    public DistributedUserService(DataSource dataSource,
                                  MongoClient mongoClient,
                                  @Value("${app.mongo.database}") String database,
                                  DtxCoordinator coordinator,
                                  SagaOrchestrator saga,
                                  OutboxRepository outboxRepo) {
        this.dataSource   = dataSource;
        this.mongoUsers   = mongoClient.getDatabase(database).getCollection("users");
        this.mongoStaging = mongoClient.getDatabase(database).getCollection("users_staging");
        this.coordinator  = coordinator;
        this.saga         = saga;
        this.outboxRepo   = outboxRepo;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  ESTRATEGIA 1: TWO-PHASE COMMIT (2PC) — H2 e Mongo participam juntos
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Salva o usuario em H2 e Mongo dentro de um 2PC manual.
     *
     * Fluxo (ver logs do DtxCoordinator):
     *   begin →
     *     prepare(H2)    → INSERT mas sem commit
     *     prepare(Mongo) → grava em users_staging
     *   se ambos OK:
     *     commit(H2), commit(Mongo)
     *   senao:
     *     rollback(H2), rollback(Mongo)
     */
    public DtxCoordinator.DtxResult saveWith2PC(UserEntity user,
                                                boolean failMongoPrepare,
                                                boolean failMongoCommit) {

        if (user.getId() == null) {
            user.setId(System.currentTimeMillis()); // ID simples gerado no service
        }

        DtxParticipant h2 = new H2DtxParticipant(
                dataSource, user, H2DtxParticipant.Operation.INSERT);

        DtxParticipant mongo = new MongoDtxParticipant(
                mongoUsers, mongoStaging, user,
                MongoDtxParticipant.Operation.INSERT,
                failMongoPrepare, failMongoCommit);

        return coordinator.run(List.of(h2, mongo));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  ESTRATEGIA 2: OUTBOX PATTERN — escrita local + propagacao assincrona
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Salva o usuario APENAS no H2, e grava UMA linha na tabela outbox_events
     * dentro da mesma transacao JPA. O OutboxRelay (rodando a cada 5s) replica
     * para o Mongo de forma assincrona, com retentativas.
     *
     * Garantia: at-least-once delivery do evento para o Mongo, mesmo que
     * a aplicacao caia entre a escrita no H2 e a tentativa de replicacao.
     */
    @Transactional
    public UserEntity saveWithOutbox(UserEntity user) {
        // 1) Persiste o User via JPA — ja participa da @Transactional
        em.persist(user);
        em.flush(); // forca geracao do ID antes de criar o evento

        // 2) Insere o evento na outbox NA MESMA TRANSACAO LOCAL
        //    Se qualquer um dos dois falhar, o commit final desfaz os dois juntos.
        String payload = user.getName() + "|" + user.getEmail();
        OutboxEvent ev = new OutboxEvent(
                OutboxEvent.EventType.USER_CREATED,
                user.getId(),
                payload);
        em.persist(ev);

        log("Outbox: user " + user.getId() + " escrito em H2 + evento PENDING criado");
        return user;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  ESTRATEGIA 3: SAGA — passos locais + compensacoes na ordem inversa
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Salva o usuario em H2 e Mongo como dois passos LOCAIS independentes.
     *
     * Diferente do 2PC, cada passo eh commitado imediatamente. Se o segundo
     * passo falhar, a compensacao do primeiro eh chamada (delete do H2).
     *
     * Vantagens vs 2PC:
     *  - Nao bloqueia recursos (sem locks distribuidos)
     *  - Funciona mesmo com bancos sem suporte a XA/transacoes
     *
     * Desvantagens vs 2PC:
     *  - Estados intermediarios sao visiveis (ler entre passo 1 e passo 2
     *    pode mostrar inconsistencia momentanea)
     */
    public SagaOrchestrator.SagaResult saveWithSaga(UserEntity user, boolean failOnMongo) {
        if (user.getId() == null) {
            user.setId(System.currentTimeMillis());
        }
        final UserEntity userRef = user;

        SagaStep stepH2 = new SagaStep() {
            @Override public String name() { return "saveInH2"; }
            @Override public void execute() throws Exception {
                try (var conn = dataSource.getConnection();
                     var ps = conn.prepareStatement(
                             "INSERT INTO users (id, name, email) VALUES (?, ?, ?)")) {
                    ps.setLong(1, userRef.getId());
                    ps.setString(2, userRef.getName());
                    ps.setString(3, userRef.getEmail());
                    ps.executeUpdate();
                }
            }
            @Override public void compensate() throws Exception {
                try (var conn = dataSource.getConnection();
                     var ps = conn.prepareStatement("DELETE FROM users WHERE id = ?")) {
                    ps.setLong(1, userRef.getId());
                    ps.executeUpdate();
                }
            }
        };

        SagaStep stepMongo = new SagaStep() {
            @Override public String name() { return "saveInMongo"; }
            @Override public void execute() throws Exception {
                if (failOnMongo) {
                    throw new RuntimeException("Falha simulada ao salvar no Mongo");
                }
                Document doc = new Document("id", userRef.getId())
                        .append("name",  userRef.getName())
                        .append("email", userRef.getEmail());
                mongoUsers.insertOne(doc);
            }
            @Override public void compensate() throws Exception {
                mongoUsers.deleteOne(Filters.eq("id", userRef.getId()));
            }
        };

        return saga.run(List.of(stepH2, stepMongo));
    }

    private void log(String msg) {
        System.out.println("[DTX-SERVICE] " + msg);
    }
}
