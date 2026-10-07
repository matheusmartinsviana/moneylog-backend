-- Busca de notas sem acento (T111, S-163): unaccent + ILIKE, sem full-text no MVP. A extensão faz parte do contrib do Postgres e é
-- "trusted" (PG 13+), então o dono do banco pode criá-la, inclusive no RDS.
CREATE EXTENSION IF NOT EXISTS unaccent;
