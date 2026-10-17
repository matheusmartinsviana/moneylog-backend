package com.moneylog.infra.income.gateways;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.income.app.gateways.TaxTableGateway;
import com.moneylog.core.income.domain.TaxBracket;
import com.moneylog.core.income.domain.TaxKind;
import com.moneylog.core.income.domain.TaxMethod;
import com.moneylog.core.income.domain.TaxReduction;
import com.moneylog.core.income.domain.TaxSource;
import com.moneylog.core.income.domain.TaxTable;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

public class TaxTableGatewayImpl implements TaxTableGateway {

    private static final String SELECT = """
            select id, kind, version, effective_from, effective_to, basis, method, contribution_ceiling_cents,
                   dependent_deduction_cents, simplified_limit_cents, reduction_full_up_to_cents, reduction_full_cents,
                   reduction_phase_out_cents, reduction_constant_cents, reduction_coefficient_micros
            from tax_tables\s
            """;

    private final JdbcTemplate jdbc;

    public TaxTableGatewayImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TaxTable> findEffective(TaxKind kind, LocalDate reference) {
        List<TaxTable> found = load(SELECT + """
                 where kind = ? and effective_from <= ? and (effective_to is null or effective_to >= ?)
                 order by effective_from desc, version desc limit 1""", kind.name(), Date.valueOf(reference), Date.valueOf(reference));
        return found.stream().findFirst();
    }

    @Override
    public List<TaxTable> listForYear(int year) {
        LocalDate first = LocalDate.of(year, 1, 1);
        LocalDate last = LocalDate.of(year, 12, 31);
        List<TaxTable> all = load(SELECT + """
                 where effective_from <= ? and (effective_to is null or effective_to >= ?)
                 order by kind, effective_from, version desc""", Date.valueOf(last), Date.valueOf(first));
        Map<String, TaxTable> latest = new LinkedHashMap<>();
        for (TaxTable table : all) {
            latest.putIfAbsent(table.kind() + "|" + table.effectiveFrom(), table);
        }
        return new ArrayList<>(latest.values());
    }

    private List<TaxTable> load(String sql, Object... args) {
        List<Row> rows = jdbc.query(sql, (rs, n) -> new Row(rs.getObject("id", UUID.class), TaxKind.valueOf(rs.getString("kind")),
                rs.getInt("version"), rs.getDate("effective_from").toLocalDate(),
                rs.getDate("effective_to") == null ? null : rs.getDate("effective_to").toLocalDate(),
                TaxMethod.valueOf(rs.getString("method")), nullableLong(rs, "contribution_ceiling_cents"),
                nullableLong(rs, "dependent_deduction_cents"), nullableLong(rs, "simplified_limit_cents"),
                nullableLong(rs, "reduction_full_up_to_cents"), nullableLong(rs, "reduction_full_cents"),
                nullableLong(rs, "reduction_phase_out_cents"), nullableLong(rs, "reduction_constant_cents"),
                nullableLong(rs, "reduction_coefficient_micros")), args);
        List<TaxTable> tables = new ArrayList<>();
        for (Row row : rows) {
            tables.add(row.toTable(brackets(row.id()), sources(row.id())));
        }
        return tables;
    }

    private List<TaxBracket> brackets(UUID tableId) {
        CurrencyCode brl = CurrencyCode.BRL;
        return jdbc.query("select from_cents, up_to_cents, rate_bps, deduction_cents from tax_table_brackets where tax_table_id = ? order by position",
                (rs, n) -> new TaxBracket(Money.ofCents(rs.getLong("from_cents"), brl),
                        nullableLong(rs, "up_to_cents") == null ? null : Money.ofCents(rs.getLong("up_to_cents"), brl),
                        rs.getInt("rate_bps"), Money.ofCents(rs.getLong("deduction_cents"), brl)), tableId);
    }

    private List<TaxSource> sources(UUID tableId) {
        return jdbc.query("select agency, norm, url, consulted_on from tax_table_sources where tax_table_id = ? order by position",
                (rs, n) -> new TaxSource(rs.getString("agency"), rs.getString("norm"), rs.getString("url"),
                        rs.getDate("consulted_on").toLocalDate()), tableId);
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record Row(UUID id, TaxKind kind, int version, LocalDate from, LocalDate to, TaxMethod method, Long ceiling,
                       Long dependent, Long simplified, Long fullUpTo, Long full, Long phaseOut, Long constant, Long coefficient) {

        TaxTable toTable(List<TaxBracket> brackets, List<TaxSource> sources) {
            CurrencyCode brl = CurrencyCode.BRL;
            TaxReduction reduction = fullUpTo == null ? null : new TaxReduction(Money.ofCents(fullUpTo, brl), Money.ofCents(full, brl),
                    Money.ofCents(phaseOut, brl), Money.ofCents(constant, brl), coefficient);
            return new TaxTable(kind, version, from, to, kind.basis(), method, brackets,
                    ceiling == null ? null : Money.ofCents(ceiling, brl), dependent == null ? null : Money.ofCents(dependent, brl),
                    simplified == null ? null : Money.ofCents(simplified, brl), reduction, sources);
        }
    }
}
