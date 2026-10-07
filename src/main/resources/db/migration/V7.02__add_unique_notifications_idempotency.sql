-- Idempotência do job (S-174): no máximo uma notificação por (usuário, tipo, referência, dia). Reexecutar o job ou reiniciar o app não duplica.
CREATE UNIQUE INDEX uq_notifications_idempotency ON notifications (user_id, kind, ref_id, on_date);
CREATE INDEX ix_notifications_user_list ON notifications (user_id, created_at DESC) WHERE archived = FALSE;
CREATE INDEX ix_notifications_user_unread ON notifications (user_id) WHERE archived = FALSE AND read_at IS NULL;
CREATE INDEX ix_notifications_push_pending ON notifications (created_at) WHERE push_eligible AND push_sent_at IS NULL;
CREATE INDEX ix_notifications_read_at ON notifications (read_at) WHERE read_at IS NOT NULL AND archived = FALSE;
