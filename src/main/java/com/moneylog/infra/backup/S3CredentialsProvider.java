package com.moneylog.infra.backup;

@FunctionalInterface
public interface S3CredentialsProvider {

    S3Credentials credentials();

    static S3CredentialsProvider fixed(S3Credentials credentials) {
        return () -> credentials;
    }
}
