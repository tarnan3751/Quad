-- GET /categories counts a campus's ACTIVE listings per category (minus blocked / suspended sellers). With this index
-- that is an index-only scan instead of reading every listing row (descriptions and search vectors included).
CREATE INDEX listings_active_category_idx ON listings (campus_domain, category, seller_id) WHERE status = 'ACTIVE';
