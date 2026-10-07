package com.moneylog.infra.web;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.ArrayList;
import java.util.List;

import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.infra.exception.RequestValidationException;
import com.moneylog.infra.exception.RequestValidationException.FieldIssue;

/**
 * Lê {@code page} (1-indexado, padrão 1) e {@code pageSize} (padrão 10, máximo 50) da query e entrega um
 * {@link PageQuery}. Fora do limite ou não numérico → {@code 400 VALIDATION_FAILED} (não {@code 422}: é erro de
 * entrada, não de regra de negócio).
 */
public class PageQueryResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return PageQuery.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        List<FieldIssue> issues = new ArrayList<>();
        int page = read(webRequest, "page", 1, 1, Integer.MAX_VALUE, issues);
        int pageSize = read(webRequest, "pageSize", PageQuery.DEFAULT_PAGE_SIZE, 1, PageQuery.MAX_PAGE_SIZE, issues);
        if (!issues.isEmpty()) {
            throw new RequestValidationException(issues);
        }
        return new PageQuery(page, pageSize);
    }

    private static int read(NativeWebRequest request, String name, int fallback, int min, int max, List<FieldIssue> issues) {
        String raw = request.getParameter(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.strip());
            if (value < min || value > max) {
                issues.add(new FieldIssue(name, "OUT_OF_RANGE", name + " deve estar entre " + min + " e " + max));
                return fallback;
            }
            return value;
        } catch (NumberFormatException e) {
            issues.add(new FieldIssue(name, "INVALID_FORMAT", name + " deve ser um inteiro"));
            return fallback;
        }
    }
}
