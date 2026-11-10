-- Listings feature: indexes for the keyset-paged browse orderings that V1 doesn't cover.

-- GET /listings?sort=PRICE_ASC|PRICE_DESC (campus + ACTIVE, then price, ties newest first)
CREATE INDEX listings_browse_price_idx ON listings (campus_domain, status, price_cents, created_at DESC, id DESC);

-- GET /me/favorites (newest-favorited first)
CREATE INDEX favorites_user_recent_idx ON favorites (user_id, created_at DESC, listing_id DESC);
