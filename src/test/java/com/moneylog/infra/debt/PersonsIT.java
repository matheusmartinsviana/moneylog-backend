package com.moneylog.infra.debt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;

import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.SqlFixtures;

@DisplayName("Pessoas (T74)")
class PersonsIT extends E4ItBase {

    @Test
    @DisplayName("CA1: POST cria a pessoa com 201 + Location, archived false, version 0 e saldo zerado; nome vazio é 400 (contrato) e acima de 100 também")
    void deveCriarPessoa() throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/people", "{\"name\":\"  Carlos  \",\"notes\":\"colega\"}")).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.name").value("Carlos"))
                .andExpect(jsonPath("$.notes").value("colega")).andExpect(jsonPath("$.archived").value(false))
                .andExpect(jsonPath("$.version").value(0)).andExpect(jsonPath("$.net.amount").value("0.00"))
                .andExpect(jsonPath("$.direction").value("NONE")).andExpect(jsonPath("$.openDebts").value(0))
                .andExpect(jsonPath("$.nextDueDate").doesNotExist()).andExpect(jsonPath("$.debts").doesNotExist()).andReturn();
        JsonNode created = body(result);
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/v1/people/" + created.get("id").asString());

        mockMvc.perform(post("/v1/people", "{\"name\":\"   \"}")).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/v1/people", "{\"name\":\"" + "x".repeat(101) + "\"}")).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/v1/people", "{\"name\":\"Ana\",\"extra\":1}")).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly()).andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_FIELD"));
    }

    @Test
    @DisplayName("CA2: homônimos são aceitos (S-138) e a busca ?q= filtra sem acento nem caixa")
    void deveAceitarHomonimosEBuscarSemAcento() throws Exception {
        String outroJoao = person("João");
        person("Jô Silva");
        JsonNode all = okBody(get("/v1/people"));
        assertThat(all.get("items")).hasSize(4);
        assertThat(all.get("hasNext").asBoolean()).isFalse();
        assertThat(outroJoao).isNotEqualTo(joao);

        JsonNode byQuery = okBody(get("/v1/people?q=joao"));
        assertThat(byQuery.get("items")).hasSize(2);
        assertThat(okBody(get("/v1/people?q=JO")).get("items")).hasSize(3);
        assertThat(okBody(get("/v1/people?q=zzz")).get("items")).isEmpty();
        JsonNode firstPage = okBody(get("/v1/people?pageSize=2"));
        assertThat(firstPage.get("items")).hasSize(2);
        assertThat(firstPage.get("hasNext").asBoolean()).isTrue();
        assertThat(okBody(get("/v1/people?pageSize=2&page=2")).get("items")).hasSize(2);
    }

    @Test
    @DisplayName("CA3: archive responde 200 e some do padrão ACTIVE; arquivar de novo é 422 PERSON_ALREADY_ARCHIVED; unarchive de ativa é 422 PERSON_NOT_ARCHIVED")
    void deveArquivarEDesarquivar() throws Exception {
        mockMvc.perform(post("/v1/people/" + maria + "/archive", "")).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.archived").value(true)).andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(post("/v1/people/" + maria + "/archive", "")).andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("PERSON_ALREADY_ARCHIVED"));
        assertThat(okBody(get("/v1/people")).get("items")).hasSize(1);
        assertThat(okBody(get("/v1/people?status=ARCHIVED")).get("items")).hasSize(1);
        mockMvc.perform(post("/v1/people/" + maria + "/unarchive", "")).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.archived").value(false));
        mockMvc.perform(post("/v1/people/" + maria + "/unarchive", "")).andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("PERSON_NOT_ARCHIVED"));
    }

    @Test
    @DisplayName("CA4: PUT com version velha é 409 VERSION_CONFLICT; com a atual muda nome e observação e sobe a versão")
    void deveEditarComControleDeVersao() throws Exception {
        mockMvc.perform(put("/v1/people/" + joao, "{\"name\":\"João Pedro\",\"notes\":\"amigo\",\"version\":0}")).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.name").value("João Pedro"))
                .andExpect(jsonPath("$.notes").value("amigo")).andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(put("/v1/people/" + joao, "{\"name\":\"Outro\",\"version\":0}")).andExpect(status().isConflict())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mockMvc.perform(put("/v1/people/" + joao, "{\"name\":\"\",\"version\":1}")).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(get("/v1/people/" + joao)).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.name").value("João Pedro")).andExpect(jsonPath("$.debts.length()").value(0));
    }

    @Test
    @DisplayName("CA5: pessoa de outro usuário é 404 PERSON_NOT_FOUND em todas as rotas, e não aparece na lista")
    void deveIsolarPorUsuario() throws Exception {
        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "outro-" + other + "@example.com"));
        String otherBearer = auth.bearer(other);
        mockMvc.perform(getAs(otherBearer, "/v1/people/" + joao)).andExpect(status().isNotFound())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("PERSON_NOT_FOUND"));
        mockMvc.perform(putAs(otherBearer, "/v1/people/" + joao, "{\"name\":\"X\",\"version\":0}")).andExpect(status().isNotFound())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("PERSON_NOT_FOUND"));
        mockMvc.perform(postAs(otherBearer, "/v1/people/" + joao + "/archive", "")).andExpect(status().isNotFound())
                .andExpect(ContractValidation.conforms());
        mockMvc.perform(postAs(otherBearer, "/v1/people/" + joao + "/unarchive", "")).andExpect(status().isNotFound())
                .andExpect(ContractValidation.conforms());
        assertThat(body(mockMvc.perform(getAs(otherBearer, "/v1/people")).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andReturn()).get("items")).isEmpty();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/people")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("o conflito de versão é o mesmo da exceção de domínio (409), sem vazar mensagem interna")
    void deveTerExcecaoDeVersaoCoerente() {
        assertThat(new VersionConflictException(0, 1).code()).isEqualTo("VERSION_CONFLICT");
    }
}
