// Browser checks for the interface languages: every screen of every role in each language with no
// interface text left in English, choosing a language before and after signing in, and dates in
// the chosen language. Needs the app on localhost:8080 with fresh dev data.
// Usage: node i18n.js [screenshot dir]
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const AXE = fs.readFileSync(require.resolve('axe-core/axe.min.js'), 'utf8');

const BASE = 'http://localhost:8080/';
const OUT = process.argv[2] || path.join(__dirname, 'screenshots');
const I18N = path.join(__dirname, '..', 'src', 'main', 'i18n');
const BUILT = path.join(__dirname, '..', 'src', 'main', 'resources', 'static', 'i18n');
const LANGS = ['ro', 'es', 'fr', 'de', 'it', 'pt'];
fs.mkdirSync(OUT, { recursive: true });
const results = [];
const consoleErrors = [];
let browser;

// English interface text that each language translates differently: seeing it on screen means a miss.
const english = fs.readFileSync(path.join(I18N, 'keys.txt'), 'utf8').split('\n')
  .map((l) => l.match(/^\d+\|(.*)$/)?.[1]).filter((t) => t && !/\{[xn]\}/.test(t));
const untranslated = Object.fromEntries(LANGS.map((lang) => {
  const dict = JSON.parse(fs.readFileSync(path.join(BUILT, lang + '.json'), 'utf8'));
  return [lang, new Set(english.filter((t) => dict[t] && dict[t] !== t && /[a-z]{3}/.test(t)))];
}));

async function newPage(width, height, lang) {
  const ctx = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: 1 });
  ctx.setDefaultTimeout(15000);
  if (lang) await ctx.addInitScript(`localStorage.setItem('foodrescue.language', ${JSON.stringify(JSON.stringify(lang))})`);
  const page = await ctx.newPage();
  page.on('console', (m) => { if (m.type() === 'error' && !/status of (401|404)/.test(m.text())) consoleErrors.push(`[${lang || 'en'} ${width}px] ${m.text()}`); });
  page.on('pageerror', (e) => consoleErrors.push(`[${lang || 'en'} ${width}px] pageerror: ${e.message}`));
  return page;
}
function record(status, name, detail = '') {
  results.push([status, name]);
  console.log(`${status.padEnd(4)}  ${name}${detail ? '\n      ' + detail : ''}`);
}
async function step(page, name, fn) {
  try { await fn(); record('PASS', name); }
  catch (e) {
    record('FAIL', name, e.message.split('\n').filter(Boolean).slice(0, 3).join(' / '));
    await page.evaluate(() => document.querySelectorAll('dialog[open]').forEach((d) => d.close())).catch(() => {});
  }
}
function expect(cond, msg) { if (!cond) throw new Error(msg); }
async function shot(page, name, full = false) { await page.waitForTimeout(300); await page.screenshot({ path: path.join(OUT, name + '.png'), fullPage: full }); }
async function axe(page, label) {
  await page.waitForTimeout(400);
  await page.evaluate(AXE); // not a script tag: the page's security policy blocks inline scripts
  const res = await page.evaluate(async () => (await axe.run(document, { runOnly: ['wcag2a', 'wcag2aa'] })).violations
    .map((v) => `${v.impact} ${v.id} (${v.nodes.length}): ${v.nodes.slice(0, 2).map((n) => n.target.join(' ')).join(' | ')}`));
  record(res.length ? 'WARN' : 'PASS', `axe WCAG A/AA on ${label}`, res.join(' ;; '));
}
/** Visible interface text (and labels) still in English. */
async function leftovers(page, lang) {
  const texts = await page.evaluate(() => {
    const out = new Set();
    const visible = (el) => el && el.getClientRects().length && getComputedStyle(el).visibility !== 'hidden';
    const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    for (let n = w.nextNode(); n; n = w.nextNode()) {
      const el = n.parentElement;
      if (!visible(el) || el.closest('script,style,[translate="no"]')) continue;
      const t = n.nodeValue.replace(/\s+/g, ' ').trim();
      if (t) out.add(t);
    }
    for (const el of document.querySelectorAll('[aria-label],[placeholder]')) {
      if (!visible(el)) continue;
      for (const a of ['aria-label', 'placeholder']) if (el.hasAttribute(a)) out.add(el.getAttribute(a).trim());
    }
    return [...out];
  });
  return texts.filter((t) => untranslated[lang].has(t));
}
async function noLeftovers(page, lang, where) {
  const left = await leftovers(page, lang);
  expect(!left.length, `${where} still in English: ${left.slice(0, 6).join(' | ')}`);
}
async function demo(page, email) {
  await page.goto(BASE);
  await page.click(`[data-demo="${email}"]`);
  await page.waitForSelector('#view-auth', { state: 'hidden' });
}
/** Demo accounts start in English; switch the account itself, as Account settings would. */
async function setAccountLanguage(page, lang) {
  await page.evaluate(async (l) => {
    const h = { Authorization: 'Bearer ' + JSON.parse(localStorage.getItem('foodrescue.token')), 'Content-Type': 'application/json' };
    const me = await (await fetch('/api/v1/users/me', { headers: h })).json();
    await fetch('/api/v1/users/me', { method: 'PUT', headers: h, body: JSON.stringify({ fullName: me.fullName, email: me.email, language: l }) });
  }, lang);
  await page.reload();
  await page.waitForSelector('#view-auth', { state: 'hidden' });
  await page.waitForFunction((l) => document.documentElement.lang === l, lang);
}

(async () => {
  browser = await chromium.launch();

  // ---------------- choosing a language ----------------
  const p = await newPage(390, 844);
  const email = `limba${Date.now()}@example.com`;
  await step(p, 'the sign-in screen switches language from the header, and remembers it', async () => {
    await p.goto(BASE);
    expect(await p.evaluate(() => document.documentElement.lang) === 'en', 'not English for an English browser');
    await p.selectOption('#langSelect', 'ro');
    await p.waitForSelector('#tabLogin:has-text("Intră în cont")');
    expect(await p.evaluate(() => document.documentElement.lang) === 'ro', 'lang attribute');
    await p.reload();
    await p.waitForSelector('#tabLogin:has-text("Intră în cont")');
    await noLeftovers(p, 'ro', 'sign-in screen');
    await shot(p, 'i18n-01-signin-ro');
  });
  await step(p, 'signing up in Romanian makes it the account language, also on other devices', async () => {
    await p.click('#tabRegister');
    await p.fill('#regName', 'Ioana Pop');
    await p.fill('#regEmail', email);
    await p.fill('#regPassword', 'parola-buna-1');
    await p.click('#registerForm [type=submit]');
    expect((await p.textContent('#regTermsError')).includes('termenii'), 'terms error not translated');
    await p.check('#regTerms');
    await p.click('#registerForm [type=submit]');
    await p.waitForSelector('#view-shop:not([hidden])');
    await noLeftovers(p, 'ro', 'Discover after sign-up');
    const fresh = await newPage(1280, 900); // another device, English by default
    await fresh.goto(BASE);
    await fresh.fill('#loginEmail', email);
    await fresh.fill('#loginPassword', 'parola-buna-1');
    await fresh.click('#loginForm [type=submit]');
    await fresh.waitForFunction(() => document.documentElement.lang === 'ro');
    await fresh.waitForSelector('#view-shop:not([hidden])');
    await fresh.context().close();
  });
  await step(p, 'Account settings change the language for good', async () => {
    await p.click('#accountBtn');
    await p.selectOption('#accLanguage', 'de');
    await Promise.all([p.waitForEvent('load'), p.click('#detailsForm [type=submit]')]);
    await p.waitForFunction(() => document.documentElement.lang === 'de');
    await p.waitForSelector('#bottomNav [data-view="shop"]:has-text("Entdecken")');
    await p.click('#logout');
    await p.waitForSelector('#tabLogin:has-text("Anmelden")'); // the device remembers it after signing out
  });
  await step(p, 'a language picked at sign-in becomes the account language', async () => {
    await p.selectOption('#langSelect', 'it');
    await p.fill('#loginEmail', email);
    await p.fill('#loginPassword', 'parola-buna-1');
    await p.click('#loginForm [type=submit]');
    await p.waitForSelector('#view-shop:not([hidden])');
    await p.waitForTimeout(800);
    const lang = await p.evaluate(async () => (await (await fetch('/api/v1/users/me', { headers: { Authorization: 'Bearer ' + JSON.parse(localStorage.getItem('foodrescue.token')) } })).json()).language);
    expect(lang === 'it', 'account language ' + lang);
  });
  await p.context().close();

  // ---------------- every language, every role ----------------
  for (const lang of LANGS) {
    const c = await newPage(390, 844, lang);
    await step(c, `${lang}: customer screens have no English left`, async () => {
      await demo(c, 'student@example.com');
      await setAccountLanguage(c, lang);
      await c.waitForSelector('#nearby[aria-busy="false"]');
      await noLeftovers(c, lang, 'Discover');
      await c.click('#placeSummary');
      await noLeftovers(c, lang, 'search form');
      await c.click('#bottomNav [data-view="orders"]');
      await c.waitForSelector('#orderHistory .history-row');
      await noLeftovers(c, lang, 'My orders');
      await c.click('#bottomNav [data-view="impact"]');
      await c.waitForSelector('#impactBody .achievement');
      await noLeftovers(c, lang, 'My impact');
      await c.click('#inboxBtn');
      await c.click('#inboxTabSettings');
      await c.waitForTimeout(800);
      await noLeftovers(c, lang, 'alert settings');
      await c.keyboard.press('Escape');
      await c.click('#accountBtn');
      await noLeftovers(c, lang, 'account settings');
      await c.keyboard.press('Escape');
      if (lang === 'ro') await shot(c, 'i18n-02-impact-ro', true);
    });
    if (lang === 'ro') {
      await step(c, 'ro: dates are in Romanian', async () => {
        await c.click('#bottomNav [data-view="impact"]');
        const text = await c.textContent('#impactBody');
        expect(/noiembrie|decembrie|octombrie/.test(text), 'no Romanian month in: ' + text.slice(0, 200));
      });
      await axe(c, 'My impact in Romanian (phone)');
    }
    await c.context().close();

    const o = await newPage(1280, 900, lang);
    await step(o, `${lang}: shop owner screens have no English left`, async () => {
      await demo(o, 'owner@example.com');
      await setAccountLanguage(o, lang);
      await o.waitForSelector('#storeContent:not([hidden]) .stat');
      await noLeftovers(o, lang, 'store Today');
      await o.click('[data-setup-today]:visible >> nth=0');
      await o.waitForSelector('#setupDialog[open]');
      await o.waitForTimeout(600);
      await noLeftovers(o, lang, 'set up today');
      await o.keyboard.press('Escape');
      await o.click('#storeTabInsights');
      await o.waitForSelector('#insightsBody .insight-stats');
      await noLeftovers(o, lang, 'Insights');
      if (lang === 'de') await shot(o, 'i18n-03-insights-de', true);
    });
    await o.context().close();

    const a = await newPage(1280, 900, lang);
    await step(a, `${lang}: admin screen has no English left`, async () => {
      await demo(a, 'admin@example.com');
      await setAccountLanguage(a, lang);
      await a.waitForSelector('#view-admin:not([hidden]) .stat');
      await a.waitForTimeout(800);
      await noLeftovers(a, lang, 'Admin');
    });
    await a.context().close();
  }

  await browser.close();
  const count = (s) => results.filter((x) => x[0] === s).length;
  console.log(`\n${count('PASS')} passed, ${count('FAIL')} failed, ${count('WARN')} accessibility warnings`);
  console.log('Console errors: ' + (consoleErrors.length ? '\n  ' + [...new Set(consoleErrors)].join('\n  ') : 'none'));
  process.exitCode = count('FAIL') ? 1 : 0;
})();
