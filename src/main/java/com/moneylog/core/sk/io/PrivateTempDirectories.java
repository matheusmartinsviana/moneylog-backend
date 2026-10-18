package com.moneylog.core.sk.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

public final class PrivateTempDirectories {

    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));

    private PrivateTempDirectories() {
    }

    public static Path create(String prefix) throws IOException {
        return Files.createTempDirectory(prefix, OWNER_ONLY);
    }
}
