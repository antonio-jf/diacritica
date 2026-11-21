# Changelog

## Unreleased

- Voice drill, hands-free: instead of answering you can say “repeat”, “slower”, “skip” or “stop” in any of the six languages (otra vez, répète, noch mal, ripeti, 再说一遍…). Repeat says “Again.” and asks again without marking you wrong; slower repeats at a slower speed; skip gives the answer and moves on; stop ends with the summary. A right answer always wins over a command. If nothing is heard, the question is asked once more.
- Voice drill: Mandarin offline speech pack now detected and downloadable (the phone's recognizer calls it cmn-Hans-CN, not zh-CN); offline recognition uses the phone's own tag; a message when a pack can't be downloaded, and a link to the phone's voice settings.
- Voice drill: a language can be one of your languages and one you practise at the same time, so any pair works (中文 → FR, DE → IT…); a question never asks and answers in the same language, and every pair comes up before any repeats. Questions and feedback are spoken in the question's own language (French, German, Italian and Mandarin frames added).
- Translate with Gemini: optional Grammar suggestions (switch under the engine, on by default): up to 3 grammar points of the language you translate from that your text uses, each with a short explanation and Make a lesson (a full lesson in Grammar › Full lessons). A separate request, so translations don't wait for it.
- Voice drill: mix question types (pick any of Say it, Listen and translate, Repeat); more than one "your language" (questions take turns between them); Endless length.
- Review: two new card types for saved words (Settings › Review, both on): Translate a sentence (a Tatoeba sentence with the word in a language you know, else the dictionary example, else one written by Gemini) and Use it in a sentence (write your own; Gemini checks the word's use and grammar). Type your answer and Check: exact matches offline, otherwise Gemini; the check suggests a rating. Without a Gemini key you compare with the answer and rate yourself.

- Voice drill (Home tile, can go in the bottom bar): questions asked out loud, answers by voice. Say it (hear a word in your language, say it in another), Listen and translate, or Repeat; words from Saved words, Vocabulary sets or Rehearse sentences; questions take turns between the languages you pick, each answer recognized in its own language. Mandarin answers also match by sound. Check pronunciation records you and asks Gemini (per syllable and tone for Mandarin, per word otherwise). Options: next question by itself, count in Review (due cards of saved words). Shows which languages lack an offline speech pack, with a download button.

- Library filters: counts sit in a small pill apart from each label (also for sources); date periods with no words are hidden; "Saved" heading renamed "Date saved"; save dates follow the phone's date format.
- Library › Saved: search box (word or its sentence/note, accents ignored) and a Filter button with language, where it was saved from, when it was saved (today, 7 days, 30 days, this year, older) and sort (newest, oldest, A–Z). Each word shows the date it was saved.
- Library: tapping a saved word or a past search opens the word sheet over the list (meaning, translations, characters, listen, save); Open in Dictionary is still there for the full entry.
- Mandarin words: a compact Characters list under the word (Dictionary, word page and word sheet) with each character's pinyin in that word and its own short meaning; tap a character to look it up.
- Settings › Rehearse › Tap words for meanings (off by default): tap any word in Rehearse prompts, references and alternatives, Test sentences and Review sentence cards to open the word sheet. Chinese picks the longest dictionary word around the tapped character.
- Dictionary: while idle, the search box sits lower on the screen under a short prompt and glides to the top when you tap it.
- Appearance: themes are now a gallery of starting points you can change part by part — headings font, text font, Chinese characters (sans or serif), background (Cream, Sage, Slate, White, Stone, Rose), accent, corners, surfaces and spacing. Save the result as your own theme; saved themes appear in the gallery.

- Settings is always in the bottom bar, at the far right (no more going through Home). The bar fits six items.
- Look up from any app: select text in Kindle, Chrome or elsewhere and pick "Look up in Diacritica". A word opens in the word sheet on top of that app; longer text opens in Translate.
- Keyboard language: answer boxes in Rehearse, Tests, Vocabulary tests, Translate (with a source language) and the Dictionary (with a source language) tell the keyboard their language, so it switches layout and autocorrect by itself.
- One word sheet everywhere: Reader, Books, Vocabulary (Details under a word), Review (Word details on a revealed card) and the overlay share the same layout and buttons.
- Saved words: a Saved card on Home with the count; Library filters by language and by where the word was saved (Dictionary, Reader, Books, Vocabulary, Review, Other apps).
- Customize Home: reorder or hide any card (Home › Customize Home, or Settings › Home).
- Settings search: type to show only the matching sections, already open.
- Rehearse keeps the sentence and what you typed if Android closes the app in the background.
- Offline: online Rehearse paths are grayed out with "needs internet"; Gemini feedback offline shows a Retry; searches made offline are now downloaded automatically when you're back online, with a note in the Dictionary.
- Clearer empty screens (Library, Review, Rehearse archive, Progress).
- Pinyin above characters in the Reader and Books grows with the Chinese characters size.
- Compose BOM 2025.04.01 (needed for keyboard language hints).

- Vocabulary: tap a word (or All languages) to see it in every language you've set up; language chips use abbreviations (EN, FR, DE…).
- Vocabulary › More topics: a catalog of 40 more specific topics (plants, science, engineering, business, music, law…) and any topic you name; Gemini writes the set in all six languages once, and it's kept for offline use.
- Books: with text selected, tapping anywhere on the page (a word or blank space) clears the selection.
- Vocabulary: 25 word sets by topic (food, people, things, places, nature, actions, time, health and hobbies) in all six languages, with articles and pinyin. Listen, add words to Review, and finish each set with a mini test (choose or type; typed answers checked for the article and accents, Mandarin in characters or pinyin).
- German verbs show how they're used: reflexive *sich*, prepositions and the case they take (e.g. *sich freuen auf + Akk.*).
- Separate size for Chinese characters in the Reader and Books text (Settings → Appearance), on top of the general text size. Menus and the rest of the app keep the normal size.
- Full grammar lessons: Gemini expands any lesson into a complete one, with how it's formed, each use with annotated examples, common mistakes, a comparison with English and Spanish, a tip and graded practice. Kept once written, in their own tab (Grammar › Full lessons), each opening as a page; lessons made from a passage are expanded automatically.
- Ask for a lesson (Grammar › Full lessons): describe a word, a Chinese character or a structure, and Gemini writes a full lesson on it.
- Reader: Rehearse on a sentence asks which language to rehearse from, any of your languages; ones the story isn't written in are translated first.
- Lessons from test reviews and passage breakdowns are longer, with more examples.

## 1.0 — first stable version (2026-11-16)

Releases are tagged `v1.0.<build>`; the build number keeps counting from the 0.3 line, so the in-app updater offers 1.0 to phones on 0.3.x.

**Dictionary**
- Offline dictionary for English, French, German, Italian, Spanish and Mandarin (Wiktionary, CC-CEDICT), as a downloadable data pack; missing words fetched online.
- Conjugation and declension tables, inflected-form notes, false-friend warnings, synonyms and etymology, German separable verbs.
- Mandarin: pinyin search, simplified/traditional, HSK levels, handwriting input; phrasal English links so words like 起床 reach every language.

**Reader**
- Parallel stories in two languages (Gemini-written, your own text, or the Classics pack), four layouts, read aloud, tap-to-lookup, known-word shading, adaptive pinyin.

**Books**
- Import your own DRM-free EPUBs and read them in their language, with tap-to-lookup, contents, search and reading settings.
- Normal text selection with a compact toolbar: Explain (Gemini breakdown: translation, word by word, grammar points, vocabulary), Translate, Rehearse, Highlight, Copy.
- Grammar lessons from passages, notes, highlights, bookmarks, swipe between chapters.

**Practice**
- Rehearse: translate sentences or short passages into your languages, graded offline and by Gemini; paths from everyday sentences to news, formal, encyclopedic, grammar, conversation, idioms, your words, your reading and weak spots; level 1–10.
- Review: FSRS spaced repetition from saved words and sentences.
- Tests: bundled and on-demand tests with scores, corrections and grammar lessons.
- Grammar: weak points and lessons from Rehearse, Tests and Books.

**Everything else**
- Translate (on-device or Gemini), Progress (accuracy, mistakes, Review, reading), Library, configurable bottom bar, four themes, large-text support.
- Backup and restore, automatic daily backup, signed releases with in-app updates.
