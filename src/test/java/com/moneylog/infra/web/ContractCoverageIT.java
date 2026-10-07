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

/**
 * T21 CA6 (S-66): cada operação de {@code contracts/openapi.yaml} tem handler e cada handler de {@code infra} tem
 * operação no contrato.
 *
 * <p>{@link #PENDING}: operações do contrato ainda sem handler. Cada task de endpoint tira as suas daqui (o teste
 * falha se uma operação já implementada continuar na lista). <b>No fim da Etapa 3 (T58) a lista ficou vazia.</b> Etapa 4: as 22
 * operações novas (pessoas, dívidas e recorrências, T73) ganharam handler (T74, T75, T77, T79, T80) no mesmo ciclo em que entraram no
 * contrato, então a lista nunca precisou ser preenchida: continua vazia.
 */
@IntegrationTest
@DisplayName("Cobertura do contrato: operações do arquivo × handlers mapeados")
class ContractCoverageIT {

    /**
     * Operações do contrato sem handler. Etapa 2: preenchida na T21 e esvaziada por T22b a T29. Etapa 3: preenchida na
     * T49 com as rotas de cartões/faturas; cada task (T51, T53, T54, T55, T57, T58) removeu as suas e a lista fechou vazia
     * na T58. Operação nova de uma etapa seguinte entra aqui enquanto não tiver handler.
     */
    private static final Set<String> PENDING = new TreeSet<>(Set.of());

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

    /** {@code /v1/accounts/{id}} e {@code /v1/accounts/{accountId}} são a mesma rota. */
    private static String normalize(String path) {
        return path.replaceAll("\\{[^}/]+}", "{}");
    }
}
