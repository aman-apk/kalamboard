# Suggestion dictionaries

This directory holds the sources and tooling inputs for the offline suggestion dictionaries
shipped in `app/src/main/assets/ime/dict/<lang>.sqlite3` and consumed by
`ime/nlp/words/SqliteWordDictionary.kt`.

## Layout

| Path | Content |
|---|---|
| `levantine/*.tsv` | Curated Levantine (Syrian) lexicon, `text<TAB>freq(200-255)` per line. ~1.7k entries across 10 categories (particles, verbs, adjectives, greetings, religious, people, daily, timeplace, chat, questions). Multi-word phrases are decomposed into word entries + high-frequency bigram/trigram chains. |
| `egyptian/*.tsv`, `gulf/*.tsv`, `iraqi/*.tsv` | Curated Egyptian / Gulf / Iraqi lexicons, same format and categories. |
| `reference/en_words_upstream.json` | The original upstream English word list (`ime/dict/data.json`, 49,981 words) kept for comparison; no longer shipped in the APK. |
| `sources/raw/` | Downloaded corpora (git-ignored). Re-download with the commands below. |

## How dialects ship (runtime overlays, NOT baked into the DB)

The base `ar.sqlite3` is **pure MSA (فصحى)**. Each dialect's category files are merged into one
TSV asset `app/src/main/assets/ime/dict/overlays/ar_<dialect>.tsv`, which
`ime/nlp/words/DialectOverlay.kt` parses at dictionary load time according to the
`suggestion__arabic_dialect` setting (default: Levantine). Word entries boost/extend the in-memory
index; phrase chains merge into bigram/trigram lookups. `DialectOverlay.parse` mirrors
`load_overlays()` in `utils/build_dictionary.py` — keep them in sync.

Regenerate a merged overlay asset after editing a dialect's category files:

```bash
d=levantine  # or egyptian / gulf / iraqi
{ echo "# ${d} dialect overlay — merged from dictionaries/${d}/*.tsv";
  echo "# columns: text<TAB>freq(200-255) — applied at runtime by DialectOverlay.kt";
  grep -hv '^#' dictionaries/${d}/*.tsv; } \
  > app/src/main/assets/ime/dict/overlays/ar_${d}.tsv
```

## Rebuilding the dictionaries

```bash
# Raw sources (dev machine only — the app itself never touches the network):
cd dictionaries/sources/raw
for l in ar en fr tr; do
  curl -sLO https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/$l/${l}_50k.txt
done
for l in ara eng fra tur; do
  curl -sLO https://downloads.tatoeba.org/exports/per_language/$l/${l}_sentences.tsv.bz2
done
cd ../../..

python3 utils/build_dictionary.py --self-test

python3 utils/build_dictionary.py --language ar \
  --wordlist dictionaries/sources/raw/ar_50k.txt \
  --sentences dictionaries/sources/raw/ara_sentences.tsv.bz2 \
  --min-bigram 2 --min-trigram 2 --max-words 60000 \
  --sources-note "OpenSubtitles 2018 frequency list (hermitdave/FrequencyWords) + Tatoeba ara sentences. Dialect layers (Levantine etc.) ship separately as runtime TSV overlays, see dictionaries/README.md" \
  --license-note "FrequencyWords: CC-BY-SA-4.0; Tatoeba: CC-BY 2.0 FR" \
  --output app/src/main/assets/ime/dict/ar.sqlite3

python3 utils/build_dictionary.py --language en \
  --wordlist dictionaries/sources/raw/en_50k.txt \
  --sentences dictionaries/sources/raw/eng_sentences.tsv.bz2 \
  --min-bigram 4 --min-trigram 8 --max-words 55000 \
  --sources-note "OpenSubtitles 2018 frequency list (hermitdave/FrequencyWords) + Tatoeba eng sentences" \
  --license-note "FrequencyWords: CC-BY-SA-4.0; Tatoeba: CC-BY 2.0 FR" \
  --output app/src/main/assets/ime/dict/en.sqlite3

# French / Turkish use the same recipe (swap fr/fra resp. tr/tur):
python3 utils/build_dictionary.py --language fr \
  --wordlist dictionaries/sources/raw/fr_50k.txt \
  --sentences dictionaries/sources/raw/fra_sentences.tsv.bz2 \
  --min-bigram 4 --min-trigram 8 --max-words 55000 \
  --sources-note "OpenSubtitles 2018 frequency list (hermitdave/FrequencyWords) + Tatoeba fra sentences" \
  --license-note "FrequencyWords: CC-BY-SA-4.0; Tatoeba: CC-BY 2.0 FR" \
  --output app/src/main/assets/ime/dict/fr.sqlite3
```

Language-specific engine notes: `WordNormalizer` folds the Turkish dotless ı to i (both sides,
golden-tested), and `KeyProximity.forLanguage` has dedicated AZERTY (fr) and Turkish-Q (tr) rows.

After rebuilding `ar.sqlite3`, also refresh the JVM test resource:
`app/src/test/resources/ar_words.tsv` (dump `SELECT word, freq FROM words ORDER BY word`,
keep the two `#` header lines — see `RealDictionaryQualityTest.kt`).

Adding another language = another frequency list (+ optional overlay/corpus) + one
`build_dictionary.py` call producing `<iso639-1>.sqlite3`; the engine picks it up purely by
`subtype.primaryLocale.language`, no code change needed.

## Data licenses

| Source | License | Used for |
|---|---|---|
| [hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords) (OpenSubtitles 2018) | CC-BY-SA-4.0 | ar/en word frequencies |
| [Tatoeba](https://tatoeba.org) sentence exports | CC-BY 2.0 FR | ar/en bigrams |
| `dictionaries/levantine/` (authored in this repo) | Apache-2.0 | Syrian/Levantine layer |

The license/source notes are also embedded in each database's `meta` table.

## Keeping the normalizers in sync

`utils/build_dictionary.py` (`normalize_ar`/`normalize_latin`) re-implements
`ime/nlp/words/WordNormalizer.kt`. The same golden cases are pinned twice:
`ArabicNormalizerTest.kt` (Kotlin) and `build_dictionary.py --self-test` (Python).
Change one → change all four places.
