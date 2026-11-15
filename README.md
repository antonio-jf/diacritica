# Diacritica — multilingual dictionary

A multi-language application for word lookup, practice, and language mastering.

[![CI](https://github.com/antonio-jf/diacritica/actions/workflows/ci.yml/badge.svg)](https://github.com/antonio-jf/diacritica/actions/workflows/ci.yml)

An Android app (Kotlin + Jetpack Compose) for looking up a word once and seeing it in all your languages
at the same time: English, French, German, Italian, Spanish and Mandarin. Works fully offline.

Phase 1 (MVP) and v1 (Review, Rehearse) of the plan in the 语言 project:

| In this build | Coming next (per the roadmap) |
|---|---|
| Offline dictionary for 6 languages, any language as the source (or auto-detect) | Parallel Reader (v2) |
| Meaning picker for ambiguous words ("key": lock / music / keyboard / crucial) | More Rehearse paths (v2/v3) |
| Articles and gender, plurals, German genitive, IPA, examples | Grid/Table dictionary layouts |
| Mandarin: simplified + traditional, pinyin with tone marks, measure words, HSK badge | More layouts per section |
| Search ignores accents; pinyin search with or without tone numbers (`yaoshi`, `yao4shi`, `钥匙`, `鑰匙`) | Camera / handwriting input |
| Inflected forms lead to the base word (`chiavi` → `chiave`) | |
| Detail view: every sense, examples, inflection table, "look up from this word" | |
| Pronunciation (on-device text-to-speech, normal and slow) and voice input | |
| Saved words, search history | |
| Languages on/off and reordering; Chinese script preference | |
| 4 themes (Paper default · Tiles · Midnight · Index), light/dark, accent choice incl. Material You, text size, language colors | |
| First-launch theme picker; Dictionary layout switch (List / Cards) that never changes the theme | |
| "Look up in Diacritica" in the text-selection menu of other apps | |

## Continuous integration

`.github/workflows/ci.yml` runs on every push:

- **Data pipeline:** builds the starter dictionary and runs `pipeline/test_build.py` and `pipeline/test_corpora.py`.
- **Android:** JVM unit tests (`./gradlew testDebugUnitTest`), a debug APK build (downloadable from the run's artifacts) and a check that the Room schema JSON is committed.
- **Lint:** `./gradlew lintDebug` fails on errors; warnings are listed in the HTML report attached to the run.

The repository ships the ~15-word starter dictionary at `app/src/main/assets/dict/dictionary.db`. The full dictionary (~240 MB, over GitHub's file limit) is built locally with `pipeline/build_dictionary.py`; after building it, run `git update-index --skip-worktree app/src/main/assets/dict/dictionary.db` so git leaves it alone.

## Build and install

1. Open this folder in **Android Studio** (Ladybug 2024.2 or newer). Let Gradle sync; it downloads the
   Android Gradle Plugin 8.7, Kotlin 2.1 and the Compose libraries.
   If Studio offers to upgrade AGP/Kotlin, it is safe to accept.
2. Plug in the Galaxy S24 with USB debugging on (Settings › About phone › Software information › tap
   *Build number* 7 times; then Developer options › USB debugging).
3. Press **Run ▶**. Or from a terminal: `./gradlew installDebug`.
4. Run the unit tests with `./gradlew test`.

> This project was written in an environment without access to the Android SDK, so it has **not been
> compiled yet**. If the first build shows errors, paste them to Claude and they will be fixed.

## Review (v1)

Every saved word (☆) becomes a few flashcards:
- **Meaning:** word → its meaning in all your languages
- **Recall:** English meaning → the word
- **Listening:** hear it → recall it
- **Article** (DE/FR/IT/ES nouns): pick der/die/das, le/la, il/lo/la, el/la
- **Pinyin** (Mandarin)

Sentences you add from Rehearse become **Sentence** cards. Cards are scheduled with **FSRS-5**
(default weights, 90% target retention). After each card you rate it Again, Hard, Good or Easy; the
buttons show when the card will come back. The number of new cards per day is set in Settings › Review.

## Rehearse (v1)

1. A sentence is shown in one of your languages; the prompt language rotates.
2. You translate it into all the others, then press Check.
3. **Offline check, instant:** exact matches, accent-only differences, and pinyin tones for Mandarin
   answers typed in pinyin.
4. **Gemini check, online:** verdict, corrected answer (shown as a diff), every error with a full grammar
   explanation, and more natural alternatives. It needs your own free API key from
   https://aistudio.google.com (Settings › Rehearse). The key stays on the phone. If the chosen model
   is unavailable, the app falls back to `gemini-3.5-flash`, `gemini-3.1-flash-lite`, then `gemini-2.5-flash`.
5. Languages with no human translation get a reference written by Gemini once, then cached.
6. **Add to Review** turns a sentence into a Review card.

Feedback layouts: *Tabs per language* or *Stacked cards* (Settings › Layouts).

### Sentence data (Tatoeba)

The app ships 12 hand-written starter sentences. For thousands more, download these files from
https://downloads.tatoeba.org/exports/per_language/ into `pipeline/data/tatoeba/`:
- `eng/eng_sentences.tsv.bz2`, `fra/fra_sentences.tsv.bz2`, `deu/deu_sentences.tsv.bz2`,
  `ita/ita_sentences.tsv.bz2`, `spa/spa_sentences.tsv.bz2`, `cmn/cmn_sentences.tsv.bz2`
- `eng/eng-fra_links.tsv.bz2`, `eng/eng-deu_links.tsv.bz2`, `eng/eng-ita_links.tsv.bz2`,
  `eng/eng-spa_links.tsv.bz2`, `eng/eng-cmn_links.tsv.bz2`

Then run:

```bash
cd pipeline && python3 build_sentences.py \
  --dir data/tatoeba --freq data/en_50k.txt \
  --out ../app/src/main/assets/sentences/sentences.db
```

Each sentence's level is set by its rarest English word in the frequency list:
A2 (top 2,000), B1 (top 5,000) or B2 (top 12,000). Sentences are 4–14 words long, and the 8,000
covering the most languages are kept.

### Practice paths

Pick one with **Path ▾** at the top of Rehearse:

| Path | Where the sentence comes from | Works offline |
|---|---|---|
| Everyday | Tatoeba, human translations | yes |
| My words | Tatoeba sentences that use words you saved or looked up | yes |
| From my reading | Sentences you've already read in Reader stories (all six languages aligned) | yes |
| Weak spots | Rounds you got partly wrong, with the same prompt language and your earlier score | yes |
| Reverse | A language you're learning → English/Spanish | yes |
| News | Global Voices articles and their human translations (OPUS) | yes |
| Formal | European Parliament speeches and their official translations (no Mandarin) | yes |
| Encyclopedic | Translated Wikipedia articles | yes |
| Grammar focus | Gemini writes a sentence that needs one structure (subjunctive, past tenses, German cases, 了/过, 把/被…); prompt in EN/ES | no |
| Conversation | Gemini writes a spoken line, with the situation shown above it | no |
| Idioms & sayings | Gemini picks an idiom; each language uses its own equivalent | no |

Sentences written by Gemini are saved, so Weak spots can bring them back later.

### News, Formal and Encyclopedic sentences (OPUS)

These come from [OPUS](https://opus.nlpl.eu), an open collection of human-translated, sentence-aligned texts, so every language has a real reference and answers are checked offline:

| Path | Corpus | License |
|---|---|---|
| News | Global Voices (citizen journalism, volunteer translations) | CC BY 3.0 |
| Formal | Europarl (European Parliament proceedings) | free to use |
| Encyclopedic | Wikimedia (Wikipedia articles translated by editors) | CC BY-SA |

Build them once on your computer (needs internet; Europarl is the big download, about 200 MB per language pair; files are cached in `data/opus/`):

```
cd pipeline
python3 build_corpora.py --freq data/en_50k.txt --out ../app/src/main/assets/corpora/corpora.db
```

English is the pivot: sentences with the same English text are grouped across the EN–FR/DE/IT/ES/ZH files. Levels come from the rarest English word, as for Tatoeba (A2 ≤ 2,000 … C1 ≤ 30,000); these texts run harder, so when B2 is on, C1 sentences are included. `--corpora news,encyclopedic` builds only some. Then Run ▶ again so the app bundles the file. Until it exists, these paths show an everyday sentence.

## Reader (v2)

Parallel stories, aligned sentence by sentence in all six languages. Bottom tab **Reader**; Settings now lives behind the gear in **Library**.

- **Shelf:** a starter story ("The Old Fisherman", B1 · HSK 3) is added on first run. *Write a new story* asks Gemini (your key, online) for a story on a topic at your level (A2–C1, HSK 2–5); it is stored on the phone and reads offline afterwards. Trash icon deletes.
- **Languages:** pick the two languages at the top (⇄ swaps). Any pair of the six works.
- **Layouts** (top-bar icon, or Settings › Layouts › Reader): Top / bottom (two panes that scroll together), Side by side, Interlinear, Numbered split.
- **Tap a sentence** to highlight it in both languages; the bar at the bottom steps ↑↓, reads either side aloud and adds the pair to Review. The last highlighted sentence is your reading progress.
- **Press and hold a word** for the popup: offline lookup (downloads missing words when online lookup is on), translations into the other language and English, pronunciation, ☆ save (creates Review cards), *Open in Dictionary*, and a link to the language's reference dictionary (Oxford Learner's, RAE, Larousse, Duden, Treccani, MOE 國語辭典).
- **Mandarin:** text is split into words with CC-CEDICT; 拼音 toggles pinyin above the characters. Traditional characters follow Settings › Script.

## Backup & restore

Settings › Backup & restore writes one `.zip` with the user database (saved words, history, Review cards and logs, Rehearse archive, stories), all settings including the Gemini key, and the log of downloaded words. Restore replaces the app's data with the backup and restarts the app. Keep backups private: they contain your API key.

**Moving data from the old ID (`com.polyglot.dict`).** In `app/build.gradle.kts` set `legacyId = true` and Run: this updates the old app and gives it Backup. Back up, set `legacyId = false`, Run, then Restore in Diacritica. Afterwards the old app can be uninstalled.

## Words that aren't stored on the phone

When a word isn't in the offline dictionary, the app downloads it from kaikki.org (Wiktionary
data, one small file per word, e.g. `kaikki.org/dictionary/English/meaning/t/te/teacher.jsonl`) and
saves it, so the next search works offline. A foreign word is linked to the other languages through
the English words its definition points to (*enseignant* → *teacher*). Translations that have no entry
of their own get theirs fetched too, which adds gender, plural and examples.
Offline searches are remembered and downloaded the next time the app starts with internet.
Downloads are kept in `fetched.jsonl` and re-applied when the dictionary file is replaced.
Turn this off in Settings › Dictionary data › *Download missing words*.

## The dictionary data

The APK ships with a **starter dictionary of ~15 words** (key, house, book, water, dog, eat, beautiful, sea,
bank…), so the app works right away. Build the full one on your computer:

```bash
cd pipeline
# 1. Download (from https://kaikki.org/dictionary/ → each language → "Download JSONL"):
#    the English, French, German, Italian and Spanish extracts of English Wiktionary.
# 2. Download CC-CEDICT: https://www.mdbg.net/chinese/dictionary?page=cc-cedict
#    (cedict_1_0_ts_utf-8_mdbg.txt.gz)
# 3. Optional: an HSK word list as "word<TAB>level" lines (hsk.tsv).
python3 build_dictionary.py \
  --en kaikki.org-dictionary-English.jsonl --fr kaikki.org-dictionary-French.jsonl \
  --de kaikki.org-dictionary-German.jsonl --it kaikki.org-dictionary-Italian.jsonl \
  --es kaikki.org-dictionary-Spanish.jsonl --cedict cedict_1_0_ts_utf-8_mdbg.txt.gz \
  --hsk hsk.tsv --out dictionary.db
```

To keep the app small, add `--top 30000 --freq-dir freq/`. This keeps only the 30,000 most common
words per language, plus their translations. `freq/` holds `en.txt`, `fr.txt`, `de.txt`, `it.txt` and
`es.txt` frequency lists (one `word count` per line, e.g. the `xx_50k.txt` files from
github.com/hermitdave/FrequencyWords). Everything else is downloaded on demand.

Inputs may be `.jsonl` or `.jsonl.gz`. The English file is large (several GB). The script streams it,
needs only Python 3.9+ with no extra packages, and takes roughly 10–30 minutes. Use `--limit 20000`
for a quick trial run.

Then either:
- **Import it on the phone:** copy `dictionary.db` to the phone, then Settings › Dictionary data › *Import dictionary…*; or
- **Bundle it in the APK:** replace `app/src/main/assets/dict/dictionary.db` and rebuild. The app notices the new file and reinstalls it.

Pipeline tests: `python3 pipeline/sample/make_sample.py && python3 pipeline/test_build.py`.

### How lookups work

Every English Wiktionary sense that has a translation table becomes a *concept*. A search in any language
finds its concepts (the English headword, or the word in another language's translations). The selected
concept is then shown in every active language, enriched with that language's own entry (gender, plural,
IPA, forms, examples). Because translation tables live on English entries, ES→DE goes through English.
The meaning picker shows the English gloss, so you can see which sense you're looking at.

## Offline pronunciation and voice input

These use the phone's own engines. Install offline data once:
- **Voices:** Settings › General management › Text-to-speech › (engine) ⚙ › Install voice data → French, German, Italian, Spanish, Chinese.
- **Speech recognition:** Google app / Speech Recognition & Synthesis › Offline speech recognition → add each language.

## Project layout

```
app/src/main/java/app/diacritica/
  data/            dictionary access (SQLite), lookup logic, normalization, articles, Room user DB
  settings/        DataStore settings (global theme + per-section layouts)
  tts/             text-to-speech wrapper
  ui/theme/        design tokens, 4 themes × light/dark, fonts
  ui/components/   themed building blocks (every screen uses only these tokens)
  ui/dictionary/   search screen, List & Cards layouts, detail screen
  ui/library/      saved words, history
  ui/review/       Review screen (flashcards)
  ui/rehearse/     Rehearse screen (Tabs / Stacked feedback layouts)
  review/          FSRS scheduler, card generation
  rehearse/        sentence data, offline rule checks, word diff, Gemini client
  ui/settings/     appearance, layouts, languages, speech, dictionary data
  ui/onboarding/   first-launch theme picker
pipeline/          build_dictionary.py, tests, starter-sample generator
```

**Rule for future layouts:** a layout composable may only rearrange content. Colors, fonts, radii and
spacing come from `Diacritica.*` tokens, so the theme stays consistent across every layout.

## Licenses

The app's code is under the MIT License (see `LICENSE`). Data sources keep their own licenses:

- Dictionary data: Wiktionary via Wiktextract (CC BY-SA 4.0), CC-CEDICT (CC BY-SA 4.0).
- Rehearse sentences: Tatoeba (CC BY 2.0 FR); built locally from OPUS: Global Voices (CC BY 3.0), Europarl, Wikimedia translations (CC BY-SA). Cite J. Tiedemann, 2012, *Parallel Data, Tools and Interfaces in OPUS*, LREC 2012.
- Fonts (SIL Open Font License 1.1): Inter, Source Serif 4, Plus Jakarta Sans, IBM Plex Sans/Mono,
  Space Grotesk. Bundled as Latin + Latin Extended subsets. Chinese uses the phone's system font.
