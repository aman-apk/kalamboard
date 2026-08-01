# Suggestion dictionaries

This directory holds the sources and tooling inputs for the offline suggestion dictionaries
shipped in `app/src/main/assets/ime/dict/<lang>.sqlite3` and consumed by
`ime/nlp/words/SqliteWordDictionary.kt`.

## Layout

| Path | Content |
|---|---|
| `levantine/*.tsv` | Curated Levantine (Syrian) lexicon, `text<TAB>freq(200-255)` per line. ~1.7k entries across 10 categories (particles, verbs, adjectives, greetings, religious, people, daily, timeplace, chat, questions). Multi-word phrases are decomposed by the build tool into word entries + high-frequency bigram chains. |
| `reference/en_words_upstream.json` | The original upstream English word list (`ime/dict/data.json`, 49,981 words) kept for comparison; no longer shipped in the APK. |
| `sources/raw/` | Downloaded corpora (git-ignored). Re-download with the commands below. |

## Rebuilding the dictionaries

```bash
# Raw sources (dev machine only — the app itself never touches the network):
cd dictionaries/sources/raw
curl -sLO https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/ar/ar_50k.txt
curl -sLO https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/en/en_50k.txt
curl -sLO https://downloads.tatoeba.org/exports/per_language/ara/ara_sentences.tsv.bz2
curl -sLO https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2
cd ../../..

python3 utils/build_dictionary.py --self-test

python3 utils/build_dictionary.py --language ar \
  --wordlist dictionaries/sources/raw/ar_50k.txt \
  $(for f in dictionaries/levantine/*.tsv; do echo --overlay "$f"; done) \
  --sentences dictionaries/sources/raw/ara_sentences.tsv.bz2 \
  --min-bigram 2 --max-words 60000 \
  --sources-note "OpenSubtitles 2018 frequency list (hermitdave/FrequencyWords) + Tatoeba ara sentences + curated Levantine/Syrian lexicon (this repo, dictionaries/levantine/)" \
  --license-note "FrequencyWords: CC-BY-SA-4.0; Tatoeba: CC-BY 2.0 FR; curated lexicon: Apache-2.0" \
  --output app/src/main/assets/ime/dict/ar.sqlite3

python3 utils/build_dictionary.py --language en \
  --wordlist dictionaries/sources/raw/en_50k.txt \
  --sentences dictionaries/sources/raw/eng_sentences.tsv.bz2 \
  --min-bigram 4 --max-words 55000 \
  --sources-note "OpenSubtitles 2018 frequency list (hermitdave/FrequencyWords) + Tatoeba eng sentences" \
  --license-note "FrequencyWords: CC-BY-SA-4.0; Tatoeba: CC-BY 2.0 FR" \
  --output app/src/main/assets/ime/dict/en.sqlite3
```

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
