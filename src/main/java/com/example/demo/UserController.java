package com.example.demo;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Controlador REST para gerenciamento de usuarios.
 *
 * Endpoints CRUD basicos (/users) — usam a implementacao de UserDao
 * selecionada por app.dao.impl (jpa, jdbc, mongo ou dtx).
 *
 * Endpoints distribuidos (/users/dtx/*) — disponiveis quando o modo "dtx"
 * esta ativo. Cada endpoint demonstra uma estrategia diferente:
 *  - /users/dtx/2pc     Two-Phase Commit
 *  - /users/dtx/outbox  Outbox Pattern
 *  - /users/dtx/saga    SAGA Pattern
 *
 * Cada um aceita query params para forcar falhas e demonstrar comportamento:
 *  ?failMongoPrepare=true  ?failMongoCommit=true  ?failOnMongo=true
 *
 * @author DAC
 * @version 2.0
 */
@RestController
@RequestMapping("/users")
public class UserController {

    private final UserDao userDao;
    private final ObjectProvider<DistributedUserService> dtxService;
    private final ObjectProvider<UserDtxDao> dtxDao;

    public UserController(UserDao userDao,
                          ObjectProvider<DistributedUserService> dtxService,
                          ObjectProvider<UserDtxDao> dtxDao) {
        this.userDao    = userDao;
        this.dtxService = dtxService;
        this.dtxDao     = dtxDao;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CRUD normal (usa o UserDao ativo segundo app.dao.impl)
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping
    public void createUser(@RequestBody UserEntity user) {
        userDao.save(user);
    }

    @GetMapping("/{id}")
    public UserEntity getUser(@PathVariable Long id) {
        return userDao.findById(id);
    }

    @GetMapping
    public List<UserEntity> getAllUsers() {
        return userDao.findAll();
    }

    @PutMapping("/{id}")
    public void updateUser(@PathVariable Long id, @RequestBody UserEntity user) {
        user.setId(id);
        userDao.update(user);
    }

    @DeleteMapping("/{id}")
    public void deleteUser(@PathVariable Long id) {
        userDao.delete(id);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Endpoints especificos do modo distribuido (DTX)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Salva usando 2PC manual sobre H2 + Mongo.
     *
     * Parametros opcionais:
     *  - failMongoPrepare=true → simula NO no prepare do Mongo (forca rollback global)
     *  - failMongoCommit=true  → simula erro no commit do Mongo (estado heuristico)
     */
    @PostMapping("/dtx/2pc")
    public Map<String, Object> create2PC(
            @RequestBody UserEntity user,
            @RequestParam(defaultValue = "false") boolean failMongoPrepare,
            @RequestParam(defaultValue = "false") boolean failMongoCommit) {

        DistributedUserService svc = dtxService.getIfAvailable();
        if (svc == null) return error("Modo dtx nao esta ativo. Defina app.dao.impl=dtx.");

        DtxCoordinator.DtxResult result = svc.saveWith2PC(user, failMongoPrepare, failMongoCommit);
        return Map.of(
                "strategy", "2PC",
                "result",   result.name(),
                "userId",   user.getId()
        );
    }

    /**
     * Salva usando Outbox Pattern — escrita local + relay assincrono.
     */
    @PostMapping("/dtx/outbox")
    public Map<String, Object> createOutbox(@RequestBody UserEntity user) {
        DistributedUserService svc = dtxService.getIfAvailable();
        if (svc == null) return error("Modo dtx nao esta ativo. Defina app.dao.impl=dtx.");

        UserEntity saved = svc.saveWithOutbox(user);
        return Map.of(
                "strategy", "OUTBOX",
                "result",   "PERSISTED_LOCAL_PENDING_REPLICATION",
                "userId",   saved.getId(),
                "note",     "Sera replicado pro Mongo pelo OutboxRelay (a cada 5s)"
        );
    }

    /**
     * Salva usando SAGA — passos locais + compensacoes na ordem inversa.
     *
     * Parametro opcional:
     *  - failOnMongo=true → forca falha no passo do Mongo, dispara compensacao
     */
    @PostMapping("/dtx/saga")
    public Map<String, Object> createSaga(
            @RequestBody UserEntity user,
            @RequestParam(defaultValue = "false") boolean failOnMongo) {

        DistributedUserService svc = dtxService.getIfAvailable();
        if (svc == null) return error("Modo dtx nao esta ativo. Defina app.dao.impl=dtx.");

        SagaOrchestrator.SagaResult result = svc.saveWithSaga(user, failOnMongo);
        return Map.of(
                "strategy", "SAGA",
                "result",   result.name(),
                "userId",   user.getId()
        );
    }

    /** Lista o estado dos DOIS bancos lado a lado, para conferencia visual. */
    @GetMapping("/dtx/state")
    public Map<String, Object> state() {
        UserDtxDao dao = dtxDao.getIfAvailable();
        if (dao == null) return error("Modo dtx nao esta ativo. Defina app.dao.impl=dtx.");

        Map<String, Object> out = new HashMap<>();
        out.put("h2",    dao.findAll());
        out.put("mongo", dao.findAllFromMongo());
        return out;
    }

    private Map<String, Object> error(String message) {
        return Map.of("error", message);
    }
}
