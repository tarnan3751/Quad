-- GET /listings?q=...&category=/condition=/minPrice=/maxPrice=: a text search reads the campus's next listings that
-- pass the filters, newest first, and looks for the text among those. With the filter columns in the index, the
-- listings that don't pass are skipped without being fetched.
CREATE INDEX listings_browse_filter_idx ON listings (campus_domain, created_at DESC, id DESC)
    INCLUDE (category, condition, price_cents) WHERE status = 'ACTIVE';
