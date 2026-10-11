package com.moneylog.infra.security;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import tools.jackson.databind.ObjectMapper;

import com.moneylog.infra.web.TraceIdFilter;

public class ProblemRequestRejectedHandler implements RequestRejectedHandler {

    private final ObjectMapper mapper;

    public ProblemRequestRejectedHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, RequestRejectedException rejected) throws IOException {
        boolean summary = request.getRequestURI() != null && request.getRequestURI().startsWith("/v1/summaries/");
        boolean search = request.getRequestURI() != null && request.getRequestURI().equals("/v1/search");
        String code = summary ? "SUMMARY_MONTH_INVALID" : search ? "SEARCH_QUERY_INVALID" : "VALIDATION_FAILED";
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("field", summary ? "month" : search ? "q" : "path");
        issue.put("code", summary ? "SUMMARY_MONTH_INVALID" : search ? "SEARCH_QUERY_INVALID" : "INVALID_FORMAT");
        issue.put("message", summary ? "O mês deve ser AAAA-MM." : search ? "Termo de busca inválido."
                : "O caminho da requisição contém caracteres inválidos.");
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "urn:moneylog:problem:" + code);
        problem.put("title", HttpStatus.BAD_REQUEST.getReasonPhrase());
        problem.put("status", HttpStatus.BAD_REQUEST.value());
        problem.put("detail", "Dados inválidos.");
        problem.put("code", code);
        problem.put("traceId", TraceIdFilter.of(request));
        problem.put("errors", List.of(issue));
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getOutputStream().write(mapper.writeValueAsBytes(problem));
    }
}
