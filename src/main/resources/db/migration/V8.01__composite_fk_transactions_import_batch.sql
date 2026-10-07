-- BUG-QA-10: a movimentação importada só pode apontar para um lote do mesmo usuário (padrão das FKs compostas, V2.xx).
-- import_batches já tem uq_import_batches_id_user. Aditiva: linhas existentes já satisfazem (o lote e a movimentação têm o mesmo user_id).
ALTER TABLE transactions DROP CONSTRAINT fk_transactions_import_batch;
ALTER TABLE transactions ADD CONSTRAINT fk_transactions_import_batch_id_user
    FOREIGN KEY (import_batch_id, user_id) REFERENCES import_batches (id, user_id);
