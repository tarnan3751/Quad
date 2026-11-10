-- POST /photos counts the uploader's photos of the last hour (upload quota): with created_at in the index that reads
-- only the recent ones, not every photo the user ever uploaded. Also serves the owner_id foreign key, so the
-- owner-only index goes.
CREATE INDEX photos_owner_recent_idx ON photos (owner_id, created_at);
DROP INDEX photos_owner_idx;
