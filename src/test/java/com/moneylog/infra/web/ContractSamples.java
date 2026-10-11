package com.moneylog.infra.web;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.stereotype.Controller;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@TestConfiguration(proxyBeanMethods = false)
public class ContractSamples {

    private static final String ID = "7c2f6b0e-35c0-4a3d-93f1-2f1f7d9b7a10";

    @Controller
    @TestComponent
    @RequestMapping("/v1/categories")
    @ResponseBody
    public static class CategoriesSample {

        @GetMapping
        public List<Map<String, Object>> list(@RequestHeader(value = "X-Sample-Case", defaultValue = "ok") String sampleCase) {
            return List.of(category(sampleCase));
        }

        @PostMapping
        public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> ignored,
                @RequestHeader(value = "X-Sample-Case", defaultValue = "ok") String sampleCase) {
            if ("rule".equals(sampleCase)) {
                throw new SampleRule();
            }
            if ("conflict".equals(sampleCase)) {
                throw new SampleConflict();
            }
            return ResponseEntity.created(URI.create("/v1/categories/" + ID)).body(category("ok"));
        }

        private static Map<String, Object> category(String sampleCase) {
            Map<String, Object> category = new java.util.LinkedHashMap<>();
            category.put("id", ID);
            category.put("name", "Alimentação");
            category.put("kind", "EXPENSE");
            category.put("system", true);
            category.put("archived", false);
            category.put("version", "wrong-type".equals(sampleCase) ? "0" : 0);
            if ("extra-field".equals(sampleCase)) {
                category.put("campoAMais", "não está no contrato");
            }
            return category;
        }
    }

    static class SampleRule extends com.moneylog.core.sk.exception.BusinessRuleException {
        SampleRule() {
            super("CATEGORY_ARCHIVED", "Categoria arquivada.");
        }
    }

    static class SampleConflict extends com.moneylog.core.sk.exception.ConflictException {
        SampleConflict() {
            super("CATEGORY_NAME_TAKEN", "Nome já usado.");
        }
    }
}
