package com.moneylog.infra.backup;

public record S3Credentials(String accessKey, String secretKey, String sessionToken) {

    @Override
    public String toString() {
        return "S3Credentials[***]";
    }
}
