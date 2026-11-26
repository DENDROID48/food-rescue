/*
 * Translations for the web page. The English text is the key: the page is written in English,
 * and this script swaps every piece of interface text for the chosen language as it appears,
 * including text that app.js adds later (lists, messages, errors from the server).
 *
 * Dictionaries are in i18n/<lang>.json (built from src/main/i18n, see README there). An entry
 * may contain {x} for a part that changes, e.g. "Hand over {n} × {x}", or {n} for a number
 * (so "{n} of {n}" never matches a product called "Box of chocolates"); the changing parts are
 * translated too when they are themselves interface text ("2 finds" inside a longer sentence).
 * A translation may reorder the parts with {1}, {2}, ...
 *
 * Text people typed (names of shops, products, people) sits inside translate="no" and is never
 * touched.
 */
const I18N = (() => {
  const LANGUAGES = ['en', 'ro', 'es', 'fr', 'de', 'it', 'pt'];
  const LOCALES = { en: 'en-GB', ro: 'ro-RO', es: 'es-ES', fr: 'fr-FR', de: 'de-DE', it: 'it-IT', pt: 'pt-PT' };
  const ATTRIBUTES = ['placeholder', 'aria-label', 'title', 'alt', 'data-label', 'data-pattern-message', 'data-required-message'];
  const SKIP = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEXTAREA', 'CODE']);

  let lang = 'en';
  let dict = null;      // English -> translation, for the current language
  let patterns = [];    // entries with {x}, as regular expressions
  const cache = new Map();
  // English originals, so switching language (or back to English) always starts from them.
  const textOriginal = new WeakMap();
  const textShown = new WeakMap();
  const attrOriginal = new WeakMap();
  let observer = null;

  const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const norm = (s) => s.replace(/\s+/g, ' ').trim();

  function compile() {
    const literal = (k) => k.replace(/\{[xn]\}/g, '').length;
    patterns = Object.keys(dict).filter((k) => /\{[xn]\}/.test(k))
      // More literal text first, so "{n} km away" wins over "{x} away".
      .sort((a, b) => literal(b) - literal(a))
      .map((k) => ({
        re: new RegExp('^' + k.split(/(\{[xn]\})/).map((part) =>
          part === '{x}' ? '(.+?)' : part === '{n}' ? '(\\d[\\d.,]*)' : escapeRe(part)).join('') + '$', 's'),
        out: dict[k],
      }));
    cache.clear();
  }

  /** The translation of one piece of text, or null when there is none. */
  function lookup(text, depth = 0) {
    if (!dict) return null;
    const hit = dict[text];
    if (hit != null) return hit;
    if (depth > 3) return null;
    if (depth === 0 && cache.has(text)) return cache.get(text);
    let result = null;
    for (const p of patterns) {
      const m = text.match(p.re);
      if (!m) continue;
      const parts = m.slice(1).map((c) => lookup(c, depth + 1) ?? c);
      let i = 0;
      result = p.out.replace(/\{(x|n|\d)\}/g, (_, k) => (k === 'x' || k === 'n' ? parts[i++] : parts[Number(k) - 1]) ?? '');
      break;
    }
    if (depth === 0) {
      if (cache.size > 5000) cache.clear();
      cache.set(text, result);
    }
    return result;
  }

  /** Translates a string (for code that needs the text itself, e.g. to fill in a form field). */
  function t(text) {
    if (!dict || text == null) return text;
    const core = norm(String(text));
    return core ? (lookup(core) ?? text) : text;
  }

  function translatable(el) {
    return el && !SKIP.has(el.tagName) && !el.closest('[translate="no"]');
  }

  function translateText(node) {
    const parent = node.parentElement;
    if (!translatable(parent)) return;
    // Text the app replaced since we last translated it is the new original.
    if (!textOriginal.has(node) || textShown.get(node) !== node.nodeValue) textOriginal.set(node, node.nodeValue);
    const original = textOriginal.get(node);
    let next = original;
    if (dict) {
      const core = norm(original);
      if (core && /[A-Za-z]/.test(core)) {
        const hit = lookup(core);
        if (hit != null) {
          const lead = original.match(/^\s*/)[0], trail = original.match(/\s*$/)[0];
          next = lead + hit + trail;
        }
      }
    }
    if (node.nodeValue !== next) node.nodeValue = next;
    textShown.set(node, next);
  }

  function translateAttributes(el) {
    if (!translatable(el)) return;
    let originals = attrOriginal.get(el);
    for (const name of ATTRIBUTES) {
      if (!el.hasAttribute(name)) continue;
      const value = el.getAttribute(name);
      if (!originals) attrOriginal.set(el, originals = {});
      const known = originals[name];
      if (!known || known.shown !== value) originals[name] = { original: value, shown: value };
      const original = originals[name].original;
      const next = dict ? (lookup(norm(original)) ?? original) : original;
      if (value !== next) el.setAttribute(name, next);
      originals[name].shown = next;
    }
  }

  function translateTree(root) {
    if (root.nodeType === Node.TEXT_NODE) { translateText(root); return; }
    if (root.nodeType !== Node.ELEMENT_NODE) return;
    if (!translatable(root)) return;
    translateAttributes(root);
    for (const el of root.querySelectorAll('*')) translateAttributes(el);
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    for (let n = walker.nextNode(); n; n = walker.nextNode()) translateText(n);
  }

  function observe() {
    if (observer) return;
    observer = new MutationObserver((records) => {
      for (const r of records) {
        if (r.type === 'childList') r.addedNodes.forEach(translateTree);
        else if (r.type === 'characterData') {
          if (textShown.get(r.target) !== r.target.nodeValue) translateText(r.target);
        } else if (r.type === 'attributes' && ATTRIBUTES.includes(r.attributeName)) {
          const known = attrOriginal.get(r.target)?.[r.attributeName];
          if (!known || known.shown !== r.target.getAttribute(r.attributeName)) translateAttributes(r.target);
        }
      }
    });
    observer.observe(document.documentElement, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ATTRIBUTES });
  }

  async function load(code) {
    if (code === 'en') return null;
    const res = await fetch(`i18n/${code}.json`);
    if (!res.ok) throw new Error('No translations for ' + code);
    return res.json();
  }

  /** Switches the page to a language; resolves once the text is swapped. */
  async function use(code) {
    if (!LANGUAGES.includes(code)) code = 'en';
    let next = null;
    try { next = await load(code); } catch { code = 'en'; }
    lang = code;
    dict = next;
    if (dict) compile(); else { patterns = []; cache.clear(); }
    document.documentElement.lang = code;
    translateTree(document.documentElement);
    observe();
    document.dispatchEvent(new CustomEvent('languagechange', { detail: code }));
  }

  /** The browser's own language if we have it, else English. */
  function preferred() {
    for (const l of navigator.languages || [navigator.language]) {
      const code = String(l).slice(0, 2).toLowerCase();
      if (LANGUAGES.includes(code)) return code;
    }
    return 'en';
  }

  return {
    LANGUAGES, use, t, preferred,
    get lang() { return lang; },
    /** For dates and numbers: toLocaleDateString(I18N.locale(), ...). */
    locale: () => LOCALES[lang],
  };
})();
