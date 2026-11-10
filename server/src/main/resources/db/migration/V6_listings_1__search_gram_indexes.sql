-- GET /listings?q=: indexes for the matches that the full-text index can't serve.
--
-- Queries of one or two characters, stop words and punctuation are matched as case-insensitive substrings (ILIKE).
-- A substring can only occur in a text that contains each of its two-character pieces, so an index of every
-- listing's one- and two-character pieces finds the candidates without reading the campus's other listings.
-- Each piece is stored as '<campus>|<piece>', which keeps a lookup proportional to the matches on that campus.
-- '^^' + the first one / two characters marks how the text starts (for "title starts with").
CREATE FUNCTION search_grams(scope text, s text) RETURNS text[]
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
    -- one pass over the characters (substr() by position would make this quadratic in the length of the text)
    SELECT ARRAY(
        SELECT DISTINCT scope || '|' || g
        FROM (SELECT c, lead(c) OVER (ORDER BY i) AS next, i
              FROM string_to_table(lower(s), NULL) WITH ORDINALITY AS t(c, i)) ch,
             LATERAL (VALUES (c), (c || next),
                             (CASE WHEN i = 1 THEN '^^' || c END), (CASE WHEN i = 1 THEN '^^' || c || next END)) x(g)
        WHERE g IS NOT NULL)
$$;

-- The pieces every text containing q must have: q's two-character pieces, or q itself when it is one character.
CREATE FUNCTION search_pattern_grams(scope text, q text) RETURNS text[]
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT ARRAY(
        SELECT DISTINCT scope || '|' || substr(t, i, 2)
        FROM lower(q) t, generate_series(1, greatest(length(t) - 1, 1)) i)
$$;

-- Only ACTIVE listings are searchable.
CREATE INDEX listings_title_grams_idx ON listings USING gin (search_grams(campus_domain, title)) WHERE status = 'ACTIVE';
CREATE INDEX listings_description_grams_idx ON listings USING gin (search_grams(campus_domain, description)) WHERE status = 'ACTIVE';

-- Relevance puts listings whose title matches the query first; this finds them when few titles match
-- (listings_search_idx covers title and description together, so a word common in descriptions swamps it).
CREATE INDEX listings_title_search_idx ON listings USING gin (to_tsvector('english', title)) WHERE status = 'ACTIVE';
