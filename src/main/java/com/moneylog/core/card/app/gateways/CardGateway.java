package com.moneylog.core.card.app.gateways;

import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.card.domain.CardStatus;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Persisted;

/**
 * Persistência de cartões (a linha de {@code credit_cards} e as regras de fechamento/vencimento, gravadas juntas).
 * Todo método filtra pelo {@code userId}; cartão de outro usuário não existe.
 */
public interface CardGateway {

    /**
     * Insere um cartão novo ou grava a mudança de um existente (detalhes, status e regras de fechamento), devolvendo o
     * cartão com a versão gravada. Gravar sempre avança a versão.
     *
     * @throws com.moneylog.core.sk.exception.VersionConflictException se outra requisição alterou antes
     */
    Persisted<CreditCard> save(CreditCard card);

    /**
     * Trava a linha do cartão ({@code SELECT ... FOR UPDATE}) até o fim da transação (ADR-006 §5). A cadeia de faturas é
     * um agregado do cartão: toda escrita nela (compra, pagamento, estorno, cancelamento, mudança de dias) trava aqui
     * antes de ler o razão. Ordem global de travas: movimentações → cartão → contas por id. Não faz nada se o cartão
     * não existe ou é de outro usuário.
     */
    void lockForUpdate(UUID userId, UUID id);

    /**
     * Descarta o que esta transação já leu do cartão e das movimentações (B1): quem leu o mundo <b>antes</b> de obter a
     * trava (por exemplo para descobrir de que cartão é uma fatura) chama isto logo depois das travas, para a leitura
     * seguinte ver o que outra transação confirmou enquanto esperava, em vez de reaproveitar o estado velho já carregado.
     * Grava o que estiver pendente antes de descartar.
     */
    void discardReadState();

    /** Fechamento e vencimento atuais (da regra mais recente) de um cartão. */
    record BillingDays(int closingDay, int dueDay) {
    }

    /**
     * Os dias atuais do cartão <b>sem carregar o agregado</b>: serve para decidir o caminho do {@code PUT} (mudança de dias
     * trava as movimentações antes do cartão) sem deixar um cartão possivelmente velho na sessão, que a leitura feita depois
     * da trava reaproveitaria. Vazio se o cartão não existe ou é de outro usuário.
     */
    Optional<BillingDays> currentBillingDays(UUID userId, UUID id);

    Optional<Persisted<CreditCard>> findById(UUID userId, UUID id);

    /** Ordem {@code name asc}; {@code status} nulo = todos. Sem {@code COUNT}. */
    Slice<Persisted<CreditCard>> list(UUID userId, CardStatus status, PageQuery page);

    /**
     * Alinha as linhas de {@code credit_card_invoices} do cartão ao calendário atual (ADR-006 §7), na transação da mudança
     * de dias: as linhas cujo fechamento continua no calendário têm {@code period_start}, {@code due_date} e
     * {@code reference_month} atualizados (são cache de exibição; a identidade é o fechamento) e as que o calendário novo
     * não tem mais e <b>nenhuma</b> movimentação referencia (nem parcela ou crédito inativos) são removidas. Linha fora do
     * calendário que ainda tem referência é inconsistência (nunca silenciada): {@code IllegalStateException}, rollback.
     * Chame depois de gravar o cartão e de regravar as movimentações.
     */
    void refreshInvoiceRows(UUID userId, CreditCard card);
}
