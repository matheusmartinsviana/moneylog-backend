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

        assertThat(email.html()).doesNotContain("<script>").doesNotContain("<img src=x").doesNotContain("onerror=alert(1)>")
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

        assertThat(html).doesNotContain("<script").doesNotContain("<link").doesNotContain("@import")
                .doesNotContain("position:").doesNotContain("display:flex").doesNotContain("display:grid").doesNotContain("javascript:")
                .doesNotContain("cdn.").contains("<table").contains("lang=\"pt-br\"").contains("charset=\"utf-8\"");
        assertThat(email.text()).doesNotContain("<").doesNotContain("&amp;");
        assertThat(email.html().replaceAll("(?s)<style>.*?</style>", "")).doesNotContain("position:").doesNotContain("flex").doesNotContain("grid");
        assertThat(email.html()).containsOnlyOnce("<style>");
    }

    @Test
    @DisplayName("ghost table do Outlook, largura máxima de 600 px e botão com fundo em tabela")
    void deveTerEstruturaParaOutlook() {
        String html = renderer.renderInvitation(model("c@mviana.tech", "ana@example.com")).html();

        assertThat(html).contains("<!--[if mso]>").contains("<![endif]-->").contains("width=\"600\"").contains("max-width:600px")
                .contains("role=\"presentation\"").contains("bgcolor=\"#1f4d3a\"").contains("mso-padding-alt");
        assertThat(count(html, "<!--[if mso]>")).isEqualTo(count(html, "<![endif]-->"));
        assertThat(html.indexOf("<!--[if mso]>\n<table")).isLessThan(html.indexOf("max-width:600px"));
        assertThat(html.lastIndexOf("</td></tr></table>\n<![endif]-->")).isGreaterThan(html.indexOf("Criar minha conta"));
    }

    @Test
    @DisplayName("modo escuro: color-scheme, media query e cores do tema escuro do app")
    void deveTratarModoEscuro() {
        String html = renderer.renderInvitation(model("c@mviana.tech", "ana@example.com")).html();

        assertThat(html).contains("name=\"color-scheme\" content=\"light dark\"").contains("@media (prefers-color-scheme: dark)")
                .contains("[data-ogsc]").contains("#0b1d15").contains("#111b15").contains("#f2f3ea").contains("#a9cdb5").contains("#0a1a12");
    }

    @Test
    @DisplayName("preheader oculto traz quem convidou e a validade, e o texto alternativo da página existe")
    void devePossuirPreheaderOculto() {
        String html = renderer.renderInvitation(model("c@mviana.tech", "ana@example.com")).html();

        assertThat(html).containsPattern("<div style=\"display:none;[^\"]*mso-hide:all;[^\"]*\">c@mviana.tech convidou você para o Monelog\\. O convite vale por 7 dias\\.");
        assertThat(html).contains("<title>Você foi convidado para o Monelog</title>").contains("aria-label=\"Você foi convidado para o Monelog\"");
    }

    @Test
    @DisplayName("marca e assunto vêm de um único ponto e aparecem em HTML, texto e assunto")
    void deveCentralizarMarcaEAssunto() {
        RenderedEmail email = renderer.renderInvitation(model("c@mviana.tech", "ana@example.com"));

        assertThat(EmailTemplateRenderer.INVITATION_SUBJECT).isEqualTo("Você foi convidado para o " + EmailTemplateRenderer.BRAND_NAME);
        assertThat(email.subject()).isEqualTo(EmailTemplateRenderer.INVITATION_SUBJECT);
        assertThat(email.html()).doesNotContain("{{brand}}").contains(">" + EmailTemplateRenderer.BRAND_NAME + "</td>").contains("alt=\"" + EmailTemplateRenderer.BRAND_NAME + "\"");
        assertThat(email.text()).endsWith(EmailTemplateRenderer.BRAND_NAME + "\n");
    }

    @Test
    @DisplayName("texto plano tem o mesmo link, validade, aviso e destinatário que o HTML")
    void textoPlanoDeveSerEquivalente() {
        RenderedEmail email = renderer.renderInvitation(model("c@mviana.tech", "ana@example.com"));
        Matcher html = Pattern.compile("https://[^\"<\\s]+").matcher(email.html());
        Matcher text = Pattern.compile("https://\\S+").matcher(email.text());

        assertThat(text.find()).isTrue();
        assertThat(email.html()).contains(text.group());
        assertThat(html.find()).isTrue();
        assertThat(email.text().replaceAll("[\\r\\n]+", " ")).contains("O convite vale por 7 dias, até 25 de outubro de 2026 às 14:30 (horário de Brasília), e só pode ser usado uma vez.")
                .contains("Se você não esperava este convite, ignore esta mensagem. Nada será criado.").contains("ana@example.com");
    }

    @Test
    @DisplayName("logo: img do ícone do app com alt, dimensões e url derivada do appBaseUrl, ao lado do nome em texto")
    void deveTerLogoDoApp() {
        String html = renderer.renderInvitation(model("c@mviana.tech", "ana@example.com")).html();
        String logo = "https://moneylog.mviana.tech/assets/icons/icon-192.png";

        assertThat(html).contains("<img src=\"" + logo + "\" width=\"48\" height=\"48\" alt=\"Monelog\"").contains("display:block;border:0");
        assertThat(count(html, "<img")).isEqualTo(1);
        assertThat(html).containsPattern("</td>\\s*<td class=\"brand\"[^>]*>Monelog</td>");
        assertThat(html.indexOf("<img")).isLessThan(html.indexOf("class=\"brand\""));
    }

    @Test
    @DisplayName("url do logo segue o appBaseUrl configurado e é escapada")
    void deveDerivarLogoDoAppBaseUrl() {
        String html = new EmailTemplateRenderer("https://app.exemplo.com/\"x&y").renderInvitation(model("c@mviana.tech", "ana@example.com")).html();

        assertThat(html).contains("src=\"https://app.exemplo.com/&quot;x&amp;y/assets/icons/icon-192.png\"");
    }

    @Test
    @DisplayName("sem imagem o layout continua íntegro: nome em texto, botão, link e validade são texto; o texto plano não cita imagem")
    void deveManterLayoutSemImagem() {
        RenderedEmail email = renderer.renderInvitation(model("c@mviana.tech", "ana@example.com"));
        String semImagem = email.html().replaceAll("(?s)<img[^>]*>", "");

        assertThat(semImagem).contains(">Monelog</td>").contains("Criar minha conta").contains("href=\"" + LINK + "\"").contains("vale por 7 dias");
        assertThat(count(semImagem, "<table")).isEqualTo(count(semImagem, "</table>"));
        assertThat(email.text()).doesNotContain("<img").doesNotContain("icon-192").doesNotContain(".png");
    }

    @Test
    @DisplayName("botão com área de toque de pelo menos 48 px")
    void deveTerBotaoGeneroso() {
        String html = renderer.renderInvitation(model("c@mviana.tech", "ana@example.com")).html();

        assertThat(html).contains("padding:16px 40px").contains("line-height:20px").contains("mso-padding-alt:16px 40px");
    }

    private static int count(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
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
