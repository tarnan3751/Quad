-- One OPEN report per (reporter, target); makes de-duplication race-safe.
CREATE UNIQUE INDEX reports_one_open_per_reporter_target
    ON reports (reporter_id, target_type, target_id) WHERE status = 'OPEN';
CREATE INDEX reports_open_target_idx ON reports (target_type, target_id) WHERE status = 'OPEN';
