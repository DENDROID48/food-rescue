// Builds the page's translation files (src/main/resources/static/i18n/<lang>.json) from
// keys.txt and <lang>.txt in this folder, and reports anything missing or inconsistent.
// Usage: node src/main/i18n/build.js
const fs = require('fs');
const path = require('path');

const dir = __dirname;
const out = path.join(dir, '..', 'resources', 'static', 'i18n');
const LANGUAGES = ['ro', 'es', 'fr', 'de', 'it', 'pt'];

function read(file) {
  const map = new Map();
  for (const line of fs.readFileSync(path.join(dir, file), 'utf8').split('\n')) {
    if (!line.trim() || line.startsWith('#')) continue;
    const bar = line.indexOf('|');
    if (bar < 1) throw new Error(`${file}: no "id|" at the start of: ${line}`);
    const id = Number(line.slice(0, bar));
    if (map.has(id)) throw new Error(`${file}: id ${id} appears twice`);
    map.set(id, line.slice(bar + 1));
  }
  return map;
}

const placeholders = (s) => (s.match(/\{[xn]\}/g) || []).join('') + '/' + ((s.match(/\{\d\}/g) || []).length);
const keys = read('keys.txt');
fs.mkdirSync(out, { recursive: true });
let problems = 0;
for (const lang of LANGUAGES) {
  const file = `${lang}.txt`;
  if (!fs.existsSync(path.join(dir, file))) { console.log(`${lang}: no ${file}, skipped`); continue; }
  const tr = read(file);
  const dict = {};
  const missing = [];
  for (const [id, english] of keys) {
    const text = tr.get(id);
    if (text == null || !text.trim()) { missing.push(id); continue; }
    // Same {x}/{n} in the same order, or explicitly numbered ones ({1}, {2}, ...).
    const numbered = /\{\d\}/.test(text);
    const want = (english.match(/\{[xn]\}/g) || []).length;
    const have = numbered ? new Set(text.match(/\{\d\}/g)).size : (text.match(/\{[xn]\}/g) || []).length;
    if (want !== have) { console.log(`${lang} ${id}: placeholders differ: "${english}" -> "${text}"`); problems++; }
    dict[english] = text;
  }
  for (const id of tr.keys()) if (!keys.has(id)) { console.log(`${lang} ${id}: not in keys.txt`); problems++; }
  if (missing.length) { console.log(`${lang}: ${missing.length} missing: ${missing.join(', ')}`); problems++; }
  fs.writeFileSync(path.join(out, `${lang}.json`), JSON.stringify(dict));
  console.log(`${lang}: ${Object.keys(dict).length} of ${keys.size}`);
}
process.exitCode = problems ? 1 : 0;
