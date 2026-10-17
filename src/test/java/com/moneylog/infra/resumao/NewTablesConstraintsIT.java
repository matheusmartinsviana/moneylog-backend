package com.moneylog.infra.resumao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

@DisplayName("Restrições das tabelas novas (V9.00 a V9.21): isolamento por usuário nas FKs compostas, checks de forma e fontes do razão")
class NewTablesConstraintsIT extends ResumaoItBase {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-10-15T12:00:00Z"));

    private UUID otherUser() {
        UUID other = UUID.randomUUID();
        committed(() -> jdbc.update("insert into users (id,email,created_at,updated_at) values (?,?,?,?)", other, "fk-" + other + "@example.com", NOW, NOW));
        return other;
    }

    private void rejects(String sql, Object... args) {
        assertThatThrownBy(() -> committedUpdate(sql, args)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void committedUpdate(String sql, Object... args) {
        committed(() -> jdbc.update(sql, args));
    }

    @Test
    @DisplayName("rateio de compra e de recorrência: pessoa de outro usuário, bps com amount, dois donos e a mesma pessoa duas vezes são recusados pelo banco")
    void rateios() throws Exception {
        basicWorld();
        UUID stranger = otherUser();
        UUID strangerPerson = UUID.randomUUID();
        committedUpdate("insert into persons (id,user_id,name,archived,created_at,updated_at) values (?,?,'Alheia',false,?,?)", strangerPerson, stranger, NOW, NOW);
        String tx = created(sharedPurchase(cardA, "100.00", 1, "2026-10-05", null, null)).get("id").asString();
        String shareSql = "insert into card_purchase_shares (id,user_id,transaction_id,position,split_mode,person_id,bps,amount_cents,currency,created_at) "
                + "values (?,?,?::uuid,?,?,?,?,?,'BRL',?)";

        rejects(shareSql, UUID.randomUUID(), userId, tx, 1, "BPS", strangerPerson, 5000, null, NOW);
        rejects(shareSql, UUID.randomUUID(), userId, tx, 1, "BPS", UUID.fromString(ana), 5000, 100L, NOW);
        rejects(shareSql, UUID.randomUUID(), userId, tx, 1, "EQUAL", UUID.fromString(ana), 5000, null, NOW);
        rejects(shareSql, UUID.randomUUID(), userId, tx, 1, "AMOUNT", UUID.fromString(ana), null, 0L, NOW);
        committedUpdate(shareSql, UUID.randomUUID(), userId, tx, 1, "BPS", UUID.fromString(ana), 5000, null, NOW);
        rejects(shareSql, UUID.randomUUID(), userId, tx, 2, "BPS", UUID.fromString(ana), 5000, null, NOW);
        committedUpdate(shareSql, UUID.randomUUID(), userId, tx, 3, "BPS", null, 5000, null, NOW);
        rejects(shareSql, UUID.randomUUID(), userId, tx, 4, "BPS", null, 5000, null, NOW);
        rejects(shareSql, UUID.randomUUID(), stranger, tx, 5, "BPS", strangerPerson, 5000, null, NOW);
    }

    @Test
    @DisplayName("cota de item de imóvel (V9.21): linha de outro usuário apontando para item alheio é recusada; cláusulas só 0 a 10000")
    void cotasDeImovel() throws Exception {
        basicWorld();
        UUID stranger = otherUser();
        String commitment = createdBody(post("/v1/commitments", "{\"kind\":\"LONG_TERM_PROJECT\",\"name\":\"Casa\",\"total\":\"1000.00\",\"schedule\":["
                + "{\"label\":\"P1\",\"type\":\"INSTALLMENT\",\"dueDate\":\"2026-12-10\",\"amount\":\"1000.00\",\"paid\":false}]}")).get("id").asString();

        rejects("insert into tracked_commitment_item_shares (commitment_id,user_id,position,share_order,person_id,amount_cents) values (?::uuid,?,1,0,null,1000)",
                commitment, stranger);
        committedUpdate("insert into tracked_commitment_item_shares (commitment_id,user_id,position,share_order,person_id,amount_cents) values (?::uuid,?,1,0,null,1000)",
                commitment, userId);
        rejects("update tracked_commitments set late_fee_bps = 10001, monthly_interest_bps = 1, termination_retention_bps = 1 where id = ?::uuid", commitment);
        rejects("insert into tracked_commitment_item_shares (commitment_id,user_id,position,share_order,person_id,amount_cents) values (?::uuid,?,1,1,null,0)",
                commitment, userId);
    }

    @Test
    @DisplayName("ativos: FGTS só RESERVED, reserva só com AVAILABLE, saldo e depósito não negativos, histórico só do próprio usuário; tabelas fiscais: basis e método amarrados ao tipo")
    void ativosEFiscais() throws Exception {
        basicWorld();
        UUID stranger = otherUser();
        String asset = "insert into assets (id,user_id,kind,name,balance_cents,balance_date,currency,availability,counts_as_reserve,archived,created_at,updated_at,version) "
                + "values (?,?,?,?,?,'2026-10-10','BRL',?,?,false,?,?,0)";
        UUID ok = UUID.randomUUID();

        rejects(asset, UUID.randomUUID(), userId, "FGTS", "FGTS", 100L, "AVAILABLE", false, NOW, NOW);
        rejects(asset, UUID.randomUUID(), userId, "OTHER", "Reserva", 100L, "RESERVED", true, NOW, NOW);
        rejects(asset, UUID.randomUUID(), userId, "OTHER", "Negativo", -1L, "AVAILABLE", false, NOW, NOW);
        rejects(asset, UUID.randomUUID(), userId, "OTHER", " ", 1L, "AVAILABLE", false, NOW, NOW);
        committedUpdate(asset, ok, userId, "OTHER", "Poupanca extra", 100L, "AVAILABLE", true, NOW, NOW);
        rejects("insert into asset_balances (id,user_id,asset_id,balance_cents,currency,balance_date,recorded_at) values (?,?,?,100,'BRL','2026-10-10',?)",
                UUID.randomUUID(), stranger, ok, NOW);
        rejects("insert into tax_tables (id,kind,version,effective_from,basis,method) values (?,'INSS',1,DATE '2031-01-01','PAYMENT_DATE','PROGRESSIVE_BY_BAND')", UUID.randomUUID());
        rejects("insert into tax_tables (id,kind,version,effective_from,basis,method) values (?,'IRRF',1,DATE '2031-01-01','PAYMENT_DATE','PROGRESSIVE_BY_BAND')", UUID.randomUUID());
        rejects("insert into tax_tables (id,kind,version,effective_from,effective_to,basis,method) values (?,'INSS',1,DATE '2031-01-01',DATE '2030-12-31','COMPETENCE','PROGRESSIVE_BY_BAND')",
                UUID.randomUUID());
        rejects("insert into income_profiles (id,user_id,source,gross_cents,currency,dependents,advance_day,advance_bps,balance_day,created_at,updated_at,version) "
                + "values (?,?,'Fonte',0,'BRL',0,null,null,1,?,?,0)", UUID.randomUUID(), userId, NOW, NOW);
        rejects("insert into income_profiles (id,user_id,source,gross_cents,currency,dependents,advance_day,advance_bps,balance_day,created_at,updated_at,version) "
                + "values (?,?,'Fonte',100,'BRL',0,null,null,29,?,?,0)", UUID.randomUUID(), userId, NOW, NOW);
        assertThat(Map.of("ok", ok)).isNotEmpty();
    }

    @Test
    @DisplayName("fontes de posting da V9.08 e V9.20 aceitas (30+ caracteres) e fonte inventada recusada; moeda diferente de BRL recusada; posting zerado recusado")
    void fontesDoRazao() throws Exception {
        basicWorld();
        String posting = "insert into ledger_postings (id,user_id,source_type,source_id,book_type,book_id,competence,effective_date,amount_cents,currency,active,created_at,updated_at) "
                + "values (?,?,?,?,'EQUITY',null,'2026-10','2026-10-15',?,?,true,?,?)";
        for (String source : new String[] {"THIRD_PARTY_RECEIPT", "THIRD_PARTY_RECEIPT_IN_BALANCE", "THIRD_PARTY_REFUND_ADJUSTMENT", "SHARE_PAYABLE"}) {
            UUID sourceId = UUID.randomUUID();
            committedUpdate(posting, UUID.randomUUID(), userId, source, sourceId, 1L, "BRL", NOW, NOW);
            committedUpdate(posting, UUID.randomUUID(), userId, source, sourceId, -1L, "BRL", NOW, NOW);
        }
        rejects(posting, UUID.randomUUID(), userId, "OUTRA_FONTE", UUID.randomUUID(), 1L, "BRL", NOW, NOW);
        rejects(posting, UUID.randomUUID(), userId, "SHARE_PAYABLE", UUID.randomUUID(), 1L, "USD", NOW, NOW);
        rejects(posting, UUID.randomUUID(), userId, "SHARE_PAYABLE", UUID.randomUUID(), 0L, "BRL", NOW, NOW);
    }

    @Test
    @DisplayName("perfil de renda: adiantamento com dia e sem percentual (ou o contrário) é recusado pelo banco, não só pelo domínio (a CHECK da V9.05 trata NULL como verdadeiro)")
    void adiantamentoMeioPreenchido() throws Exception {
        basicWorld();
        String sql = "insert into income_profiles (id,user_id,source,gross_cents,currency,dependents,advance_day,advance_bps,balance_day,created_at,updated_at,version) "
                + "values (?,?,'Fonte',100,'BRL',0,?,?,1,?,?,0)";

        rejects(sql, UUID.randomUUID(), userId, 16, null, NOW, NOW);
        rejects(sql, UUID.randomUUID(), userId, null, 4000, NOW, NOW);
    }
}
