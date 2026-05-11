package com.example.demo;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Participante H2 da transacao distribuida.
 *
 * Implementa 2PC manual usando uma transacao JDBC local:
 *  - prepare(): abre conexao com autoCommit=false, executa o SQL e... NAO commita.
 *               Se o INSERT/UPDATE funcionou, vota YES. Senao, lanca excecao.
 *               A conexao permanece aberta segurando o lock.
 *  - commit():  faz conn.commit() — efetiva o que foi preparado.
 *  - rollback(): faz conn.rollback() — desfaz tudo da fase 1.
 *
 * Importante:
 *  - A conexao do prepare() eh mantida aberta ate commit() ou rollback().
 *  - Isso simula o comportamento de um XAResource real, mas usando JDBC simples.
 *  - Em XA real, o RM grava o trabalho em log durable na fase 1 (presumed abort)
 *    para sobreviver a crashes; aqui o log durable eh o proprio WAL do H2.
 */
public class H2DtxParticipant implements DtxParticipant {

    public enum Operation { INSERT, UPDATE, DELETE }

    private final DataSource dataSource;
    private final UserEntity user;
    private final Operation operation;

    private Connection conn;          // mantida aberta entre prepare e commit/rollback
    private boolean prepared = false;

    public H2DtxParticipant(DataSource dataSource, UserEntity user, Operation operation) {
        this.dataSource = dataSource;
        this.user       = user;
        this.operation  = operation;
    }

    @Override
    public String name() {
        return "H2-" + operation.name();
    }

    @Override
    public boolean prepare() throws Exception {
        conn = dataSource.getConnection();
        conn.setAutoCommit(false);

        switch (operation) {
            case INSERT -> doInsert();
            case UPDATE -> doUpdate();
            case DELETE -> doDelete();
        }

        prepared = true;
        return true; // se chegou aqui sem excecao, vota YES
    }

    private void doInsert() throws Exception {
        String sql = "INSERT INTO users (name, email) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, user.getName());
            ps.setString(2, user.getEmail());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) user.setId(keys.getLong(1));
            }
        }
    }

    private void doUpdate() throws Exception {
        String sql = "UPDATE users SET name = ?, email = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, user.getName());
            ps.setString(2, user.getEmail());
            ps.setLong(3, user.getId());
            ps.executeUpdate();
        }
    }

    private void doDelete() throws Exception {
        String sql = "DELETE FROM users WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, user.getId());
            ps.executeUpdate();
        }
    }

    @Override
    public void commit() throws Exception {
        if (!prepared) throw new IllegalStateException("commit() chamado sem prepare()");
        try {
            conn.commit();
        } finally {
            close();
        }
    }

    @Override
    public void rollback() throws Exception {
        if (conn == null) return;
        try {
            conn.rollback();
        } finally {
            close();
        }
    }

    private void close() {
        try { conn.close(); } catch (Exception ignored) {}
        conn = null;
        prepared = false;
    }
}
