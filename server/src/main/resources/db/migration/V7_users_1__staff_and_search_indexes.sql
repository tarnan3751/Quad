-- Admin screens: the staff list and the user search used to read every user.

-- GET /mod/staff
CREATE INDEX users_staff_idx ON users (role) WHERE role IN ('ADMIN', 'MODERATOR');

-- GET /mod/users/search matches a substring of the email or the display name and returns the first 20 by name.
-- A text most users have ("edu") is found by reading users in name order; a rare one through the one- and
-- two-character pieces of both fields (search_grams, V6_listings_1).
CREATE INDEX users_search_order_idx ON users (lower(display_name), id) WHERE email_verified AND deleted_at IS NULL;
CREATE INDEX users_search_grams_idx ON users USING gin (search_grams('', email), search_grams('', display_name))
    WHERE email_verified AND deleted_at IS NULL;
