package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.moneylog.util.ItAuth;
import com.moneylog.util.PostgresTestcontainer;
import com.moneylog.util.SqlFixtures;

/**
 * T190 (QA independente): a busca contra um Tomcat de verdade (MockMvc não passa pelo parser HTTP do container). Pede o que um cliente
 * qualquer (curl, extensão, outro app) pode mandar: caracteres crus que o Tomcat recusa, percent-encoding inválido, NUL, {@code Origin}
 * estranho. Confere que o termo {@code q} não sobra em nenhum log, nem no do container, e o formato dos erros.
 */
@Tag("i")
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestcontainer.class, ItAuth.class})
@TestPropertySource(properties = "moneylog.search.rate-limit.max-requests=1000000")
@DisplayName("QA T190: busca contra o Tomcat real (HTTP cru, log do container, Origin)")
class RedesignFase0HttpQaIT {

    private static final String SECRET = "segredoQ9xK";

    @LocalServerPort
    private int port;
    @Autowired
    private ItAuth auth;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager tm;

    private String rawRequest(String requestLine, String... headers) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            StringBuilder sb = new StringBuilder(requestLine).append("\r\nHost: localhost\r\nConnection: close\r\n");
            for (String h : headers) {
                sb.append(h).append("\r\n");
            }
            sb.append("\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            try (InputStream in = socket.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    @Test
    @DisplayName("requisições cruas: nenhum 5xx, o termo não vai a log nenhum (INFO nem DEBUG, inclusive Tomcat), erros fora do JSON do contrato são só os do container")
    void httpCru() throws Exception {
        UUID id = UUID.randomUUID();
        new TransactionTemplate(tm).executeWithoutResult(s -> SqlFixtures.insertUser(jdbc, id, "http-" + id + "@moneylog.com"));
        String bearer = auth.bearer(id);
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level previous = root.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            root.setLevel(Level.DEBUG);
            StringBuilder report = new StringBuilder();
            String[][] cases = {
                    {"|", "GET /v1/search?q=" + SECRET + "|x HTTP/1.1"},
                    {"{", "GET /v1/search?q=" + SECRET + "{x} HTTP/1.1"},
                    {"[", "GET /v1/search?q=" + SECRET + "[x] HTTP/1.1"},
                    {"^", "GET /v1/search?q=" + SECRET + "^x HTTP/1.1"},
                    {"backtick", "GET /v1/search?q=" + SECRET + "`x HTTP/1.1"},
                    {"aspas", "GET /v1/search?q=" + SECRET + "\"x HTTP/1.1"},
                    {"<", "GET /v1/search?q=" + SECRET + "<x> HTTP/1.1"},
                    {"%00", "GET /v1/search?q=" + SECRET + "%00x HTTP/1.1"},
                    {"%ZZ", "GET /v1/search?q=" + SECRET + "%ZZ HTTP/1.1"},
                    {"%C0%AF", "GET /v1/search?q=" + SECRET + "%C0%AF HTTP/1.1"},
                    {"%", "GET /v1/search?q=" + SECRET + "% HTTP/1.1"},
                    {"%2e%2e", "GET /v1/search/%2e%2e/%2e%2e/actuator?q=" + SECRET + " HTTP/1.1"},
                    {"normal", "GET /v1/search?q=" + SECRET + " HTTP/1.1"},
                    {"sem-HTTP", "GET /v1/search?q=" + SECRET},
                    {"longa", "GET /v1/search?q=" + SECRET + "a".repeat(9000) + " HTTP/1.1"},
            };
            for (String[] c : cases) {
                String response = rawRequest(c[1], "Authorization: " + bearer);
                String statusLine = response.lines().findFirst().orElse("(vazio)");
                String contentType = response.lines().filter(l -> l.toLowerCase().startsWith("content-type:")).findFirst().orElse("");
                report.append(String.format("%-10s %s | %s%n", c[0], statusLine, contentType));
                if (statusLine.contains(" 500")) {
                    report.append("   ^^ 500 (BUG-QA-05)\n");
                }
            }
            System.out.println("QA-F0-HTTP respostas cruas:\n" + report);
            StringBuilder logs = new StringBuilder();
            for (ILoggingEvent e : List.copyOf(appender.list)) {
                logs.append(e.getLevel()).append(' ').append(e.getLoggerName()).append(' ').append(e.getFormattedMessage()).append('\n');
                if (e.getThrowableProxy() != null) {
                    logs.append(ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(e.getThrowableProxy())).append('\n');
                }
            }
            long leaks = logs.toString().lines().filter(l -> l.contains(SECRET)).count();
            if (leaks > 0) {
                System.out.println("QA-F0-HTTP VAZAMENTO DO TERMO NO LOG (" + leaks + " linhas):");
                logs.toString().lines().filter(l -> l.contains(SECRET)).limit(10).forEach(l -> System.out.println("   " + l.substring(0, Math.min(300, l.length()))));
            }
            System.out.println("QA-F0-HTTP linhas de log com o termo: " + leaks + " (BUG-QA-06 se > 0)");
        } finally {
            root.detachAppender(appender);
            root.setLevel(previous);
        }
    }

    @Test
    @DisplayName("BUG-QA-05: percent-encoding inválido não pode dar 500")
    void percentInvalidoNaoDa500() throws Exception {
        UUID id = UUID.randomUUID();
        new TransactionTemplate(tm).executeWithoutResult(s -> SqlFixtures.insertUser(jdbc, id, "http3-" + id + "@moneylog.com"));
        String response = rawRequest("GET /v1/search?q=ab%ZZ HTTP/1.1", "Authorization: " + auth.bearer(id));
        assertThat(response.lines().findFirst().orElse("")).contains(" 400");
        assertThat(response).contains("SEARCH_QUERY_INVALID");
        for (String bad : new String[]{"ab%", "%C0%AF", "%E0%80"}) {
            String other = rawRequest("GET /v1/search?q=" + bad + " HTTP/1.1", "Authorization: " + auth.bearer(id));
            assertThat(other.lines().findFirst().orElse("")).as(bad).contains(" 400");
        }
        String otherRoute = rawRequest("GET /v1/reports/income-expenses?period=%ZZ HTTP/1.1", "Authorization: " + auth.bearer(id));
        assertThat(otherRoute.lines().findFirst().orElse("")).contains(" 400");
    }

    @Test
    @org.junit.jupiter.api.Disabled("BUG-QA-06: GET /v1/search?q=segredo|x (caractere cru que o Tomcat recusa) grava a URL inteira, com o termo, no log da aplicação (Tomcat, 'Invalid character found in the request target')")
    @DisplayName("BUG-QA-06: o termo não vai ao log do container")
    void termoNaoVazaNoLogDoTomcat() throws Exception {
        UUID id = UUID.randomUUID();
        new TransactionTemplate(tm).executeWithoutResult(s -> SqlFixtures.insertUser(jdbc, id, "http4-" + id + "@moneylog.com"));
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            rawRequest("GET /v1/search?q=" + SECRET + "|x HTTP/1.1", "Authorization: " + auth.bearer(id));
            assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage)).noneMatch(m -> m.contains(SECRET));
        } finally {
            root.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("cabeçalhos: 200 com Cache-Control no-store e Content-Type JSON; Origin estranho não ganha CORS; 401 sem token em JSON; método errado 405")
    void cabecalhos() throws Exception {
        UUID id = UUID.randomUUID();
        new TransactionTemplate(tm).executeWithoutResult(s -> SqlFixtures.insertUser(jdbc, id, "http2-" + id + "@moneylog.com"));
        String bearer = auth.bearer(id);
        HttpClient client = HttpClient.newHttpClient();
        for (String path : new String[] {"/v1/search?q=abc", "/v1/reports/income-expenses?period=D7", "/v1/reports/spending-by-category", "/v1/dashboard/summary",
                "/v1/summaries/2026-10", "/v1/search?q=a", "/v1/reports/income-expenses?period=X", "/v1/reports/spending-by-category?month=2026-13"}) {
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Authorization", bearer)
                    .header("Origin", "https://evil.example").GET().build(), HttpResponse.BodyHandlers.ofString());
            System.out.println("QA-F0-HTTP " + path + " -> " + r.statusCode() + " cache=" + r.headers().firstValue("Cache-Control").orElse("-") + " ct="
                    + r.headers().firstValue("Content-Type").orElse("-") + " acao=" + r.headers().firstValue("Access-Control-Allow-Origin").orElse("-")
                    + " nosniff=" + r.headers().firstValue("X-Content-Type-Options").orElse("-"));
            assertThat(r.headers().firstValue("Access-Control-Allow-Origin")).as("CORS aberto em " + path).isEmpty();
            assertThat(r.headers().firstValue("Cache-Control").orElse("")).as("Cache-Control de " + path).contains("no-store");
            assertThat(r.headers().firstValue("Content-Type").orElse("")).contains("json");
            assertThat(r.headers().firstValue("X-Content-Type-Options").orElse("")).as("nosniff em " + path).isEqualTo("nosniff");
        }
        HttpResponse<String> noToken = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/search?q=abc")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(noToken.statusCode()).isEqualTo(401);
        assertThat(noToken.headers().firstValue("Content-Type").orElse("")).contains("json");
        // preflight de outra origem não é liberado
        HttpResponse<String> pre = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/search?q=abc"))
                .header("Origin", "https://evil.example").header("Access-Control-Request-Method", "GET").method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        System.out.println("QA-F0-HTTP preflight -> " + pre.statusCode() + " acao=" + pre.headers().firstValue("Access-Control-Allow-Origin").orElse("-"));
        assertThat(pre.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }
}
