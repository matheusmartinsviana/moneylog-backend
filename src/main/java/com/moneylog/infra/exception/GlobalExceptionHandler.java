package com.moneylog.infra.exception;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

import com.moneylog.core.ai.exception.AiExceptions.AiProviderUnavailableException;
import com.moneylog.core.ai.exception.AiExceptions.AiRateLimitedException;
import com.moneylog.core.invitation.exception.HasInvitationId;
import com.moneylog.core.mail.app.EmailDeliveryException;
import com.moneylog.core.quickentry.exception.QuickEntryErrorCodes;
import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.ConflictException;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.exception.NotFoundException;
import com.moneylog.core.sk.exception.UnauthorizedException;
import com.moneylog.infra.security.OriginNotAllowedException;
import com.moneylog.infra.security.QuickTokenInvalidException;
import com.moneylog.infra.security.TooManyAttemptsException;
import com.moneylog.infra.exception.RequestValidationException.FieldIssue;
import com.moneylog.infra.web.TraceIdFilter;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String CONFLICT = "CONFLICT";

    private static final java.util.Set<String> LOCK_SQL_STATES = java.util.Set.of("40P01", "55P03", "40001");

    private static final URI ABOUT_BLANK = URI.create("about:blank");
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Object> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, ex.code(), ex.getMessage(), request);
    }

    @ExceptionHandler(EmailDeliveryException.class)
    ResponseEntity<Object> handleEmailDelivery(EmailDeliveryException ex, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_GATEWAY, ex.code(), ex.getMessage(), request);
        addInvitationId(problem, ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(problem);
    }

    @ExceptionHandler(com.moneylog.core.importing.exception.ImportingExceptions.ImportApplyFailedException.class)
    ResponseEntity<Object> handleImportApplyFailed(
            com.moneylog.core.importing.exception.ImportingExceptions.ImportApplyFailedException ex, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.code(), ex.getMessage(), request);
        problem.setProperty("position", ex.position());
        problem.setProperty("cause", ex.causeCode());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }

    @ExceptionHandler(BusinessRuleException.class)
    ResponseEntity<Object> handleBusinessRule(BusinessRuleException ex, HttpServletRequest request) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, ex.code(), ex.getMessage(), request);
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Object> handleConflict(ConflictException ex, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, ex.code(), ex.getMessage(), request);
        addInvitationId(problem, ex);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    private static void addInvitationId(ProblemDetail problem, Exception ex) {
        if (ex instanceof HasInvitationId withId && withId.invitationId() != null) {
            problem.setProperty("invitationId", withId.invitationId());
        }
    }

    @ExceptionHandler(com.moneylog.core.card.exception.CardExceptions.InvoiceResolutionCollidedException.class)
    ResponseEntity<Object> handleInvoiceResolutionCollided(
            com.moneylog.core.card.exception.CardExceptions.InvoiceResolutionCollidedException ex, HttpServletRequest request) {
        return lockFailure(ex, request);
    }

    @ExceptionHandler(AiRateLimitedException.class)
    ResponseEntity<Object> handleAiRateLimited(AiRateLimitedException ex, HttpServletRequest request) {
        return respond(HttpStatus.TOO_MANY_REQUESTS, ex.code(), ex.getMessage(), request);
    }

    @ExceptionHandler(AiProviderUnavailableException.class)
    ResponseEntity<Object> handleAiUnavailable(AiProviderUnavailableException ex, HttpServletRequest request) {
        return respond(HttpStatus.SERVICE_UNAVAILABLE, ex.code(), ex.getMessage(), request);
    }

    @ExceptionHandler(UnauthorizedException.class)
    ResponseEntity<Object> handleUnauthorized(UnauthorizedException ex, HttpServletRequest request) {
        return respond(HttpStatus.UNAUTHORIZED, ex.code(), ex.getMessage(), request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<Object> handleOptimisticLock(ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "VERSION_CONFLICT", "O registro foi alterado por outra requisição.", request);
    }

    ResponseEntity<Object> lockFailure(Exception ex, HttpServletRequest request) {
        log.warn("Falha de trava/deadlock (traceId={}): {}", TraceIdFilter.of(request), ex.toString());
        ProblemDetail problem = problem(HttpStatus.CONFLICT, CONFLICT,
                "A operação colidiu com outra em andamento. Tente de novo.", request);
        return ResponseEntity.status(HttpStatus.CONFLICT).header(HttpHeaders.RETRY_AFTER, "1").body(problem);
    }

    static boolean isLockFailure(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof PessimisticLockingFailureException || t instanceof PessimisticLockException
                    || t instanceof LockTimeoutException || t instanceof org.hibernate.exception.LockAcquisitionException
                    || t instanceof org.hibernate.PessimisticLockException) {
                return true;
            }
            if (t instanceof java.sql.SQLException sql && sql.getSQLState() != null
                    && LOCK_SQL_STATES.contains(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    @ExceptionHandler(QuickTokenInvalidException.class)
    ResponseEntity<Object> handleQuickTokenInvalid(QuickTokenInvalidException ex, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.UNAUTHORIZED, QuickEntryErrorCodes.QUICK_TOKEN_INVALID,
                "Token inválido ou vencido. Gere um novo em Ajustes, Atalhos.", request);
        problem.setTitle("Token inválido");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer").body(problem);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return respond(HttpStatus.UNAUTHORIZED, UNAUTHENTICATED, "Autenticação necessária.", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, FORBIDDEN, "Acesso negado.", request);
    }

    @ExceptionHandler(OriginNotAllowedException.class)
    ResponseEntity<Object> handleOrigin(OriginNotAllowedException ex, HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, "ORIGIN_NOT_ALLOWED", "Origem não permitida.", request);
    }

    @ExceptionHandler(TooManyAttemptsException.class)
    ResponseEntity<Object> handleTooManyAttempts(TooManyAttemptsException ex, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                "Muitas tentativas. Tente de novo mais tarde.", request);
        long seconds = Math.max(1, (ex.retryAfter().toMillis() + 999) / 1000);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
                .body(problem);
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<Object> handleInvariant(DomainException ex, HttpServletRequest request) {
        return internalError(ex, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
        return isLockFailure(ex) ? lockFailure(ex, request) : internalError(ex, request);
    }

    private ResponseEntity<Object> internalError(Exception ex, HttpServletRequest request) {
        log.error("Erro inesperado (traceId={})", TraceIdFilter.of(request), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR,
                "Erro inesperado. Informe o traceId ao suporte.", request);
    }

    @ExceptionHandler(RequestValidationException.class)
    ResponseEntity<Object> handleRequestValidation(RequestValidationException ex, HttpServletRequest request) {
        return validationFailed(ex.issues(), request);
    }

    @ExceptionHandler(com.moneylog.core.thirdparty.exception.ThirdPartyExceptions.IdempotencyKeyRequiredException.class)
    ResponseEntity<Object> handleIdempotencyKeyRequired(
            com.moneylog.core.thirdparty.exception.ThirdPartyExceptions.IdempotencyKeyRequiredException ex, HttpServletRequest request) {
        return validationFailed(List.of(new FieldIssue("Idempotency-Key", "REQUIRED", ex.getMessage())), request);
    }

    @ExceptionHandler(InvalidPathValueException.class)
    ResponseEntity<Object> handleInvalidPathValue(InvalidPathValueException ex, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ex.code(), "Dados inválidos.", request);
        problem.setProperty("errors", List.of(new FieldIssue(ex.field(), ex.code(), ex.getMessage())));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<FieldIssue> issues = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String path = violation.getPropertyPath().toString();
            issues.add(new FieldIssue(path.substring(path.lastIndexOf('.') + 1),
                    constraintCode(violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName()),
                    violation.getMessage()));
        }
        return validationFailed(issues, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        List<FieldIssue> issues = new ArrayList<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            issues.add(new FieldIssue(error.getField(), constraintCode(error.getCode()), error.getDefaultMessage()));
        }
        for (ObjectError error : ex.getBindingResult().getGlobalErrors()) {
            issues.add(new FieldIssue(error.getObjectName(), constraintCode(error.getCode()), error.getDefaultMessage()));
        }
        return validationFailed(issues, servlet(request));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                           HttpHeaders headers, HttpStatusCode status,
                                                                           WebRequest request) {
        List<FieldIssue> issues = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            MethodParameter parameter = result.getMethodParameter();
            String name = parameter.getParameterName() == null ? "parameter" + parameter.getParameterIndex()
                    : parameter.getParameterName();
            result.getResolvableErrors().forEach(error -> issues.add(new FieldIssue(name,
                    constraintCode(error.getCodes() == null || error.getCodes().length == 0 ? null
                            : lastSegment(error.getCodes()[error.getCodes().length - 1])),
                    error.getDefaultMessage())));
        });
        return validationFailed(issues, servlet(request));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof com.moneylog.infra.web.BodyTooLargeException) {
                return respond(HttpStatus.CONTENT_TOO_LARGE, com.moneylog.infra.web.JsonBodyLimitFilter.CODE,
                        "O corpo da requisição passa do limite de tamanho.", servlet(request));
            }
        }
        FieldIssue issue;
        if (ex.getCause() instanceof StreamReadException) {
            issue = new FieldIssue("body", "MALFORMED_JSON", "O corpo não é um JSON válido.");
        } else if (ex.getCause() instanceof UnrecognizedPropertyException unknown) {
            issue = new FieldIssue(jacksonPath(unknown), "UNKNOWN_FIELD",
                    "Campo desconhecido: '" + unknown.getPropertyName() + "'.");
        } else if (ex.getCause() instanceof JacksonException jackson) {
            issue = new FieldIssue(jacksonPath(jackson), "INVALID_FORMAT", "Valor inválido ou de tipo incorreto.");
        } else {
            issue = new FieldIssue("body", "REQUIRED", "O corpo da requisição é obrigatório e precisa ser um JSON válido.");
        }
        return validationFailed(List.of(issue), servlet(request));
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            org.springframework.web.bind.MissingServletRequestParameterException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return missingValue(ex, ex.getParameterName(), request);
    }

    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(
            org.springframework.web.bind.ServletRequestBindingException ex, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        if (ex instanceof MissingRequestValueException missing) {
            return missingValue(ex, missingName(missing), request);
        }
        return validationFailed(List.of(new FieldIssue("request", "INVALID_FORMAT", "Requisição inválida.")),
                servlet(request));
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        String field = ex instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException mismatch
                ? mismatch.getName() : String.valueOf(ex.getPropertyName());
        return validationFailed(List.of(new FieldIssue(field, "INVALID_FORMAT", "Valor inválido para '" + field + "'.")),
                servlet(request));
    }

    private ResponseEntity<Object> missingValue(Exception ex, String name, WebRequest request) {
        return validationFailed(List.of(new FieldIssue(name, "REQUIRED", "'" + name + "' é obrigatório.")), servlet(request));
    }

    private static String missingName(MissingRequestValueException ex) {
        if (ex instanceof org.springframework.web.bind.MissingRequestHeaderException header) {
            return header.getHeaderName();
        }
        if (ex instanceof org.springframework.web.bind.MissingServletRequestParameterException parameter) {
            return parameter.getParameterName();
        }
        return "request";
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(org.springframework.web.multipart.MaxUploadSizeExceededException ex,
                                                                          HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        HttpServletRequest servlet = servlet(request);
        if (servlet.getRequestURI().matches("/v1/goals/[^/]+/photos/?")) {
            return respond(HttpStatus.UNPROCESSABLE_ENTITY, "GOAL_PHOTO_TOO_LARGE", "A foto passa do limite de tamanho.", servlet);
        }
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, "VAULT_IMPORT_TOO_LARGE", "O arquivo enviado passa do limite de 20 MB.", servlet);
    }

    @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
    ResponseEntity<Object> handleMultipart(org.springframework.web.multipart.MultipartException ex, HttpServletRequest request) {
        log.debug("Multipart inválido (traceId={}): {}", TraceIdFilter.of(request), ex.toString());
        return validationFailed(List.of(new FieldIssue("file", "INVALID_FORMAT", "O envio multipart está malformado.")), request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            enrich(problem, defaultCode(statusCode), servlet(request));
        }
        return response;
    }

    private ResponseEntity<Object> validationFailed(List<FieldIssue> issues, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, "Dados inválidos.", request);
        problem.setProperty("errors", issues);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    private ResponseEntity<Object> respond(HttpStatus status, String code, String detail, HttpServletRequest request) {
        return ResponseEntity.status(status).body(problem(status, code, detail, request));
    }

    public static ProblemDetail problem(HttpStatus status, String code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        enrich(problem, code, request);
        return problem;
    }

    private static void enrich(ProblemDetail problem, String code, HttpServletRequest request) {
        String resolvedCode = problem.getProperties() != null && problem.getProperties().containsKey("code")
                ? String.valueOf(problem.getProperties().get("code")) : code;
        if (problem.getType() == null || ABOUT_BLANK.toString().equals(problem.getType().toString())) {
            problem.setType(URI.create("urn:moneylog:problem:" + resolvedCode));
        }
        if (problem.getTitle() == null) {
            HttpStatus resolved = HttpStatus.resolve(problem.getStatus());
            problem.setTitle(resolved == null ? "Erro" : resolved.getReasonPhrase());
        }
        problem.setProperty("code", resolvedCode);
        problem.setProperty("traceId", request == null ? null : TraceIdFilter.of(request));
    }

    private static String defaultCode(HttpStatusCode status) {
        if (status.value() == 400) {
            return VALIDATION_FAILED;
        }
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return resolved == null ? "HTTP_" + status.value()
                : resolved.name().toUpperCase(Locale.ROOT);
    }

    private static HttpServletRequest servlet(WebRequest request) {
        return ((ServletWebRequest) request).getRequest();
    }

    static String constraintCode(String constraint) {
        if (constraint == null || constraint.isBlank()) {
            return "INVALID";
        }
        return constraint.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    private static String lastSegment(String code) {
        return code.substring(code.lastIndexOf('.') + 1);
    }

    private static String jacksonPath(JacksonException ex) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference reference : ex.getPath()) {
            if (reference.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.isEmpty() ? "body" : path.toString();
    }
}
