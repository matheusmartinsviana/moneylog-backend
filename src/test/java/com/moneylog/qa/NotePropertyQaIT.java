package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.qa.CardQaWorld.Actor;
import com.moneylog.qa.CardQaWorld.Res;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

/**
 * T113 (QA), critério 3 (propriedade): operações aleatórias (criar, editar, renomear/mover, lixeira, restaurar, esvaziar) sobre notas com wikilinks
 * de todas as formas (nome, caminho, .md, caixa/acento trocados, alias, seção, embed, espaços, dentro de código inline e de bloco), com um oráculo
 * independente do código de produção. Depois de cada operação: todo wikilink gravado resolve como o oráculo manda; renomear reescreve exatamente os
 * links que apontavam para a nota movida (e mais nada, byte a byte), nenhum link vira órfão por causa do rename, e os backlinks batem.
 */
@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T113: propriedade de wikilinks, renomear/mover, lixeira e backlinks (oráculo independente, semente fixa)")
class NotePropertyQaIT {

    private static final long[] SEEDS = System.getProperty("qa.seeds") == null ? new long[] {20261009L, 7L, 4242L, 99L, 123456L}
            : java.util.Arrays.stream(System.getProperty("qa.seeds").split(",")).mapToLong(Long::parseLong).toArray();
    private static final int OPS = 140;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ItAuth auth;
    @Autowired
    private PlatformTransactionManager tm;
    @Autowired
    private MutableClock clock;

    private CardQaWorld w;

    @BeforeEach
    void cenario() {
        clock.set(MutableClockConfig.INITIAL);
        w = new CardQaWorld(mvc, om, jdbc, auth, tm, clock);
    }

    // ------------------------------------------------------------------------------------------------ modelo

    /** Um pedaço de uma linha: texto, link, código inline, ou um bloco de código cercado (a linha inteira). */
    private record Seg(char kind, String target, String section, String alias, boolean embed, String padL, String padR, String text) {
        static Seg text(String t) {
            return new Seg('T', null, null, null, false, "", "", t);
        }

        static Seg link(String target, String section, String alias, boolean embed, String padL, String padR) {
            return new Seg('L', target, section, alias, embed, padL, padR, null);
        }

        static Seg inline(String target) {
            return new Seg('I', target, null, null, false, "", "", null);
        }

        static Seg fence(String target) {
            return new Seg('F', target, null, null, false, "", "", null);
        }

        Seg withTarget(String t) {
            return new Seg(kind, t, section, alias, embed, padL, padR, text);
        }

        String render() {
            return switch (kind) {
                case 'T' -> text;
                case 'L' -> (embed ? "!" : "") + "[[" + padL + target + (section == null ? "" : "#" + section) + (alias == null ? "" : "|" + alias) + padR + "]]";
                case 'I' -> "`[[" + target + "]]`";
                default -> "```\n[[" + target + "]]\n```";
            };
        }
    }

    private static final class NoteM {
        String id;
        String path;
        List<Seg> segs;
        boolean trashed;
        long version;

        String body() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < segs.size(); i++) {
                Seg s = segs.get(i);
                if (s.kind() == 'F') {
                    sb.append(s.render()).append('\n');
                } else {
                    sb.append(s.render());
                    sb.append(i + 1 < segs.size() && segs.get(i + 1).kind() != 'F' ? " " : "\n");
                }
            }
            return sb.toString();
        }
    }

    private static final class Vault {
        final Map<String, NoteM> byId = new LinkedHashMap<>();

        List<NoteM> active() {
            return byId.values().stream().filter(n -> !n.trashed).toList();
        }

        NoteM activeByKey(String path) {
            for (NoteM n : active()) {
                if (n.path.toLowerCase(Locale.ROOT).equals(path.toLowerCase(Locale.ROOT))) {
                    return n;
                }
            }
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------------ oráculo (independente do código de produção)

    private static String fold(String s) {
        String d = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return Normalizer.normalize(d.toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
    }

    private static String noExt(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".md") ? path.substring(0, path.length() - 3) : path;
    }

    private static String resolve(String target, List<NoteM> active) {
        String t = target.strip();
        t = noExt(t).replaceFirst("^/+", "").strip();
        String folded = fold(t);
        if (folded.isEmpty()) {
            return null;
        }
        int slash = folded.lastIndexOf('/');
        String name = slash < 0 ? folded : folded.substring(slash + 1);
        NoteM best = null;
        for (NoteM n : active) {
            String full = fold(noExt(n.path));
            String nm = full.substring(full.lastIndexOf('/') + 1);
            if (!nm.equals(name)) {
                continue;
            }
            if (slash >= 0 && !(full.equals(folded) || full.endsWith("/" + folded))) {
                continue;
            }
            if (best == null || compare(n, best) < 0) {
                best = n;
            }
        }
        return best == null ? null : best.id;
    }

    private static int compare(NoteM x, NoteM y) {
        return Comparator.<NoteM>comparingInt(n -> n.path.length()).thenComparing(n -> fold(noExt(n.path))).thenComparing(n -> java.util.UUID.fromString(n.id)).compare(x, y);
    }

    // ------------------------------------------------------------------------------------------------ gerador

    private static final String[] NAMES = {"Revisão", "revisao", "REVISÃO", "MacBook", "macbook", "Café", "cafe", "Ação", "acao", "Ünï", "uni", "ß", "SS", "İstanbul", "istanbul",
            "100%", "x_y", "o'brien", "a&b", "(plano)", "uma frase longa", "uma  frase  longa", "emoji 🚀", "日本語", "ǅ", "Mixed Case", "e", "é", "É", "a.b"};
    private static final String[] FOLDERS = {"", "", "", "p/", "q/", "p/q/", "diário/", "Ações/2026/"};

    private static String name(Random r) {
        return NAMES[r.nextInt(NAMES.length)];
    }

    private String randomPath(Random r) {
        return FOLDERS[r.nextInt(FOLDERS.length)] + name(r) + ".md";
    }

    /** Caminho livre de equivalência por acento/caixa com qualquer outra nota (esse empate é o BUG-QA-08, coberto à parte). */
    private String freshPath(Random r, Vault v, String exceptId) {
        for (int i = 0; i < 30; i++) {
            String path = randomPath(r);
            boolean clash = v.byId.values().stream().anyMatch(n -> !n.id.equals(exceptId) && fold(noExt(n.path)).equals(fold(noExt(path))));
            if (!clash || v.byId.values().stream().anyMatch(n -> !n.id.equals(exceptId) && n.path.equalsIgnoreCase(path) && !n.trashed)) {
                if (!clash) {
                    return path;
                }
                return path;
            }
        }
        return randomPath(r);
    }

    private Seg randomLink(Random r, Vault v) {
        List<NoteM> all = new ArrayList<>(v.byId.values());
        String target;
        if (all.isEmpty() || r.nextInt(6) == 0) {
            target = name(r);
        } else {
            NoteM n = all.get(r.nextInt(all.size()));
            String noext = noExt(n.path);
            String nm = noext.substring(noext.lastIndexOf('/') + 1);
            target = switch (r.nextInt(6)) {
                case 0 -> noext;
                case 1 -> nm.toUpperCase(Locale.ROOT);
                case 2 -> nm + ".md";
                case 3 -> fold(nm);
                case 4 -> "/" + noext;
                default -> nm;
            };
        }
        if (target.contains("[") || target.contains("]") || target.contains("|") || target.contains("#") || target.isBlank()) {
            target = "Revisão";
        }
        return Seg.link(target, r.nextInt(6) == 0 ? "Seção " + r.nextInt(3) : null, r.nextInt(5) == 0 ? "apelido " + r.nextInt(9) : null, r.nextInt(10) == 0,
                r.nextInt(8) == 0 ? " " : "", r.nextInt(8) == 0 ? " " : "");
    }

    private List<Seg> randomSegs(Random r, Vault v) {
        List<Seg> segs = new ArrayList<>();
        int n = r.nextInt(8);
        for (int i = 0; i < n; i++) {
            switch (r.nextInt(8)) {
                case 0, 1, 2 -> segs.add(randomLink(r, v));
                case 3 -> segs.add(Seg.inline(randomLink(r, v).target()));
                case 4 -> segs.add(Seg.fence(randomLink(r, v).target()));
                default -> segs.add(Seg.text(r.nextBoolean() ? "texto solto de " + r.nextInt(100) : "Linha com acentuação e emoji 🚀 " + r.nextInt(100)));
            }
        }
        return segs;
    }

    // ------------------------------------------------------------------------------------------------ verificações

    private void check(Actor a, Vault v, String label, List<String> violations) throws Exception {
        List<NoteM> active = v.active();
        // 1) o conjunto de notas bate
        Map<String, String> dbNotes = new HashMap<>();
        jdbc.query("select id, path, deleted_at is not null as trashed from notes where user_id = ?", rs -> {
            dbNotes.put(rs.getString("id"), rs.getString("path") + "|" + rs.getBoolean("trashed"));
        }, a.id);
        Map<String, String> modelNotes = new HashMap<>();
        v.byId.values().forEach(n -> modelNotes.put(n.id, n.path + "|" + n.trashed));
        if (!dbNotes.equals(modelNotes)) {
            violations.add(label + ": notas no banco diferem do modelo: " + dbNotes + " x " + modelNotes);
            return;
        }
        // 2) cada nota: corpo e wikilinks gravados
        Set<String> expectedBacklinks = new TreeSet<>();
        Map<String, Set<String>> backlinksByTarget = new HashMap<>();
        for (NoteM n : v.byId.values()) {
            String body = jdbc.queryForObject("select body from notes where id = ?::uuid", String.class, n.id);
            if (!body.equals(n.body())) {
                violations.add(label + ": corpo de " + n.path + " difere do modelo: [" + body.replace("\n", "\\n") + "] x [" + n.body().replace("\n", "\\n") + "]");
            }
            Set<String> expected = new TreeSet<>();
            Set<String> seen = new java.util.HashSet<>();
            for (Seg s : n.segs) {
                if (s.kind() == 'L') {
                    if (!seen.add(fold(noExt(s.target().strip())))) {
                        continue;
                    }
                    String target = resolve(s.target(), active);
                    expected.add(s.embed() + "|" + s.target() + "|" + s.section() + "|" + s.alias() + "|" + (target == null ? "pendente" : target));
                    if (target != null && !target.equals(n.id) && !n.trashed) {
                        backlinksByTarget.computeIfAbsent(target, k -> new TreeSet<>()).add(n.path);
                    }
                }
            }
            Set<String> actual = new TreeSet<>();
            jdbc.query("select embed, target_text, section, alias, target_note_id from note_links where note_id = ?::uuid and origin = 'WIKILINK'", rs -> {
                actual.add(rs.getBoolean("embed") + "|" + rs.getString("target_text") + "|" + rs.getString("section") + "|" + rs.getString("alias") + "|"
                        + (rs.getString("target_note_id") == null ? "pendente" : rs.getString("target_note_id")));
            }, n.id);
            if (!expected.equals(actual)) {
                Set<String> missing = new TreeSet<>(expected);
                missing.removeAll(actual);
                Set<String> extra = new TreeSet<>(actual);
                extra.removeAll(expected);
                violations.add(label + ": wikilinks de " + n.path + (n.trashed ? " (lixeira)" : "") + " divergem do oráculo. faltam " + translate(missing, v) + " sobram " + translate(extra, v) + " corpo=[" + n.body().replace("\n", "\\n") + "] real=" + translate(actual, v));
            }
        }
        // 3) backlinks (amostra)
        for (NoteM n : active) {
            JsonNode slice = a.getOk("/v1/notes/" + n.id + "/backlinks?pageSize=50");
            Set<String> got = new TreeSet<>();
            slice.get("items").forEach(i -> got.add(i.get("path").asString()));
            Set<String> want = backlinksByTarget.getOrDefault(n.id, Set.of());
            if (!got.equals(want)) {
                violations.add(label + ": backlinks de " + n.path + ": api=" + got + " oráculo=" + want);
            }
        }
        // 4) nenhum link aponta para a lixeira
        long toTrash = jdbc.queryForObject("select count(*) from note_links l join notes t on t.id = l.target_note_id where l.user_id = ? and l.origin = 'WIKILINK' and t.deleted_at is not null", Long.class, a.id);
        if (toTrash != 0) {
            violations.add(label + ": " + toTrash + " wikilinks apontam para notas na lixeira");
        }
    }

    private String translate(Set<String> items, Vault v) {
        List<String> out = new ArrayList<>();
        for (String item : items) {
            String[] parts = item.split("\\|", -1);
            String last = parts[parts.length - 1];
            NoteM n = v.byId.get(last);
            parts[parts.length - 1] = n == null ? last : "->" + n.path;
            out.add(String.join("|", parts));
        }
        return out.toString();
    }

    // ------------------------------------------------------------------------------------------------ operações

    @Test
    @DisplayName("BUG-QA-08: o nome escrito exatamente igual vence o nome só equivalente (acento/caixa)")
    void correspondenciaExataVence() throws Exception {
        Actor a = w.new Actor("e6exact");
        int wrong = 0;
        for (int i = 0; i < 12; i++) {
            NoteQa.createOk(w, a, "Café" + i + ".md", "com acento");
            String exact = NoteQa.createOk(w, a, "Cafe" + i + ".md", "sem acento");
            String reader = NoteQa.createOk(w, a, "leitor" + i + ".md", "[[Cafe" + i + "]]");
            String target = jdbc.queryForObject("select target_note_id::text from note_links where note_id = ?::uuid", String.class, reader);
            if (!exact.equals(target)) {
                wrong++;
            }
        }
        System.out.println("QA-E6-PROP exato x equivalente: " + wrong + " de 12 resolveram para a nota errada");
        assertThat(wrong).isZero();
    }

    private int silentRepoints;
    private int moves;
    private int rewrittenTotal;

    @Test
    @DisplayName("sementes fixas: wikilinks sempre resolvem como o oráculo; renomear reescreve exatamente os links que apontavam para a nota movida, sem tocar em nenhum outro byte; backlinks consistentes")
    void propriedade() throws Exception {
        List<String> violations = new ArrayList<>();
        Map<String, Integer> statuses = new java.util.TreeMap<>();
        for (long seed : SEEDS) {
            Random r = new Random(seed);
            Actor a = w.new Actor("e6prop" + seed);
            Vault v = new Vault();
            for (int op = 0; op < OPS; op++) {
                String label = "seed " + seed + " op " + op;
                try {
                    String what = step(a, v, r, label, violations, statuses);
                    check(a, v, label + " (" + what + ")", violations);
                } catch (AssertionError | RuntimeException e) {
                    violations.add(label + ": exceção " + e);
                    break;
                }
                if (violations.size() > 25) {
                    break;
                }
            }
        }
        System.out.println("QA-E6-PROP status=" + statuses + " moves=" + moves + " notasReescritas=" + rewrittenTotal + " reapontamentosSilenciosos=" + silentRepoints
                + " violacoes=" + violations.size());
        violations.forEach(x -> System.out.println("QA-E6-PROP VIOLACAO " + x));
        violations.addAll(w.contractViolations);
        assertThat(violations).as("violações").isEmpty();
    }

    private String step(Actor a, Vault v, Random r, String label, List<String> violations, Map<String, Integer> statuses) throws Exception {
        int dice = r.nextInt(100);
        List<NoteM> all = new ArrayList<>(v.byId.values());
        List<NoteM> active = v.active();
        if (dice < 22 || all.size() < 4) {
            String path = r.nextInt(6) == 0 && !all.isEmpty() ? all.get(r.nextInt(all.size())).path : freshPath(r, v, null);
            List<Seg> segs = randomSegs(r, v);
            NoteM n = new NoteM();
            n.path = path;
            n.segs = segs;
            Res res = NoteQa.create(w, a, path, n.body());
            statuses.merge("create " + res.status(), 1, Integer::sum);
            boolean taken = v.activeByKey(path) != null || (all.size() >= 5000);
            if (taken) {
                assertThat(res.status()).as("%s criar %s ocupado", label, path).isEqualTo(422);
                assertThat(res.code()).isEqualTo("NOTE_PATH_TAKEN");
            } else {
                assertThat(res.status()).as("%s criar %s: %s", label, path, res).isEqualTo(201);
                n.id = res.node().get("id").asString();
                n.version = 0;
                v.byId.put(n.id, n);
            }
            return "create";
        }
        if (dice < 40 && !active.isEmpty()) {
            NoteM n = active.get(r.nextInt(active.size()));
            n.segs = randomSegs(r, v);
            n.version = jdbc.queryForObject("select version from notes where id = ?::uuid", Long.class, n.id);
            Res res = NoteQa.edit(w, a, n.id, n.body(), n.version);
            statuses.merge("edit " + res.status(), 1, Integer::sum);
            assertThat(res.status()).as("%s editar: %s", label, res).isEqualTo(200);
            n.version = res.node().get("version").asLong();
            return "edit";
        }
        if (dice < 72 && !active.isEmpty()) {
            return move(a, v, r, label, violations, statuses, active.get(r.nextInt(active.size())));
        }
        if (dice < 84 && !active.isEmpty()) {
            NoteM n = active.get(r.nextInt(active.size()));
            Res res = NoteQa.trash(a, n.id);
            statuses.merge("trash " + res.status(), 1, Integer::sum);
            assertThat(res.status()).as("%s lixeira: %s", label, res).isEqualTo(200);
            n.trashed = true;
            return "trash";
        }
        if (dice < 94) {
            List<NoteM> trashed = all.stream().filter(n -> n.trashed).toList();
            if (!trashed.isEmpty()) {
                NoteM n = trashed.get(r.nextInt(trashed.size()));
                Res res = NoteQa.restore(a, n.id);
                statuses.merge("restore " + res.status(), 1, Integer::sum);
                if (v.activeByKey(n.path) != null) {
                    assertThat(res.status()).as("%s restaurar com caminho ocupado", label).isEqualTo(422);
                    assertThat(res.code()).isEqualTo("NOTE_PATH_TAKEN");
                } else {
                    assertThat(res.status()).as("%s restaurar: %s", label, res).isEqualTo(200);
                    n.trashed = false;
                    n.version = res.node().get("version").asLong();
                }
                return "restore";
            }
        }
        if (dice >= 94) {
            Res res = a.call(org.springframework.http.HttpMethod.DELETE, "/v1/notes/trash", null, null);
            statuses.merge("empty " + res.status(), 1, Integer::sum);
            assertThat(res.status()).as("%s esvaziar", label).isEqualTo(204);
            v.byId.values().removeIf(n -> n.trashed);
            return "empty";
        }
        return "noop";
    }

    private String move(Actor a, Vault v, Random r, String label, List<String> violations, Map<String, Integer> statuses, NoteM m) throws Exception {
        String newPath = r.nextInt(8) == 0 ? v.active().get(r.nextInt(v.active().size())).path : freshPath(r, v, m.id);
        List<NoteM> activeBefore = v.active();
        NoteM clash = v.activeByKey(newPath);
        m.version = jdbc.queryForObject("select version from notes where id = ?::uuid", Long.class, m.id);
        Res res = NoteQa.move(w, a, m.id, newPath, m.version);
        statuses.merge("move " + res.status(), 1, Integer::sum);
        if (clash != null && !clash.id.equals(m.id)) {
            assertThat(res.status()).as("%s mover para ocupado %s", label, newPath).isEqualTo(422);
            assertThat(res.code()).isEqualTo("NOTE_PATH_TAKEN");
            return "move recusado";
        }
        assertThat(res.status()).as("%s mover %s -> %s: %s", label, m.path, newPath, res).isEqualTo(200);
        moves++;
        // links que apontavam para M antes (oráculo), e o índice depois
        Map<String, String> beforeTarget = new HashMap<>();
        for (NoteM n : v.byId.values()) {
            for (int i = 0; i < n.segs.size(); i++) {
                if (n.segs.get(i).kind() == 'L') {
                    beforeTarget.put(n.id + "#" + i, resolve(n.segs.get(i).target(), activeBefore));
                }
            }
        }
        String oldPath = m.path;
        m.path = res.node().get("note").get("path").asString();
        m.version = res.node().get("note").get("version").asLong();
        List<NoteM> activeAfter = v.active();
        Set<String> rewrittenIds = new LinkedHashSet<>();
        res.node().get("rewrittenNotes").forEach(x -> rewrittenIds.add(x.get("id").asString()));
        for (NoteM n : v.byId.values()) {
            // texto esperado como padrão: partes fixas literais e o alvo dos links de M como grupo
            StringBuilder regex = new StringBuilder();
            List<Integer> affected = new ArrayList<>();
            for (int i = 0; i < n.segs.size(); i++) {
                Seg s = n.segs.get(i);
                boolean isAffected = s.kind() == 'L' && m.id.equals(beforeTarget.get(n.id + "#" + i));
                if (isAffected) {
                    affected.add(i);
                }
            }
            String body = jdbc.queryForObject("select body from notes where id = ?::uuid", String.class, n.id);
            if (affected.isEmpty()) {
                if (!body.equals(n.body())) {
                    violations.add(label + ": " + n.path + " NÃO tinha link para a nota movida e mesmo assim mudou: [" + n.body().replace("\n", "\\n") + "] -> [" + body.replace("\n", "\\n") + "]");
                }
                if (rewrittenIds.contains(n.id)) {
                    violations.add(label + ": " + n.path + " listada em rewrittenNotes sem ter link para a nota movida");
                }
                continue;
            }
            // monta o regex a partir do corpo do modelo, trocando o alvo dos links afetados
            String model = n.body();
            int cursor = 0;
            List<Seg> render = n.segs;
            // percorre os segmentos na ordem de renderização
            StringBuilder expectedFixed = new StringBuilder();
            for (int i = 0; i < render.size(); i++) {
                Seg s = render.get(i);
                String piece = s.render();
                String sep = s.kind() == 'F' ? "\n" : (i + 1 < render.size() && render.get(i + 1).kind() != 'F' ? " " : "\n");
                if (affected.contains(i)) {
                    regex.append(Pattern.quote((s.embed() ? "!" : "") + "[[" + s.padL())).append("(?<t").append(i).append(">[^\\[\\]|#]+?)")
                            .append(Pattern.quote((s.section() == null ? "" : "#" + s.section()) + (s.alias() == null ? "" : "|" + s.alias()) + s.padR() + "]]" + sep));
                } else {
                    regex.append(Pattern.quote(piece + sep));
                }
            }
            Matcher match = Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(body);
            if (!match.matches()) {
                violations.add(label + ": " + n.path + " foi reescrita fora do alvo dos links: esperado o padrão do modelo, veio [" + body.replace("\n", "\\n") + "] (modelo [" + model.replace("\n", "\\n") + "])");
                continue;
            }
            for (int i : affected) {
                String newTarget = match.group("t" + i);
                String resolved = resolve(newTarget, activeAfter);
                if (!m.id.equals(resolved)) {
                    violations.add(label + ": " + n.path + " link '" + n.segs.get(i).target() + "' -> '" + newTarget + "' deixou de apontar para a nota movida (" + oldPath + " -> " + m.path + ")");
                }
                n.segs.set(i, n.segs.get(i).withTarget(newTarget));
            }
            if (!rewrittenIds.contains(n.id) && !n.id.equals(m.id) && !body.equals(model)) {
                violations.add(label + ": " + n.path + " mudou mas não está em rewrittenNotes");
            }
            if (!n.id.equals(m.id) && body.equals(model) && rewrittenIds.contains(n.id)) {
                violations.add(label + ": " + n.path + " em rewrittenNotes mas o texto não mudou");
            }
            if (rewrittenIds.contains(n.id) && !n.id.equals(m.id)) {
                rewrittenTotal++;
            }
            if (!n.id.equals(m.id) && !n.trashed) {
                n.version = jdbc.queryForObject("select version from notes where id = ?::uuid", Long.class, n.id);
            } else if (!n.id.equals(m.id)) {
                n.version = jdbc.queryForObject("select version from notes where id = ?::uuid", Long.class, n.id);
            }
        }
        // versões das notas reescritas mudam no banco; sincroniza o modelo
        for (NoteM n : v.byId.values()) {
            n.version = jdbc.queryForObject("select version from notes where id = ?::uuid", Long.class, n.id);
        }
        // links que NÃO apontavam para M e passaram a apontar (empate de caminho curto): registro
        for (NoteM n : v.byId.values()) {
            for (int i = 0; i < n.segs.size(); i++) {
                String before = beforeTarget.get(n.id + "#" + i);
                if (n.segs.get(i).kind() == 'L' && before != null && !before.equals(m.id) && m.id.equals(resolve(n.segs.get(i).target(), activeAfter))) {
                    silentRepoints++;
                    System.out.println("QA-E6-PROP reapontamento silencioso: " + n.path + " [[" + n.segs.get(i).target() + "]] apontava para " + v.byId.get(before).path + " e agora aponta para " + m.path);
                }
            }
        }
        // o corpo da própria nota movida (links para ela mesma) segue o mesmo padrão: o modelo foi atualizado acima quando afetado
        return "move " + oldPath + " -> " + m.path;
    }
}
