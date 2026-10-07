package com.moneylog.infra.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.interceptor.NoRollbackRuleAttribute;
import org.springframework.transaction.interceptor.RollbackRuleAttribute;
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.interceptor.MatchAlwaysTransactionAttributeSource;

/**
 * Fronteira transacional dos casos de uso (BE 03, BE 09). O {@code core} é Java puro e não tem {@code @Transactional}:
 * quem declara a transação é a configuração da feature, embrulhando o caso de uso num proxy. Efeitos colaterais
 * (eventos, e-mail) são disparados pelo chamador <b>depois</b> que o proxy devolve (já com commit).
 */
public final class TransactionalProxies {

    private TransactionalProxies() {
    }

    /** Uma transação por chamada; faz rollback em qualquer {@code RuntimeException} (padrão do Spring). */
    public static <T> T required(Class<T> type, T target, PlatformTransactionManager transactionManager) {
        return build(type, target, transactionManager, false, List.of());
    }

    /** Transação só de leitura (nenhum flush, otimizações do driver). */
    public static <T> T readOnly(Class<T> type, T target, PlatformTransactionManager transactionManager) {
        return build(type, target, transactionManager, true, List.of());
    }

    /**
     * Leitura com <b>um instantâneo só</b> ({@code REPEATABLE_READ}, somente leitura): todas as consultas do caso de uso
     * enxergam o mesmo estado do banco (B8, BUG-QA-01). Em {@code READ COMMITTED}, uma movimentação confirmada entre a
     * leitura dos lançamentos e a das movimentações aparecia sem lançamentos, e a lista e os totais descreviam estados
     * diferentes. Use em todo caso de uso de leitura com mais de uma consulta.
     */
    public static <T> T snapshotReadOnly(Class<T> type, T target, PlatformTransactionManager transactionManager) {
        return build(type, target, transactionManager, true, List.of(), Isolation.REPEATABLE_READ);
    }

    /**
     * Como {@link #required}, mas as exceções listadas <b>confirmam</b> a transação (commit) antes de propagar. Usado
     * quando a falha é parte do resultado (ex.: reuso do refresh revoga a família e responde 401).
     */
    public static <T> T requiredCommittingOn(Class<T> type, T target, PlatformTransactionManager transactionManager,
                                             Class<? extends Throwable>... commitOn) {
        List<RollbackRuleAttribute> rules = new ArrayList<>();
        for (Class<? extends Throwable> exception : commitOn) {
            rules.add(new NoRollbackRuleAttribute(exception));
        }
        return build(type, target, transactionManager, false, rules);
    }

    private static <T> T build(Class<T> type, T target, PlatformTransactionManager transactionManager, boolean readOnly,
                               List<RollbackRuleAttribute> rules) {
        return build(type, target, transactionManager, readOnly, rules, Isolation.DEFAULT);
    }

    private static <T> T build(Class<T> type, T target, PlatformTransactionManager transactionManager, boolean readOnly,
                               List<RollbackRuleAttribute> rules, Isolation isolation) {
        RuleBasedTransactionAttribute attribute = new RuleBasedTransactionAttribute();
        attribute.setPropagationBehavior(Propagation.REQUIRED.value());
        attribute.setReadOnly(readOnly);
        attribute.setIsolationLevel(isolation.value());
        attribute.setRollbackRules(rules);
        MatchAlwaysTransactionAttributeSource source = new MatchAlwaysTransactionAttributeSource();
        source.setTransactionAttribute(attribute);
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(type);
        factory.addAdvice(new TransactionInterceptor(transactionManager, source));
        return type.cast(factory.getProxy(type.getClassLoader()));
    }
}
