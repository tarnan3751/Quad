-- Photos: server-made JPEG variants (small thumbnail, display size) next to the original upload.
-- thumb_key / display_key are storage keys under the uploads dir (NULL = no variant: clients fall back to the original;
-- either may equal storage_key when the original is already small enough). variants_done = false means the variants
-- haven't been attempted yet: the background backfill picks such rows up (oldest first).
ALTER TABLE photos ADD COLUMN thumb_key text,
                   ADD COLUMN display_key text,
                   ADD COLUMN variants_done boolean NOT NULL DEFAULT false;
CREATE INDEX photos_variants_pending_idx ON photos (created_at, id) WHERE NOT variants_done;
