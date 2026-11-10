-- Orders: disputes, automatic release of held payments, and DISPUTED as a live order status.

-- A DISPUTED order is still live: it holds its listing (RESERVED) exactly like PENDING_PAYMENT and PAID.
DROP INDEX orders_one_live_per_listing;
CREATE UNIQUE INDEX orders_one_live_per_listing ON orders (listing_id) WHERE status IN ('PENDING_PAYMENT', 'PAID', 'DISPUTED');

-- Start of the auto-release clock of a PAID order: when it was paid, or when the buyer last withdrew a dispute.
-- The payment is released to the seller AUTO_RELEASE_HOURS later (computed at read time, so changing the setting
-- applies to every waiting order). Orders already PAID when this migration runs get a full window from now, since
-- their buyers paid before the rule existed. (A PAID row without it, e.g. inserted by hand, counts from paid_at.)
ALTER TABLE orders ADD COLUMN release_clock_from timestamptz;
UPDATE orders SET release_clock_from = now() WHERE status = 'PAID';
-- auto-release sweep: PAID orders whose clock started long enough ago
CREATE INDEX orders_auto_release_idx ON orders ((COALESCE(release_clock_from, paid_at, created_at))) WHERE status = 'PAID';

-- One row per dispute. At most one OPEN dispute per order; closed ones are kept as history (a buyer may withdraw a
-- dispute and open another later).
CREATE TABLE order_disputes (
    id              uuid PRIMARY KEY,
    order_id        uuid NOT NULL REFERENCES orders(id),
    opened_by       uuid REFERENCES users(id),     -- the buyer; NULL = opened by the platform (seller suspended)
    reason          text NOT NULL,                 -- DisputeReason
    details         text NOT NULL DEFAULT '',
    status          text NOT NULL DEFAULT 'OPEN',  -- DisputeStatus
    outcome         text,                          -- DisputeOutcome, set when CLOSED
    resolved_by     uuid REFERENCES users(id),     -- moderator, for moderator outcomes
    resolution_note text,                          -- moderator's internal note
    opened_at       timestamptz NOT NULL DEFAULT now(),
    closed_at       timestamptz
);
CREATE UNIQUE INDEX order_disputes_one_open ON order_disputes (order_id) WHERE status = 'OPEN';
CREATE INDEX order_disputes_order_idx ON order_disputes (order_id, opened_at DESC, id DESC);
-- moderators' queue (open: oldest first) and history (closed: newest first)
CREATE INDEX order_disputes_open_queue_idx ON order_disputes (opened_at, id) WHERE status = 'OPEN';
CREATE INDEX order_disputes_closed_idx ON order_disputes (closed_at DESC, id DESC) WHERE status = 'CLOSED';

-- The payments sweep looks for live orders of suspended sellers; suspended accounts are rare.
CREATE INDEX users_suspended_idx ON users (id) WHERE suspended;
