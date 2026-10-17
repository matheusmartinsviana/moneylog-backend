package com.moneylog.core.sk.provenance;

import com.moneylog.core.sk.exception.BusinessRuleException;

public class ProvenanceInvalidException extends BusinessRuleException {

    public ProvenanceInvalidException(String field, String value) {
        super("PROVENANCE_INVALID", "Proveniência inválida: " + field + " fora do enum.");
    }
}
