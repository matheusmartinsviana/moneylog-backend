-- Etapa 6, revisão A4 (S-278): refreshWikilinks lia TODOS os wikilinks do usuário a cada escrita (milhões de linhas em 5.000 notas x 500 links).
-- target_name_key = o nome do alvo do wikilink sem .md, sem caixa e sem acento (a mesma chave com que a aplicação indexa os nomes das notas).
-- Com ele só se recalcula o que o nome afetado alcança. Linhas antigas ficam NULL: a aplicação as inclui no próximo recálculo e preenche a chave
-- (a dobra de caixa/acento é a do Java e não se reproduz em SQL sem divergir).
ALTER TABLE note_links ADD COLUMN target_name_key VARCHAR(255);

CREATE INDEX ix_note_links_user_wikilink_name ON note_links (user_id, target_name_key) WHERE origin = 'WIKILINK';
