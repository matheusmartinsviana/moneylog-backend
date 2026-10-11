package com.moneylog.util;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.test.web.servlet.ResultMatcher;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport.Level;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.parser.OpenAPIV3Parser;

public final class ContractValidation {

    public static final Path SPEC_FILE = locate();
    private static final OpenAPI SPEC = new OpenAPIV3Parser().read(SPEC_FILE.toUri().toString());

    private static final OpenApiInteractionValidator FULL = validator(false);
    private static final OpenApiInteractionValidator RESPONSE_ONLY = validator(true);

    private ContractValidation() {
    }

    public static ResultMatcher conforms() {
        ResultMatcher schema = openApi().isValid(FULL);
        return result -> {
            requireBearerWhenProtected(result.getRequest());
            schema.match(result);
        };
    }

    private static void requireBearerWhenProtected(jakarta.servlet.http.HttpServletRequest request) {
        OpenAPI spec = SPEC;
        String uri = request.getRequestURI();
        for (var entry : spec.getPaths().entrySet()) {
            if (!uri.matches(entry.getKey().replaceAll("\\{[^}/]+}", "[^/]+"))) {
                continue;
            }
            Operation operation = entry.getValue().readOperationsMap().entrySet().stream()
                    .filter(e -> e.getKey().name().equalsIgnoreCase(request.getMethod())).map(java.util.Map.Entry::getValue)
                    .findFirst().orElse(null);
            if (operation == null) {
                continue;
            }
            var security = operation.getSecurity() != null ? operation.getSecurity() : spec.getSecurity();
            boolean protectedOperation = security != null && !security.isEmpty();
            String header = request.getHeader("Authorization");
            if (protectedOperation && (header == null || !header.startsWith("Bearer "))) {
                throw new AssertionError("validation.request.security.missing: " + request.getMethod() + " " + uri
                        + " exige Authorization: Bearer (o contrato declara bearerAuth)");
            }
            return;
        }
    }

    public static ResultMatcher responseOnly() {
        return openApi().isValid(RESPONSE_ONLY);
    }

    public static OpenAPI spec() {
        return new OpenAPIV3Parser().read(SPEC_FILE.toUri().toString());
    }

    private static OpenApiInteractionValidator validator(boolean ignoreRequest) {
        LevelResolver.Builder levels = LevelResolver.create();
        if (ignoreRequest) {
            levels.withLevel("validation.request", Level.IGNORE);
        }
        return OpenApiInteractionValidator.createForSpecificationUrl(SPEC_FILE.toUri().toString())
                .withLevelResolver(levels.build())
                .build();
    }

    private static Path locate() {
        Path path = Path.of("").toAbsolutePath().resolve("contracts/openapi.yaml").normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("contracts/openapi.yaml não encontrado em " + path
                    + " (rode o Maven a partir da raiz do repositório moneylog-backend)");
        }
        return path;
    }
}
