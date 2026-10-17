package com.moneylog.util;

import java.io.IOException;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

public final class MigrationFiles {

    private static final Pattern VERSION = Pattern.compile("^V(\\d+)\\.(\\d+)__.*\\.sql$");

    private MigrationFiles() {
    }

    public static String latest() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql");
            return Arrays.stream(resources).map(Resource::getFilename).map(VERSION::matcher).filter(Matcher::matches)
                    .max(java.util.Comparator.comparingLong(m -> Long.parseLong(m.group(1)) * 1000 + Long.parseLong(m.group(2))))
                    .map(m -> m.group(1) + "." + m.group(2)).orElseThrow();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static int countAfter(String exclusiveVersion) {
        String[] parts = exclusiveVersion.split("\\.");
        long threshold = Long.parseLong(parts[0]) * 1000 + Long.parseLong(parts[1]);
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql");
            return (int) Arrays.stream(resources).map(Resource::getFilename).map(VERSION::matcher).filter(Matcher::matches)
                    .filter(m -> Long.parseLong(m.group(1)) * 1000 + Long.parseLong(m.group(2)) > threshold).count();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
