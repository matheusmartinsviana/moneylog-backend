package com.moneylog.infra.config;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.backup.app.BackupCrypto;
import com.moneylog.core.backup.app.BackupService;
import com.moneylog.core.backup.app.BackupStorage;
import com.moneylog.core.backup.app.DatabaseDumper;
import com.moneylog.infra.backup.BackupJob;
import com.moneylog.infra.backup.InstanceMetadataCredentialsProvider;
import com.moneylog.infra.backup.LocalDirBackupStorage;
import com.moneylog.infra.backup.PgDumpCommand;
import com.moneylog.infra.backup.S3BackupStorage;
import com.moneylog.infra.backup.S3Credentials;
import com.moneylog.infra.backup.S3CredentialsProvider;

/**
 * Backup do banco (T176). Desligado por padrão: só liga com {@code moneylog.backup.enabled=true}. Armazenamento
 * {@code local} (padrão, {@code BACKUP_DIR}) ou {@code s3} (bucket, região e credenciais por ambiente ou role da instância).
 * {@code BACKUP_ENCRYPTION_KEY} opcional liga a cifra. Nada de segredo no código.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "moneylog.backup.enabled", havingValue = "true")
public class BackupConfig {

    @Bean
    DatabaseDumper databaseDumper(@Value("${spring.datasource.url}") String url, @Value("${spring.datasource.username}") String user,
                                  @Value("${spring.datasource.password}") String password) {
        return PgDumpCommand.fromJdbcUrl(url, user, password);
    }

    @Bean
    BackupStorage backupStorage(@Value("${moneylog.backup.storage:local}") String type, @Value("${moneylog.backup.dir:/var/backups/moneylog}") String dir,
                                @Value("${moneylog.backup.s3.endpoint:}") String endpoint, @Value("${moneylog.backup.s3.region:}") String region,
                                @Value("${moneylog.backup.s3.bucket:}") String bucket,
                                @Value("${moneylog.backup.s3.access-key:}") String accessKey,
                                @Value("${moneylog.backup.s3.secret-key:}") String secretKey,
                                @Value("${moneylog.backup.s3.session-token:}") String sessionToken,
                                @Value("${moneylog.backup.s3.imds-endpoint:http://169.254.169.254}") String imds, ObjectMapper mapper,
                                Clock clock) {
        if ("local".equals(type)) {
            return new LocalDirBackupStorage(Path.of(dir));
        }
        if (!"s3".equals(type)) {
            throw new IllegalStateException("moneylog.backup.storage deve ser 'local' ou 's3'.");
        }
        if (region.isBlank() || bucket.isBlank()) {
            throw new IllegalStateException("BACKUP_S3_REGION e BACKUP_S3_BUCKET são obrigatórios com o armazenamento s3.");
        }
        S3CredentialsProvider credentials = accessKey.isBlank() ? new InstanceMetadataCredentialsProvider(imds, mapper)
                : S3CredentialsProvider.fixed(new S3Credentials(accessKey, secretKey, sessionToken));
        return new S3BackupStorage(endpoint, region, bucket, credentials, clock);
    }

    @Bean
    BackupService backupService(DatabaseDumper dumper, BackupStorage storage, @Value("${moneylog.backup.encryption-key:}") String key,
                                @Value("${moneylog.backup.prefix:moneylog/}") String prefix,
                                @Value("${moneylog.backup.retention-days:7}") int retentionDays, Clock clock) {
        Optional<BackupCrypto> crypto = key.isBlank() ? Optional.empty() : Optional.of(new BackupCrypto(key));
        return new BackupService(dumper, storage, crypto, prefix, retentionDays, clock);
    }

    @Bean
    BackupJob backupJob(BackupService service) {
        return new BackupJob(service);
    }
}
