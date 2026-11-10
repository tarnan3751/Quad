-- Payments: money-movement claims on orders, lookup indexes, and an out-of-order guard for payout-account sync.

-- While a Stripe transfer (buyer confirmed) or refund (order cancelled) is in flight the order is "claimed" for
-- that action ('TRANSFER' | 'REFUND'), so the two can never both run for one order. The claim is re-entrant
-- (a retry of the same action proceeds with the same idempotency key) and is released only after a definitive
-- processor rejection, which also bumps action_attempt so the next try uses a fresh idempotency key.
ALTER TABLE orders ADD COLUMN pending_action text;
ALTER TABLE orders ADD COLUMN action_attempt int NOT NULL DEFAULT 0;

-- expiry sweep over stale PENDING_PAYMENT orders
CREATE INDEX orders_pending_created_idx ON orders (created_at) WHERE status = 'PENDING_PAYMENT';
-- charge.refunded webhooks are matched by payment intent
CREATE INDEX orders_payment_intent_idx ON orders (stripe_payment_intent_id) WHERE stripe_payment_intent_id IS NOT NULL;

-- Timestamp of the newest connected-account state applied to users.{stripe_details_submitted,payouts_enabled};
-- older account.updated events delivered late are ignored.
CREATE TABLE payout_account_sync (
    account_id text PRIMARY KEY,
    state_at   timestamptz NOT NULL
);
