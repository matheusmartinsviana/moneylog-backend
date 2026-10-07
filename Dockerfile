# syntax=docker/dockerfile:1
# Imagem de produção do backend (T175). Build: docker build -t moneylog-backend:local backend/
# Multi-stage: o JDK e o Maven ficam só no estágio de build; a imagem final leva apenas o JRE e o jar em camadas.

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src/ src/
# Os testes rodam no CI; aqui só empacota.
RUN ./mvnw -B -q -DskipTests package \
    && java -Djarmode=tools -jar target/moneylog-backend-*.jar extract --layers --destination /layers

FROM eclipse-temurin:21-jre-alpine
# pg_dump/pg_restore para o job de backup (T176); wget (busybox) para o healthcheck.
RUN apk add --no-cache postgresql17-client \
    && addgroup -S -g 10001 moneylog && adduser -S -u 10001 -G moneylog moneylog \
    && mkdir -p /var/backups/moneylog && chown moneylog:moneylog /var/backups/moneylog
WORKDIR /app
COPY --from=build --chown=moneylog:moneylog /layers/dependencies/ ./
COPY --from=build --chown=moneylog:moneylog /layers/spring-boot-loader/ ./
COPY --from=build --chown=moneylog:moneylog /layers/snapshot-dependencies/ ./
COPY --from=build --chown=moneylog:moneylog /layers/application/ ./
USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/moneylog-backend-0.1.0-SNAPSHOT.jar"]
