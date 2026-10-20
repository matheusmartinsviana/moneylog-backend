package com.moneylog.infra.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;

import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;

@IntegrationTest
@DisplayName("Cobertura do contrato: operações do arquivo × handlers mapeados")
class ContractCoverageIT {

    private static final Set<String> PENDING = new TreeSet<>(Set.<String>of());

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("todo handler de infra existe no contrato e toda operação do contrato tem handler (ou está pendente)")
    void deveCobrirOContratoNosDoisLados() {
        Set<String> inContract = contractOperations();
        Set<String> handled = handledOperations();

        Set<String> handlerWithoutContract = new TreeSet<>(handled);
        handlerWithoutContract.removeAll(inContract);
        Set<String> contractWithoutHandler = new TreeSet<>(inContract);
        contractWithoutHandler.removeAll(handled);
        contractWithoutHandler.removeAll(PENDING);
        Set<String> stalePending = new TreeSet<>(PENDING);
        stalePending.removeAll(inContract);
        stalePending.retainAll(handled);
        Set<String> pendingAlreadyHandled = new TreeSet<>(PENDING);
        pendingAlreadyHandled.retainAll(handled);

        assertThat(handlerWithoutContract).as("handlers de infra sem operação no contrato").isEmpty();
        assertThat(contractWithoutHandler).as("operações do contrato sem handler (e fora de PENDING)").isEmpty();
        assertThat(pendingAlreadyHandled).as("PENDING com handler já implementado: remova da lista").isEmpty();
        assertThat(PENDING).as("PENDING só pode citar operações do contrato").isSubsetOf(inContract);
        assertThat(stalePending).isEmpty();
    }

    @Test
    @DisplayName("toda operação do contrato tem operationId único")
    void deveTerOperationIdUnico() {
        OpenAPI spec = ContractValidation.spec();
        Set<String> ids = new TreeSet<>();
        spec.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
            assertThat(operation.getOperationId()).as("operationId de %s %s", method, path).isNotBlank();
            assertThat(ids.add(operation.getOperationId())).as("operationId repetido: " + operation.getOperationId()).isTrue();
        }));
    }

    private static Set<String> contractOperations() {
        Set<String> operations = new TreeSet<>();
        for (var entry : ContractValidation.spec().getPaths().entrySet()) {
            PathItem item = entry.getValue();
            for (PathItem.HttpMethod method : item.readOperationsMap().keySet()) {
                operations.add(method + " " + normalize(entry.getKey()));
            }
        }
        return operations;
    }

    private Set<String> handledOperations() {
        Set<String> operations = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, method) -> {
            if (!method.getBeanType().getPackageName().startsWith("com.moneylog.infra")) {
                return;
            }
            for (RequestMethod requestMethod : info.getMethodsCondition().getMethods()) {
                for (String pattern : patterns(info)) {
                    operations.add(requestMethod.name() + " " + normalize(pattern));
                }
            }
        });
        return operations;
    }

    private static Set<String> patterns(RequestMappingInfo info) {
        return info.getPathPatternsCondition() == null ? Set.of() : info.getPathPatternsCondition().getPatternValues();
    }

    private static String normalize(String path) {
        return path.replaceAll("\\{[^}/]+}", "{}");
    }
}
