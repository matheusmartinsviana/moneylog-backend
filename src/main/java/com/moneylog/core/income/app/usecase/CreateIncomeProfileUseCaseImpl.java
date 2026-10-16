package com.moneylog.core.income.app.usecase;

import java.util.UUID;

import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.income.app.IncomeProfileSpec;
import com.moneylog.core.income.app.gateways.IncomeProfileGateway;
import com.moneylog.core.income.domain.IncomeProfile;
import com.moneylog.core.income.domain.PayrollDeduction;
import com.moneylog.core.income.exception.IncomeExceptions.IncomeProfileNotFoundException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.IdempotentClaim;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;

public class CreateIncomeProfileUseCaseImpl implements CreateIncomeProfileUseCase {

    private final IncomeProfileGateway profiles;
    private final DebtGateway debts;
    private final IdempotencyGateway idempotency;
    private final IdGenerator ids;
    private final TimeProvider time;

    public CreateIncomeProfileUseCaseImpl(IncomeProfileGateway profiles, DebtGateway debts, IdempotencyGateway idempotency,
                                          IdGenerator ids, TimeProvider time) {
        this.profiles = profiles;
        this.debts = debts;
        this.idempotency = idempotency;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public Versioned<IncomeProfile> handle(CreateIncomeProfileCommand command) {
        UUID profileId = ids.next();
        if (command.idempotencyKey() != null) {
            String hash = hashOf(command.spec());
            Claim existing = IdempotentClaim.claimOrFind(idempotency, command.userId(), IDEMPOTENCY_SCOPE, command.idempotencyKey(), hash,
                    profileId, time.now());
            if (existing != null) {
                IdempotentClaim.requireSameCommand(existing, hash);
                return profiles.findById(command.userId(), existing.resourceId())
                        .orElseThrow(() -> new IncomeProfileNotFoundException(existing.resourceId()));
            }
        }
        IncomeProfileSpec spec = command.spec();
        IncomeProfile profile = IncomeProfile.create(profileId, command.userId(), spec.source(), spec.gross(), spec.dependents(),
                spec.advance(), spec.balanceDayOfMonth(), spec.deductions(), spec.thirteenth());
        profile.defineProvenance(spec.provenance());
        IncomeDebts.linkPayrollDebts(debts, command.userId(), spec);
        return profiles.insert(profile);
    }

    static String hashOf(IncomeProfileSpec spec) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("source=").append(spec.source() == null ? "-" : spec.source().strip())
                .append(";gross=").append(spec.gross() == null ? "-" : spec.gross().toPlainString())
                .append(";dependents=").append(spec.dependents())
                .append(";advance=").append(spec.advance() == null ? "-" : spec.advance().dayOfMonth() + "/" + spec.advance().percentBps())
                .append(";balanceDay=").append(spec.balanceDayOfMonth())
                .append(";provenance=").append(spec.provenance() == null ? "-" : spec.provenance().origin() + "/" + spec.provenance().confidence())
                .append(";thirteenth=").append(spec.thirteenth() == null ? "-"
                        : spec.thirteenth().enabled() + "/" + spec.thirteenth().firstInstallmentMonth()).append(";");
        for (PayrollDeduction d : spec.deductions()) {
            canonical.append("d=").append(d.kind()).append('|').append(d.label()).append('|')
                    .append(d.amount() == null ? "-" : d.amount().toPlainString()).append('|').append(d.percentBps()).append('|')
                    .append(d.startsOn()).append('|').append(d.endsOn()).append('|').append(d.debtId()).append('|')
                    .append(d.interestAmount() == null ? "-" : d.interestAmount().toPlainString()).append(";");
        }
        return IdempotentClaim.hash(canonical.toString());
    }
}
