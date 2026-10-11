package com.moneylog.core.note.app.vault;

import java.io.IOException;
import java.io.InputStream;

public interface ZipSource {

    InputStream open() throws IOException;

    long size();
}
