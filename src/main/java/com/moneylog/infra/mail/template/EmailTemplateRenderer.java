package com.moneylog.infra.mail.template;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.moneylog.core.mail.app.EmailTemplate;
import com.moneylog.core.mail.app.InvitationEmailModel;
import com.moneylog.core.mail.app.RenderedEmail;

public class EmailTemplateRenderer implements EmailTemplate {

    static final String INVITATION_SUBJECT = "Você foi convidado para o Monelog";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z]+)}}");
    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter EXPIRATION = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy 'às' HH:mm", Locale.of("pt", "BR"));

    private final String appBaseUrl;
    private final String invitationHtml;
    private final String invitationText;

    public EmailTemplateRenderer(String appBaseUrl) {
        this.appBaseUrl = appBaseUrl.endsWith("/") ? appBaseUrl.substring(0, appBaseUrl.length() - 1) : appBaseUrl;
        this.invitationHtml = load("mail/invitation.html");
        this.invitationText = load("mail/invitation.txt");
    }

    @Override
    public RenderedEmail renderInvitation(InvitationEmailModel model) {
        Map<String, String> values = Map.of(
                "inviterEmail", model.inviterEmail(),
                "inviteeEmail", model.inviteeEmail(),
                "link", appBaseUrl + "/convite#" + model.token(),
                "expiresAt", EXPIRATION.format(model.expiresAt().atZone(ZONE)),
                "validity", model.validityDays() == 1 ? "1 dia" : model.validityDays() + " dias");
        return new RenderedEmail(INVITATION_SUBJECT, fill(invitationHtml, values, EmailTemplateRenderer::escapeHtml), fill(invitationText, values, v -> v));
    }

    static String fill(String template, Map<String, String> values, UnaryOperator<String> escape) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 256);
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value == null) {
                throw new IllegalStateException("Placeholder sem valor no template de e-mail: " + matcher.group(1));
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(escape.apply(value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    static String escapeHtml(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String load(String path) {
        try (InputStream in = EmailTemplateRenderer.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Template de e-mail ausente: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
