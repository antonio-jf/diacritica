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

- **Data pipeline:** builds the starter dictionary and runs `pipeline/test_build.py`, `test_corpora.py`, `test_books.py` and `test_packs.py`.
- **Android:** JVM unit tests (`./gradlew testDebugUnitTest`), a debug APK build (downloadable from the run's artifacts) and a check that the Room schema JSON is committed.
- **Lint:** `./gradlew lintDebug` fails on errors; warnings are listed in the HTML report attached to the run.

The repository ships the ~15-word starter dictionary at `app/src/main/assets/dict/dictionary.db`. The full dictionary (~240 MB, over GitHub's file limit) is built locally with `pipeline/build_dictionary.py`; after building it, run `git update-index --skip-worktree app/src/main/assets/dict/dictionary.db` so git leaves it alone.

## Releases

Every push to `main` (a merged pull request) runs `.github/workflows/release.yml`: unit tests, a signed release APK, and a GitHub Release tagged `v0.3.<commit count>` with the APK and its SHA-256. The release APK contains only 64-bit ARM code (every recent phone and tablet; about 40 MB); debug builds keep all processor types for emulators. Download the APK from the latest release on the phone and install it; each release updates the previous one. You can also run it by hand from the Actions tab.

Signing: all releases use one key, kept out of the repo. CI reads it from four repository secrets (`DIACRITICA_KEYSTORE_BASE64`, `DIACRITICA_KEYSTORE_PASSWORD`, `DIACRITICA_KEY_ALIAS`, `DIACRITICA_KEY_PASSWORD`). Locally, `keystore.properties` in the project root (gitignored) points at the same key, so builds from Android Studio and from Releases can update each other. Without it, local builds use the debug key. Lose the key and future releases can't update installed copies: keep a backup.

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
   is unavailable, the app falls back to `gemini-3.5-flash-lite`, `gemini-3.1-flash-lite`, then `gemini-2.5-flash`.
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

They are not bundled in the APK: install them from **Settings › Data packs** (about 2 MB). To rebuild the pack yourself (needs internet; Europarl is the big download, about 200 MB per language pair; files are cached in `data/opus/`):

```
cd pipeline
python3 build_corpora.py --freq data/en_50k.txt --out build/corpora.db
```

English is the pivot: sentences with the same English text are grouped across the EN–FR/DE/IT/ES/ZH files (Global Voices calls Mandarin `zhs`/`zht`; the script tries these codes). Mandarin rarely shares an English sentence with the others, so English–Mandarin pairs are also kept, up to a quarter of each level. Levels come from the rarest English word, as for Tatoeba (A2 ≤ 2,000 … C1 ≤ 30,000); these texts run harder, so when B2 is on, C1 sentences are included. `--corpora news,encyclopedic` builds only some. Then run `make_packs.py --corpora build/corpora.db` and upload the pack (see Data packs). Until the pack is installed, these paths show an everyday sentence.

## Tests

Eleven bundled tests of 8 sentences each, from A2 (HSK 2) to B2: everyday basics, past tenses, the subjunctive, if-clauses, object pronouns, prepositions and German cases, word order, comparisons and relative clauses, work emails, Mandarin 了/过/measure words, and travel. Read the sentences in English or Spanish and translate them into any of your languages (by default the ones you're learning).

- Answers are saved as you type; leave and come back to an unfinished test any time.
- **Send for review:** Gemini (your key) marks every answer out of 100 with corrections and explanations, writes a short summary, and adds 1–4 grammar lessons aimed at your recurring mistakes, each with examples and two practice sentences. *Practise as a mini test* turns those into a follow-up test.
- Without a key or internet the attempt waits as *Waiting for review*; send it later.
- Reviewed tests count in Progress (accuracy per language and mistakes by type) and are included in backups.
- The tests live in `app/src/main/assets/tests/tests.json`; add your own sets there.

## When a language has no word for a meaning

Wiktionary's translation tables have gaps (*trickster* has none at all). The dictionary then looks for words in that language whose own English definition is the word: French *filou* "trickster", German *Tagtraum* "daydream". Exact definitions rank first, then lists, then phrases; matching part of speech is preferred. These show as "Matched by definition (Wiktionary)". If nothing matches, **Suggest a translation** asks the Translate engine: Gemini (knows the meaning, adds articles) when selected and a key is set, otherwise ML Kit on the phone. Suggestions are labelled as machine translations.

## Mandarin lookups

- **Everyday reading first** for characters with several readings (看 kàn before kān, 行 xíng before háng, 还 hái before huán): a hand-checked list (`assets/zh/main_readings.tsv`), then ordinary readings before surnames and place names, then the reading Wiktionary's translations use most.
- **HSK levels** (HSK 3.0; 7 = levels 7–9) from `assets/zh/hsk.tsv` ([complete-hsk-vocabulary](https://github.com/drkameleon/complete-hsk-vocabulary), MIT), so no dictionary rebuild is needed.
- **Meanings in CC-CEDICT's order:** 放 shows *put, place, release, free…* rather than *lay* first. Up to three CC-CEDICT meanings that Wiktionary doesn't link are added (放: *release*, *free*).

## Gemini models and free limits

Settings › Gemini offers 3.5 Flash-Lite (default: the largest free allowance, about 500 requests a day), 3.6 Flash and 3.8 Flash (smarter, about 20 free requests a day), or any model ID. When a model runs out of free requests for the day, the app switches to Flash-Lite until midnight Pacific time. A retired ID saved earlier (`gemini-3.5-flash`, which Google now forwards to 3.6 Flash and its small quota) is replaced by the default.

## Grammar

Home › Grammar (also linked from Tests) gathers what you keep getting wrong:

- **Weak points:** mistake types per language from Gemini-checked Rehearse rounds and reviewed tests, worst first (mistakes in the last 30 days, then overall), with the trend against the 30 days before and your latest example. A type that happened only once is left out. The top three are marked *Recommended next*.
- **Practice test:** Gemini writes six new sentences aimed at that point, based on your own mistakes, and opens them as a test (reviewed like any other, so it feeds back into this page).
- **Rehearse:** switches Rehearse to Grammar focus on the matching structure (German cases, subjunctive, 了/过…).
- **Your lessons:** every lesson from your test reviews, newest first; the same lesson from several tests appears once with a count. Each can be practised as a mini test.

## Reader: forms and pinyin

- **Inflected words** say what they are: long-press *nahm* and the popup shows "nahm: first/third-person singular preterite of **nehmen** ›"; tap it to look up *nehmen*. Also in the Dictionary. Ambiguous forms list both (*fui*: preterite of *ir* and of *ser*).
- **Pinyin** (拼音 menu in Mandarin stories): on every word, only on words with uncommon characters (outside the 500 / 1,000 / 2,000 / 3,000 most common; default 1,000), or off. Character ranks come from the most frequent HSK word each character appears in (`assets/zh/char_rank.txt`). A sentence with no uncommon characters gets no pinyin row.

## Chinese handwriting

A writing square (with 田字格 guide lines) for Mandarin characters, in the Dictionary search bar, in Translate and in Mandarin test answers. Candidates appear as you write; tap one to insert it. Recognition runs on the phone with ML Kit Digital Ink; the model (about 20 MB) downloads once, the first time you write.

## Word details

- **False friends:** look-alike words that mean something else in another of your languages get a warning, e.g. ES *embarazada* ≠ EN *embarrassed*. It shows on dictionary results, on a word's page and in the Reader's word popup, with what each word means and how to say it instead. The list (about 100 hand-checked pairs across EN, ES, FR, DE and IT) is `app/src/main/assets/falsefriends/false_friends.json`; add pairs there.
- **Synonyms and etymology** from Wiktionary, on a word's page. Tap a synonym to look it up. Dictionaries built before this have none; the page offers to fetch them for that word from Wiktionary, and rebuilding the dictionary (or the next dictionary pack) adds them for every word.
- **German separable verbs** are flagged from their conjugation table: *anfangen* → SEPARABLE an|fangen → fängt … an.

## Layouts

Each section has its own layout (Settings › Layouts, or the layout icon in the section's top bar); the theme stays the same everywhere.

| Section | Layouts |
|---|---|
| Dictionary results | List · Cards · Grid (two tiles per row, main word big) · Table (dense rows: language, word, grammar) |
| Rehearse feedback | Tabs per language · Stacked cards · Score tiles (verdict and error count per language, tap for details) · Report rows (compact, corrections inline, "Why" for explanations) |
| Reader | Top / bottom · Side by side · Interlinear · Numbered split |
| Word popup (Reader) | Bottom sheet · Floating card (centred dialog) · Tooltip (small card next to the word; "More" opens the sheet) |

## Progress

Home › Progress, for the last 7 days, 30 days or all time:

- **Rehearse:** accuracy per language (correct or acceptable, out of the answers that were checked).
- **Mistakes by type:** the error types from grading (gender, agreement, word order, tones…), overall or per language.
- **Review:** cards reviewed, share remembered (any answer but Again), per language, and mature cards (interval of 3 weeks or more).
- **Reading and words:** sentences read in the Reader, words read in your Reader languages (characters for Mandarin), lookups and saved words.

## Data packs

Settings › Data packs shows everything kept for offline use and downloads bigger data instead of building it on a computer:

- **Download:** packs listed in `packs.json` on the GitHub release tagged `data-packs` (full dictionary, Everyday sentences, News/Formal/Encyclopedic sentences, Classics). Downloads show progress, can be cancelled, are checked with SHA-256 and only then installed; on mobile data the app asks first for packs over 20 MB. *Remove* goes back to what ships with the app (the starter dictionary and Everyday sentences; News/Formal/Encyclopedic and Classics are packs only).
- **On this phone:** the dictionary (built-in, imported file or pack; import a `.db` you built), downloaded words (Wiktionary lookups), sentence counts per Rehearse source, Translate's on-device models per language (download or delete), and a shortcut to the phone's voices.

Publishing packs (on your computer, after building the databases):

```
cd pipeline
python3 make_packs.py --out dist \
    --dictionary ../app/src/main/assets/dict/dictionary.db \
    --sentences ../app/src/main/assets/sentences/sentences.db \
    --corpora build/corpora.db
gh release create data-packs dist/* --title "Data packs" --notes "Offline data for Diacritica" --latest=false
# updates: gh release upload data-packs dist/* --clobber
```

Once the full dictionary is a pack, the APK can ship with only the starter dictionary and stay small.

## Translate

Home › Translate turns a sentence or paragraph (up to 5,000 characters) into all your active languages at once.

- **From:** detect the language automatically (ML Kit language ID; Chinese characters are always Mandarin) or pick it.
- **On-device** (default): ML Kit translation on the phone. Each language's model downloads once (about 30 MB) and then works offline.
- **Gemini:** more natural translations, online with your key. Without a key or internet it falls back to on-device.
- Each result can be read aloud, copied, added to Review (your text on the front), used as the new source ("Translate from here"), or, for a single word, opened in the Dictionary. Mandarin results show pinyin.
- Selecting text in another app and choosing *Look up in Diacritica* opens a word in the Dictionary and longer text in Translate.

## Reader

The Reader opens on a gallery with three tabs:

- **My shelf:** your stories and books, with reading progress.
- **Classics:** public-domain novels and stories in two languages, lined up sentence by sentence (OPUS Books: Austen, Verne, Dumas, Kafka, Galdós…; EN, ES, FR, DE, IT, no Mandarin). Search by title or author; show only your Reader pair or all pairs. Opening a book puts it on your shelf. Comes as a data pack, or build it yourself:
  ```
  cd pipeline
  python3 build_books.py --out ../app/src/main/assets/books/books.db
  ```
  The script splits each language pair's text back into books using the `.ids` file in the OPUS download (which document each line comes from). The first build gave 104 books (36 titles by 21 authors) across the 10 pairs of EN/ES/FR/DE/IT, about 150 MB, so it isn't committed: publish it as a data pack (`make_packs.py --books`) or leave it in `assets/books/` for your own builds (it's then bundled into the APK).
- **New story:** one-tap story ideas at a suggested level, or your own topic and level; Gemini writes them in all six languages.

Reading:

- Any two languages; four layouts (Top / bottom, Side by side, Interlinear, Numbered split). In the two-pane layouts, **drag the divider** to give either language more room (double-tap to split evenly); it's remembered.
- Tap a sentence to highlight it in both languages and save your place. The sentence bar steps through, reads either side aloud, **sends the sentence to Rehearse** (the story's other languages are the references) or adds the pair to Review.
- Press and hold a word for its dictionary popup (bottom sheet, floating card or tooltip). Its **translation is underlined in the other language's sentence** when the dictionary's translations appear there (offline matching, including inflected forms such as *pêcheur* → *pêcheurs*).
- Mandarin: words segmented with CC-CEDICT, pinyin above the characters (toggle), traditional characters per Settings.

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
- On-device translation: Google ML Kit (Translate, Language ID), under Google's ML Kit terms.
- Rehearse sentences: Tatoeba (CC BY 2.0 FR); built locally from OPUS: Global Voices (CC BY 3.0), Europarl, Wikimedia translations (CC BY-SA). Cite J. Tiedemann, 2012, *Parallel Data, Tools and Interfaces in OPUS*, LREC 2012.
- Fonts (SIL Open Font License 1.1): Inter, Source Serif 4, Plus Jakarta Sans, IBM Plex Sans/Mono,
  Space Grotesk. Bundled as Latin + Latin Extended subsets. Chinese uses the phone's system font.
