package com.moneylog.core.finance.app.export;

import java.io.IOException;
import java.io.OutputStream;

public interface CsvExport {

    String fileName();

    void writeTo(OutputStream out) throws IOException;
}
