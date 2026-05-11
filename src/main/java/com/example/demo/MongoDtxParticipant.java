package com.example.demo;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;

/**
 * Participante MongoDB da transacao distribuida.
 *
 * Como o MongoDB single-node NAO tem XA e suas transacoes nativas exigem
 * replica set, simulamos 2PC manualmente:
 *
 *  - prepare(): valida que conseguimos conectar e fazer a operacao,
 *               sem aplicar definitivamente. Para INSERT, gravamos
 *               o documento numa colecao auxiliar "users_staging".
 *               Para UPDATE/DELETE, lemos o estado atual e guardamos
 *               como "undo log" em memoria.
 *  - commit():  move o documento de users_staging para users (INSERT),
 *               ou simplesmente aplica a mudanca (UPDATE/DELETE).
 *  - rollback(): apaga da staging (INSERT), ou nao faz nada — o doc
 *               nem foi tocado em users (UPDATE/DELETE).
 *
 * Este eh um modelo didatico que torna 2PC visivel sobre Mongo.
 * Em producao com replica set, usariamos session.startTransaction() / commit().
 *
 * Tem um flag failOnPrepare/failOnCommit para simular falhas e demonstrar
 * o rollback global e o estado de partial failure.
 */
public class MongoDtxParticipant implements DtxParticipant {

    public enum Operation { INSERT, UPDATE, DELETE }

    private final MongoCollection<Document> mainCol;     // colecao final "users"
    private final MongoCollection<Document> stagingCol;  // colecao auxiliar "users_staging"
    private final UserEntity user;
    private final Operation operation;
    private final boolean failOnPrepare;
    private final boolean failOnCommit;

    // Para INSERT, guardamos a referencia do staged document (id no Mongo)
    // Para UPDATE/DELETE, guardamos o estado anterior para possivel undo manual
    private Document stagedDoc;
    private Document previousState;

    public MongoDtxParticipant(MongoCollection<Document> mainCol,
                               MongoCollection<Document> stagingCol,
                               UserEntity user,
                               Operation operation,
                               boolean failOnPrepare,
                               boolean failOnCommit) {
        this.mainCol       = mainCol;
        this.stagingCol    = stagingCol;
        this.user          = user;
        this.operation     = operation;
        this.failOnPrepare = failOnPrepare;
        this.failOnCommit  = failOnCommit;
    }

    @Override
    public String name() {
        return "Mongo-" + operation.name();
    }

    @Override
    public boolean prepare() throws Exception {
        if (failOnPrepare) {
            throw new RuntimeException("Falha simulada no PREPARE do Mongo");
        }

        switch (operation) {
            case INSERT -> {
                // Insere na colecao staging com flag indicando que ainda nao foi commitado.
                stagedDoc = new Document("id",    user.getId())
                        .append("name",  user.getName())
                        .append("email", user.getEmail())
                        .append("_staged", true);
                stagingCol.insertOne(stagedDoc);
            }
            case UPDATE -> {
                // Salva estado anterior em memoria para rollback semantico (caso ja tenhamos aplicado)
                previousState = mainCol.find(Filters.eq("id", user.getId())).first();
                if (previousState == null) {
                    throw new IllegalStateException("UPDATE: documento nao encontrado no Mongo (id="
                            + user.getId() + ")");
                }
            }
            case DELETE -> {
                previousState = mainCol.find(Filters.eq("id", user.getId())).first();
                if (previousState == null) {
                    throw new IllegalStateException("DELETE: documento nao encontrado no Mongo (id="
                            + user.getId() + ")");
                }
            }
        }
        return true;
    }

    @Override
    public void commit() throws Exception {
        if (failOnCommit) {
            throw new RuntimeException("Falha simulada no COMMIT do Mongo");
        }

        switch (operation) {
            case INSERT -> {
                // Insere no destino final e remove o staged
                Document finalDoc = new Document("id", user.getId())
                        .append("name",  user.getName())
                        .append("email", user.getEmail());
                mainCol.insertOne(finalDoc);
                stagingCol.deleteOne(Filters.eq("id", user.getId()));
            }
            case UPDATE -> {
                mainCol.updateOne(
                        Filters.eq("id", user.getId()),
                        Updates.combine(
                                Updates.set("name",  user.getName()),
                                Updates.set("email", user.getEmail())
                        )
                );
            }
            case DELETE -> {
                mainCol.deleteOne(Filters.eq("id", user.getId()));
            }
        }
    }

    @Override
    public void rollback() throws Exception {
        switch (operation) {
            case INSERT -> {
                // Remove da staging — o documento nunca chegou no destino final
                if (stagedDoc != null) {
                    stagingCol.deleteOne(Filters.eq("id", user.getId()));
                }
            }
            case UPDATE, DELETE -> {
                // Nao precisamos fazer nada — a operacao final nao foi aplicada.
                // (Em caso de heuristico/partial failure, usariamos previousState
                //  para restaurar manualmente — base do SAGA.)
            }
        }
    }
}
