package com.moneylog.core.backup.app;

import java.nio.file.Path;

public interface DatabaseDumper {

    void dump(Path target);

    void restore(Path source);
}
