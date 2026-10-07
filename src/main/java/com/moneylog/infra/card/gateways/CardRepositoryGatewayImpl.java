package com.moneylog.infra.card.gateways;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.BillingRule;
import com.moneylog.core.card.domain.CardStatus;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.card.persistence.BillingRuleEntity;
import com.moneylog.infra.card.persistence.BillingRuleRepositoryJpa;
import com.moneylog.infra.card.persistence.CardEntity;
import com.moneylog.infra.card.persistence.CardRepositoryJpa;
import com.moneylog.infra.persistence.OffsetLimit;

/** {@link CardGateway} sobre JPA: a linha do cartão e as regras de fechamento/vencimento são gravadas juntas. */
public class CardRepositoryGatewayImpl implements CardGateway {

    private final CardRepositoryJpa cards;
    private final BillingRuleRepositoryJpa rules;
    private final IdGenerator ids;
    private final TimeProvider time;
    private final InvoiceRowGateway invoiceRows;
    private final EntityManager entityManager;

    public CardRepositoryGatewayImpl(CardRepositoryJpa cards, BillingRuleRepositoryJpa rules, IdGenerator ids, TimeProvider time,
                                     InvoiceRowGateway invoiceRows, EntityManager entityManager) {
        this.entityManager = entityManager;
        this.cards = cards;
        this.rules = rules;
        this.ids = ids;
        this.time = time;
        this.invoiceRows = invoiceRows;
    }

    @Override
    public void refreshInvoiceRows(UUID userId, CreditCard card) {
        invoiceRows.refresh(userId, card);
    }

    @Override
    public Persisted<CreditCard> save(CreditCard card) {
        Instant now = time.now();
        CardEntity entity = cards.findByIdAndUserId(card.id(), card.userId()).orElse(null);
        if (entity == null) {
            entity = new CardEntity(card.id(), card.userId(), card.name(), card.institution(), card.limit().cents(),
                    card.currency().value(), card.defaultPaymentAccountId(), card.status(), now);
        } else {
            if (entity.getVersion() != card.version()) {
                throw new VersionConflictException(card.version(), entity.getVersion());
            }
            entity.apply(card.name(), card.institution(), card.limit().cents(), card.defaultPaymentAccountId(), card.status(), now);
        }
        CardEntity saved = cards.saveAndFlush(entity);
        List<BillingRuleEntity> savedRules = syncRules(saved, card, now);
        return CardRepositoryMapper.toPersisted(saved, savedRules);
    }

    /** Alinha as regras gravadas às do domínio: atualiza os dias, insere as novas e remove as que o domínio descartou. */
    private List<BillingRuleEntity> syncRules(CardEntity card, CreditCard domain, Instant now) {
        Map<String, BillingRuleEntity> stored = new HashMap<>();
        rules.findByUserIdAndCardId(card.getUserId(), card.getId()).forEach(rule -> stored.put(rule.getEffectiveFrom(), rule));
        List<BillingRuleEntity> result = new ArrayList<>();
        for (BillingRule rule : domain.billingRules()) {
            String from = rule.effectiveFrom().toString();
            BillingRuleEntity existing = stored.remove(from);
            if (existing == null) {
                existing = new BillingRuleEntity(ids.next(), card.getUserId(), card.getId(), from, rule.closingDay().day(),
                        rule.dueDay().day(), now);
            } else {
                existing.apply(rule.closingDay().day(), rule.dueDay().day(), now);
            }
            result.add(existing);
        }
        rules.deleteAll(stored.values());
        rules.saveAll(result);
        rules.flush();
        return result;
    }

    @Override
    public void lockForUpdate(UUID userId, UUID id) {
        cards.lockByIdAndUserId(id, userId);
    }

    @Override
    public void discardReadState() {
        entityManager.flush();
        entityManager.clear();
    }

    @Override
    public Optional<BillingDays> currentBillingDays(UUID userId, UUID id) {
        return rules.latestDays(userId, id, org.springframework.data.domain.PageRequest.of(0, 1)).stream().findFirst()
                .map(days -> new BillingDays(days.getClosingDay(), days.getDueDay()));
    }

    @Override
    public Optional<Persisted<CreditCard>> findById(UUID userId, UUID id) {
        return cards.findByIdAndUserId(id, userId)
                .map(entity -> CardRepositoryMapper.toPersisted(entity, rules.findByUserIdAndCardId(userId, id)));
    }

    @Override
    public Slice<Persisted<CreditCard>> list(UUID userId, CardStatus status, PageQuery page) {
        if (page.isBeyondAnyData()) {
            return new Slice<>(List.of(), false);
        }
        List<CardEntity> fetched = cards.findPage(userId, status, OffsetLimit.of(page));
        List<CardEntity> pageEntities = fetched.size() > page.pageSize() ? fetched.subList(0, page.pageSize()) : fetched;
        Map<UUID, List<BillingRuleEntity>> rulesByCard = new HashMap<>();
        if (!pageEntities.isEmpty()) {
            rules.findByUserIdAndCardIdIn(userId, pageEntities.stream().map(CardEntity::getId).toList())
                    .forEach(rule -> rulesByCard.computeIfAbsent(rule.getCardId(), id -> new ArrayList<>()).add(rule));
        }
        return new Slice<>(pageEntities.stream()
                .map(entity -> CardRepositoryMapper.toPersisted(entity, rulesByCard.getOrDefault(entity.getId(), List.of()))).toList(),
                fetched.size() > page.pageSize());
    }
}
