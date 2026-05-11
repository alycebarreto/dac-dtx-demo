# Roteiro de Apresentação — Transações Distribuídas

**Duração estimada:** 12-15 min
**Stack:** Spring Boot 2.7 + H2 + MongoDB (Docker) + Gradle + Java 17

## Antes de começar (preparação)

Deixe pronto na máquina:
- Terminal 1: aberto na pasta do projeto, com a aplicação **já rodando** (`bootRun`)
- Terminal 2: aberto na mesma pasta, vazio, pronto pra colar comandos
- VSCode aberto com os arquivos principais na lateral
- Docker Desktop rodando (confira com `docker ps` → tem que ver `dac-mongo`)
- O `README.md` aberto numa aba do VSCode pra consulta rápida

**Atalho útil:** deixa um arquivo `comandos.txt` com os 6 comandos PowerShell prontos pra você só copiar e colar.

---

## Parte 1 — Abertura e o problema (1 min)

### O que falar

> "O trabalho pediu pra implementar transações distribuídas usando 2PC, Outbox e SAGA, sem usar nenhum framework, salvando a mesma entidade em dois bancos diferentes — no nosso caso, **H2 e MongoDB**.
>
> O problema central é simples de enunciar e difícil de resolver: como garantir que quando eu salvo um usuário, ele apareça nos DOIS bancos, ou em NENHUM? Em monolito isso é trivial com `@Transactional`. Quando os dados estão espalhados, não dá mais."

### O que mostrar

- VSCode aberto com a estrutura do projeto na lateral
- Abrir o `application.properties` e destacar a linha `app.dao.impl=dtx`

---

## Parte 2 — Os 3 padrões (3 min)

### Mapeamento do diagrama UML com o código

> "Antes de mergulhar no código, só quero deixar claro a correspondência entre o diagrama UML do projeto e os nomes das classes:"

| Diagrama UML | Classe no código |
|---|---|
| **Controller** | `UserController` |
| **DTxCoord** | `DtxCoordinator` |
| **UserH2DAO** | `H2DtxParticipant` |
| **UserMongoDAO** | `MongoDtxParticipant` |

> "Eu usei `Participant` em vez de `DAO` porque é o termo técnico da literatura de 2PC — quem participa do protocolo é chamado de Resource Manager ou Participant, padrão usado pelo XA e por Transaction Managers como Atomikos. Funcionalmente é o mesmo papel do DAO no diagrama: persistir os dados implementando o ciclo de vida do 2PC."

### O que falar

> "Implementei os três padrões clássicos que a literatura propõe pra esse problema:
>
> **1. Two-Phase Commit (2PC)** — coordinator faz duas fases: PREPARE pergunta pros bancos 'você consegue commitar?', e COMMIT efetiva. Se algum vota 'não', rollback global.
>
> **2. Transactional Outbox** — escrevo no H2 e numa tabela de eventos NA MESMA transação local. Depois um processo assíncrono replica pro Mongo. Garante 'at-least-once delivery'.
>
> **3. SAGA** — quebro a operação em passos locais commitados imediatamente. Se um falha, executo as compensações dos anteriores na ordem inversa. Sem lock distribuído."

### O que mostrar

Abra estes arquivos rapidamente na ordem (uns 15 segundos cada):

1. **`DtxCoordinator.java`** — destaque o método `run()`. Diga: "aqui está o 2PC: fase 1 PREPARE em todos, se passou faz COMMIT, senão ROLLBACK."

2. **`H2DtxParticipant.java` + `MongoDtxParticipant.java`** — destaque os 3 métodos: `prepare()`, `commit()`, `rollback()`. Diga: "cada banco implementa esses 3 métodos do jeito dele."

3. **`OutboxRelay.java`** — destaque o método `@Scheduled drain()`. Diga: "esse aqui roda a cada 5 segundos, lê eventos PENDING e replica pro Mongo. É a parte assíncrona do padrão Outbox."

4. **`SagaOrchestrator.java`** — destaque o bloco `Collections.reverse(executed)`. Diga: "se um passo falha, compenso os anteriores em ordem INVERSA — isso é o coração do SAGA."

---

## Parte 3 — Demonstração ao vivo (6-8 min)

> "Agora vou rodar 6 cenários: 2 felizes e 4 com falhas simuladas, pra mostrar o comportamento de cada padrão."

Antes de começar a rodar, mostre o Terminal 1 com a aplicação rodando e o Terminal 2 vazio.

### Cenário 1 — 2PC feliz

```powershell
Invoke-RestMethod -Method POST -Uri http://localhost:8081/users/dtx/2pc -ContentType 'application/json' -Body '{"name":"Alice","email":"alice@dac.com"}'
```

> "Alice. Resultado: **COMMITTED**. Olhando o terminal da aplicação..."

**Mostre o Terminal 1** e aponte:
```
FASE 1: PREPARE
  [H2-INSERT] VOTOU: YES
  [Mongo-INSERT] VOTOU: YES
FASE 2: COMMIT
  [H2-INSERT] COMMIT OK
  [Mongo-INSERT] COMMIT OK
```

> "Vê os logs? PREPARE em ambos, ambos votaram YES, COMMIT em ambos. Cenário ideal."

### Cenário 2 — 2PC rollback

```powershell
Invoke-RestMethod -Method POST -Uri "http://localhost:8081/users/dtx/2pc?failMongoPrepare=true" -ContentType 'application/json' -Body '{"name":"Bob","email":"bob@dac.com"}'
```

> "Bob, com falha forçada no prepare do Mongo. Resultado: **ROLLED_BACK**."

**Mostre o Terminal 1**: o log mostra Mongo votando NO e H2 fazendo rollback.

> "O coordinator detectou voto negativo, abortou a transação inteira. Bob não vai existir em banco nenhum."

### Cenário 3 — 2PC com PARTIAL_FAILURE (o ponto mais importante!)

```powershell
Invoke-RestMethod -Method POST -Uri "http://localhost:8081/users/dtx/2pc?failMongoCommit=true" -ContentType 'application/json' -Body '{"name":"Carol","email":"carol@dac.com"}'
```

> "Carol, com falha forçada agora no COMMIT do Mongo. Resultado: **PARTIAL_FAILURE**."

**Esse é o cenário-chave para impressionar o professor.** Pause aqui e fale:

> "Olha o que aconteceu: PREPARE foi OK nos dois. H2 commitou. Mongo falhou no commit. **Agora o H2 já não consegue mais fazer rollback** porque ele já commitou. Esse é o famoso **estado heurístico** do 2PC — depois que todos votam YES, se um RM cai no commit, fica inconsistente.
>
> **Esse é exatamente o problema que motivou a criação do SAGA e do Outbox.** Em produção, Transaction Managers como Atomikos gravam logs duráveis pra recuperação manual, mas o problema fundamental do 2PC continua: ele **bloqueia recursos** e tem esse buraco entre fase 1 e fase 2."

### Cenário 4 — Outbox

```powershell
Invoke-RestMethod -Method POST -Uri http://localhost:8081/users/dtx/outbox -ContentType 'application/json' -Body '{"name":"Dave","email":"dave@dac.com"}'
```

> "Dave, via Outbox. Resposta imediata: PERSISTED_LOCAL_PENDING_REPLICATION. Salvou no H2 com um evento PENDING. Agora vou esperar 5 segundos..."

**Espere 5-6 segundos e mostre o Terminal 1**:

```
[OUTBOX-RELAY] Drenando outbox: 1 evento(s) PENDING
[OUTBOX-RELAY]   ✓ OutboxEvent[id=N, ...] replicado para Mongo
```

> "Pronto, o relay assíncrono drenou a outbox e replicou pro Mongo. Se o Mongo estivesse fora do ar, o evento ficaria PENDING e seria retentado. **At-least-once delivery garantida**."

### Cenário 5 — SAGA feliz

```powershell
Invoke-RestMethod -Method POST -Uri http://localhost:8081/users/dtx/saga -ContentType 'application/json' -Body '{"name":"Eve","email":"eve@dac.com"}'
```

> "Eve via SAGA. Resultado: **COMPLETED**. Dois passos rodaram, nenhum falhou."

### Cenário 6 — SAGA com compensação (segundo ponto importante)

```powershell
Invoke-RestMethod -Method POST -Uri "http://localhost:8081/users/dtx/saga?failOnMongo=true" -ContentType 'application/json' -Body '{"name":"Frank","email":"frank@dac.com"}'
```

> "Frank, com falha forçada no Mongo. Resultado: **COMPENSATED**."

**Mostre o Terminal 1**:

```
[SAGA xxxx] → executando: saveInH2          → OK
[SAGA xxxx] → executando: saveInMongo       → FALHOU
[SAGA xxxx] ── INICIANDO COMPENSACOES ──
[SAGA xxxx] ← compensando: saveInH2         → COMPENSADO
```

> "Vê a diferença pro 2PC? No 2PC do Cenário 3, o H2 ficou inconsistente. Aqui, **a SAGA executou a compensação** — DELETOU o Frank do H2 — e voltou ao estado consistente. **Sem precisar de lock distribuído.**"

---

## Parte 4 — Estado final dos dois bancos (2 min)

```powershell
Invoke-RestMethod -Method GET -Uri http://localhost:8081/users/dtx/state | ConvertTo-Json -Depth 5
```

> "Esse endpoint lista os dois bancos lado a lado. Vamos conferir:"

Aponte:

- **Alice** está nos dois ✓ (2PC feliz)
- **Bob** não está em nenhum ✓ (rollback global)
- **Carol** está só no H2 ⚠ — **estado heurístico do 2PC** (o problema)
- **Dave** está nos dois ✓ (Outbox replicou)
- **Eve** está nos dois ✓ (SAGA feliz)
- **Frank** não está em nenhum ✓ (SAGA compensou)

> "Cada padrão demonstrou exatamente o comportamento previsto pela teoria. E a Carol é a evidência didática do limite do 2PC."

---

## Parte 5 — Comparativo e quando usar cada um (1 min)

> "Resumindo:"

| Aspecto | 2PC | Outbox | SAGA |
|---|---|---|---|
| **Consistência** | forte (mas com estado heurístico) | eventual | eventual |
| **Bloqueia recursos?** | sim (locks distribuídos) | não | não |
| **Funciona com banco sem XA?** | só simulando | sim | sim |
| **Tolerante a falhas?** | médio | alto (retry) | alto (compensações) |
| **Quando usar** | sistemas bancários legados | microsserviços modernos | fluxos com etapas longas |

> "Hoje em dia, **2PC praticamente não é usado em microsserviços novos** — o problema do bloqueio mata a escalabilidade. A indústria adotou Outbox + SAGA como padrão de facto."

---

## Parte 6 — Encerramento (30s)

> "Resumindo o que foi entregue:
>
> - **2PC manual** com coordinator próprio e participantes para H2 e Mongo
> - **Outbox Pattern** com tabela de eventos + relay assíncrono
> - **SAGA Pattern** com orchestrator e compensações inversas
> - Tudo escrito do zero, **sem framework de transação distribuída**, como o trabalho pediu
> - 6 cenários demonstráveis cobrindo casos felizes e de falha
>
> Obrigada! Alguma pergunta?"

---

## Perguntas que o professor pode fazer (e respostas-cola)

### "No diagrama tá UserH2DAO e UserMongoDAO, mas no código está H2DtxParticipant e MongoDtxParticipant. Por quê?"

> "Funcionalmente é a mesma coisa — DAOs que persistem os dados implementando o ciclo de vida do 2PC. Eu usei `Participant` porque é o termo técnico da literatura de 2PC: quem participa do protocolo é chamado de **Resource Manager** ou **Participant**, padrão usado pela API XA e por Transaction Managers como Atomikos. Mantive essa nomenclatura pra deixar claro que essas classes não fazem só CRUD — elas implementam `prepare()`, `commit()` e `rollback()`, que é o que define um participante de transação distribuída."

### "Por que o Mongo aparece como 'XA simulado'?"

> "MongoDB single-node não tem suporte XA nativo. As transações nativas do Mongo só funcionam em replica set. Pra fins didáticos, simulei o protocolo manualmente usando uma coleção `users_staging`: no prepare gravo lá, no commit movo pra coleção definitiva, no rollback apago da staging. Em produção com replica set, usaria `session.startTransaction()`."

### "Por que tem dois coordinators (XATransactionCoordinator e DtxCoordinator)?"

> "O `XATransactionCoordinator` usa a API padrão `javax.transaction.xa.XAResource` e funciona só com bancos XA-capable como o H2 — está no projeto como referência do protocolo padrão. O `DtxCoordinator` foi criado por mim sobre uma interface mais simples (`DtxParticipant`), justamente pra permitir que o Mongo entre como participante, já que ele não tem XA nativo."

### "Por que o Outbox usa JPA e o 2PC usa JDBC bruto?"

> "São requisitos diferentes. O Outbox precisa que duas escritas (User + evento) sejam atômicas localmente — `@Transactional` do JPA resolve isso elegantemente. O 2PC precisa de controle fino sobre prepare/commit/rollback em conexões mantidas abertas entre as fases — JDBC bruto deixa cada fase do protocolo visível no código."

### "E se o relay do Outbox falhar?"

> "O evento fica PENDING e o relay retenta no próximo ciclo (a cada 5 segundos). Tem um contador de tentativas — depois de 5 tentativas falhadas, marca como FAILED pra intervenção manual. É um trade-off clássico: aceito atraso (consistência eventual) em troca de não bloquear nada."

### "Por que a SAGA é melhor que o 2PC em microsserviços?"

> "Três motivos: (1) não bloqueia recursos — cada passo é uma tx local commitada na hora; (2) tolera bancos sem XA, o que é o normal hoje; (3) lida com falhas explicitamente via compensação, em vez de assumir que tudo vai dar certo entre prepare e commit. Em troca, aceita estados intermediários visíveis — outros leitores podem ver o sistema 'no meio' da operação."

### "E se a compensação da SAGA falhar?"

> "Esse caso eu loguei como `COMPENSATION_FAILED`. Em produção, isso vira alerta pra intervenção manual ou pra um retry queue. Por isso, em SAGA bem desenhada, as compensações são desenhadas pra serem **idempotentes** — pode rodar várias vezes sem problema."

---

## Checklist final antes da apresentação

- [ ] Docker rodando, `dac-mongo` ativo (`docker ps`)
- [ ] Aplicação rodando no Terminal 1 (`.\gradlew.bat bootRun`)
- [ ] Terminal 2 aberto na pasta do projeto
- [ ] `comandos.txt` com os 6 comandos prontos
- [ ] VSCode com os arquivos `DtxCoordinator`, `H2DtxParticipant`, `MongoDtxParticipant`, `OutboxRelay`, `SagaOrchestrator` abertos em abas
- [ ] README aberto pra consulta
- [ ] Áudio do PC OK (caso haja som)
- [ ] Respirar fundo. Você sabe disso. 🚀
