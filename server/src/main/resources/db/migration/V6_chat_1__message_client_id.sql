-- Idempotent sends: the sender's client names each message, and a repeat of the same id returns the stored row.
ALTER TABLE messages ADD COLUMN client_id text;
CREATE UNIQUE INDEX messages_sender_client_id_idx ON messages (sender_id, client_id) WHERE client_id IS NOT NULL;
