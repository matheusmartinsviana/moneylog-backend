package com.moneylog.core.finance.app.transactions.usecase;

import java.time.Instant;
import java.util.UUID;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.finance.app.transactions.AccountTransactionFactory;
import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.finance.app.transactions.TransactionDetailsAssembler;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.app.gateways.TransactionRevisionGateway;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionEditing;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotEditableException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotFoundException;

/**
 * Edição (T27, S-68, S-73, S-81). A substituta é montada pelas mesmas fábricas da criação; a ordem de gravação é:
 * linha da movimentação (a checagem de versão e o lock da linha: duas edições concorrentes com a mesma versão
 * serializam aqui e a segunda recebe 409) → revisão do estado anterior → lançamentos antigos inativos ({@code EDIT}) →
 * lançamentos novos. A revisão sempre precede a troca dos lançamentos. Qualquer recusa de regra acontece antes de gravar
 * alguma coisa.
 */
public class UpdateTransactionUseCaseImpl implements UpdateTransactionUseCase {

    private final TransactionGateway transactions;
    private final AccountGateway accounts;
    private final AccountTransactionFactory factory;
    private final TransactionRevisionGateway revisions;
    private final LedgerWriteGateway ledger;
    private final TransactionDetailsAssembler assembler;
    private final TimeProvider time;

    public UpdateTransactionUseCaseImpl(TransactionGateway transactions, AccountGateway accounts, AccountTransactionFactory factory,
                                        TransactionRevisionGateway revisions, LedgerWriteGateway ledger,
                                        TransactionDetailsAssembler assembler, TimeProvider time) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.factory = factory;
        this.revisions = revisions;
        this.ledger = ledger;
        this.assembler = assembler;
        this.time = time;
    }

    @Override
    public TransactionDetails handle(UpdateTransactionCommand command) {
        CreateTransactionCommand body = command.body();
        UUID userId = body.userId();
        // B1: trava a linha antes de ler a soma dos reembolsos (despesa) ou o saldo "já reembolsado" (reembolso).
        transactions.lockForUpdate(userId, command.transactionId());
        Transaction current = transactions.findById(userId, command.transactionId()).map(Persisted::value)
                .orElseThrow(() -> new TransactionNotFoundException(command.transactionId()));
        if (!current.isActive()) {
            throw new TransactionNotEditableException("ela está estornada.");
        }
        if (body.type() != current.type()) {
            throw new TransactionNotEditableException("o tipo não muda (é " + current.type() + ", veio " + body.type() + ").");
        }
        if (current.cardId() != null && current.type() != TransactionType.CARD_PURCHASE) {
            // crédito no cartão e pagamento de fatura: não se editam (estorne e lance de novo, S-122)
            throw new TransactionNotEditableException("o crédito no cartão e o pagamento de fatura não são editáveis; estorne e lance de novo.");
        }
        if (current.debtId() != null) {
            // emprestar, receber e pagar mexem na dívida (origem e quitações): não se editam; o caminho é estornar e lançar de novo
            throw new TransactionNotEditableException("a movimentação de dívida (emprestar, receber, pagar) não é editável; "
                    + "estorne (as guardas da dívida valem) e lance de novo.");
        }
        if (current.version() != command.version()) {
            throw new VersionConflictException(command.version(), current.version());
        }
        if (current.type() == TransactionType.CARD_PURCHASE) {
            return editCardPurchase(command, current);
        }
        factory.lockFor(body, current);
        ensureAccountsUsable(userId, current);
        Money activeRefunds = current.type() == TransactionType.EXPENSE ? transactions.sumActiveRefundsOf(userId, current.id())
                : Money.zero(CurrencyCode.BRL);
        Transaction replacement = factory.build(body, current.id(), current.origin(), current);
        Transaction edited = TransactionEditing.replace(current, replacement, activeRefunds);

        Instant now = time.now();
        Persisted<Transaction> saved = transactions.update(edited);
        revisions.save(userId, current, Math.toIntExact(current.version()) + 1, now);
        ledger.deactivateBySource(userId, PostingSourceType.TRANSACTION, current.id(), EDIT_REASON, now);
        ledger.append(userId, edited.entries());
        return assembler.assemble(saved);
    }

    /** Conta arquivada (S-73): editar mudaria o saldo de uma conta que só arquiva zerada; o caminho é desarquivar. */
    private void ensureAccountsUsable(UUID userId, Transaction current) {
        for (UUID accountId : new UUID[] {current.accountId(), current.counterpartAccountId()}) {
            if (accountId != null) {
                Account account = accounts.findById(userId, accountId).map(Persisted::value)
                        .orElseThrow(() -> new AccountNotFoundException(accountId));
                account.ensureCanReceiveTransactions();
            }
        }
    }

    /**
     * Edição limitada da compra no cartão (S-121): só descrição, categoria e observação. As parcelas e as faturas não mudam;
     * os lançamentos são regravados na categoria nova (os das parcelas canceladas seguem inativos). Mesma ordem de gravação
     * da edição comum: linha da movimentação (versão) → revisão do estado anterior → lançamentos antigos inativos →
     * lançamentos novos. Travas: a movimentação (já travada) → o cartão.
     */
    private TransactionDetails editCardPurchase(UpdateTransactionCommand command, Transaction current) {
        CreateTransactionCommand body = command.body();
        UUID userId = body.userId();
        if (!(body instanceof CreateTransactionCommand.CardPurchase purchase)) {
            throw new TransactionNotEditableException("o corpo não é de uma compra no cartão.");
        }
        factory.lockFor(body, current);
        Money activeRefunds = transactions.sumActiveRefundsOf(userId, current.id());
        Transaction replacement = factory.buildCardPurchaseEdit(purchase, current, activeRefunds);
        Instant now = time.now();
        Persisted<Transaction> saved = transactions.update(replacement);
        revisions.save(userId, current, Math.toIntExact(current.version()) + 1, now);
        ledger.deactivateBySource(userId, PostingSourceType.TRANSACTION, current.id(), EDIT_REASON, now);
        ledger.append(userId, replacement.entries().stream().filter(com.moneylog.core.sk.ledger.LedgerEntry::active).toList());
        return assembler.assemble(saved);
    }
}
