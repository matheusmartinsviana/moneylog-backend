package com.moneylog.core.importing.app.gateways;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.importing.domain.CategorySuggester.HistoryEntry;
import com.moneylog.core.importing.domain.CategorySuggester.Rule;
import com.moneylog.core.importing.domain.ImportDirection;
import com.moneylog.core.importing.parser.ColumnMapping;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

/** Histórico para sugerir categoria, regras aprendidas e mapeamentos de colunas salvos. */
public interface ImportHistoryGateway {

    /** Regra aprendida e confirmada. */
    record StoredRule(UUID id, ImportDirection direction, String normalizedDescription, UUID categoryId, String method) {
    }

    /** As movimentações ativas mais recentes com categoria (até {@code limit}), com a descrição ainda crua (o caso de uso normaliza). */
    List<HistoryRow> recentCategorized(UUID userId, int limit);

    record HistoryRow(ImportDirection direction, String description, UUID categoryId, String method) {
        public HistoryEntry toEntry(String normalized) {
            return new HistoryEntry(direction, normalized, categoryId, method);
        }
    }

    List<Rule> rules(UUID userId);

    Slice<StoredRule> listRules(UUID userId, PageQuery page);

    /** Cria ou atualiza a regra (uma por usuário, direção e descrição normalizada). */
    void upsertRule(UUID userId, UUID id, ImportDirection direction, String normalizedDescription, UUID categoryId, String method);

    /** {@code false} se a regra não existe (ou é de outro usuário). */
    boolean deleteRule(UUID userId, UUID ruleId);

    Optional<ColumnMapping> findMapping(UUID userId, UUID sourceId);

    void saveMapping(UUID userId, UUID sourceId, ColumnMapping mapping);
}
