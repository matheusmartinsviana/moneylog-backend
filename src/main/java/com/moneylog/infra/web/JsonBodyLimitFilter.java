package com.moneylog.infra.web;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import tools.jackson.databind.ObjectMapper;

public class JsonBodyLimitFilter extends OncePerRequestFilter {

    public static final String CODE = "PAYLOAD_TOO_LARGE";

    private final long maxBytes;
    private final ObjectMapper mapper;

    public JsonBodyLimitFilter(long maxBytes, ObjectMapper mapper) {
        this.maxBytes = maxBytes;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String type = request.getContentType();
        if (type == null) {
            return true;
        }
        String normalized = type.toLowerCase(Locale.ROOT);
        return !(normalized.startsWith("application/json") || normalized.contains("+json"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            reject(request, response);
            return;
        }
        chain.doFilter(new LimitedRequest(request, maxBytes), response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "urn:moneylog:problem:" + CODE);
        problem.put("title", HttpStatus.CONTENT_TOO_LARGE.getReasonPhrase());
        problem.put("status", HttpStatus.CONTENT_TOO_LARGE.value());
        problem.put("detail", "O corpo da requisição passa do limite de tamanho.");
        problem.put("code", CODE);
        problem.put("traceId", TraceIdFilter.of(request));
        response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getOutputStream().write(mapper.writeValueAsBytes(problem));
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long max;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long max) {
            super(request);
            this.max = max;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedStream(super.getInputStream(), max);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class LimitedStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long max;
        private long read;

        LimitedStream(ServletInputStream delegate, long max) {
            this.delegate = delegate;
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count > 0) {
                count(count);
            }
            return count;
        }

        private void count(int bytes) throws IOException {
            read += bytes;
            if (read > max) {
                throw new BodyTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
