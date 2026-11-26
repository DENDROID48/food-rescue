# Translations

The web page is written in English and shown in English, Romanian, Spanish, French, German,
Italian or Portuguese. The page picks the browser's language on a first visit; people can change it
on the sign-in screen and in Account settings, and the account remembers it (emails and push
notifications use it too).

## How it works

- `keys.txt` has every piece of interface text in English, numbered: `id|text`.
- `ro.txt`, `es.txt`, … have the translation of each line, under the same id.
- `node src/main/i18n/build.js` turns them into `src/main/resources/static/i18n/<lang>.json`, the
  files the page loads. It stops with an error when a line is missing or its placeholders differ.
  A Java test (`TranslatorTest`) fails when the built files are out of date.
- `static/i18n.js` swaps the text on the page as it appears, including text added later (lists,
  messages, errors from the server). The server translates push notifications with the same files
  (`Translator`); the two password emails are in `MailService`.

Placeholders:

- `{x}` is a part that changes, for example `Hand over {n} × {x}`. The parts are translated too
  when they are interface text themselves.
- `{n}` is a number only, so `{n} of {n}` never matches a product called "Box of chocolates".
- A translation can change the order with `{1}`, `{2}`, … instead of `{x}`/`{n}`.

Names that people type (shops, products, people, places) are never translated: they do not match
an entry, and code that shows them can wrap them in `translate="no"`.

## Adding or changing text

1. Add the English text to `keys.txt` with the next free id (never reuse or renumber an id).
2. Add the translation with the same id to every language file.
3. Run `node src/main/i18n/build.js`.
4. Run the browser check `npm run i18n` in `e2e/`: it fails if a screen still shows English.

To find text that is not in `keys.txt` yet, run `node collect-texts.js texts.json` in `e2e/` with the
app running: it walks through every role and dialog and saves every text the page showed.

## Adding a language

Add `<code>.txt`, the code to `LANGUAGES` in `build.js`, `i18n.js` (with its locale for dates) and
`AccountUpdateRequest.LANGUAGES`, an `<option>` to both language lists in `index.html`, and the
emails in `MailService`.

## Quality

The translations were written for this app, not by professional translators. Have a native speaker
read each language before launch, especially Romanian, and the legal pages (which are English only).
