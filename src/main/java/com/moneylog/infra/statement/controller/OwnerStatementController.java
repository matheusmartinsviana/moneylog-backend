package com.moneylog.infra.statement.controller;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.moneylog.core.finance.app.statement.GetOwnerStatementUseCase;
import com.moneylog.core.finance.app.statement.GetOwnerStatementUseCase.GetOwnerStatementQuery;
import com.moneylog.core.finance.domain.OwnerStatement;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.sk.time.FinancialDates;
import com.moneylog.infra.exception.InvalidPathValueException;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.web.QueryMonths;

@RestController
@RequestMapping("/v1/owner-statement")
public class OwnerStatementController {

    public static final String OWNER_STATEMENT_PERIOD_INVALID = "OWNER_STATEMENT_PERIOD_INVALID";

    private final GetOwnerStatementUseCase statement;

    public OwnerStatementController(GetOwnerStatementUseCase statement) {
        this.statement = statement;
    }

    @GetMapping
    public OwnerStatementResponse get(@CurrentUser UUID userId, @RequestParam(required = false) String from,
                                      @RequestParam(required = false) String months, @RequestParam(required = false) String asOf) {
        Competence parsedFrom = from == null ? null : QueryMonths.parse(from, OWNER_STATEMENT_PERIOD_INVALID, "from");
        Integer parsedMonths = parseMonths(months);
        if (parsedFrom != null && !FinancialDates.isInWindow(parsedFrom.plusMonths(parsedMonths == null ? 0 : parsedMonths - 1).lastDay())) {
            throw new InvalidPathValueException(OWNER_STATEMENT_PERIOD_INVALID, "months", "A janela passa de 2100-12.");
        }
        return OwnerStatementResponse.of(statement.handle(new GetOwnerStatementQuery(userId, parsedFrom, parsedMonths, parseAsOf(asOf))));
    }

    private static Integer parseMonths(String months) {
        if (months == null) {
            return null;
        }
        try {
            int value = Integer.parseInt(months.strip());
            if (value >= OwnerStatement.MIN_MONTHS && value <= OwnerStatement.MAX_MONTHS) {
                return value;
            }
        } catch (NumberFormatException e) {
            throw invalid("months", "'months' deve ser um inteiro de 1 a 12.");
        }
        throw invalid("months", "'months' deve ser um inteiro de 1 a 12.");
    }

    private static LocalDate parseAsOf(String asOf) {
        if (asOf == null) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(asOf.strip());
            if (FinancialDates.isInWindow(date)) {
                return date;
            }
        } catch (DateTimeParseException e) {
            throw invalid("asOf", "'asOf' deve ser uma data AAAA-MM-DD.");
        }
        throw invalid("asOf", "'asOf' fora da janela de 2000-01-01 a 2100-12-31.");
    }

    private static InvalidPathValueException invalid(String field, String message) {
        return new InvalidPathValueException(OWNER_STATEMENT_PERIOD_INVALID, field, message);
    }
}
