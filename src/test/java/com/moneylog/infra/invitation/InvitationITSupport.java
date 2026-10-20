package com.moneylog.infra.invitation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.auth.app.gateways.PasswordVerifier;
import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.mail.app.EmailMessage;
import com.moneylog.core.mail.app.gateways.EmailSender;
import com.moneylog.infra.mail.FakeEmailSender;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

abstract class InvitationITSupport {

    static final String OWNER_EMAIL = "owner@example.com";
    static final String MEMBER_PASSWORD = "senha-do-membro-456";
    static final String NEW_PASSWORD = "senha-do-convidado-789";
    static final String EMAIL_PREFIX = "t5-";
    private static final AtomicInteger IPS = new AtomicInteger(1);
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final Pattern LINK = Pattern.compile("/convite#([A-Za-z0-9_-]+)");

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected UserGateway users;
    @Autowired
    protected PasswordVerifier passwordVerifier;
    @Autowired
    protected ItAuth auth;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected PlatformTransactionManager transactionManager;
    @Autowired
    private EmailSender emailSender;

    @BeforeEach
    void resetState() {
        cleanUp();
        clock.set(MutableClockConfig.INITIAL);
        fake().clear();
    }

    @AfterEach
    void tearDownState() {
        cleanUp();
        clock.set(MutableClockConfig.INITIAL);
        fake().clear();
    }

    private void cleanUp() {
        write("delete from refresh_tokens where user_id in (select id from users where email like '" + EMAIL_PREFIX + "%')");
        write("delete from categories where user_id in (select id from users where email like '" + EMAIL_PREFIX + "%')");
        write("delete from user_invitations");
        write("delete from users where email like '" + EMAIL_PREFIX + "%'");
    }

    protected void write(String sql, Object... args) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> jdbc.update(sql, args));
    }

    protected FakeEmailSender fake() {
        return (FakeEmailSender) emailSender;
    }

    protected static String newEmail() {
        return EMAIL_PREFIX + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    protected static String newIp() {
        int n = IPS.incrementAndGet();
        return "198.51." + (n / 250 % 250) + "." + (n % 250 + 1);
    }

    protected User existingUser(String email, String status) {
        UUID id = UUID.randomUUID();
        users.insert(User.member(id, email));
        write("update users set password_hash = ?, password_set_at = now(), status = ?, disabled_at = case when ? = 'DISABLED' then now() end where id = ?",
                passwordVerifier.encode(MEMBER_PASSWORD), status, status, id);
        return users.findById(id).orElseThrow();
    }

    protected MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    protected MockHttpServletRequestBuilder ownerPost(String path, String body) {
        return json(post(path).header("Authorization", auth.ownerBearer()), body);
    }

    protected MvcResult invite(String email) throws Exception {
        return mockMvc.perform(ownerPost("/v1/invitations", "{\"email\":\"" + email + "\"}")).andReturn();
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected UUID inviteOk(String email) throws Exception {
        MvcResult result = invite(email);
        if (result.getResponse().getStatus() != 201) {
            throw new AssertionError("convite não criado: " + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString());
        }
        return UUID.fromString(body(result).get("id").asText());
    }

    protected String lastToken() {
        EmailMessage message = fake().last();
        if (message == null) {
            throw new AssertionError("nenhum e-mail enviado");
        }
        Matcher matcher = LINK.matcher(message.text());
        if (!matcher.find()) {
            throw new AssertionError("o e-mail não traz o link /convite#<token>");
        }
        return matcher.group(1);
    }

    protected MockHttpServletRequestBuilder preview(String token, String ip) {
        return json(post("/v1/invitations/preview").with(from(ip)), "{\"token\":\"" + token + "\"}");
    }

    protected MockHttpServletRequestBuilder accept(String token, String password, String ip) {
        return json(post("/v1/invitations/accept").with(from(ip)), "{\"token\":\"" + token + "\",\"password\":\"" + password + "\"}");
    }

    protected MockHttpServletRequestBuilder login(String email, String password) {
        return json(post("/v1/auth/login").with(from(newIp())), "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    protected static org.springframework.test.web.servlet.request.RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    protected MockHttpServletRequestBuilder ownerGet(String path) {
        return get(path).header("Authorization", auth.ownerBearer());
    }

    protected MockHttpServletRequestBuilder ownerDelete(String path) {
        return delete(path).header("Authorization", auth.ownerBearer());
    }

    protected String dbStatus(UUID invitationId) {
        return jdbc.queryForObject("select status from user_invitations where id = ?", String.class, invitationId);
    }

    protected List<String> storedStatuses(String email) {
        return new ArrayList<>(jdbc.queryForList("select status from user_invitations where lower(email) = lower(?) order by created_at, id", String.class, email));
    }

    protected void advance(Duration duration) {
        clock.advance(duration);
    }

    protected <T> List<T> runTogether(List<java.util.concurrent.Callable<T>> tasks) throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(tasks.size());
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<T>> futures = new ArrayList<>();
            for (java.util.concurrent.Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (java.util.concurrent.Future<T> future : futures) {
                results.add(future.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    protected int statusOf(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }
}
