package com.moneylog.infra.web;

import java.util.Map;
import java.util.UUID;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.ConflictException;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.exception.NotFoundException;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.security.CurrentUser;

@TestConfiguration(proxyBeanMethods = false)
public class WebFoundationSamples {

    public record SampleInput(@NotBlank String name,
                              @Pattern(regexp = "^\\d{1,13}(\\.\\d{1,2})?$") String amount,
                              Money price) {
    }

    @Controller
    @TestComponent
    @RequestMapping("/test")
    @ResponseBody
    public static class SampleController {

        private final TimeProvider timeProvider;

        public SampleController(TimeProvider timeProvider) {
            this.timeProvider = timeProvider;
        }

        @GetMapping("/me")
        public Map<String, Object> me(@CurrentUser UUID userId) {
            return Map.of("userId", userId.toString());
        }

        @GetMapping("/not-found")
        public String notFound() {
            throw new SampleNotFound();
        }

        @GetMapping("/rule")
        public String rule() {
            throw new SampleRule();
        }

        @GetMapping("/conflict")
        public String conflict() {
            throw new SampleConflict();
        }

        @GetMapping("/invariant")
        public String invariant() {
            throw new SampleInvariant();
        }

        @GetMapping("/boom")
        public String boom() {
            throw new IllegalStateException("segredo interno: jdbc:postgresql://prod/senha");
        }

        @PostMapping("/input")
        public Map<String, Object> input(@Valid @RequestBody SampleInput input) {
            return Map.of("name", input.name());
        }

        @GetMapping("/money")
        public Money money() {
            return Money.of("1234.5");
        }

        @PostMapping("/money")
        public Money echoMoney(@RequestBody SampleInput input) {
            return input.price();
        }

        @GetMapping("/page")
        public PageQuery page(PageQuery query) {
            return query;
        }

        @GetMapping("/competence")
        public String competence(@RequestParam Competence competence) {
            return competence.toString();
        }

        @GetMapping("/header")
        public String header(@RequestHeader("Idempotency-Key") String key) {
            return key;
        }

        @GetMapping("/today")
        public Map<String, String> today() {
            return Map.of("today", timeProvider.today().toString(), "zone", timeProvider.zone().getId(),
                    "now", timeProvider.now().toString());
        }
    }

    static class SampleNotFound extends NotFoundException {
        SampleNotFound() {
            super("SAMPLE_NOT_FOUND", "Amostra inexistente.");
        }
    }

    static class SampleRule extends BusinessRuleException {
        SampleRule() {
            super("SAMPLE_RULE", "Regra de amostra violada.");
        }
    }

    static class SampleConflict extends ConflictException {
        SampleConflict() {
            super("SAMPLE_CONFLICT", "Conflito de amostra.");
        }
    }

    static class SampleInvariant extends DomainException {
        SampleInvariant() {
            super("SAMPLE_INVARIANT", "Invariante interna quebrada com detalhe interno.");
        }
    }
}
