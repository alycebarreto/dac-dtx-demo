# DAC — Transações Distribuídas (2PC, Outbox, SAGA)

Trabalho da disciplina DAC. Persiste a mesma entidade `User` em dois bancos (H2 e MongoDB) usando três padrões clássicos de transação distribuída, sem framework (sem Atomikos, sem JTA).

## Padrões

- **2PC** — coordinator manual com fases PREPARE e COMMIT/ROLLBACK sobre H2 e Mongo.
- **Outbox** — escreve no H2 e numa tabela de eventos na mesma transação local; um relay assíncrono replica pro Mongo a cada 5s.
- **SAGA** — passos locais commitados imediatamente; se algum falha, executa compensações na ordem inversa.

Tudo orquestrado em `DistributedUserService` e exposto via REST em `UserController` sob `/users/dtx/*`.

## Pré-requisitos

- Java 17 (Adoptium Temurin)
- Docker Desktop (pro MongoDB local)

## Como rodar

Sobe um MongoDB local via Docker (uma vez só):

```powershell
docker run -d --name dac-mongo -p 27017:27017 mongo:7
```

Roda a aplicação:

```powershell
.\gradlew.bat bootRun
```

Sobe na porta 8081. Aguarde até ver `Started DemoApplication`.

## Configuração

Configurações em `src/main/resources/application.properties`:

```properties
app.dao.impl=dtx
app.mongo.uri=mongodb://localhost:27017
app.mongo.database=demo
```

A URI do MongoDB Atlas do enunciado fica como referência comentada no arquivo. Estou usando Mongo local em Docker pela estabilidade da demo (o Atlas do enunciado apresentou erro de conexão TLS no horário dos testes). Para usar o Atlas, basta descomentar a linha correspondente.

## Como testar (PowerShell)

Os 6 cenários abaixo cobrem casos felizes e de falha para cada padrão.

```powershell
# 1) 2PC feliz → COMMITTED
Invoke-RestMethod -Method POST -Uri http://localhost:8081/users/dtx/2pc -ContentType 'application/json' -Body '{"name":"Alice","email":"alice@dac.com"}'

# 2) 2PC com voto NO no Mongo → ROLLED_BACK
Invoke-RestMethod -Method POST -Uri "http://localhost:8081/users/dtx/2pc?failMongoPrepare=true" -ContentType 'application/json' -Body '{"name":"Bob","email":"bob@dac.com"}'

# 3) 2PC com falha no commit do Mongo → PARTIAL_FAILURE (estado heurístico)
Invoke-RestMethod -Method POST -Uri "http://localhost:8081/users/dtx/2pc?failMongoCommit=true" -ContentType 'application/json' -Body '{"name":"Carol","email":"carol@dac.com"}'

# 4) Outbox → PERSISTED_LOCAL_PENDING_REPLICATION (espera 5s pelo relay)
Invoke-RestMethod -Method POST -Uri http://localhost:8081/users/dtx/outbox -ContentType 'application/json' -Body '{"name":"Dave","email":"dave@dac.com"}'

# 5) SAGA feliz → COMPLETED
Invoke-RestMethod -Method POST -Uri http://localhost:8081/users/dtx/saga -ContentType 'application/json' -Body '{"name":"Eve","email":"eve@dac.com"}'

# 6) SAGA com falha no Mongo → COMPENSATED
Invoke-RestMethod -Method POST -Uri "http://localhost:8081/users/dtx/saga?failOnMongo=true" -ContentType 'application/json' -Body '{"name":"Frank","email":"frank@dac.com"}'

# Estado dos dois bancos lado a lado
Invoke-RestMethod -Method GET -Uri http://localhost:8081/users/dtx/state | ConvertTo-Json -Depth 5
```

## Resultado esperado

| Cenário | Usuário | H2 | Mongo |
|---|---|---|---|
| 1 | Alice | sim | sim |
| 2 | Bob | não | não |
| 3 | Carol | sim | não |
| 4 | Dave | sim | sim (após ~5s) |
| 5 | Eve | sim | sim |
| 6 | Frank | não | não |

A inconsistência no caso 3 (Carol só no H2) é proposital: demonstra o estado heurístico do 2PC, problema clássico que motivou a criação do SAGA e do Outbox.

## Endpoints

| Método | Path |
|---|---|
| POST | `/users/dtx/2pc` |
| POST | `/users/dtx/outbox` |
| POST | `/users/dtx/saga` |
| GET | `/users/dtx/state` |

Query params para simular falhas: `failMongoPrepare`, `failMongoCommit`, `failOnMongo`.

## Estrutura do código

```
DemoApplication           ponto de entrada
UserController            endpoints REST
UserEntity / UserDao      modelo + interface CRUD
UserDtxDao                impl ativa (app.dao.impl=dtx)

DtxParticipant            interface (prepare/commit/rollback)
H2DtxParticipant          H2 como participante do 2PC
MongoDtxParticipant       Mongo como participante (XA simulado)
DtxCoordinator            coordenador 2PC manual

SagaStep / SagaOrchestrator    coordenação SAGA + compensações

OutboxEvent / OutboxRepository entidade + repositório da outbox
OutboxRelay                    poller assíncrono H2 → Mongo

DistributedUserService    orquestra 2PC + Outbox + SAGA
```

## Mapeamento com o diagrama UML

| Diagrama | Código |
|---|---|
| Controller | `UserController` |
| DTxCoord | `DtxCoordinator` |
| UserH2DAO | `H2DtxParticipant` |
| UserMongoDAO | `MongoDtxParticipant` |

Usei `Participant` no lugar de `DAO` porque é o termo padrão da literatura de 2PC (Resource Manager / Participant). Funcionalmente é o mesmo papel.

## Observações

- Mongo single-node não tem XA nativo. `MongoDtxParticipant` simula prepare/commit/rollback usando uma coleção auxiliar `users_staging`.
- Arquivos `SimpleXid`, `UserXaDao` e `XATransactionCoordinator` são do esqueleto original, mostrando XA real com H2.
