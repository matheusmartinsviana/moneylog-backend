package com.moneylog.infra.quickentry.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;

import com.moneylog.infra.web.BodyTooLargeException;

@DisplayName("Advice do lançamento rápido: ramos de borda")
class QuickEntryExceptionAdviceTest {

    private final QuickEntryExceptionAdvice advice = new QuickEntryExceptionAdvice();
    private final HttpInputMessage input = new MockHttpInputMessage(new byte[0]);

    @Test
    @DisplayName("corpo que estoura o limite durante a leitura vira 413 PAYLOAD_TOO_LARGE em problem+json")
    void deveResponder413QuandoALeituraEstouraOLimite() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("grande", new RuntimeException(new BodyTooLargeException()), input);

        ResponseEntity<Object> response = advice.handleNotReadable(ex, new MockHttpServletRequest("POST", "/v1/quick-entries"));

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(((ProblemDetail) response.getBody()).getProperties()).containsEntry("code", "PAYLOAD_TOO_LARGE");
    }

    @Test
    @DisplayName("corpo ilegível sem causa conhecida vira 400 VALIDATION_FAILED com o detail do Atalho")
    void deveResponder400SemCausaConhecida() {
        ResponseEntity<Object> response = advice.handleNotReadable(new HttpMessageNotReadableException("x", input),
                new MockHttpServletRequest("POST", "/v1/quick-entries"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(((ProblemDetail) response.getBody()).getDetail()).isEqualTo(QuickEntryExceptionAdvice.VALIDATION_DETAIL);
    }
}
