package com.moneylog.core.ai.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Fatos calculados pelo backend: a única coisa que a IA recebe e a única fonte de números que ela pode citar (S-184). Chaves e valores são
 * texto simples ({@code "income" -> "3000.00"}); a IA só reformula, nunca calcula. O JSON enviado ao provedor sai daqui, determinístico, então o
 * hash da prévia identifica exatamente o que foi (ou será) enviado.
 */
public final class AiFacts {

    private final Map<String, String> values;

    public AiFacts(Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        Map<String, String> copy = new LinkedHashMap<>();
        values.forEach((k, v) -> copy.put(Objects.requireNonNull(k, "chave"), Objects.requireNonNull(v, "valor")));
        this.values = Collections.unmodifiableMap(copy);
    }

    public Map<String, String> values() {
        return values;
    }

    public int size() {
        return values.size();
    }

    /** JSON do payload: {@code {"purpose":...,"language":...,"facts":{...}}}. Os valores são dado, nunca instrução. */
    public String toPayload(AiPurpose purpose, String language) {
        StringBuilder json = new StringBuilder("{\"purpose\":").append(quote(purpose.name()))
                .append(",\"language\":").append(quote(language)).append(",\"facts\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append(quote(e.getKey())).append(':').append(quote(e.getValue()));
        }
        return json.append("}}").toString();
    }

    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
