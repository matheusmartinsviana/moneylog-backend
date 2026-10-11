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

public final class TransactionalProxies {

    private TransactionalProxies() {
    }

    public static <T> T required(Class<T> type, T target, PlatformTransactionManager transactionManager) {
        return build(type, target, transactionManager, false, List.of());
    }

    public static <T> T readOnly(Class<T> type, T target, PlatformTransactionManager transactionManager) {
        return build(type, target, transactionManager, true, List.of());
    }

    public static <T> T snapshotReadOnly(Class<T> type, T target, PlatformTransactionManager transactionManager) {
        return build(type, target, transactionManager, true, List.of(), Isolation.REPEATABLE_READ);
    }

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
