# moneylog — backend

Spring Boot 4.1 (Java 21) com Clean Architecture por feature: `core` (domínio puro, sem framework) e `infra` (Spring, JPA, web). Decisões: [ADR-001](../docs/adrs/ADR-001-plataforma-dados-stack.md), [ADR-002](../docs/adrs/ADR-002-razao-e-dinheiro.md), [ADR-004](../docs/adrs/ADR-004-idioma-do-codigo.md). Modelo de domínio: [D1](../docs/arquitetura/dominio-financeiro.md). Arquitetura: [D2](../docs/arquitetura/arquitetura.md).

## Pré-requisitos
- **JDK 21**. O `java` padrão do PATH não serve (o Maven do brew usaria o JDK 26 e o `maven-enforcer-plugin` falha):
  ```bash
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  ```
- **Docker Desktop** rodando (Postgres local e Testcontainers).
- Maven não precisa estar instalado: use o wrapper `./mvnw`.

## Configuração (`.env`)
Na raiz do repositório:
```bash
cp .env.example .env   # preencha DB_PASSWORD, JWT_SECRET etc. O .env está no .gitignore.
```
Nenhum segredo fica no código, no `application*.yaml` nem no `docker-compose.yml`.

> **Cuidado com `$` (hash BCrypt).** O Docker Compose interpola `$VAR` no `.env` inteiro, e o shell também, ao carregar o arquivo. Um hash como `$2a$10$...` sem aspas é corrompido em silêncio (vira `a.Kxyz`, por exemplo). Coloque-o entre **aspas simples**, que o Compose e o shell tratam como literal:
> ```
> MONEYLOG_OWNER_PASSWORD_HASH='$2a$10$...'
> ```
> Não use o escape `$$`: o Compose aceita, mas o shell transforma `$$` no PID do processo.

## Autenticação (T22b, ADR-003)
Um usuário só (o dono), sem cadastro público. JWT HS256 de 15 min (em memória no front) + refresh opaco rotativo de 30 dias em cookie `ml_refresh` (`HttpOnly; Secure; SameSite=Strict; Path=/v1/auth`). Variáveis de ambiente (veja `.env.example`):

| Variável | Para quê |
|---|---|
| `JWT_SECRET` | segredo do JWT, **no mínimo 32 bytes** (o app não sobe com menos). `openssl rand -base64 48` |
| `MONEYLOG_OWNER_EMAIL` | e-mail do dono. Criado no primeiro boot; se mudar, o e-mail é atualizado mantendo o `id` (os dados continuam visíveis) |
| `MONEYLOG_OWNER_PASSWORD_HASH` | hash BCrypt da senha, **entre aspas simples** no `.env` (o `$` é interpolado por Compose e shell). Não use `$$` |
| `MONEYLOG_OWNER_PASSWORD_HASH_FILE` | alternativa: caminho de um arquivo com só o hash (vale mais que o valor direto). Evita o problema do `$` |
| `MONEYLOG_ALLOWED_ORIGINS` | origens do front, separadas por vírgula; `refresh` e `logout` conferem o header `Origin` (CSRF). Sem CORS |
| `MONEYLOG_TRUSTED_PROXIES` | CIDRs de proxies de confiança (CloudFront/ALB). Só então o IP do rate limit vem de `X-Forwarded-For`. **Vazio = o header é ignorado** |

Propriedades (`application.yaml`, prefixo `moneylog.auth`): `access-token-ttl` (`PT15M`), `refresh-ttl` (`P30D`), `reuse-grace` (`PT30S`: refresh já rotacionado reapresentado até 30 s depois não é reuso e gera um par novo da mesma família; depois disso, ou se já revogado, `401 REFRESH_INVALID` e a família inteira é revogada), `max-login-failures`/`login-window` (5 falhas por IP em 15 min → `429` com `Retry-After`), `cookie-secure` (padrão `true`).

- **`cookie-secure=false` é plano B** e só é aceito com o perfil `dev` ativo **e** todas as `MONEYLOG_ALLOWED_ORIGINS` em `localhost`/`127.0.0.1`; em qualquer outro caso o app não sobe. O caminho principal é HTTPS local com `mkcert`, documentado no `frontend/README.md` (T30).
- Trocar a senha é trocar o hash no ambiente e reiniciar. Isso **não** revoga os refresh tokens emitidos; para derrubar todas as sessões, troque o `JWT_SECRET` e limpe `refresh_tokens` (S-97).
- O rate limit é em memória (uma instância); com mais de uma, precisa de armazenamento compartilhado (pendência do deploy).
- Nos testes de integração, `src/test/resources/application-it.yaml` traz um segredo de 32+ bytes e o hash BCrypt de `senha-forte-123` (valores só de teste).

## Rodando
```bash
# 1. Postgres local (postgres:17, com healthcheck)
docker compose up -d --wait          # na raiz

# 2. Aplicação com o perfil dev, carregando o .env no ambiente
cd backend
set -a && source ../.env && set +a
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# 3. Health
curl -s localhost:8080/actuator/health   # {"status":"UP"}
```
Só o `health` fica exposto no actuator. O schema é criado **só** pelas migrações Flyway (`src/main/resources/db/migration`); o Hibernate roda com `ddl-auto: validate`.

## Testes
| Comando | O que roda |
|---|---|
| `./mvnw test` | só os unitários (`*Test`, surefire): domínio do `core`, ArchUnit e consistência da configuração. Não precisa de Docker. |
| `./mvnw verify` | unitários + integração (`*IT`, tag `i`, failsafe) com Testcontainers (`postgres:17`, a mesma imagem do compose) + quality gate do JaCoCo. Precisa do Docker. |

- **Quality gate**: `jacoco:check` exige ≥ 90% de linhas cobertas em `com.moneylog.core..` (S-29), medido pelos unitários. Relatório em `target/site/jacoco/index.html`.
- **ArchUnit** (`ArchitectureTest`): o `core` não pode depender de `com.moneylog.infra..`, `org.springframework..`, `jakarta.persistence..`, `org.hibernate..`, `com.fasterxml..`, `tools.jackson..` (Jackson 3) nem `lombok..`, e não pode usar `double`/`float` (campos, assinaturas ou chamadas). Também: features do core sem ciclos, `*UseCaseImpl` só em `core.<feature>.app`, `*Entity` só em `infra.<feature>.persistence`.
- Testes de integração usam a meta-anotação `@IntegrationTest` (`com.moneylog.util`). Sem H2.
- Testes de domínio: JUnit puro, `@DisplayName` em português, factories `umX()`/`umaX()` com UUIDs e datas fixos, e relógio fixo (`ClockTimeProvider` com `Clock.fixed`).

## Pacotes
```
com.moneylog
├── core/              Java puro
│   ├── sk/            shared kernel: money, time, ledger (Posting, PostingSource, LedgerEntry, VoidDecision), exception, page
│   ├── account/       conta (domain, exception)
│   ├── category/      categoria (domain, exception)
│   ├── transaction/   movimentação: agregado, 11 tipos, fábricas por grupo, guardas de estorno (TransactionVoidPolicy)
│   ├── card/          cartão, calendário, parcelas, fatura (status derivado), crédito transportado, limite, guardas
│   ├── debt/          pessoas e dívidas (status derivado, perdão, guardas)
│   ├── recurrence/    recorrência (agenda, ocorrências previstas, estimativa mensal)
│   ├── finance/       lentes: razão, caixa, fluxo, consumo, lançamentos, patrimônio, compromissos, dashboard
│   ├── importing/     importação de extrato: parsers CSV/OFX puros, lote, revisão, deduplicação, sugestão por histórico (Etapa 7)
│   └── notification/  central, lembretes, configurações, geradores, resumo diário e porta de push (Etapa 7)
└── infra/             Spring (vazio na Etapa 1)
```
Schema: `src/main/resources/db/migration/V1.00`–`V1.08` (editáveis até o primeiro banco fora do dev, plano T11).

## Importação de extrato e notificações (Etapa 7)
- `POST /v1/imports` (CSV/OFX, até 5 MB e 5.000 linhas) só **prepara** o lote; nada vira movimentação até `POST /v1/imports/{id}/apply`, que usa os mesmos handlers da criação manual. O arquivo bruto não é guardado. Amostras de teste ficam nos próprios testes (`CsvStatementParserTest`, `OfxStatementParserTest`).
- O job de notificações (`moneylog.notifications.job.*`) roda a cada 15 min em `America/Sao_Paulo` e é idempotente (índice único). Nos ITs fica desligado.
- **Push preparado e desligado**: `moneylog.push.enabled=false`. Para ligar: `MONEYLOG_PUSH_VAPID_PUBLIC`, `MONEYLOG_PUSH_VAPID_PRIVATE`, `MONEYLOG_PUSH_VAPID_SUBJECT` (ver `.env.example`), HTTPS real e PWA instalada. Detalhes em `docs/adrs/ADR-012-notificacoes-e-push.md`.
