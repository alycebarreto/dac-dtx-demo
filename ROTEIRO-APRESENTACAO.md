# Roteiro de Apresentação — Transações Distribuídas

Duração estimada: 12-15 min.

## Antes de começar

- Terminal 1 com a aplicação rodando (`.\gradlew.bat bootRun`)
- Terminal 2 vazio, na pasta do projeto, pra rodar os comandos de teste
- VSCode aberto com os arquivos principais nas abas
- Docker rodando o container `dac-mongo`
- Arquivo `comandos.txt` aberto pra copiar e colar os testes

## Abertura (1 min)

> "O trabalho pediu pra implementar transações distribuídas usando 2PC, Outbox e SAGA, sem usar nenhum framework, salvando a mesma entidade em dois bancos diferentes — H2 e MongoDB.
>
> O problema central é simples: como garantir que quando eu salvo um usuário, ele apareça nos dois bancos ou em nenhum? Em monolito é trivial com `@Transactional`. Quando os dados estão em bancos separados, não dá mais."

Mostrar o `application.properties` destacando `app.dao.impl=dtx`.

## Mapeamento do diagrama UML com o código

| Diagrama | Código |
|---|---|
| Controller | `UserController` |
| DTxCoord | `DtxCoordinator` |
| UserH2DAO | `H2DtxParticipant` |
| UserMongoDAO | `MongoDtxParticipant` |

Falar:

> "Usei `Participant` em vez de `DAO` porque é o termo técnico da literatura de 2PC — quem participa do protocolo é chamado de Resource Manager ou Participant, padrão usado pela API XA e por Transaction Managers como Atomikos. Funcionalmente é o mesmo papel do DAO no diagrama."

## Os três padrões (3 min)

> "Implementei os três padrões clássicos que a literatura propõe pra esse problema:
>
> Two-Phase Commit — coordinator faz duas fases: PREPARE pergunta pros bancos se conseguem commitar, e COMMIT efetiva. Se algum vota não, rollback global.
>
> Transactional Outbox — escrevo no H2 e numa tabela de eventos na mesma transação local. Depois um processo assíncrono replica pro Mongo. Garante at-least-once delivery.
>
> SAGA — quebro a operação em passos locais commitados imediatamente. Se um falha, executo as compensações dos anteriores na ordem inversa, sem lock distribuído."

Abrir rapidamente, uns 15-20 segundos cada:

1. `DtxCoordinator.java` — método `run()`: fase 1 PREPARE, fase 2 COMMIT ou ROLLBACK.
2. `H2DtxParticipant.java` e `MongoDtxParticipant.java` — destacar `prepare()`, `commit()`, `rollback()`.
3. `OutboxRelay.java` — método `@Scheduled drain()`: roda a cada 5s, lê eventos PENDING, replica no Mongo.
4. `SagaOrchestrator.java` — destacar `Collections.reverse(executed)`: compensação em ordem inversa.

## Demonstração ao vivo (6-8 min)

### 1. 2PC feliz

```powershell
Invoke-RestMethod -Method POST -Uri http://localhost:8081/users/dtx/2pc -ContentType 'application/json' -Body '{"name":"Alice","email":"alice@dac.com"}'
```

Resultado: `COMMITTED`. Mostrar terminal 1 com `FASE 1: PREPARE` e `FASE 2: COMMIT` em ambos os bancos.

### 2. 2PC com voto NO no Mongo

```powershell
Invoke-RestMethod -Method POST -Uri "http://localhost:8081/users/dtx/2pc?failMongoPrepare=true" -ContentType 'application/json' -Body '{"name":"Bob","email":"bob@dac.com"}'
```

Resultado: `ROLLED_BACK`. Bob não entra em banco nenhum.

### 3. 2PC com falha no commit do Mongo (estado heurístico — ponto-chave)

```powershell
Invoke-RestMethod -Method POST -Uri "http://localhost:8081/users/dtx/2pc?failMongoCommit=true" -ContentType 'application/json' -Body '{"name":"Carol","email":"carol@dac.com"}'
```

Resultado: `PARTIAL_FAILURE`. Pausar aqui e falar:

> "Carol foi inserida no H2 mas não no Mongo. PREPARE foi OK nos dois. H2 commitou. Mongo falhou no commit. Agora o H2 já não consegue mais desfazer porque já commitou. Esse é o famoso estado heurístico do 2PC — depois que todos votam YES, se um RM cai no commit, fica inconsistente. Esse é exatamente o problema que motivou a criação do SAGA e do Outbox."

### 4. Outbox

```powershell
Invoke-RestMethod -Method POST -Uri http://localhost:8081/users/dtx/outbox -ContentType 'application/json' -Body '{"name":"Dave","email":"dave@dac.com"}'
```

Resultado imediato: `PERSISTED_LOCAL_PENDING_REPLICATION`. Esperar 5 segundos, mostrar terminal 1 com `[OUTBOX-RELAY]` replicando.

### 5. SAGA feliz

```powershell
Invoke-RestMethod -Method POST -Uri http://localhost:8081/users/dtx/saga -ContentType 'application/json' -Body '{"name":"Eve","email":"eve@dac.com"}'
```

Resultado: `COMPLETED`.

### 6. SAGA com compensação

```powershell
Invoke-RestMethod -Method POST -Uri "http://localhost:8081/users/dtx/saga?failOnMongo=true" -ContentType 'application/json' -Body '{"name":"Frank","email":"frank@dac.com"}'
```

Resultado: `COMPENSATED`. Mostrar terminal 1 com `← compensando: saveInH2`. Falar:

> "Vê a diferença pro caso 3? No 2PC, o H2 ficou inconsistente porque já tinha commitado. Aqui, a SAGA executou a compensação — deletou o Frank do H2 — e voltou ao estado consistente. Sem precisar de lock distribuído."

### Estado final dos dois bancos

```powershell
Invoke-RestMethod -Method GET -Uri http://localhost:8081/users/dtx/state | ConvertTo-Json -Depth 5
```

Resumo:
- Alice nos dois (2PC feliz)
- Bob em nenhum (rollback global)
- Carol só no H2 (estado heurístico do 2PC)
- Dave nos dois (Outbox replicou)
- Eve nos dois (SAGA feliz)
- Frank em nenhum (SAGA compensou)

## Comparativo (1 min)

| Aspecto | 2PC | Outbox | SAGA |
|---|---|---|---|
| Consistência | forte (com estado heurístico) | eventual | eventual |
| Bloqueia recursos | sim | não | não |
| Funciona sem XA | só simulando | sim | sim |
| Tolerância a falhas | média | alta (retry) | alta (compensações) |

> "Hoje em dia, 2PC quase não é usado em microsserviços novos por causa do problema do bloqueio. A indústria adotou Outbox e SAGA como padrão de facto."

## Encerramento (30s)

> "Resumindo o que foi entregue: 2PC manual com coordinator próprio e participantes pra H2 e Mongo, Outbox Pattern com tabela de eventos e relay assíncrono, e SAGA com orchestrator e compensações inversas. Tudo escrito do zero, sem framework de transação distribuída, conforme o trabalho pediu. Obrigada."

## Perguntas que podem aparecer

**No diagrama tá UserH2DAO e UserMongoDAO, no código tá H2DtxParticipant e MongoDtxParticipant. Por quê?**

Funcionalmente é a mesma coisa. Usei `Participant` porque é o termo técnico da literatura de 2PC, padrão usado pela API XA e por Transaction Managers reais. Essas classes não fazem só CRUD — elas implementam o ciclo de vida do protocolo (prepare/commit/rollback), que é o que define um participante de transação distribuída.

**Por que o Mongo aparece como XA simulado?**

MongoDB single-node não tem suporte XA nativo, e as transações nativas só funcionam em replica set. Pra fins didáticos, simulei o protocolo manualmente: no prepare gravo numa coleção `users_staging`, no commit movo pra coleção definitiva, no rollback apago da staging. Em produção com replica set, usaria `session.startTransaction()`.

**Por que tem dois coordinators (XATransactionCoordinator e DtxCoordinator)?**

O `XATransactionCoordinator` usa a API padrão `javax.transaction.xa.XAResource` — funciona só com bancos XA-capable como o H2. Está no projeto como referência do protocolo padrão. O `DtxCoordinator` foi feito sobre uma interface mais simples (`DtxParticipant`), pra permitir que o Mongo entre como participante, já que ele não tem XA nativo.

**Por que o Outbox usa JPA e o 2PC usa JDBC bruto?**

Requisitos diferentes. O Outbox precisa que duas escritas (User + evento) sejam atômicas localmente — `@Transactional` do JPA resolve elegantemente. O 2PC precisa de controle fino sobre prepare/commit/rollback em conexões mantidas abertas entre as fases — JDBC bruto deixa cada fase do protocolo visível no código.

**E se o relay do Outbox falhar?**

O evento fica PENDING e o relay retenta no próximo ciclo. Tem contador de tentativas — depois de 5 falhas, marca como FAILED pra intervenção manual.

**E se a compensação da SAGA falhar?**

Loguei como `COMPENSATION_FAILED`. Em produção, isso vira alerta pra intervenção manual ou pra um retry queue. Por isso compensações são desenhadas pra serem idempotentes.

**Por que o Mongo está local em Docker em vez do Atlas do enunciado?**

Tentei usar o Atlas, mas o cluster do enunciado apresentou erro de conexão TLS no horário dos testes. Pra estabilidade da demo, optei por subir um Mongo local via Docker. A URI do Atlas continua no `application.properties` como comentário, pronta pra ser ativada — é só descomentar.

## Checklist final

- Docker rodando, `dac-mongo` ativo (`docker ps`)
- Aplicação rodando no terminal 1
- Terminal 2 pronto, na pasta do projeto
- `comandos.txt` aberto pra copiar
- Abas do VSCode: DtxCoordinator, H2DtxParticipant, MongoDtxParticipant, OutboxRelay, SagaOrchestrator
- README aberto pra consulta
