INSERT INTO notification_settings (user_id, push_enabled, version, updated_at)
SELECT DISTINCT s.user_id, TRUE, 1, now()
FROM push_subscriptions s
WHERE NOT EXISTS (SELECT 1 FROM notification_settings n WHERE n.user_id = s.user_id);

UPDATE notification_settings n
SET push_enabled = TRUE, version = n.version + 1, updated_at = now()
WHERE n.push_enabled = FALSE
  AND EXISTS (SELECT 1 FROM push_subscriptions s WHERE s.user_id = n.user_id);
