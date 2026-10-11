package com.moneylog.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.moneylog.core.finance.app.gateways.LedgerReadGateway;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.ledger.gateways.LedgerReadGatewayImpl;
import com.moneylog.infra.ledger.gateways.LedgerWriteGatewayImpl;
import com.moneylog.infra.ledger.persistence.LedgerPostingRepositoryJpa;

@Configuration(proxyBeanMethods = false)
public class LedgerConfig {

    @Bean
    LedgerWriteGateway ledgerWriteGateway(LedgerPostingRepositoryJpa repository, IdGenerator ids, TimeProvider time) {
        return new LedgerWriteGatewayImpl(repository, ids, time);
    }

    @Bean
    LedgerReadGateway ledgerReadGateway(LedgerPostingRepositoryJpa repository) {
        return new LedgerReadGatewayImpl(repository);
    }
}
