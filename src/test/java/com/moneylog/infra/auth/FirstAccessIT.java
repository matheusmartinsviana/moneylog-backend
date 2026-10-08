package com.moneylog.infra.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import jakarta.servlet.http.Cookie;

import com.moneylog.core.auth.app.usecase.EnsureOwnerUseCase;
import com.moneylog.core.auth.app.usecase.EnsureOwnerUseCase.EnsureOwnerCommand;
import com.moneylog.core.auth.app.usecase.PrepareFirstAccessUseCase;
import com.moneylog.core.auth.app.usecase.PrepareFirstAccessUseCase.PrepareFirstAccessCommand;
import com.moneylog.infra.web.WebFoundationSamples;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

/**
 * Primeiro acesso do dono (ADR-015) sem hash no ambiente: a senha só vive no banco. Dados e senhas são sintéticos.
 */
@IntegrationTest
@Import({MutableClockConfig.class, WebFoundationSamples.class})
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {"moneylog.owner.password-hash=", "moneylog.owner.setup-code=it-setup-code-0123456789"})
@DisplayName("Primeiro acesso do dono (ADR-015)")
class FirstAccessIT {

    private static final String ORIGIN = "https://app.moneylog.test";
    private static final String EMAIL = "owner@moneylog.com";
    private static final String CODE = "it-setup-code-0123456789";
    private static final String NEW_PASSWORD = "uma-senha-sintetica-forte";
    private static final AtomicInteger IPS = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired
    private MutableClock clock;
    @Autowired
    private ItAuth auth;
    @Autowired
    private EnsureOwnerUseCase ensureOwner;
    @Autowired
    private PrepareFirstAccessUseCase prepareFirstAccess;

    private UUID ownerId;

    @BeforeEach
    void pendente() {
        clock.set(MutableClockConfig.INITIAL);
        ownerId = auth.ownerId();
        ensureOwner.handle(new EnsureOwnerCommand(EMAIL, true));
        prepareFirstAccess.handle(new PrepareFirstAccessCommand(CODE, Duration.ofHours(24)));
    }

    @AfterEach
    void restaurar() {
        clock.set(MutableClockConfig.INITIAL);
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(s ->
                jdbc.update("update users set password_hash = null, password_set_at = null, password_setup_pending = false,"
                        + " setup_code_hash = null, setup_code_expires_at = null"));
    }

    private static String novoIp() {
        return "198.51.100." + (IPS.incrementAndGet() % 250 + 1);
    }

    private MockHttpServletRequestBuilder setup(String email, String code, String password, String ip) {
        return post("/v1/auth/setup-password").contentType(MediaType.APPLICATION_JSON).with(request -> {
            request.setRemoteAddr(ip);
            return request;
        }).content("{\"email\":\"" + email + "\",\"setupCode\":\"" + code + "\",\"newPassword\":\"" + password + "\"}");
    }

    private MockHttpServletRequestBuilder login(String password) {
        return post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON).with(request -> {
            request.setRemoteAddr(novoIp());
            return request;
        }).content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + password + "\"}");
    }

    private static String cookieValue(MvcResult result) {
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).startsWith("ml_refresh=");
        return setCookie.substring("ml_refresh=".length(), setCookie.indexOf(';'));
    }

    private MvcResult definirSenha() throws Exception {
        return mockMvc.perform(setup(EMAIL, CODE, NEW_PASSWORD, novoIp())).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andReturn();
    }

    @Test
    @DisplayName("fluxo feliz: status true, define a senha, devolve a sessão, status false, login novo vale e o id é o mesmo")
    void fluxoFeliz() throws Exception {
        mockMvc.perform(get("/v1/auth/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordSetupRequired").value(true)).andExpect(ContractValidation.conforms());
        mockMvc.perform(login("senha-forte-123")).andExpect(status().isUnauthorized());

        MvcResult result = definirSenha();

        assertThat(result.getResponse().getHeader("Set-Cookie")).contains("HttpOnly").contains("SameSite=Strict");
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select id from users", UUID.class)).isEqualTo(ownerId);
        assertThat(jdbc.queryForObject("select password_hash from users", String.class)).startsWith("$2").doesNotContain(NEW_PASSWORD);
        assertThat(jdbc.queryForObject("select setup_code_hash from users", String.class)).isNull();
        mockMvc.perform(get("/v1/auth/status")).andExpect(jsonPath("$.passwordSetupRequired").value(false));
        mockMvc.perform(login(NEW_PASSWORD)).andExpect(status().isOk()).andExpect(ContractValidation.conforms());
        mockMvc.perform(login("senha-forte-123")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("código reutilizado: depois de usado, o mesmo código recebe 401 INVALID_SETUP_CODE")
    void codigoDeUsoUnico() throws Exception {
        definirSenha();

        mockMvc.perform(setup(EMAIL, CODE, "outra-senha-sintetica-1", novoIp())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SETUP_CODE")).andExpect(ContractValidation.conforms());
        mockMvc.perform(login(NEW_PASSWORD)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("código errado e e-mail errado: 401 igual; nada é gravado")
    void codigoErrado() throws Exception {
        mockMvc.perform(setup(EMAIL, "codigo-errado-0123456789", NEW_PASSWORD, novoIp())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SETUP_CODE")).andExpect(ContractValidation.conforms());
        mockMvc.perform(setup("intruso@moneylog.com", CODE, NEW_PASSWORD, novoIp())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SETUP_CODE"));

        assertThat(jdbc.queryForObject("select password_hash from users", String.class)).isNull();
        mockMvc.perform(get("/v1/auth/status")).andExpect(jsonPath("$.passwordSetupRequired").value(true));
    }

    @Test
    @DisplayName("código expirado (24 h): 401")
    void codigoExpirado() throws Exception {
        clock.advance(Duration.ofHours(24).plusSeconds(1));

        mockMvc.perform(setup(EMAIL, CODE, NEW_PASSWORD, novoIp())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SETUP_CODE"));
    }

    @Test
    @DisplayName("senha fraca (curta ou igual ao e-mail): 422 WEAK_PASSWORD sem ecoar a senha; o código continua valendo")
    void senhaFraca() throws Exception {
        mockMvc.perform(setup(EMAIL, CODE, "curta-demai", novoIp())).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("WEAK_PASSWORD")).andExpect(ContractValidation.conforms())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("curta-demai"));
        mockMvc.perform(setup(EMAIL, CODE, EMAIL, novoIp())).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));

        definirSenha();
    }

    @Test
    @DisplayName("rate limit por IP: 5 códigos errados e a 6ª tentativa, mesmo certa, recebe 429 com Retry-After")
    void limiteDeTentativas() throws Exception {
        String ip = novoIp();
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(setup(EMAIL, "codigo-errado-" + i + "-0123456789", NEW_PASSWORD, ip)).andExpect(status().isUnauthorized());
        }

        mockMvc.perform(setup(EMAIL, CODE, NEW_PASSWORD, ip)).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS")).andExpect(header().string("Retry-After", "900"))
                .andExpect(ContractValidation.conforms());
        mockMvc.perform(setup(EMAIL, CODE, NEW_PASSWORD, novoIp())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("trocar a senha revoga todos os refresh tokens existentes")
    void revogaSessoes() throws Exception {
        String antigo = cookieValue(definirSenha());
        mockMvc.perform(post("/v1/auth/refresh").header("Origin", ORIGIN).cookie(new Cookie("ml_refresh", antigo)))
                .andExpect(status().isOk());
        MvcResult login = mockMvc.perform(login(NEW_PASSWORD)).andExpect(status().isOk()).andReturn();

        // redefinição pedida pelo dono (variável de uso único): derruba todas as sessões
        ensureOwner.handle(new EnsureOwnerCommand(EMAIL, true));
        prepareFirstAccess.handle(new PrepareFirstAccessCommand(CODE, Duration.ofHours(24)));

        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where revoked_at is null", Integer.class)).isZero();
        mockMvc.perform(post("/v1/auth/refresh").header("Origin", ORIGIN).cookie(new Cookie("ml_refresh", cookieValue(login))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_INVALID"));
        mockMvc.perform(login(NEW_PASSWORD)).andExpect(status().isUnauthorized());

        // o novo primeiro acesso também revoga o que existir
        MvcResult nova = definirSenha();
        assertThat(cookieValue(nova)).isNotEqualTo(antigo);
        mockMvc.perform(post("/v1/auth/refresh").header("Origin", ORIGIN).cookie(new Cookie("ml_refresh", cookieValue(nova))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("reset repetido no reinício é idempotente: o código não é regerado nem as sessões revogadas de novo")
    void resetIdempotente() throws Exception {
        String hashAntes = jdbc.queryForObject("select setup_code_hash from users", String.class);

        assertThat(ensureOwner.handle(new EnsureOwnerCommand(EMAIL, true)).passwordReset()).isFalse();

        assertThat(jdbc.queryForObject("select setup_code_hash from users", String.class)).isEqualTo(hashAntes);
    }

    @Test
    @DisplayName("isolamento: o setup não exige token, mas as rotas de dados continuam exigindo; o status não traz dado do dono")
    void isolamento(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/v1/accounts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/auth/status")).andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEqualTo("{\"passwordSetupRequired\":true}"));
        definirSenha();
        mockMvc.perform(get("/v1/accounts").header("Authorization", auth.ownerBearer())).andExpect(status().isOk());

        assertThat(output.getAll()).doesNotContain(CODE).doesNotContain(NEW_PASSWORD);
    }
}
