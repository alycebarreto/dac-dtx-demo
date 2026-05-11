# DAC — Transações Distribuídas: 2PC + Outbox + SAGA

Projeto da disciplina **DAC** que demonstra três padrões clássicos de transação distribuída, persistindo a mesma entidade `User` em **dois bancos de dados diferentes** (H2 e MongoDB) de forma consistente, **sem usar nenhum framework** de transação distribuída (sem Atomikos, Narayana, JTA). Toda a coordenação é manual e visível nos logs.

## Padrões implementados

| Padrão | O que faz | Onde está |
|---|---|---|
| **Two-Phase Commit (2PC)** | Coordinator manual com fases PREPARE e COMMIT/ROLLBACK sobre H2 e Mongo. Se algum participante vota "NO", rollback global. | `DtxCoordinator`, `H2DtxParticipant`, `MongoDtxParticipant` |
| **Transactional Outbox** | Escreve o User e um evento na tabela `outbox_events` **na mesma transação local**. Um relay assíncrono replica para o Mongo a cada 5s com retry. | `OutboxEvent`, `OutboxRepository`, `OutboxRelay` |
| **SAGA** | Passos locais commitados imediatamente. Se algum falha, executa compensações na ordem inversa. | `SagaStep`, `SagaOrchestrator` |

Orquestrados em `DistributedUserService`, expostos via REST em `UserController` sob `/users/dtx/*`.

---

## Como rodar

### Pré-requisitos

- **Java 17** (Adoptium Temurin) — https://adoptium.net/temurin/releases/?version=17
- **Docker Desktop** — para subir o MongoDB local
- **Gradle Wrapper** já vem incluso no projeto

### 1. Subir o MongoDB

```bash
docker run -d --name dac-mongo -p 27017:27017 mongo:7
```

Conferir:

```bash
docker ps
```

Para parar/reiniciar depois:

```bash
docker stop dac-mongo
docker start dac-mongo
```

### 2. Rodar a aplicação

Windows (PowerShell):

```powershell
.\gradlew.bat bootRun
```

Linux/Mac:

```bash
./gradlew bootRun
```

A aplicação sobe na **porta 8081**. Aguarde até ver:

```
[CONFIG] Implementacao de UserDao ativa: dtx (UserDtxDao)
Started DemoApplication in X seconds
```

### 3. Configuração

`src/main/resources/application.properties`:

```properties
# Implementação ativa: jpa | jdbc | mongo | dtx
app.dao.impl=dtx

# H2 in-memory
spring.datasource.url=jdbc:h2:mem:testdb

# MongoDB (Docker local)
app.mongo.uri=mongodb://localhost:27017
app.mongo.database=demo

server.port=8081
```

Para usar o MongoDB Atlas em vez do Docker local, basta trocar a URI.

---

## Como testar (PowerShell)

Abra um **segundo terminal** (mantenha a aplicação rodando no primeiro). Os 6 cenários abaixo demonstram cada padrão em condições felizes e de falha.

### Cenário 1 — 2PC com sucesso (commit em ambos)

```powershell
Invoke-RestMethod -Method POST -Uri http://localhost:8081/users/dtx/2pc -ContentType 'application/json' -Body '{"name":"Alice","email":"alice@dac.com"}'
```

**Esperado:** `result: COMMITTED`. Alice aparece no H2 e no Mongo.

### Cenário 2 — 2PC com falha no PREPARE do Mongo (rollback global)

```powershell
Invoke-RestMethod -Method POST -Uri "http://localhost:8081/users/dtx/2pc?failMongoPrepare=true" -ContentType 'application/json' -Body '{"name":"Bob","email":"bob@dac.com"}'
```

**Esperado:** `result: ROLLED_BACK`. Bob **não** aparece em banco nenhum.

### Cenário 3 — 2PC com falha no COMMIT do Mongo (estado heurístico)

```powershell
Invoke-RestMethod -Method POST -Uri "http://localhost:8081/users/dtx/2pc?failMongoCommit=true" -ContentType 'application/json' -Body '{"name":"Carol","email":"carol@dac.com"}'
```

**Esperado:** `result: PARTIAL_FAILURE`. Carol aparece **só no H2**, demonstrando o famoso problema do estado heurístico do 2PC — depois que todos votam YES, se um RM falha no commit, fica inconsistente. É justamente esse problema que motivou a criação do SAGA e do Outbox.

### Cenário 4 — Outbox Pattern

```powershell
Invoke-RestMethod -Method POST -Uri http://localhost:8081/users/dtx/outbox -ContentType 'application/json' -Body '{"name":"Dave","email":"dave@dac.com"}'
```

**Esperado:** `result: PERSISTED_LOCAL_PENDING_REPLICATION`. Dave é gravado no H2 com um evento `PENDING` na outbox. Em até 5 segundos, o `OutboxRelay` replica para o Mongo. Confira nos logs:

```
[OUTBOX-RELAY] Drenando outbox: 1 evento(s) PENDING
[OUTBOX-RELAY]   ✓ OutboxEvent[id=N, type=USER_CREATED, ...] replicado para Mongo
```

### Cenário 5 — SAGA com sucesso

```powershell
Invoke-RestMethod -Method POST -Uri http://localhost:8081/users/dtx/saga -ContentType 'application/json' -Body '{"name":"Eve","email":"eve@dac.com"}'
```

**Esperado:** `result: COMPLETED`. Eve aparece nos dois bancos.

### Cenário 6 — SAGA com falha (compensação)

```powershell
Invoke-RestMethod -Method POST -Uri "http://localhost:8081/users/dtx/saga?failOnMongo=true" -ContentType 'application/json' -Body '{"name":"Frank","email":"frank@dac.com"}'
```

**Esperado:** `result: COMPENSATED`. Frank **não** aparece em banco nenhum — a compensação `saveInH2.compensate()` desfez o passo do H2 quando o passo do Mongo falhou. Confira nos logs:

```
[SAGA xxxx] → executando: saveInH2
[SAGA xxxx]   [saveInH2] OK
[SAGA xxxx] → executando: saveInMongo
[SAGA xxxx]   [saveInMongo] FALHOU: Falha simulada ao salvar no Mongo
[SAGA xxxx] ── INICIANDO COMPENSACOES (ordem inversa) ──
[SAGA xxxx] ← compensando: saveInH2
[SAGA xxxx]   [saveInH2] COMPENSADO
```

### Conferir estado dos dois bancos

```powershell
Invoke-RestMethod -Method GET -Uri http://localhost:8081/users/dtx/state | ConvertTo-Json -Depth 5
```

Retorna JSON com `h2: [...]` e `mongo: [...]` lado a lado.

---

## Resultados esperados (resumo)

| Cenário | Usuário | H2 | Mongo | Conceito demonstrado |
|---|---|---|---|---|
| 1 | Alice | ✓ | ✓ | 2PC feliz: ambos prepare YES, ambos commit |
| 2 | Bob | ✗ | ✗ | 2PC rollback: 1 vota NO, ambos desfazem |
| 3 | Carol | ✓ | ✗ | Estado heurístico (limite do 2PC) |
| 4 | Dave | ✓ | ✓ (após ~5s) | Outbox: at-least-once via relay assíncrono |
| 5 | Eve | ✓ | ✓ | SAGA feliz: todos os passos OK |
| 6 | Frank | ✗ | ✗ | SAGA compensada: passo 2 falha, passo 1 desfeito |

---

## Endpoints

| Método | Path | Descrição |
|---|---|---|
| POST | `/users` | CRUD padrão (usa impl ativa) |
| GET | `/users` | lista todos |
| GET | `/users/{id}` | busca por id |
| PUT | `/users/{id}` | atualiza |
| DELETE | `/users/{id}` | remove |
| POST | `/users/dtx/2pc` | 2PC sobre H2 + Mongo |
| POST | `/users/dtx/outbox` | Outbox + relay assíncrono |
| POST | `/users/dtx/saga` | SAGA com compensações |
| GET | `/users/dtx/state` | estado dos 2 bancos lado a lado |

**Query params para simular falhas:**
- `?failMongoPrepare=true` — força "NO" na fase 1 do Mongo (Cenário 2)
- `?failMongoCommit=true` — força erro na fase 2 do Mongo (Cenário 3)
- `?failOnMongo=true` — força falha no passo Mongo da SAGA (Cenário 6)

---

## Arquitetura

```
HTTP POST /users/dtx/{2pc | outbox | saga}
                ↓
        UserController
                ↓
   DistributedUserService     ← orquestra os 3 padrões
        ↓        ↓        ↓
   Coordinator  Outbox   Saga
        ↓        ↓        ↓
   [H2 + Mongo participants] + OutboxRelay + SagaSteps
        ↓                ↓
   ┌────────┐      ┌──────────┐
   │  H2    │      │ MongoDB  │
   │ users  │      │  users   │
   │ outbox │      │ staging  │
   └────────┘      └──────────┘
```

### Mapeamento Diagrama UML ↔ Código

O diagrama de componentes do projeto (UML "Método 2PC") usa nomes em estilo DAO. No código, segui a nomenclatura da literatura de 2PC (Resource Manager / Participant). A correspondência é direta:

| Diagrama UML | Classe no código | Papel |
|---|---|---|
| **Controller** | `UserController` | Recebe a requisição HTTP e dispara o fluxo |
| **DTxCoord** | `DtxCoordinator` | Coordena as fases PREPARE/COMMIT/ROLLBACK do 2PC |
| **UserH2DAO** | `H2DtxParticipant` | DAO H2 com lifecycle 2PC (prepare/commit/rollback) |
| **UserMongoDAO** | `MongoDtxParticipant` | DAO Mongo com lifecycle 2PC (prepare/commit/rollback simulados) |
| **H2 In-Memory** | `jdbc:h2:mem:testdb` | banco SQL em memória |
| **MongoDB** | `mongo:7` (Docker) | banco documental |

Por que `Participant` em vez de `DAO`? Na literatura de 2PC, quem participa do protocolo é chamado de **Resource Manager** (RM) ou **Participant** — é o termo técnico usado pelo padrão XA e por Transaction Managers como Atomikos e Narayana. Mantive essa nomenclatura para deixar claro que as classes não fazem apenas CRUD: elas implementam o ciclo de vida do protocolo 2PC. Funcionalmente, equivalem aos DAOs do diagrama.

A única diferença estrutural é o `DistributedUserService` entre o Controller e o Coordinator — ele existe porque o mesmo service expõe os três padrões (2PC, Outbox, SAGA) ao Controller.

## Estrutura dos arquivos

```
src/main/java/com/example/demo/
├── DemoApplication.java           ponto de entrada (@EnableScheduling)
├── UserEntity.java                entidade JPA users
├── UserController.java            endpoints REST (CRUD + /dtx/*)
├── UserDao.java                   interface comum
├── UserJpaDao.java                impl JPA (modo "jpa")
├── UserJdbcDao.java               impl JDBC (modo "jdbc")
├── UserMongoDao.java              impl Mongo (modo "mongo")
├── UserDtxDao.java                impl distribuída (modo "dtx")
├── UserDaoConfig.java             seleciona impl ativa
├── MongoConfig.java               configura MongoClient
│
├── DtxParticipant.java            interface (prepare/commit/rollback)
├── H2DtxParticipant.java          H2 como participante
├── MongoDtxParticipant.java       Mongo como participante (XA simulado)
├── DtxCoordinator.java            coordenador 2PC manual
│
├── SagaStep.java                  interface (execute/compensate)
├── SagaOrchestrator.java          coordenador SAGA
│
├── OutboxEvent.java               entidade outbox_events
├── OutboxRepository.java          operações JPA na outbox
├── OutboxRelay.java               @Scheduled poller H2 → Mongo
│
├── DistributedUserService.java    orquestra 2PC + Outbox + SAGA
│
├── SimpleXid.java                 (legado) Xid para XA
├── UserXaDao.java                 (legado) DAO XA só com H2
└── XATransactionCoordinator.java  (legado) coordenador XA real javax.transaction.xa
```

## Decisões de projeto

**Por que o Mongo XA é "simulado"?**
MongoDB single-node não suporta XA, e suas transações nativas exigem replica set. Para fins didáticos, `MongoDtxParticipant` simula prepare/commit/rollback usando uma coleção `users_staging` (commit em duas fases manualmente). Em produção com replica set, usaríamos `ClientSession.startTransaction()`.

**Por que duas implementações de coordenador (XATransactionCoordinator e DtxCoordinator)?**
- `XATransactionCoordinator` usa `javax.transaction.xa.XAResource` real — funciona apenas com bancos XA-capable como H2. Mantido como referência do protocolo padrão.
- `DtxCoordinator` trabalha sobre uma interface mais simples (`DtxParticipant`) — permite incluir o Mongo (que não tem XA) no mesmo protocolo 2PC.

**Por que o Outbox usa JPA e o 2PC usa JDBC bruto?**
- Outbox precisa que duas escritas (User + evento) sejam atômicas localmente — `@Transactional` do JPA resolve elegantemente.
- 2PC precisa de controle fino sobre prepare/commit/rollback em conexões separadas — JDBC bruto deixa cada fase visível.

## Sequência de demonstração sugerida

Para apresentação:

1. Abrir `application.properties` e mostrar `app.dao.impl=dtx`.
2. Rodar `docker ps` mostrando o `dac-mongo` ativo.
3. Subir a aplicação com `.\gradlew.bat bootRun`.
4. Executar os 6 cenários em outro terminal, na ordem (1 → 6).
5. Após cada cenário, mostrar o terminal da aplicação com os logs `[DTX]`, `[OUTBOX-RELAY]` e `[SAGA]`.
6. Encerrar com `GET /users/dtx/state` mostrando a consistência dos dois bancos.
7. Discutir o resultado do **Cenário 3 (PARTIAL_FAILURE)** como o limite que motivou a criação do SAGA e do Outbox.
