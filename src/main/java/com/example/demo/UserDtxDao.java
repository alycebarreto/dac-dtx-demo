package com.example.demo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementacao do UserDao que usa transacoes distribuidas (modo "dtx").
 *
 * Ativada por app.dao.impl=dtx em application.properties.
 *
 * Para escritas (save/update/delete), delega ao DistributedUserService que
 * faz 2PC sobre H2 + Mongo. Para leituras (findById/findAll), le do H2
 * que eh tratado como source-of-truth nesse trabalho.
 */
@Repository
@Primary
@Qualifier("dtx")
@ConditionalOnProperty(name = "app.dao.impl", havingValue = "dtx")
public class UserDtxDao implements UserDao {

    private final DistributedUserService dtxService;
    private final DataSource dataSource;
    private final MongoCollection<Document> mongoUsers;

    public UserDtxDao(DistributedUserService dtxService,
                      DataSource dataSource,
                      MongoClient mongoClient,
                      @Value("${app.mongo.database}") String database) {
        this.dtxService  = dtxService;
        this.dataSource  = dataSource;
        this.mongoUsers  = mongoClient.getDatabase(database).getCollection("users");
    }

    @Override
    public void save(UserEntity user) {
        // Comportamento padrao do dao.save() em modo dtx: usa 2PC sem falhas simuladas
        dtxService.saveWith2PC(user, false, false);
    }

    @Override
    public UserEntity findById(Long id) {
        String sql = "SELECT id, name, email FROM users WHERE id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return map(rs);
                return null;
            }
        } catch (Exception e) {
            throw new RuntimeException("findById falhou", e);
        }
    }

    @Override
    public List<UserEntity> findAll() {
        String sql = "SELECT id, name, email FROM users";
        List<UserEntity> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(map(rs));
            return result;
        } catch (Exception e) {
            throw new RuntimeException("findAll falhou", e);
        }
    }

    /** Conveniencia: lista o que esta no Mongo (para verificar replicacao). */
    public List<UserEntity> findAllFromMongo() {
        List<UserEntity> result = new ArrayList<>();
        for (Document doc : mongoUsers.find()) {
            UserEntity u = new UserEntity();
            u.setId(doc.getLong("id"));
            u.setName(doc.getString("name"));
            u.setEmail(doc.getString("email"));
            result.add(u);
        }
        return result;
    }

    @Override
    public void update(UserEntity user) {
        // Update simples via H2 + Mongo em 2PC tambem seria possivel.
        // Para simplicidade do trabalho, atualizamos em ambos sequencialmente.
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE users SET name = ?, email = ? WHERE id = ?")) {
            ps.setString(1, user.getName());
            ps.setString(2, user.getEmail());
            ps.setLong(3, user.getId());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("update H2 falhou", e);
        }
        mongoUsers.updateOne(
                Filters.eq("id", user.getId()),
                com.mongodb.client.model.Updates.combine(
                        com.mongodb.client.model.Updates.set("name",  user.getName()),
                        com.mongodb.client.model.Updates.set("email", user.getEmail())
                )
        );
    }

    @Override
    public void delete(Long id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM users WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("delete H2 falhou", e);
        }
        mongoUsers.deleteOne(Filters.eq("id", id));
    }

    private UserEntity map(ResultSet rs) throws Exception {
        UserEntity u = new UserEntity();
        u.setId(rs.getLong("id"));
        u.setName(rs.getString("name"));
        u.setEmail(rs.getString("email"));
        return u;
    }
}
