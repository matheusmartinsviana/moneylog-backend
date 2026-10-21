package com.moneylog.infra.quickentry.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.category.exception.CategoryExceptions.DefaultCategoryUnavailableException;
import com.moneylog.core.quickentry.exception.QuickEntryErrorCodes;
import com.moneylog.core.transaction.exception.TransactionExceptions.IdempotencyKeyReusedException;
import com.moneylog.infra.exception.GlobalExceptionHandler;
import com.moneylog.infra.exception.RequestValidationException;
import com.moneylog.infra.exception.RequestValidationException.FieldIssue;
import com.moneylog.infra.web.BodyTooLargeException;
import com.moneylog.infra.web.JsonBodyLimitFilter;

@RestControllerAdvice(assignableTypes = QuickEntryController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class QuickEntryExceptionAdvice {

    static final String VALIDATION_DETAIL = "Dados inválidos. Confira o Atalho e tente de novo.";
    static final String KEY_REUSED_DETAIL = "Este envio repetiu uma chave com outro valor. Rode o Atalho de novo.";
    static final String DEFAULT_CATEGORY_DETAIL = "A categoria padrão não está disponível. Informe uma categoria no Atalho.";

    private static final Pattern SAFE_FIELD = Pattern.compile("[A-Za-z0-9_.\\[\\]-]{1,60}");

    @ExceptionHandler(RequestValidationException.class)
    ResponseEntity<Object> handleRequestValidation(RequestValidationException ex, HttpServletRequest request) {
        return validationFailed(ex.issues().stream().map(issue -> new FieldIssue(safeField(issue.field()), issue.code(), issue.message()))
                .toList(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Object> handleInvalidArgument(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<FieldIssue> issues = new ArrayList<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            String code = constraintCode(error.getCode());
            issues.add(new FieldIssue(safeField(error.getField()), code, "NOT_NULL".equals(code) ? "Campo obrigatório." : "Valor inválido."));
        }
        return validationFailed(issues, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Object> handleNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof BodyTooLargeException) {
                ProblemDetail problem = GlobalExceptionHandler.problem(HttpStatus.CONTENT_TOO_LARGE, JsonBodyLimitFilter.CODE,
                        "O corpo da requisição passa do limite de tamanho.", request);
                return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(problem);
            }
        }
        FieldIssue issue;
        if (ex.getCause() instanceof StreamReadException) {
            issue = new FieldIssue("body", "MALFORMED_JSON", "O corpo não é um JSON válido.");
        } else if (ex.getCause() instanceof UnrecognizedPropertyException unknown) {
            issue = new FieldIssue(safeField(unknown.getPropertyName()), "UNKNOWN_FIELD", "Campo desconhecido.");
        } else if (ex.getCause() instanceof JacksonException jackson) {
            issue = new FieldIssue(jacksonPath(jackson), "INVALID_FORMAT", "Valor inválido ou de tipo incorreto.");
        } else {
            issue = new FieldIssue("body", "REQUIRED", "O corpo da requisição é obrigatório.");
        }
        return validationFailed(List.of(issue), request);
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ResponseEntity<Object> handleKeyReused(IdempotencyKeyReusedException ex, HttpServletRequest request) {
        ProblemDetail problem = GlobalExceptionHandler.problem(HttpStatus.CONFLICT, ex.code(), KEY_REUSED_DETAIL, request);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(DefaultCategoryUnavailableException.class)
    ResponseEntity<Object> handleDefaultCategory(DefaultCategoryUnavailableException ex, HttpServletRequest request) {
        ProblemDetail problem = GlobalExceptionHandler.problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.code(), DEFAULT_CATEGORY_DETAIL, request);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }

    @ExceptionHandler(AccountNotFoundException.class)
    ResponseEntity<Object> handleAccountMissing(AccountNotFoundException ex, HttpServletRequest request) {
        ProblemDetail problem = GlobalExceptionHandler.problem(HttpStatus.UNPROCESSABLE_ENTITY, QuickEntryErrorCodes.QUICK_ACCOUNT_NOT_ALLOWED,
                "A conta deste token não está mais ativa. Gere um novo token em Ajustes, Atalhos.", request);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }

    private static ResponseEntity<Object> validationFailed(List<FieldIssue> issues, HttpServletRequest request) {
        ProblemDetail problem = GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST, GlobalExceptionHandler.VALIDATION_FAILED,
                VALIDATION_DETAIL, request);
        problem.setProperty("errors", issues);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    private static String safeField(String field) {
        return field != null && SAFE_FIELD.matcher(field).matches() ? field : "body";
    }

    private static String constraintCode(String constraint) {
        if (constraint == null || constraint.isBlank()) {
            return "INVALID";
        }
        return constraint.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    private static String jacksonPath(JacksonException ex) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference reference : ex.getPath()) {
            if (reference.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getPropertyName());
            }
        }
        return path.isEmpty() ? "body" : safeField(path.toString());
    }
}
