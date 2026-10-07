package com.moneylog.infra.backup;

/** Fornece credenciais a cada requisição (a role da EC2 as rotaciona). */
@FunctionalInterface
public interface S3CredentialsProvider {

    S3Credentials credentials();

    static S3CredentialsProvider fixed(S3Credentials credentials) {
        return () -> credentials;
    }
}
