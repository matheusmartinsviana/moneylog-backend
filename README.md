# moneylog backend

[![ci](https://github.com/matheusmartinsviana/moneylog-backend/actions/workflows/ci.yml/badge.svg)](https://github.com/matheusmartinsviana/moneylog-backend/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-6DB33F?logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white)

API do [moneylog](https://github.com/matheusmartinsviana/moneylog), um controle financeiro pessoal. Spring Boot 4.1
(Java 21) e PostgreSQL, com Clean Architecture por feature: `core` (domínio puro) e `infra` (Spring, JPA, web).
O razão sempre fecha em zero e todo cálculo financeiro fica aqui. O contrato está em `contracts/openapi.yaml`.

## Requisitos

- JDK 21 (`JAVA_HOME` apontando para ele)
- Docker (Postgres local e Testcontainers)
- Maven não precisa estar instalado: use `./mvnw`

## Rodando

```bash
cp .env.example .env        # preencha DB_PASSWORD, JWT_SECRET e os dados do dono
docker compose up -d --wait # Postgres 17
set -a && source .env && set +a
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
curl -s localhost:8080/actuator/health
```

O schema vem só das migrações Flyway em `src/main/resources/db/migration` (o Hibernate roda com `ddl-auto: validate`).
Migração já aplicada não se edita.

## Configuração

Nenhum segredo fica no código. Tudo vem do ambiente (veja `.env.example`):

| Variável | Para quê |
|---|---|
| `JWT_SECRET` | segredo do JWT, no mínimo 32 bytes (`openssl rand -base64 48`) |
| `MONEYLOG_OWNER_EMAIL` | e-mail do dono, criado no primeiro boot |
| `MONEYLOG_OWNER_SETUP_CODE` | código de uso único do primeiro acesso, para definir a senha |
| `MONEYLOG_ALLOWED_ORIGINS` | origens do frontend, separadas por vírgula (conferidas no `Origin` do refresh e do logout) |
| `MONEYLOG_TRUSTED_PROXIES` | CIDRs de proxies confiáveis; vazio ignora `X-Forwarded-For` |
| `MONEYLOG_PUSH_VAPID_*` | chaves do web push (push vem desligado: `moneylog.push.enabled=false`) |

Autenticação de usuário único: JWT de 15 minutos mais refresh opaco rotativo de 30 dias em cookie `HttpOnly`,
`Secure`, `SameSite=Strict`. Tentativas de login são limitadas por IP.

## Testes

| Comando | O que roda |
|---|---|
| `./mvnw test` | testes unitários e ArchUnit, sem Docker |
| `./mvnw verify` | unitários e integração com Testcontainers; deixa de fora os ITs pesados (tag `slow`) |
| `./mvnw verify -Pslow` | tudo, incluindo os ITs `slow`, com o gate de cobertura do JaCoCo (90% de linhas em `core`) |

O ArchUnit garante que o `core` não depende de `infra`, Spring, JPA nem Jackson, e não usa `double`/`float`.

## Estrutura

```
com.moneylog
├── core/    domínio e casos de uso, Java puro
│   ├── sk/ account/ category/ transaction/ card/ debt/ thirdparty/ purchasegroup/
│   ├── commitment/ recurrence/ income/ asset/ goal/ finance/ insights/
│   └── importing/ notification/ note/ search/ profile/ ai/ backup/ auth/
└── infra/   controllers, persistência JPA, gateways e configuração
```

## Produção

Imagem multi-stage (JRE 21 alpine, usuário não root) com o perfil `prod`: logs JSON, probes de liveness e
readiness, cookie `Secure`, Swagger desligado e segredos lidos de arquivos em `SECRETS_DIR`.

```bash
docker build -t moneylog-backend:local .
```

Decisões de arquitetura, deploy e backup ficam no [repositório principal](https://github.com/matheusmartinsviana/moneylog).
