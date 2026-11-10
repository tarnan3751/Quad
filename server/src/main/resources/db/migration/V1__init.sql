-- Campus Market base schema. Owned by the tech lead; feature work adds V2_<area>_<n>__<desc>.sql files, never edits this one.

CREATE TABLE users (
    id                      uuid PRIMARY KEY,
    email                   text NOT NULL,
    password_hash           text NOT NULL,
    display_name            text NOT NULL,
    bio                     text NOT NULL DEFAULT '',
    avatar_photo_id         uuid,
    campus_domain           text NOT NULL,
    role                    text NOT NULL DEFAULT 'USER',          -- UserRole
    email_verified          boolean NOT NULL DEFAULT false,
    verification_code_hash  text,
    verification_expires_at timestamptz,
    verification_attempts   int NOT NULL DEFAULT 0,
    suspended               boolean NOT NULL DEFAULT false,
    suspended_until         timestamptz,                           -- null + suspended = indefinite
    suspension_reason       text,
    deleted_at              timestamptz,
    stripe_account_id       text,
    stripe_details_submitted boolean NOT NULL DEFAULT false,
    payouts_enabled         boolean NOT NULL DEFAULT false,
    rating_sum              int NOT NULL DEFAULT 0,
    rating_count            int NOT NULL DEFAULT 0,
    created_at              timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX users_email_key ON users (lower(email));
CREATE INDEX users_stripe_account_idx ON users (stripe_account_id);

CREATE TABLE refresh_tokens (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  text NOT NULL UNIQUE,
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);

CREATE TABLE photos (
    id           uuid PRIMARY KEY,
    owner_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    storage_key  text NOT NULL,          -- file name under the uploads dir; public URL = <publicBaseUrl>/uploads/<storage_key>
    content_type text NOT NULL,
    size_bytes   bigint NOT NULL,
    width        int,
    height       int,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX photos_owner_idx ON photos (owner_id);

CREATE TABLE listings (
    id              uuid PRIMARY KEY,
    seller_id       uuid NOT NULL REFERENCES users(id),
    campus_domain   text NOT NULL,
    title           text NOT NULL,
    description     text NOT NULL DEFAULT '',
    category        text NOT NULL,                  -- ListingCategory
    condition       text NOT NULL,                  -- ListingCondition
    price_cents     int NOT NULL CHECK (price_cents >= 0),
    currency        text NOT NULL DEFAULT 'usd',
    status          text NOT NULL DEFAULT 'ACTIVE', -- ListingStatus
    pickup_location text NOT NULL DEFAULT '',
    search          tsvector GENERATED ALWAYS AS (
                        setweight(to_tsvector('english', title), 'A') ||
                        setweight(to_tsvector('english', description), 'B')
                    ) STORED,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX listings_search_idx ON listings USING gin (search);
CREATE INDEX listings_browse_idx ON listings (campus_domain, status, created_at DESC);
CREATE INDEX listings_seller_idx ON listings (seller_id, created_at DESC);

CREATE TABLE listing_photos (
    listing_id uuid NOT NULL REFERENCES listings(id) ON DELETE CASCADE,
    photo_id   uuid NOT NULL REFERENCES photos(id) ON DELETE CASCADE,
    position   int NOT NULL,
    PRIMARY KEY (listing_id, photo_id)
);
CREATE INDEX listing_photos_order_idx ON listing_photos (listing_id, position);

CREATE TABLE favorites (
    user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    listing_id uuid NOT NULL REFERENCES listings(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, listing_id)
);
CREATE INDEX favorites_listing_idx ON favorites (listing_id);

CREATE TABLE blocks (
    blocker_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_id, blocked_id)
);
CREATE INDEX blocks_blocked_idx ON blocks (blocked_id);

CREATE TABLE conversations (
    id                   uuid PRIMARY KEY,
    listing_id           uuid NOT NULL REFERENCES listings(id),
    buyer_id             uuid NOT NULL REFERENCES users(id),
    seller_id            uuid NOT NULL REFERENCES users(id),
    last_message_at      timestamptz,
    last_message_preview text,
    buyer_last_read_at   timestamptz,
    seller_last_read_at  timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),
    UNIQUE (listing_id, buyer_id)
);
CREATE INDEX conversations_buyer_idx ON conversations (buyer_id, last_message_at DESC);
CREATE INDEX conversations_seller_idx ON conversations (seller_id, last_message_at DESC);

CREATE TABLE messages (
    id              uuid PRIMARY KEY,
    conversation_id uuid NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender_id       uuid NOT NULL REFERENCES users(id),
    body            text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX messages_conversation_idx ON messages (conversation_id, created_at DESC, id);

CREATE TABLE orders (
    id                         uuid PRIMARY KEY,
    listing_id                 uuid NOT NULL REFERENCES listings(id),
    buyer_id                   uuid NOT NULL REFERENCES users(id),
    seller_id                  uuid NOT NULL REFERENCES users(id),
    amount_cents               int NOT NULL,
    fee_cents                  int NOT NULL,
    currency                   text NOT NULL DEFAULT 'usd',
    status                     text NOT NULL DEFAULT 'PENDING_PAYMENT', -- OrderStatus
    stripe_checkout_session_id text,
    stripe_payment_intent_id   text,
    stripe_transfer_id         text,
    stripe_refund_id           text,
    checkout_url               text,
    created_at                 timestamptz NOT NULL DEFAULT now(),
    paid_at                    timestamptz,
    completed_at               timestamptz,
    cancelled_at               timestamptz
);
CREATE INDEX orders_buyer_idx ON orders (buyer_id, created_at DESC);
CREATE INDEX orders_seller_idx ON orders (seller_id, created_at DESC);
CREATE INDEX orders_listing_idx ON orders (listing_id);
CREATE UNIQUE INDEX orders_session_idx ON orders (stripe_checkout_session_id) WHERE stripe_checkout_session_id IS NOT NULL;
-- at most one live order per listing
CREATE UNIQUE INDEX orders_one_live_per_listing ON orders (listing_id) WHERE status IN ('PENDING_PAYMENT', 'PAID');

CREATE TABLE stripe_events (
    id          text PRIMARY KEY,       -- Stripe event id, for webhook idempotency
    type        text NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ratings (
    id         uuid PRIMARY KEY,
    order_id   uuid NOT NULL REFERENCES orders(id),
    rater_id   uuid NOT NULL REFERENCES users(id),
    ratee_id   uuid NOT NULL REFERENCES users(id),
    stars      int NOT NULL CHECK (stars BETWEEN 1 AND 5),
    comment    text NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (order_id, rater_id)
);
CREATE INDEX ratings_ratee_idx ON ratings (ratee_id, created_at DESC);

CREATE TABLE reports (
    id                uuid PRIMARY KEY,
    reporter_id       uuid NOT NULL REFERENCES users(id),
    target_type       text NOT NULL,   -- ReportTargetType
    target_id         uuid NOT NULL,
    target_user_id    uuid REFERENCES users(id),   -- user responsible for the reported content
    reason            text NOT NULL,   -- ReportReason
    details           text NOT NULL DEFAULT '',
    target_summary    text NOT NULL DEFAULT '',
    status            text NOT NULL DEFAULT 'OPEN', -- ReportStatus
    resolved_by       uuid REFERENCES users(id),
    resolution_action text,            -- ModerationAction
    resolution_note   text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    resolved_at       timestamptz
);
CREATE INDEX reports_status_idx ON reports (status, created_at);
CREATE INDEX reports_target_idx ON reports (target_type, target_id);
CREATE INDEX reports_target_user_idx ON reports (target_user_id, status);

CREATE TABLE moderation_actions (
    id           uuid PRIMARY KEY,
    moderator_id uuid NOT NULL REFERENCES users(id),
    action       text NOT NULL,        -- ModerationAction
    target_type  text NOT NULL,        -- ReportTargetType
    target_id    uuid NOT NULL,
    note         text NOT NULL DEFAULT '',
    report_id    uuid REFERENCES reports(id),
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX moderation_actions_created_idx ON moderation_actions (created_at DESC);
