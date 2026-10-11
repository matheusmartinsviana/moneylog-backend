package com.moneylog.infra.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

import com.moneylog.core.note.util.NoteTestKit.Zip;
import com.moneylog.util.ContractValidation;

@DisplayName("Vault: exportar e importar o zip compatível com o Obsidian (T112, S-157, S-162, S-166)")
class VaultIT extends NotesItBase {

    private org.springframework.test.web.servlet.RequestBuilder upload(String bearerToken, byte[] zip, String mode) {
        var request = MockMvcRequestBuilders.multipart("/v1/vault/import").file(new MockMultipartFile("file", "vault.zip", "application/zip", zip))
                .header(HttpHeaders.AUTHORIZATION, bearerToken);
        return mode == null ? request : request.param("mode", mode);
    }

    private JsonNode imported(byte[] zip, String mode) throws Exception {
        return body(mockMvc.perform(upload(bearer, zip, mode)).andExpect(status().isOk()).andExpect(ContractValidation.responseOnly()).andReturn());
    }

    private void rejected(byte[] zip, int status, String code) throws Exception {
        mockMvc.perform(upload(bearer, zip, null)).andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code)).andExpect(ContractValidation.responseOnly());
    }

    private Map<String, String> unzip(byte[] bytes) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                files.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    private byte[] exportBytes(String bearerToken) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get("/v1/vault/export").header(HttpHeaders.AUTHORIZATION, bearerToken)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    private static byte[] obsidianSample() {
        return new Zip().dir("Meu Vault").add(".obsidian/app.json", "{}").add(".obsidian/workspace.json", "{\"main\":{}}").add(".trash/Velha.md", "# lixeira do Obsidian")
                .add("__MACOSX/._Welcome.md", "lixo do macOS").add(".DS_Store", "x")
                .add("Welcome.md", "---\ntags:\n  - inicio\n  - Revisão Mensal\naliases: [Boas-vindas]\ncssclasses: wide\n---\n# Bem-vindo\nVeja [[Projetos/MG Tech|o cliente]], [[Revisão de outubro#Resumo]] e ![[logo.png]].\n\n#primeiro-passo\n")
                .add("Projetos/MG Tech.md", "# MG Tech\n\n- [ ] enviar proposta\n- Ver [[Welcome]]\n\n```js\nconst a = '[[nao-e-link]]';\n```\n")
                .add("Revisão de outubro.md", "---\ntitle: Revisão do mês\n---\n## Resumo\nCompra do [[Notebook MacBook]] em 6x. #revisao-mensal\n")
                .add("Projetos/Anexos/foto.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3}).add("Quadro.canvas", "{\"nodes\":[]}")
                .add("Diário/2026-10-05.md", "Hoje: [[Welcome]]\r\nSegunda linha com CRLF\r\n").bytes();
    }

    @Test
    @DisplayName("CA3: pasta no formato do Obsidian: importa só os .md (com pastas, frontmatter e wikilinks), ignora e lista anexos, canvas, .obsidian, .trash e __MACOSX; ![[logo.png]] fica no texto como link pendente")
    void deveImportarUmaPastaDoObsidian() throws Exception {
        JsonNode report = imported(obsidianSample(), "SKIP");
        assertThat(report.get("created").asInt()).isEqualTo(4);
        assertThat(report.get("skipped").asInt()).isZero();
        java.util.List<String> ignored = new java.util.ArrayList<>();
        report.get("ignoredFiles").forEach(n -> ignored.add(n.asString()));
        assertThat(ignored).containsExactlyInAnyOrder(".obsidian/app.json", ".obsidian/workspace.json", ".trash/Velha.md", "__MACOSX/._Welcome.md", ".DS_Store",
                "Projetos/Anexos/foto.png", "Quadro.canvas");
        assertThat(paths(listNotes("sort=PATH"))).containsExactly("Diário/2026-10-05.md", "Projetos/MG Tech.md", "Revisão de outubro.md", "Welcome.md");

        String welcomeId = noteIdByPath("Welcome.md");
        JsonNode welcome = getNote(welcomeId);
        assertThat(welcome.get("body").asString()).contains("![[logo.png]]").startsWith("---\ntags:\n  - inicio");
        assertThat(welcome.get("tags")).extracting(JsonNode::asString).containsExactlyInAnyOrder("inicio", "revisao-mensal", "primeiro-passo");
        assertThat(welcome.get("title").asString()).isEqualTo("Welcome");
        Map<String, String> states = new LinkedHashMap<>();
        welcome.get("links").forEach(l -> states.put(l.get("text").asString(), l.get("state").asString()));
        assertThat(states).containsEntry("Projetos/MG Tech", "RESOLVED").containsEntry("Revisão de outubro", "RESOLVED").containsEntry("logo.png", "PENDING");
        JsonNode review = getNote(noteIdByPath("Revisão de outubro.md"));
        assertThat(review.get("title").asString()).isEqualTo("Revisão do mês");
        assertThat(review.get("links").get(0).get("state").asString()).isEqualTo("PENDING");
        JsonNode project = getNote(noteIdByPath("Projetos/MG Tech.md"));
        assertThat(project.get("links").size()).isEqualTo(1);
        assertThat(getNote(noteIdByPath("Diário/2026-10-05.md")).get("body").asString()).isEqualTo("Hoje: [[Welcome]]\r\nSegunda linha com CRLF\r\n");
        assertThat(report.get("pendingLinks").asInt()).isEqualTo(2);
        assertThat(backlinks(welcomeId).get("items").size()).isEqualTo(2);
    }

    private String noteIdByPath(String path) {
        return jdbc.queryForObject("SELECT id FROM notes WHERE user_id = ? AND path = ?", String.class, userId, path);
    }

    @Test
    @DisplayName("CA1: o vault do cenário exporta com reviews/2026-10.md, frontmatter YAML (moneylog.links) e corpo intacto; a lixeira não vai; download sem cache; abre numa pasta do Obsidian")
    void deveExportar() throws Exception {
        String macbook = createNote("MacBook.md", "notebook").get("id").asString();
        createNote("reviews/2026-10.md", "---\ntags: [revisao-mensal]\n---\n# Outubro\nCompra do [[MacBook]] #outubro",
                "[" + link("TRANSACTION", notebookId) + "," + monthLink("2026-10") + "," + link("NOTE", macbook) + "]");
        createNote("projetos/MG Tech.md", "cliente");
        String gone = createNote("lixo.md", "x").get("id").asString();
        trashNote(gone);
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/v1/vault/export").header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip")).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"moneylog-vault-2026-10-10.zip\"")).andExpect(ContractValidation.conforms()).andReturn();
        Map<String, String> files = unzip(result.getResponse().getContentAsByteArray());
        assertThat(files).containsOnlyKeys("MacBook.md", "reviews/2026-10.md", "projetos/MG Tech.md");
        assertThat(files.get("MacBook.md")).isEqualTo("notebook");
        assertThat(files.get("reviews/2026-10.md")).isEqualTo("---\ntags: [revisao-mensal]\nmoneylog:\n  links:\n    - { type: TRANSACTION, id: " + notebookId
                + " }\n    - { type: MONTHLY_SUMMARY, key: 2026-10 }\n    - { type: NOTE, path: \"MacBook.md\" }\n---\n# Outubro\nCompra do [[MacBook]] #outubro");
    }

    @Test
    @DisplayName("CA2: exportar e importar em outro usuário recria as mesmas notas, tags e vínculos; a transação que ele não tem vira vínculo pendente e entra em pendingLinks")
    void deveFazerOCicloComOutroUsuario() throws Exception {
        String macbook = createNote("MacBook.md", "notebook #hardware").get("id").asString();
        createNote("reviews/2026-10.md", "Compra do [[MacBook]] e [[Inexistente]] #revisao-mensal",
                "[" + link("TRANSACTION", notebookId) + "," + monthLink("2026-10") + "," + link("NOTE", macbook) + "," + link("GOAL", goalId) + "]");
        byte[] zip = exportBytes(bearer);

        UUID otherId = UUID.randomUUID();
        committed(() -> com.moneylog.util.SqlFixtures.insertUser(jdbc, otherId, "outro-" + otherId + "@example.com"));
        String other = auth.bearer(otherId);
        String otherGoal = body(mockMvc.perform(postAs(other, "/v1/goals", "{\"name\":\"Reserva dele\",\"target\":\"10.00\",\"priority\":\"LOW\"}")).andExpect(status().isCreated()).andReturn()).get("id").asString();
        assertThat(otherGoal).isNotEqualTo(goalId);
        JsonNode report = body(mockMvc.perform(upload(other, zip, "SKIP")).andExpect(status().isOk()).andExpect(ContractValidation.responseOnly()).andReturn());
        assertThat(report.get("created").asInt()).isEqualTo(2);
        assertThat(report.get("pendingLinks").asInt()).isEqualTo(3);
        MvcResult list = mockMvc.perform(getAs(other, "/v1/notes?sort=PATH")).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn();
        assertThat(paths(body(list))).containsExactly("MacBook.md", "reviews/2026-10.md");
        String reviewId = jdbc.queryForObject("SELECT id::text FROM notes WHERE user_id = ? AND path = 'reviews/2026-10.md'", String.class, otherId);
        JsonNode review = body(mockMvc.perform(getAs(other, "/v1/notes/" + reviewId)).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn());
        assertThat(review.get("body").asString()).isEqualTo("Compra do [[MacBook]] e [[Inexistente]] #revisao-mensal");
        assertThat(review.get("tags")).extracting(JsonNode::asString).containsExactly("revisao-mensal");
        assertThat(linkOf(review, "TRANSACTION").get("state").asString()).isEqualTo("PENDING");
        assertThat(linkOf(review, "TRANSACTION").get("text").asString()).isEqualTo(notebookId);
        assertThat(linkOf(review, "MONTHLY_SUMMARY").get("state").asString()).isEqualTo("RESOLVED");
        assertThat(linkOf(review, "NOTE").get("state").asString()).isEqualTo("RESOLVED");
        assertThat(linkOf(review, "NOTE").get("target").get("label").asString()).isEqualTo("MacBook");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM note_links WHERE user_id = ? AND (target_transaction_id IS NOT NULL OR target_goal_id IS NOT NULL)", Long.class, otherId)).isZero();
        assertThat(getNote(macbook).get("version").asLong()).isZero();
        Map<String, String> again = unzip(exportBytes(other));
        assertThat(again.get("reviews/2026-10.md")).contains("{ type: TRANSACTION, id: " + notebookId + " }").endsWith("---\nCompra do [[MacBook]] e [[Inexistente]] #revisao-mensal");
    }

    @Test
    @DisplayName("CA5/CA6: conflito: SKIP pula e relata; reimportar o mesmo zip com SKIP não duplica; RENAME cria 'nome (2).md' e relata")
    void deveTratarConflitosEReimportar() throws Exception {
        byte[] zip = new Zip().add("a.md", "um").add("pasta/b.md", "dois").bytes();
        JsonNode first = imported(zip, "SKIP");
        assertThat(first.get("created").asInt()).isEqualTo(2);
        JsonNode second = imported(zip, null);
        assertThat(second.get("created").asInt()).isZero();
        assertThat(second.get("skipped").asInt()).isEqualTo(2);
        assertThat(second.get("skippedFiles")).extracting(JsonNode::asString).containsExactly("a.md", "pasta/b.md");
        assertThat(count("notes")).isEqualTo(2L);
        JsonNode renamed = imported(new Zip().add("A.md", "tres").bytes(), "RENAME");
        assertThat(renamed.get("created").asInt()).isEqualTo(1);
        assertThat(renamed.get("renamedFiles").get(0).get("from").asString()).isEqualTo("A.md");
        assertThat(renamed.get("renamedFiles").get(0).get("to").asString()).isEqualTo("A (2).md");
        assertThat(paths(listNotes("sort=PATH"))).containsExactly("A (2).md", "a.md", "pasta/b.md");
        assertThat(getNote(noteIdByPath("a.md")).get("body").asString()).isEqualTo("um");
        trashNote(noteIdByPath("a.md"));
        assertThat(imported(zip, "SKIP").get("created").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA7 (S-273): YAML malicioso, quebrado ou grande demais não é frontmatter: a nota entra com o texto inteiro como corpo, sem aviso e sem perder nada")
    void devePreservarFrontmatterMalicioso() throws Exception {
        String hostile = "---\nx: !!java.lang.ProcessBuilder [[\"calc\"]]\n---\ncorpo";
        String bomb = "---\na: &a [*a]\nb: *a\n---\ncorpo2";
        String big = "---\nx: " + "a".repeat(70_000) + "\n---\ncorpo3";
        JsonNode report = imported(new Zip().add("m.md", hostile).add("bomba.md", bomb).add("grande.md", big).bytes(), "SKIP");
        assertThat(report.get("created").asInt()).isEqualTo(3);
        assertThat(report.get("warnings")).isEmpty();
        assertThat(getNote(noteIdByPath("m.md")).get("body").asString()).isEqualTo(hostile);
        assertThat(getNote(noteIdByPath("bomba.md")).get("body").asString()).isEqualTo(bomb);
        assertThat(getNote(noteIdByPath("grande.md")).get("body").asString()).isEqualTo(big);
    }

    @Test
    @DisplayName("CA4: zip hostil (../../etc/passwd.md, caminho absoluto, nome com \\, controle) é 422 VAULT_IMPORT_INVALID e NADA é gravado, nem as notas boas do mesmo zip")
    void deveRecusarZipHostil() throws Exception {
        for (String name : new String[] {"../../etc/passwd.md", "/etc/passwd.md", "a\\b.md", "ok/../../fora.md", "C:/x.md", "x\u0000.md", "../evil.sh"}) {
            rejected(new Zip().add("boa.md", "x").add(name, "mal").bytes(), 422, "VAULT_IMPORT_INVALID");
        }
        rejected("isto não é um zip".getBytes(StandardCharsets.UTF_8), 422, "VAULT_IMPORT_INVALID");
        rejected(new byte[] {'P', 'K', 3, 4, 1, 2, 3}, 422, "VAULT_IMPORT_INVALID");
        mockMvc.perform(MockMvcRequestBuilders.multipart("/v1/vault/import").file(new MockMultipartFile("file", "vazio.zip", "application/zip", new byte[0]))
                .header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VAULT_IMPORT_INVALID"));
        mockMvc.perform(MockMvcRequestBuilders.multipart("/v1/vault/import").header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isBadRequest());
        mockMvc.perform(upload(bearer, new Zip().add("a.md", "x").bytes(), "OVERWRITE")).andExpect(status().isBadRequest());
        assertThat(count("notes")).isZero();
        assertThat(count("note_links")).isZero();
        assertThat(count("note_tags")).isZero();
    }

    @Test
    @DisplayName("CA4: mais de 5.500 entradas, arquivo acima de 20 MB e zip bomb (300 KB que descomprimem a 301 MB) são 422 VAULT_IMPORT_TOO_LARGE e nada é gravado")
    void deveRecusarZipsGrandesDemais() throws Exception {
        Zip many = new Zip().add("primeira.md", "x");
        for (int i = 0; i < 5_500; i++) {
            many.add("n" + i + ".md", "");
        }
        rejected(many.bytes(), 422, "VAULT_IMPORT_TOO_LARGE");
        rejected(new byte[20 * 1024 * 1024 + 1], 422, "VAULT_IMPORT_TOO_LARGE");
        ByteArrayOutputStream bomb = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bomb)) {
            zip.putNextEntry(new ZipEntry("bomba.bin"));
            byte[] block = new byte[1024 * 1024];
            for (int i = 0; i < 301; i++) {
                zip.write(block);
            }
            zip.closeEntry();
        }
        assertThat(bomb.size()).isLessThan(1024 * 1024);
        rejected(bomb.toByteArray(), 422, "VAULT_IMPORT_TOO_LARGE");
        assertThat(count("notes")).isZero();
    }

    @Test
    @DisplayName("nomes sem suporte, nota grande demais, controle e encoding inválido não derrubam a importação: as boas entram e o relatório avisa")
    void deveAvisarEImportarAsBoas() throws Exception {
        JsonNode report = imported(new Zip().add("boa.md", "x").add("con.md", "x").add("a<b>.md", "x").add("grande.md", "a".repeat(204_801)).add("ctl.md", "a\u0001b")
                .add("latin1.md", new byte[] {'a', (byte) 0xE9}).add("pasta/.oculta/x.md", "x").bytes(), "SKIP");
        assertThat(report.get("created").asInt()).isEqualTo(1);
        java.util.Set<String> codes = new java.util.TreeSet<>();
        report.get("warnings").forEach(w -> codes.add(w.get("code").asString()));
        assertThat(codes).containsExactlyInAnyOrder("NOTE_PATH_INVALID", "NOTE_TOO_LARGE", "TEXT_INVALID_CHARACTERS", "NOTE_INVALID_ENCODING");
        assertThat(paths(listNotes(null))).containsExactly("boa.md");
    }

    @Test
    @DisplayName("importar duas vezes concorrentes o mesmo zip com SKIP cria cada nota uma vez só e nunca responde 5xx")
    void deveSerSeguroNaConcorrencia() throws Exception {
        byte[] zip = new Zip().add("a.md", "x").add("b.md", "y").add("c.md", "z").bytes();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> mockMvc.perform(upload(bearer, zip, "SKIP")).andReturn().getResponse().getStatus()));
            }
            for (var result : results) {
                assertThat(result.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("notes")).isEqualTo(3L);
    }

    @Test
    @DisplayName("rotas protegidas: sem token é 401; o vault não vaza notas de outro usuário na exportação")
    void deveProtegerAsRotas() throws Exception {
        createNote("minha.md", "segredo do dono");
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/vault/export")).andExpect(status().isUnauthorized());
        mockMvc.perform(MockMvcRequestBuilders.multipart("/v1/vault/import").file(new MockMultipartFile("file", "v.zip", "application/zip", new Zip().add("a.md", "x").bytes())))
                .andExpect(status().isUnauthorized());
        Map<String, String> other = unzip(exportBytes(otherUserBearer()));
        assertThat(other).isEmpty();
    }
}
