package com.moneylog.util;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionState;

public final class SqlFixtures {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-10-10T12:00:00Z"));
    private static final Timestamp NEWER_THAN_ANY_OWNER = Timestamp.from(Instant.parse("2099-01-01T00:00:00Z"));

    private SqlFixtures() {
    }

    public static void insertUser(JdbcTemplate jdbc, UUID id, String email) {
        jdbc.update("INSERT INTO users (id, email, created_at, updated_at) VALUES (?, ?, ?, ?)", id, email,
                NEWER_THAN_ANY_OWNER, NEWER_THAN_ANY_OWNER);
    }

    public static void insertAccount(JdbcTemplate jdbc, Account a) {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, name, type, institution, opening_balance_cents, currency, opening_date,
                                      status, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, 'BRL', ?, ?, ?, ?, ?)""", a.id(), a.userId(), a.name(), a.type().name(), a.institution(),
                a.openingBalance().cents(), Date.valueOf(a.openingDate()), a.status().name(), NOW, NOW, a.version());
    }

    public static void insertCategory(JdbcTemplate jdbc, Category c) {
        jdbc.update("""
                INSERT INTO categories (id, user_id, name, kind, system, archived, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", c.id(), c.userId(), c.name(), c.kind().name(), c.isSystem(),
                c.isArchived(), NOW, NOW, c.version());
    }

    public static void insertTransactionRow(JdbcTemplate jdbc, Transaction t) {
        TransactionState s = t.state();
        String direction = s.investmentDirection() != null ? s.investmentDirection().name()
                : s.adjustmentDirection() == null ? null : s.adjustmentDirection().name();
        jdbc.update("""
                INSERT INTO transactions (id, user_id, type, status, amount_cents, currency, transaction_date, description, notes,
                    payment_method, account_id, counterpart_account_id, category_id, refund_of_id, direction, adjustment_kind,
                    reason, source, void_reason, voided_at, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, 'BRL', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL', ?, ?, ?, ?, ?)""",
                s.id(), s.userId(), s.type().name(), s.status().name(), s.amount().cents(), Date.valueOf(s.date()), s.description(),
                s.notes(), s.paymentMethod() == null ? null : s.paymentMethod().name(), s.accountId(), s.counterpartAccountId(),
                s.categoryId(), s.refundOfId(), direction, s.adjustmentKind() == null ? null : s.adjustmentKind().name(), s.reason(),
                s.voidReason(), s.voidedAt() == null ? null : Timestamp.from(s.voidedAt()), NOW, NOW, s.version());
    }
}
