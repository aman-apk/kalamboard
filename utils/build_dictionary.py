#!/usr/bin/env python3
# Copyright (C) 2026 The FlorisBoard Contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Builds the per-language suggestion dictionaries (`<lang>.sqlite3`) consumed by
`ime/nlp/words/SqliteWordDictionary.kt`.

Schema of the produced database:
    meta(key TEXT PRIMARY KEY, value TEXT)
    words(word TEXT PRIMARY KEY, norm TEXT NOT NULL, freq INTEGER NOT NULL, flags INTEGER NOT NULL)
    bigrams(w1_norm TEXT NOT NULL, w2 TEXT NOT NULL, freq INTEGER NOT NULL,
            PRIMARY KEY (w1_norm, w2)) WITHOUT ROWID

Inputs:
  --wordlist FILE      "word count" per line (hermitdave/FrequencyWords format). Repeatable;
                       counts of repeated lists are summed after per-list max-normalization.
  --overlay FILE       curated TSV "text<TAB>freq(0-255)" (e.g. dictionaries/levantine/*.tsv).
                       Single words override/boost the base frequency; multi-word phrases are
                       decomposed: each token becomes a word entry and each consecutive token
                       pair becomes a high-frequency bigram (this is how phrases like
                       "يعطيك العافية" are modeled — the engine chains them word by word).
  --sentences FILE     Tatoeba per-language export (id<TAB>lang<TAB>sentence), optionally .bz2.
                       Used to mine bigrams.
  --blocklist FILE     one word per line; matching words get FLAG_POSSIBLY_OFFENSIVE (they are
                       kept in the DB and filtered at runtime by the user's setting).

IMPORTANT: normalize_ar()/normalize_latin() below re-implement WordNormalizer.kt and MUST stay
in sync with it. `--self-test` pins the same golden cases as ArabicNormalizerTest.kt.
"""

import argparse
import bz2
import math
import re
import sqlite3
import sys
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

FLAG_POSSIBLY_OFFENSIVE = 1

# --- Normalization (mirror of WordNormalizer.kt) -------------------------------------------------

_AR_STRIP = set(
    [chr(c) for c in range(0x064B, 0x0660)]  # harakat/tanween/shadda/sukun U+064B..U+065F
    + [chr(0x0670)]                          # superscript alef
    + [chr(c) for c in range(0x06D6, 0x06EE)]  # Quranic annotation marks
    + ["ـ"]                             # tatweel
)
_AR_MAP = {
    "أ": "ا", "إ": "ا", "آ": "ا", "ٱ": "ا",
    "ة": "ه", "ى": "ي", "ؤ": "و", "ئ": "ي",
}
for _i in range(10):
    _AR_MAP[chr(0x0660 + _i)] = str(_i)  # ٠..٩
    _AR_MAP[chr(0x06F0 + _i)] = str(_i)  # ۰..۹


def normalize_ar(word: str) -> str:
    out = []
    for ch in word:
        if ch in _AR_STRIP:
            continue
        out.append(_AR_MAP.get(ch, ch))
    return "".join(out)


def normalize_latin(word: str) -> str:
    decomposed = unicodedata.normalize("NFD", word.lower())
    return "".join(ch for ch in decomposed if unicodedata.category(ch) != "Mn")


def normalizer_for(language: str):
    return normalize_ar if language == "ar" else normalize_latin


# --- Token validation ----------------------------------------------------------------------------

_AR_WORD_RE = re.compile(r"^[ء-ي٠-٩]{1,24}$")  # after normalization
_LATIN_WORD_RE = re.compile(r"^[a-z][a-z']{0,23}$")
_EN_SINGLE_CHAR_WHITELIST = {"a", "i"}


def is_valid_word(language: str, norm: str) -> bool:
    if language == "ar":
        return len(norm) >= 2 and bool(_AR_WORD_RE.match(norm))
    if len(norm) == 1:
        return norm in _EN_SINGLE_CHAR_WHITELIST
    return bool(_LATIN_WORD_RE.match(norm))


_TOKEN_SPLIT_RE = re.compile(r"[^\wء-ٟ٠-٩ٰ']+")


def tokenize(sentence: str):
    return [t for t in _TOKEN_SPLIT_RE.split(sentence) if t]


# --- Build steps ---------------------------------------------------------------------------------

def load_wordlists(paths, language, max_words):
    """Merges frequency lists into {word: count_scaled_0_1}, keeping raw display forms."""
    norm = normalizer_for(language)
    merged = Counter()
    for path in paths:
        counts = {}
        with open(path, encoding="utf-8") as f:
            for line in f:
                parts = line.strip().split()
                if len(parts) != 2:
                    continue
                word, count = parts[0], parts[1]
                if not count.isdigit():
                    continue
                if not is_valid_word(language, norm(word)):
                    continue
                counts[word] = counts.get(word, 0) + int(count)
        if not counts:
            print(f"warning: wordlist {path} contributed 0 words", file=sys.stderr)
            continue
        top = max(counts.values())
        for word, count in counts.items():
            merged[word] += count / top  # per-list max-normalization before summing
    ranked = merged.most_common(max_words)
    return dict(ranked)


def scale_frequencies(weights):
    """Log-scales relative weights into the 1..255 byte range used by the engine."""
    if not weights:
        return {}
    top = max(weights.values())
    scaled = {}
    for word, weight in weights.items():
        scaled[word] = max(1, round(255 * math.log1p(weight * 1000) / math.log1p(top * 1000)))
    return scaled


def load_overlays(paths, language):
    """Returns (word_boosts {word: freq}, phrase_bigrams {(w1, w2): freq})."""
    norm = normalizer_for(language)
    word_boosts = {}
    phrase_bigrams = {}
    for path in paths:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                parts = line.split("\t")
                if len(parts) != 2 or not parts[1].isdigit():
                    print(f"warning: skipping malformed overlay line in {path}: {line!r}", file=sys.stderr)
                    continue
                text, freq = " ".join(parts[0].split()), int(parts[1])
                tokens = text.split(" ")
                for token in tokens:
                    if not is_valid_word(language, norm(token)):
                        break
                else:
                    for token in tokens:
                        word_boosts[token] = max(word_boosts.get(token, 0), freq)
                    for w1, w2 in zip(tokens, tokens[1:]):
                        key = (norm(w1), w2)
                        phrase_bigrams[key] = max(phrase_bigrams.get(key, 0), freq)
    return word_boosts, phrase_bigrams


def mine_bigrams(path, language, valid_norms, canonical_by_norm, min_count):
    """Counts (norm(w1), canonical_display(w2)) pairs over a Tatoeba-style sentence export."""
    norm = normalizer_for(language)
    counts = Counter()
    opener = bz2.open if str(path).endswith(".bz2") else open
    with opener(path, mode="rt", encoding="utf-8", errors="replace") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            sentence = parts[2] if len(parts) >= 3 else parts[0]
            tokens = tokenize(sentence)
            norms = [norm(t) for t in tokens]
            for i in range(len(tokens) - 1):
                n1, n2 = norms[i], norms[i + 1]
                if n1 in valid_norms and n2 in valid_norms:
                    counts[(n1, canonical_by_norm[n2])] += 1
    return {pair: c for pair, c in counts.items() if c >= min_count}


def scale_bigrams(mined, phrase_bigrams, max_per_w1):
    """Log-scales mined counts to 1..200 (curated phrase chains keep their 200-255 band on top),
    then caps the follower list per w1."""
    scaled = {}
    if mined:
        top = max(mined.values())
        for pair, count in mined.items():
            scaled[pair] = max(1, round(200 * math.log1p(count) / math.log1p(top)))
    for pair, freq in phrase_bigrams.items():
        scaled[pair] = max(scaled.get(pair, 0), freq)
    by_w1 = defaultdict(list)
    for (w1, w2), freq in scaled.items():
        by_w1[w1].append((freq, w2))
    capped = {}
    for w1, followers in by_w1.items():
        followers.sort(reverse=True)
        for freq, w2 in followers[:max_per_w1]:
            capped[(w1, w2)] = freq
    return capped


def build(args):
    language = args.language
    norm = normalizer_for(language)

    print(f"[1/5] loading wordlists: {args.wordlist}")
    weights = load_wordlists(args.wordlist, language, args.max_words)
    freqs = scale_frequencies(weights)
    print(f"      {len(freqs)} base words")

    print(f"[2/5] applying overlays: {args.overlay}")
    word_boosts, phrase_bigrams = load_overlays(args.overlay, language)
    for word, freq in word_boosts.items():
        freqs[word] = max(freqs.get(word, 0), freq)
    print(f"      {len(word_boosts)} overlay words, {len(phrase_bigrams)} phrase-chain bigrams")

    blocked = set()
    for path in args.blocklist:
        with open(path, encoding="utf-8") as f:
            blocked.update(w.strip() for w in f if w.strip() and not w.startswith("#"))

    # Resolve canonical display spelling per norm key (highest frequency wins).
    rows = []
    canonical_by_norm = {}
    for word, freq in freqs.items():
        n = norm(word)
        if not is_valid_word(language, n):
            continue
        flags = FLAG_POSSIBLY_OFFENSIVE if word in blocked or n in blocked else 0
        rows.append((word, n, freq, flags))
        best = canonical_by_norm.get(n)
        if best is None or freqs[best] < freq:
            canonical_by_norm[n] = word
    valid_norms = set(canonical_by_norm)

    mined = {}
    if args.sentences:
        print(f"[3/5] mining bigrams from {args.sentences}")
        mined = mine_bigrams(args.sentences, language, valid_norms, canonical_by_norm, args.min_bigram)
        print(f"      {len(mined)} mined bigrams (min count {args.min_bigram})")
    else:
        print("[3/5] no sentence corpus given, skipping bigram mining")

    bigrams = scale_bigrams(mined, phrase_bigrams, args.max_bigrams_per_word)
    print(f"[4/5] {len(bigrams)} bigrams after scaling/capping")

    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    if out.exists():
        out.unlink()
    db = sqlite3.connect(out)
    db.executescript(
        """
        PRAGMA page_size = 4096;
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
        CREATE TABLE words (
            word TEXT PRIMARY KEY,
            norm TEXT NOT NULL,
            freq INTEGER NOT NULL,
            flags INTEGER NOT NULL DEFAULT 0
        ) WITHOUT ROWID;
        CREATE INDEX idx_words_norm ON words(norm);
        CREATE TABLE bigrams (
            w1_norm TEXT NOT NULL,
            w2 TEXT NOT NULL,
            freq INTEGER NOT NULL,
            PRIMARY KEY (w1_norm, w2)
        ) WITHOUT ROWID;
        """
    )
    db.executemany("INSERT INTO words VALUES (?,?,?,?)", rows)
    db.executemany(
        "INSERT INTO bigrams VALUES (?,?,?)",
        [(w1, w2, freq) for (w1, w2), freq in bigrams.items()],
    )
    meta = {
        "schema_version": "1",
        "language": language,
        "word_count": str(len(rows)),
        "bigram_count": str(len(bigrams)),
        "sources": args.sources_note,
        "license": args.license_note,
    }
    db.executemany("INSERT INTO meta VALUES (?,?)", meta.items())
    db.commit()
    db.execute("VACUUM")
    db.close()
    size_kib = out.stat().st_size // 1024
    print(f"[5/5] wrote {out} ({size_kib} KiB, {len(rows)} words, {len(bigrams)} bigrams)")


# --- Self test (mirror of ArabicNormalizerTest.kt) -----------------------------------------------

GOLDEN_AR = [
    ("أحمد", "احمد"), ("إسلام", "اسلام"), ("آمين", "امين"), ("ٱلله", "الله"),
    ("مدرسة", "مدرسه"), ("حياة", "حياه"), ("مصطفى", "مصطفي"), ("على", "علي"),
    ("مسؤول", "مسوول"), ("رئيس", "رييس"), ("شيء", "شيء"),
    ("مُحَمَّد", "محمد"), ("الحمدُ لِلَّه", "الحمد لله"), ("مـــرحبا", "مرحبا"), ("قُرْآن", "قران"),
    ("سنة ٢٠٢٦", "سنه 2026"), ("۱۲۳", "123"),
    ("شو", "شو"), ("هلق", "هلق"), ("منيح", "منيح"), ("بدي", "بدي"), ("كتير", "كتير"),
]
GOLDEN_LATIN = [("Hello", "hello"), ("café", "cafe"), ("keyboard", "keyboard")]


def self_test():
    failures = 0
    for word, expected in GOLDEN_AR:
        actual = normalize_ar(word)
        if actual != expected:
            print(f"FAIL ar: {word!r} -> {actual!r}, expected {expected!r}")
            failures += 1
    for word, expected in GOLDEN_LATIN:
        actual = normalize_latin(word)
        if actual != expected:
            print(f"FAIL latin: {word!r} -> {actual!r}, expected {expected!r}")
            failures += 1
    if failures:
        sys.exit(f"{failures} golden case(s) failed")
    print(f"self-test OK ({len(GOLDEN_AR) + len(GOLDEN_LATIN)} golden cases)")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--self-test", action="store_true", help="run normalizer golden cases and exit")
    parser.add_argument("--language", choices=["ar", "en"], help="target language")
    parser.add_argument("--wordlist", action="append", default=[], help="frequency list ('word count' per line)")
    parser.add_argument("--overlay", action="append", default=[], help="curated TSV overlay (text<TAB>freq)")
    parser.add_argument("--sentences", help="sentence corpus for bigram mining (Tatoeba export, may be .bz2)")
    parser.add_argument("--blocklist", action="append", default=[], help="possibly-offensive word list")
    parser.add_argument("--max-words", type=int, default=60000)
    parser.add_argument("--min-bigram", type=int, default=2, help="min corpus count for a mined bigram")
    parser.add_argument("--max-bigrams-per-word", type=int, default=16)
    parser.add_argument("--sources-note", default="", help="free-text source attribution stored in meta")
    parser.add_argument("--license-note", default="", help="free-text license note stored in meta")
    parser.add_argument("--output", help="output sqlite3 path")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return
    if not (args.language and args.wordlist and args.output):
        parser.error("--language, --wordlist and --output are required (or use --self-test)")
    build(args)


if __name__ == "__main__":
    main()
