package com.moneylog.infra.backup;

/** Credenciais temporárias ou estáticas do S3. Nunca entram em log nem em {@code toString}. */
public record S3Credentials(String accessKey, String secretKey, String sessionToken) {

    @Override
    public String toString() {
        return "S3Credentials[***]";
    }
}
