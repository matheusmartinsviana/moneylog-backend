package com.moneylog.infra.ai.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.moneylog.core.ai.app.LlmClient;

public class FakeLlmClient implements LlmClient {

    public static final String DEFAULT_REPLY = "{\"text\":\"Resumo do mês gerado pelo modo de teste, sem números.\",\"factRefs\":[],\"caveat\":null}";

    public static final String PARTIAL_REPLY = "{\"text\":\"Resumo do mês gerado pelo modo de teste, sem números.\",\"factRefs\":[],"
            + "\"caveat\":\"O mês ainda não terminou.\"}";

    private final List<LlmRequest> received = new ArrayList<>();
    private Function<LlmRequest, LlmResponse> script = r -> new LlmResponse(
            r.userContent().contains("\"partial\":\"true\"") ? PARTIAL_REPLY : DEFAULT_REPLY, 100, 20);

    public FakeLlmClient() {
    }

    public FakeLlmClient(Function<LlmRequest, LlmResponse> script) {
        this.script = script;
    }

    public synchronized FakeLlmClient thenAnswer(Function<LlmRequest, LlmResponse> newScript) {
        this.script = newScript;
        return this;
    }

    @Override
    public synchronized LlmResponse complete(LlmRequest request) throws LlmException {
        received.add(request);
        return script.apply(request);
    }

    public synchronized List<LlmRequest> received() {
        return List.copyOf(received);
    }
}
