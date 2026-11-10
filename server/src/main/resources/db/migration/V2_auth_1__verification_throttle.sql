-- Auth: per-account throttling of verification-code issuance (resend cooldown + daily cap), so neither the
-- inbox owner can be mail-bombed nor a code brute-forced by cycling through fresh codes.
ALTER TABLE users ADD COLUMN verification_sent_at      timestamptz;          -- when the current code was issued
ALTER TABLE users ADD COLUMN verification_window_start timestamptz;          -- start of the current 24h issuance window
ALTER TABLE users ADD COLUMN verification_sends        int NOT NULL DEFAULT 0; -- codes issued in that window
