package com.moneylog.core.note.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.moneylog.core.note.app.vault.ZipSource;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;

public final class NoteTestKit {

    public static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    public static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID OTHER_USER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private NoteTestKit() {
    }

    public static TimeProvider time() {
        return new TimeProvider() {
            @Override
            public Instant now() {
                return NOW;
            }

            @Override
            public LocalDate today() {
                return LocalDate.of(2026, 10, 5);
            }

            @Override
            public ZoneId zone() {
                return ZoneId.of("America/Sao_Paulo");
            }
        };
    }

    public static IdGenerator ids() {
        AtomicLong counter = new AtomicLong();
        return () -> new UUID(1L, counter.incrementAndGet());
    }

    public static UUID id(long n) {
        return new UUID(1L, n);
    }

    public static class InMemoryIdempotency implements IdempotencyGateway {
        private final Map<String, Claim> claims = new HashMap<>();

        @Override
        public boolean claim(UUID userId, String scope, UUID key, String requestHash, UUID resourceId, Instant now, Instant expiredBefore) {
            String k = userId + scope + key;
            if (claims.containsKey(k) && claims.get(k).createdAt().isAfter(expiredBefore)) {
                return false;
            }
            claims.put(k, new Claim(requestHash, resourceId, now));
            return true;
        }

        @Override
        public Optional<Claim> find(UUID userId, String scope, UUID key) {
            return Optional.ofNullable(claims.get(userId + scope + key));
        }
    }

    public static final class Zip {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final ZipOutputStream out = new ZipOutputStream(bytes);

        public Zip add(String name, String content) {
            return add(name, content.getBytes(StandardCharsets.UTF_8));
        }

        public Zip add(String name, byte[] content) {
            try {
                out.putNextEntry(new ZipEntry(name));
                out.write(content);
                out.closeEntry();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return this;
        }

        public Zip dir(String name) {
            try {
                out.putNextEntry(new ZipEntry(name.endsWith("/") ? name : name + "/"));
                out.closeEntry();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return this;
        }

        public byte[] bytes() {
            try {
                out.finish();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return bytes.toByteArray();
        }

        public ZipSource source() {
            return NoteTestKit.source(bytes());
        }
    }

    public static ZipSource source(byte[] data) {
        return new ZipSource() {
            @Override
            public InputStream open() {
                return new ByteArrayInputStream(data);
            }

            @Override
            public long size() {
                return data.length;
            }
        };
    }

    public static ZipSource sourceWithSize(byte[] data, long size) {
        return new ZipSource() {
            @Override
            public InputStream open() {
                return new ByteArrayInputStream(data);
            }

            @Override
            public long size() {
                return size;
            }
        };
    }
}
