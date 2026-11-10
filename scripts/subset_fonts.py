"""Subsets the bundled Plus Jakarta Sans instances: usage `subset_fonts.py <src dir> <out dir>`.

Keeps every Latin, Latin Extended (A, B, IPA, Additional), combining-mark, punctuation, currency, letterlike, arrow, math
and shape codepoint the fonts have, and the OpenType features text gets by default (ccmp, locl, liga, calt, kern, mark)
plus tnum / pnum / case / cpsp. Drops the alternates nothing in the app can reach (stylistic sets, aalt, super- and
subscript forms, fractions), glyph names and the three Greek letters.
"""
import os, sys
from fontTools import subset
from fontTools.ttLib import TTFont

RANGES = [(0x0000, 0x036F), (0x0E3F, 0x0E3F), (0x1E00, 0x1EFF), (0x2000, 0x20BF),
          (0x2100, 0x21FF), (0x2200, 0x22FF), (0x25A0, 0x25FF), (0x2600, 0x26FF), (0x2B00, 0x2BFF),
          (0xFB00, 0xFB06)]
FEATURES = ['ccmp', 'locl', 'liga', 'calt', 'kern', 'mark', 'mkmk', 'tnum', 'pnum', 'case', 'cpsp']

src, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
for name in sorted(os.listdir(src)):
    if not name.endswith('.ttf'):
        continue
    font = TTFont(os.path.join(src, name))
    have = set(font.getBestCmap())
    keep = sorted(c for c in have if any(a <= c <= b for a, b in RANGES))
    options = subset.Options()
    options.layout_features = FEATURES
    options.name_IDs = ['*']          # keeps the copyright and licence records (OFL)
    options.name_languages = ['*']
    options.notdef_outline = True
    options.glyph_names = False
    options.hinting = True
    options.legacy_kern = False
    subsetter = subset.Subsetter(options)
    subsetter.populate(unicodes=keep)
    subsetter.subset(font)
    font.save(os.path.join(out, name))
    before = os.path.getsize(os.path.join(src, name)); after = os.path.getsize(os.path.join(out, name))
    print(f'{name}: {before} -> {after} bytes, {len(have)} -> {len(keep)} codepoints, {len(font.getGlyphOrder())} glyphs; dropped',
          ''.join(chr(c) for c in sorted(have - set(keep))))
