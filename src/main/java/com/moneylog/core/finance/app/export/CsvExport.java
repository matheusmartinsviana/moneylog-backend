package com.moneylog.core.finance.app.export;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Arquivo CSV pronto para ser escrito: as linhas já foram calculadas dentro da transação do caso de uso; {@link #writeTo} só serializa,
 * linha a linha, no fluxo informado (sem montar o arquivo inteiro em memória e sem tocar o banco).
 */
public interface CsvExport {

    /** Nome sugerido do arquivo ({@code Content-Disposition}). */
    String fileName();

    /** Escreve UTF-8 com BOM, separador {@code ;} e fim de linha {@code CRLF}. */
    void writeTo(OutputStream out) throws IOException;
}
