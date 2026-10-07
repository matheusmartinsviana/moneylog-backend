package com.moneylog.core.note.app.vault;

import java.io.IOException;
import java.io.InputStream;

/** O zip enviado: pode ser aberto mais de uma vez (a importação lê duas vezes: valida tudo, depois grava). */
public interface ZipSource {

    InputStream open() throws IOException;

    /** Tamanho em bytes do arquivo enviado (compactado). */
    long size();
}
