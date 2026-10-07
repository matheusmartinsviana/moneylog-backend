package com.moneylog.core.transaction.app.gateways;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.transaction.app.query.TransactionFilter;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.transaction.domain.Transaction;

/**
 * Persistência de movimentações (a linha de {@code transactions}; os lançamentos vão por {@code LedgerWriteGateway}).
 * Todo método filtra pelo {@code userId}.
 */
public interface TransactionGateway {

    /** Insere uma movimentação nova e devolve a versão gravada. */
    Persisted<Transaction> insert(Transaction transaction);

    /**
     * Grava o estado atual de uma movimentação que já existe (edição e estorno) e devolve a versão gravada, com a
     * {@code version} incrementada.
     *
     * @throws com.moneylog.core.sk.exception.VersionConflictException se a versão do agregado não é a gravada
     * @throws com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotFoundException se não existe
     */
    Persisted<Transaction> update(Transaction transaction);

    /**
     * Trava a linha da movimentação ({@code SELECT ... FOR UPDATE}) até o fim da transação corrente (B1). Serializa as
     * regras do reembolso (teto, estorno e edição da despesa original), que são "ler a soma e depois gravar". Deve ser
     * chamado <b>antes</b> de ler a movimentação ou a soma dos reembolsos, para a leitura seguinte já enxergar o que a
     * transação concorrente confirmou. Não faz nada se a movimentação não existe ou é de outro usuário.
     */
    void lockForUpdate(UUID userId, UUID id);

    /**
     * Trava (em ordem de id) as linhas de <b>todas</b> as movimentações de um cartão até o fim da transação: a primeira trava
     * da mudança de fechamento/vencimento, que regrava as faturas das parcelas e dos créditos. A ordem global é movimentações
     * → cartão → contas; sem travar as linhas antes do cartão, um estorno (que trava a linha e depois o cartão) e a mudança
     * de dias (que tem o cartão e quer a linha) esperariam um pelo outro. Chame <b>antes</b> de travar o cartão e de ler a
     * cadeia. Não faz nada se o cartão não tem movimentação.
     */
    void lockAllOfCard(UUID userId, UUID cardId);

    /** Reidrata a movimentação (com os lançamentos) ou vazio se não existe ou é de outro usuário. */
    Optional<Persisted<Transaction>> findById(UUID userId, UUID id);

    /**
     * Página de movimentações que casam com o filtro, em {@code date desc, created_at desc, id desc}, <b>sem</b>
     * {@code COUNT} (busca {@code pageSize + 1}). Só as linhas da página: os totais saem das calculadoras do
     * {@code core.finance} (S-74, S-79), nunca daqui. A regra de "casa com o filtro" é a de {@link TransactionFilter}.
     */
    Slice<Persisted<Transaction>> list(UUID userId, TransactionFilter filter, PageQuery page);

    /**
     * Todas as movimentações de um cartão, ativas e estornadas, com os lançamentos: a entrada de
     * {@code CardInvoices.chainFor}, a única forma de montar a cadeia de faturas (S-135). Só as do cartão, não o
     * histórico inteiro do usuário.
     */
    List<Transaction> findAllOfCard(UUID userId, UUID cardId);

    /** Σ dos reembolsos <b>ativos</b> ligados a uma despesa (alimenta o teto do reembolso, S-9). */
    Money sumActiveRefundsOf(UUID userId, UUID originalId);
}
