# moneylog: backend

[![ci](https://github.com/matheusmartinsviana/moneylog-backend/actions/workflows/ci.yml/badge.svg)](https://github.com/matheusmartinsviana/moneylog-backend/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-6DB33F?logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white)
![Flyway](https://img.shields.io/badge/Flyway-migrations-CC0200?logo=flyway&logoColor=white)
![OpenAPI](https://img.shields.io/badge/OpenAPI-contract--first-6BA539?logo=openapiinitiative&logoColor=white)

API do **moneylog**, controle financeiro pessoal. Spring Boot 4.1 (Java 21) com Clean Architecture por feature:
`core` (domínio puro, sem framework) e `infra` (Spring, JPA, web). O razão sempre fecha em zero, e todo cálculo
financeiro mora aqui; o frontend só exibe o que a API devolve.

- **Contrato primeiro:** `contracts/openapi.yaml` é a fonte da verdade; o frontend mantém uma cópia sincronizada.
- **Banco:** PostgreSQL 17, schema só por migrações Flyway (`ddl-auto: validate`), sem H2 nos testes.
- **Decisões:** [ADR-001](https://github.com/matheusmartinsviana/moneylog/blob/main/docs/adrs/ADR-001-plataforma-dados-stack.md), [ADR-002](https://github.com/matheusmartinsviana/moneylog/blob/main/docs/adrs/ADR-002-razao-e-dinheiro.md), [ADR-004](https://github.com/matheusmartinsviana/moneylog/blob/main/docs/adrs/ADR-004-idioma-do-codigo.md). Modelo de domínio: [D1](https://github.com/matheusmartinsviana/moneylog/blob/main/docs/arquitetura/dominio-financeiro.md). Arquitetura: [D2](https://github.com/matheusmartinsviana/moneylog/blob/main/docs/arquitetura/arquitetura.md).

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
| `MONEYLOG_OWNER_PASSWORD_HASH` | (opcional desde o ADR-015; retrocompatibilidade) hash BCrypt da senha, **entre aspas simples** no `.env` (o `$` é interpolado por Compose e shell). Não use `$$` |
| `MONEYLOG_OWNER_PASSWORD_HASH_FILE` | alternativa: caminho de um arquivo com só o hash (vale mais que o valor direto). Evita o problema do `$` |
| `MONEYLOG_OWNER_SETUP_CODE` / `_FILE` | código de uso único do primeiro acesso (mín. 24 caracteres variados; em prod, por secret). Vazio: gerado e impresso **uma vez** no log do perfil dev |
| `MONEYLOG_OWNER_RESET_PASSWORD` | `true` apaga a senha do banco e exige novo primeiro acesso (uso único: **remova depois**) |
| `MONEYLOG_ALLOWED_ORIGINS` | origens do front, separadas por vírgula; `refresh` e `logout` conferem o header `Origin` (CSRF). Sem CORS |
| `MONEYLOG_TRUSTED_PROXIES` | CIDRs de proxies de confiança (CloudFront/ALB). Só então o IP do rate limit vem de `X-Forwarded-For`. **Vazio = o header é ignorado** |

Propriedades (`application.yaml`, prefixo `moneylog.auth`): `access-token-ttl` (`PT15M`), `refresh-ttl` (`P30D`), `reuse-grace` (`PT30S`: refresh já rotacionado reapresentado até 30 s depois não é reuso e gera um par novo da mesma família; depois disso, ou se já revogado, `401 REFRESH_INVALID` e a família inteira é revogada), `max-login-failures`/`login-window` (5 falhas por IP em 15 min → `429` com `Retry-After`), `cookie-secure` (padrão `true`).

- **`cookie-secure=false` é plano B** e só é aceito com o perfil `dev` ativo **e** todas as `MONEYLOG_ALLOWED_ORIGINS` em `localhost`/`127.0.0.1`; em qualquer outro caso o app não sobe. O caminho principal é HTTPS local com `mkcert`, documentado no `frontend/README.md` (T30).
- **Primeiro acesso (ADR-015).** `GET /v1/auth/status` → `{passwordSetupRequired}`; `POST /v1/auth/setup-password {email, setupCode, newPassword}` define a senha (12+ caracteres, até 72 bytes, diferente do e-mail), devolve a sessão e revoga todos os refresh tokens. Ordem do login: senha do banco (a única que vale quando definida) > redefinição pendente (ninguém entra) > hash do ambiente (retrocompatibilidade). Trocar o e-mail do dono é só mudar `MONEYLOG_OWNER_EMAIL` (mesmo id, dados intactos).
- (Legado, sem senha no banco) Trocar a senha é trocar o hash no ambiente e reiniciar. Isso **não** revoga os refresh tokens emitidos; para derrubar todas as sessões, troque o `JWT_SECRET` e limpe `refresh_tokens` (S-97).
- O rate limit é em memória (uma instância); com mais de uma, precisa de armazenamento compartilhado (pendência do deploy).
- Nos testes de integração, `src/test/resources/application-it.yaml` traz um segredo de 32+ bytes e o hash BCrypt de `senha-forte-123` (valores só de teste).

## Rodando
```bash
# 1. Postgres local (postgres:17, com healthcheck)
docker compose up -d --wait

# 2. Aplicação com o perfil dev, carregando o .env no ambiente
set -a && source .env && set +a
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# 3. Health
curl -s localhost:8080/actuator/health   # {"status":"UP"}
```
Só o `health` fica exposto no actuator. O schema é criado **só** pelas migrações Flyway (`src/main/resources/db/migration`); o Hibernate roda com `ddl-auto: validate`.

## Testes
| Comando | O que roda |
|---|---|
| `./mvnw test` | só os unitários (`*Test`, surefire): domínio do `core`, ArchUnit e consistência da configuração. Não precisa de Docker. |
| `./mvnw verify` | unitários + integração (`*IT`, tag `i`, failsafe) com Testcontainers (`postgres:17`, a mesma imagem do compose). Os ITs pesados (tag `slow`) ficam de fora e o gate de cobertura é pulado. Precisa do Docker. |
| `./mvnw verify -Pslow` | tudo: inclui os ITs `slow` e liga o quality gate do JaCoCo. É a rodada completa (dezenas de minutos). |

- **Quality gate** (perfil `slow`): `jacoco:check` exige ≥ 90% de linhas cobertas em `com.moneylog.core..` (S-29), medido pelos unitários. Relatório em `target/site/jacoco/index.html`.
- **ArchUnit** (`ArchitectureTest`): o `core` não pode depender de `com.moneylog.infra..`, `org.springframework..`, `jakarta.persistence..`, `org.hibernate..`, `com.fasterxml..`, `tools.jackson..` (Jackson 3) nem `lombok..`, e não pode usar `double`/`float` (campos, assinaturas ou chamadas). Também: features do core sem ciclos, `*UseCaseImpl` só em `core.<feature>.app`, `*Entity` só em `infra.<feature>.persistence`.
- Testes de integração usam a meta-anotação `@IntegrationTest` (`com.moneylog.util`). Sem H2.
- Testes de domínio: JUnit puro, `@DisplayName` em português, factories `umX()`/`umaX()` com UUIDs e datas fixos, e relógio fixo (`ClockTimeProvider` com `Clock.fixed`).
- Fixtures de cenário (`infra/resumao`) são sintéticas; dado financeiro real nunca entra no repositório.

## Pacotes
```
com.moneylog
├── core/              Java puro
│   ├── sk/            shared kernel: money, time, ledger, exception, page
│   ├── account/       conta
│   ├── category/      categoria
│   ├── transaction/   movimentação: agregado, tipos, fábricas por grupo, guardas de estorno
│   ├── card/          cartão, calendário, parcelas, fatura (status derivado, valor do documento), limite
│   ├── debt/          pessoas e dívidas (status derivado, perdão, guardas)
│   ├── thirdparty/    recebíveis de terceiros por parcela e pessoa, com mês previsto editável
│   ├── purchasegroup/ compra agrupada
│   ├── commitment/    compromissos acompanhados
│   ├── recurrence/    recorrência (agenda, ocorrências previstas, estimativa mensal)
│   ├── income/        renda detalhada, tabelas fiscais e consignado
│   ├── asset/         ativos não disponíveis e reserva
│   ├── goal/          metas de sonho, com fotos
│   ├── finance/       lentes: razão, caixa, fluxo, consumo, patrimônio, resultado do mês do dono, simulador
│   ├── insights/      insights e alertas
│   ├── importing/     importação de extrato: parsers CSV/OFX, lote, revisão, deduplicação
│   ├── notification/  central, lembretes, configurações, geradores, resumo diário e porta de push
│   ├── note/          vault de notas
│   ├── search/        busca global
│   ├── profile/       foto de perfil e apelido
│   ├── ai/            AIService com redação de dados pessoais
│   ├── backup/        exportação e restauração
│   └── auth/          sessão, refresh rotativo, primeiro acesso
└── infra/             Spring: controllers, JPA, gateways, configuração
```
Schema: migrações em `src/main/resources/db/migration`. As séries `V1.xx` e `V9.xx` são aplicadas em ordem; migração já aplicada não se edita (o Flyway valida o checksum).

## Importação de extrato e notificações (Etapa 7)
- `POST /v1/imports` (CSV/OFX, até 5 MB e 5.000 linhas) só **prepara** o lote; nada vira movimentação até `POST /v1/imports/{id}/apply`, que usa os mesmos handlers da criação manual. O arquivo bruto não é guardado. Amostras de teste ficam nos próprios testes (`CsvStatementParserTest`, `OfxStatementParserTest`).
- O job de notificações (`moneylog.notifications.job.*`) roda a cada 15 min em `America/Sao_Paulo` e é idempotente (índice único). Nos ITs fica desligado.
- **Push preparado e desligado**: `moneylog.push.enabled=false`. Para ligar: `MONEYLOG_PUSH_VAPID_PUBLIC`, `MONEYLOG_PUSH_VAPID_PRIVATE`, `MONEYLOG_PUSH_VAPID_SUBJECT` (ver `.env.example`), HTTPS real e PWA instalada. Detalhes em `docs/adrs/ADR-012-notificacoes-e-push.md`.

## Metas de sonho com fotos (ADR-018)
- `POST /v1/goals/{id}/photos` (multipart `file`, só JPEG/PNG, até 8 MB) valida pelos **magic bytes**, lê as dimensões do cabeçalho antes de decodificar, recodifica sempre (sem EXIF/GPS) e guarda só a exibição (1280 px) e a miniatura (320 px) em `goal_photo_blobs` (`bytea`, tabela separada dos metadados) atrás da porta `PhotoStorage`. O conteúdo sai só por `GET .../photos/{photoId}/content` autenticado (`no-store`, `nosniff`). Nunca vai para log nem para a IA.
- Limites (`moneylog.goals.photos.*`): 8 por meta, 40 por usuário, 40 MB por usuário, 24 MP e 8000 px, 30 envios/min. Uma só meta em destaque por usuário (`PUT /v1/goals/{id}/featured`).
- Imagens dos testes são **geradas por código** (`com.moneylog.util.SyntheticImages`); não há foto nem binário versionado. Não existe seed de demonstração no repositório: quando houver, deve gerar as fotos com `SyntheticImages`/`ImageIO`.

## Produção, imagem Docker e backup (Etapa 9)
- Imagem: `docker build -t moneylog-backend:local .` (multi-stage, JRE 21 alpine, usuário não-root, `SPRING_PROFILES_ACTIVE=prod`). No repositório principal (moneylog), suba com `docker compose -f docker-compose.prod.yml --env-file .env up --build` (precisa de `MONEYLOG_TRUSTED_PROXIES`; local: `172.16.0.0/12`).
- Perfil `prod` (`application-prod.yaml`): logs JSON (ECS), swagger e api-docs desligados, probes `liveness`/`readiness`, cookie `Secure`. Segredos por arquivo: cada arquivo de `SECRETS_DIR` (padrão `/run/secrets`) com o nome da variável (ex.: `JWT_SECRET`).
- Backup: `docs/operacao/backup.md`. Deploy na AWS (preparado, não executado): `docs/operacao/deploy-aws.md` e `docs/adrs/ADR-005-topologia-aws-e-banco.md`.

## Repositórios
Este repositório (`moneylog-backend`) faz parte do projeto **moneylog**, que tem um repositório principal com
`backend/` e `frontend/` como submódulos (docs, infra e scripts de deploy ficam lá). O contrato
`contracts/openapi.yaml` é daqui; o frontend mantém uma cópia sincronizada (`npm run contract:sync`).

Commits: Conventional Commits e **sem `Co-Authored-By`** nem linha de atribuição. Ative o hook com
`git config core.hooksPath .githooks`.
