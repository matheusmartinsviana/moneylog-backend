package com.moneylog.infra.mail.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.mail.app.InvitationEmailModel;
import com.moneylog.core.mail.app.RenderedEmail;

@DisplayName("Renderer do e-mail de convite")
class EmailTemplateRendererTest {

    private static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_ABCDE";
    private static final String LINK = "https://moneylog.mviana.tech/convite#" + TOKEN;
    private final EmailTemplateRenderer renderer = new EmailTemplateRenderer("https://moneylog.mviana.tech/");

    private InvitationEmailModel model(String inviter, String invitee) {
        return new InvitationEmailModel(inviter, invitee, TOKEN, Instant.parse("2026-10-25T17:30:00Z"), 7);
    }

    @Test
    @DisplayName("resolve todos os placeholders e traz link, validade, aviso e quem convidou no HTML e no texto")
    void deveResolverPlaceholders() {
        RenderedEmail email = renderer.renderInvitation(model("c@mviana.tech", "ana@example.com"));

        assertThat(email.subject()).isEqualTo("Você foi convidado para o Monelog");
        for (String body : new String[] {email.html(), email.text()}) {
            assertThat(body).doesNotContain("{{").doesNotContain("}}")
                    .contains(LINK).contains("c@mviana.tech").contains("ana@example.com")
                    .contains("7 dias").contains("25 de outubro de 2026 às 14:30").contains("só pode ser usado uma vez")
                    .contains("Se você não esperava este convite, ignore esta mensagem.");
        }
        assertThat(email.html()).contains("href=\"" + LINK + "\"");
    }

    @Test
    @DisplayName("validade de um dia fica no singular no HTML e no texto")
    void deveUsarSingularParaUmDia() {
        RenderedEmail email = renderer.renderInvitation(new InvitationEmailModel("c@mviana.tech", "ana@example.com", TOKEN, Instant.parse("2026-10-25T17:30:00Z"), 1));

        for (String body : new String[] {email.html(), email.text()}) {
            assertThat(body).contains("vale por 1 dia,").doesNotContain("1 dias");
        }
    }

    @Test
    @DisplayName("o token fica no fragmento: nunca em query nem no caminho")
    void deveManterTokenNoFragmento() {
        RenderedEmail email = renderer.renderInvitation(model("c@mviana.tech", "ana@example.com"));

        for (String body : new String[] {email.html(), email.text()}) {
            Matcher links = Pattern.compile("https://\\S*" + Pattern.quote(TOKEN)).matcher(body);
            int count = 0;
            while (links.find()) {
                count++;
                assertThat(links.group()).isEqualTo(LINK).doesNotContain("?");
            }
            assertThat(count).isPositive();
            assertThat(body.replace(LINK, "")).doesNotContain(TOKEN);
        }
    }

    @Test
    @DisplayName("valores dinâmicos são escapados no HTML, inclusive <script> no e-mail do convidado")
    void deveEscaparHtml() {
        RenderedEmail email = renderer.renderInvitation(model("\"><img src=x onerror=alert(1)>@x.com", "<script>alert(1)</script>@x.com"));

        assertThat(email.html()).doesNotContain("<script>").doesNotContain("<img").doesNotContain("onerror=alert(1)>")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;@x.com");
        assertThat(email.text()).contains("<script>alert(1)</script>@x.com");
    }

    @Test
    @DisplayName("valor com {{outro}} ou $ não é reinterpretado")
    void naoDeveReinterpretarValores() {
        RenderedEmail email = renderer.renderInvitation(model("{{link}}$1\\@x.com", "ana@example.com"));

        assertThat(email.html()).contains("{{link}}$1\\@x.com");
        assertThat(email.text()).contains("{{link}}$1\\@x.com");
    }

    @Test
    @DisplayName("placeholder sem valor falha em vez de sair em branco")
    void deveFalharComPlaceholderSemValor() {
        assertThatThrownBy(() -> EmailTemplateRenderer.fill("oi {{nome}}", Map.of(), v -> v))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("nome");
    }

    @Test
    @DisplayName("token fora de base64url é recusado antes de virar link")
    void deveRecusarTokenInvalido() {
        Instant expires = Instant.parse("2026-10-25T17:30:00Z");
        assertThatThrownBy(() -> new InvitationEmailModel("a@x.com", "b@x.com", "curto", expires, 7)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvitationEmailModel("a@x.com", "b@x.com", TOKEN + "?x=1&y", expires, 7)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvitationEmailModel("a@x.com", "b@x.com", TOKEN, expires, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("lint do HTML: sem script, CSS externo, position/flex/grid; link único em fragmento; estilo inline; texto alternativo em texto puro")
    void deveSerCompativelComClientesDeEmail() {
        RenderedEmail email = renderer.renderInvitation(model("c@mviana.tech", "ana@example.com"));
        String html = email.html().toLowerCase();

        assertThat(html).doesNotContain("<script").doesNotContain("<link").doesNotContain("<style").doesNotContain("@import")
                .doesNotContain("position:").doesNotContain("display:flex").doesNotContain("display:grid").doesNotContain("javascript:")
                .doesNotContain("<img").doesNotContain("cdn.").contains("<table").contains("lang=\"pt-br\"").contains("charset=\"utf-8\"");
        assertThat(email.text()).doesNotContain("<").doesNotContain("&amp;");
        assertThat(email.html().replaceAll("(?s)<head>.*?</head>", "")).doesNotContain("class=");
    }

    @Test
    @DisplayName("sem cifrão, cadeado ou escudo e com a marca Monelog (voz do DS 00)")
    void deveRespeitarVozDaMarca() {
        RenderedEmail email = renderer.renderInvitation(model("c@mviana.tech", "ana@example.com"));

        for (String body : new String[] {email.subject(), email.html(), email.text()}) {
            assertThat(body.toLowerCase()).doesNotContain("$").doesNotContain("r$").doesNotContain("cadeado").doesNotContain("escudo")
                    .doesNotContain("🔒").doesNotContain("🛡");
        }
        assertThat(email.text()).contains("Monelog").doesNotContain("!");
        assertThat(email.subject()).doesNotContain("!");
    }
}
