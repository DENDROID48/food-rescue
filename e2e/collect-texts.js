// Collects every piece of interface text the app shows, by walking through every role and dialog,
// to find text that is not in src/main/i18n/keys.txt yet (see the README there).
// Not a test. Usage: node collect-texts.js out.json  (app running with fresh dev data)
const { chromium } = require('playwright');
const fs = require('fs');
const BASE = 'http://localhost:8080/';
const ATTRS = ['placeholder', 'aria-label', 'title', 'alt', 'data-label', 'data-pattern-message', 'data-required-message'];
const seen = new Set();
const LANG = process.env.LANG_UI || 'en'; // collect with the interface in this language

const COLLECT = (attrs) => {
  window.__texts = window.__texts || new Set();
  const add = (s) => { s = (s || '').replace(/\s+/g, ' ').trim(); if (/[A-Za-z]{2}/.test(s)) window.__texts.add(s); };
  const grab = (root) => {
    if (root.nodeType === 3) { if (!root.parentElement?.closest('script,style')) add(root.nodeValue); return; }
    if (root.nodeType !== 1 || root.closest('script,style')) return;
    for (const el of [root, ...root.querySelectorAll('*')]) for (const a of attrs) if (el.hasAttribute(a)) add(el.getAttribute(a));
    const w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    for (let n = w.nextNode(); n; n = w.nextNode()) if (!n.parentElement.closest('script,style')) add(n.nodeValue);
  };
  const start = () => {
    grab(document.documentElement);
    new MutationObserver((rs) => rs.forEach((r) => {
      if (r.type === 'childList') r.addedNodes.forEach(grab);
      else if (r.type === 'characterData') add(r.target.nodeValue);
      else grab(r.target);
    })).observe(document.documentElement, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: attrs });
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
};

async function page(browser, w, h) {
  const ctx = await browser.newContext({ viewport: { width: w, height: h }, permissions: ['geolocation'], geolocation: { latitude: 45.7537, longitude: 21.2257 } });
  ctx.setDefaultTimeout(6000);
  await ctx.addInitScript(`(${COLLECT.toString()})(${JSON.stringify(ATTRS)})`);
  await ctx.addInitScript(`localStorage.setItem('foodrescue.language', ${JSON.stringify(JSON.stringify(LANG))})`);
  const p = await ctx.newPage();
  p.on('framenavigated', () => {});
  return p;
}
async function dump(p) {
  try { (await p.evaluate(() => [...(window.__texts || [])])).forEach((s) => seen.add(s)); } catch { /* navigated away */ }
}
const tryDo = async (p, fn) => { try { await fn(); } catch (e) { console.log('skip:', e.message.split('\n')[0]); } await p.waitForTimeout(350); await dump(p); };
const esc = (p) => p.keyboard.press('Escape');

async function login(p, email) {
  await p.goto(BASE);
  if (await p.locator(`[data-demo="${email}"]`).count()) await p.click(`[data-demo="${email}"]`);
  else { await p.fill('#loginEmail', email); await p.fill('#loginPassword', 'demo1234'); await p.click('#loginForm [type=submit]'); }
  await p.waitForSelector('#view-auth', { state: 'hidden' });
  if (LANG !== 'en') {
    // The account's own language wins after sign-in, so set it for this account first.
    await p.evaluate(async (lang) => {
      const h = { Authorization: 'Bearer ' + JSON.parse(localStorage.getItem('foodrescue.token')), 'Content-Type': 'application/json' };
      const me = await (await fetch('/api/v1/users/me', { headers: h })).json();
      await fetch('/api/v1/users/me', { method: 'PUT', headers: h, body: JSON.stringify({ fullName: me.fullName, email: me.email, language: lang }) });
    }, LANG);
    await p.reload();
    await p.waitForSelector('#view-auth', { state: 'hidden' });
  }
  await p.waitForTimeout(2500);
}

(async () => {
  const browser = await chromium.launch();
  // Signed out
  for (const [w, h] of [[1280, 900], [390, 844]]) {
    const p = await page(browser, w, h);
    await p.goto(BASE); await p.waitForTimeout(1500);
    await tryDo(p, () => p.click('#loginForm [type=submit]'));
    await tryDo(p, async () => { await p.fill('#loginEmail', 'x@y.ro'); await p.fill('#loginPassword', 'wrongpass'); await p.click('#loginForm [type=submit]'); await p.waitForTimeout(1500); });
    await tryDo(p, () => p.click('#tabRegister'));
    await tryDo(p, () => p.click('#registerForm [type=submit]'));
    await tryDo(p, async () => { await p.fill('#regEmail', 'bad'); await p.fill('#regPassword', 'x'); await p.click('#registerForm [type=submit]'); });
    await tryDo(p, async () => { await p.click('#tabLogin'); await p.click('#forgotOpen'); await p.click('#forgotSubmit'); await p.fill('#forgotEmail', 'nobody@example.com'); await p.click('#forgotSubmit'); await p.waitForTimeout(800); await esc(p); });
    await tryDo(p, async () => { await p.click('#openCredits'); await p.waitForTimeout(400); await esc(p); });
    await tryDo(p, async () => { await p.goto(BASE + '#reset/abc'); await p.waitForTimeout(600); await p.fill('#resetPassword', 'newpass123'); await p.click('#resetForm [type=submit]'); await p.waitForTimeout(800); await esc(p); });
    await p.context().close();
  }

  // Customer
  for (const [w, h] of [[1280, 900], [390, 844]]) {
    const p = await page(browser, w, h);
    await login(p, 'student@example.com'); await dump(p);
    await tryDo(p, () => p.click('#placeSummary'));
    await tryDo(p, async () => { await p.click('#locate'); await p.waitForTimeout(2000); });
    await tryDo(p, async () => { await p.fill('#placeQuery', 'zzqqxx nowhere'); await p.click('.search-submit'); await p.waitForTimeout(2500); });
    await tryDo(p, async () => { await p.fill('#placeQuery', 'Piata'); await p.click('.search-submit'); await p.waitForTimeout(2500); });
    await tryDo(p, () => p.click('label.chip:has-text("1 km")'));
    await tryDo(p, async () => { await p.waitForTimeout(1500); await p.click('label.chip:has-text("25 km")'); await p.waitForTimeout(1500); });
    await tryDo(p, async () => { for (const v of ['price', 'discount', 'deadline', 'distance']) { await p.selectOption('#sortBy, select[aria-label="Sort results"]', v).catch(() => {}); await p.waitForTimeout(300); } });
    await tryDo(p, () => p.click('#mapToggle'));
    await tryDo(p, () => p.click('#mapToggle'));
    await tryDo(p, async () => { await p.click('.food-card .star-btn >> nth=0'); await p.waitForTimeout(600); await p.click('.food-card .star-btn >> nth=0'); });
    await tryDo(p, async () => { await p.click('.food-card [data-fav="store"] >> nth=0'); await p.waitForTimeout(600); await p.click('.food-card [data-fav="store"] >> nth=0'); });
    await tryDo(p, async () => { await p.click('[data-reserve]:visible >> nth=0'); await p.waitForTimeout(500); await p.click('#reserveDialog [data-qty="+"], #reserveDialog button[aria-label*="more"]').catch(() => {}); await p.waitForTimeout(300); });
    await tryDo(p, async () => { await p.click('#reserveDialog .btn-primary'); await p.waitForTimeout(1500); });
    await tryDo(p, async () => { await p.click('#reserveDialog [data-copy], #reserveDialog .btn:has-text("Copy")'); await p.waitForTimeout(300); await esc(p); });
    await tryDo(p, async () => { await p.click('[data-view="orders"]:visible'); await p.waitForTimeout(1500); });
    await tryDo(p, async () => { await p.click('#historyMore'); });
    await tryDo(p, async () => { await p.click('[data-cancel] >> nth=0'); await p.waitForTimeout(400); await p.click('#confirmOk'); await p.waitForTimeout(1200); });
    await tryDo(p, async () => { await p.click('[data-view="impact"]:visible'); await p.waitForTimeout(1500); });
    await tryDo(p, async () => { await p.click('[data-view="shop"]:visible'); await p.click('#inboxBtn'); await p.waitForTimeout(1000); });
    await tryDo(p, async () => { await p.click('#inboxTabSettings'); await p.waitForTimeout(1200); });
    await tryDo(p, async () => { await p.check('#quietOn'); await p.fill('#quietStart', ''); await p.click('#alertsForm [type=submit]'); await p.waitForTimeout(800); await p.uncheck('#quietOn'); await p.click('#alertsForm [type=submit]'); await p.waitForTimeout(1000); await esc(p); });
    await tryDo(p, async () => { await p.click('#accountBtn'); await p.click('#detailsForm [type=submit]'); await p.click('#deleteStart'); await p.click('#deleteForm [type=submit]'); await p.fill('#deletePassword', 'wrong-one'); await p.click('#deleteForm [type=submit]'); await p.waitForTimeout(800); });
    await tryDo(p, async () => { await p.fill('#accCurrentPassword', 'wrong-one'); await p.fill('#accNewPassword', 'whatever12'); await p.click('#passwordForm [type=submit]'); await p.waitForTimeout(800); await p.fill('#accEmail', 'x' + Date.now() + '@x.ro'); await p.click('#detailsForm [type=submit]'); await p.waitForTimeout(500); await esc(p); });
    await tryDo(p, async () => { await p.click('#hideEligibility'); await p.waitForTimeout(800); });
    await p.context().close();
  }

  // Verified customer
  {
    const p = await page(browser, 1280, 900);
    await login(p, 'beneficiary@example.com'); await dump(p);
    await tryDo(p, () => p.click('#freeOnly'));
    await tryDo(p, async () => { await p.click('[data-claim]:visible >> nth=0'); await p.waitForTimeout(500); await p.click('#reserveDialog .btn-primary'); await p.waitForTimeout(1500); await esc(p); });
    await tryDo(p, async () => { await p.click('[data-claim]:visible >> nth=0'); await p.waitForTimeout(500); await p.click('#reserveDialog .btn-primary'); await p.waitForTimeout(1500); await esc(p); });
    await p.context().close();
  }

  // Applicant
  {
    const p = await page(browser, 1280, 900);
    await login(p, 'applicant@example.com'); await dump(p);
    await p.context().close();
  }

  // Store owner
  for (const [w, h] of [[1280, 900], [390, 844]]) {
    const p = await page(browser, w, h);
    await login(p, 'owner@example.com'); await dump(p);
    await tryDo(p, async () => { await p.click('#pickupForm [type=submit], form:has(#pickupCode) [type=submit]'); await p.fill('#pickupCode', '123456'); await p.click('form:has(#pickupCode) [type=submit]'); await p.waitForTimeout(1000); });
    await tryDo(p, async () => { await p.click('[data-setup-today]:visible >> nth=0'); await p.waitForTimeout(800); await p.click('#setupForm .autopilot button, #autopilotFill, button:has-text("Fill in suggestions")').catch(() => {}); await p.waitForTimeout(800); await p.click('#setupPublish'); await p.waitForTimeout(600); await esc(p); });
    await tryDo(p, async () => { await p.click('#newTemplate, [data-new-template]:visible >> nth=0'); await p.waitForTimeout(500); await p.click('#templateForm [type=submit]'); await p.waitForTimeout(400); await p.click('#templateForm label:has-text("Surprise bag")').catch(() => {}); await esc(p); });
    await tryDo(p, async () => { await p.click('[data-edit-template] >> nth=0'); await p.waitForTimeout(600); await esc(p); });
    await tryDo(p, async () => { await p.click('text=Add a one-off offer'); await p.waitForTimeout(500); await p.click('#itemForm [type=submit]'); await p.waitForTimeout(400); await esc(p); });
    await tryDo(p, async () => { await p.click('[data-open-drop]:visible >> nth=0'); await p.waitForTimeout(600); await p.click('#dropDialog [data-drop-mode="product"]').catch(() => {}); await p.waitForTimeout(300); await p.click('#dropDialog [data-drop-mode="bundle"]').catch(() => {}); await p.waitForTimeout(300); await p.click('#dropDialog [type=submit]'); await p.waitForTimeout(500); await esc(p); });
    await tryDo(p, async () => { await p.click('[data-edit-offer] >> nth=0, [data-offer-edit] >> nth=0'); await p.waitForTimeout(500); await esc(p); });
    await tryDo(p, async () => { await p.click('button:has-text("Pause") >> nth=0'); await p.waitForTimeout(800); await p.click('button:has-text("Resume") >> nth=0').catch(() => {}); await p.waitForTimeout(800); });
    await tryDo(p, async () => { await p.click('[data-archive-template] >> nth=0'); await p.waitForTimeout(400); await esc(p); });
    await tryDo(p, async () => { await p.click('#editStore'); await p.waitForTimeout(600); await esc(p); });
    await tryDo(p, async () => { await p.click('#addStore'); await p.waitForTimeout(600); await p.click('#storeForm [type=submit]'); await p.waitForTimeout(400); await esc(p); });
    await tryDo(p, async () => { await p.click('#storeTabInsights'); await p.waitForTimeout(2500); for (const d of ['7', '90']) { await p.click(`[data-days="${d}"], button:has-text("${d === '7' ? '7 days' : '3 months'}")`).catch(() => {}); await p.waitForTimeout(1500); } await p.click('summary:has-text("Show as a table")').catch(() => {}); });
    await tryDo(p, async () => { await p.click('[data-insight-action] >> nth=0'); await p.waitForTimeout(600); await esc(p); });
    await tryDo(p, async () => { await p.click('#storeTabToday'); await p.selectOption('#storeSelect', { index: 2 }).catch(() => {}); await p.waitForTimeout(2000); });
    await tryDo(p, async () => { await p.click('#accountBtn'); await p.waitForTimeout(500); await esc(p); });
    await p.context().close();
  }

  // Administrator
  for (const [w, h] of [[1280, 900], [390, 844]]) {
    const p = await page(browser, w, h);
    await login(p, 'admin@example.com'); await dump(p);
    await tryDo(p, async () => { await p.click('button:has-text("Reject") >> nth=0'); await p.waitForTimeout(400); await esc(p); });
    await tryDo(p, async () => { await p.click('button:has-text("Suspend") >> nth=0'); await p.waitForTimeout(400); await p.click('#confirmOk'); await p.waitForTimeout(1000); await p.click('button:has-text("Reactivate") >> nth=0'); await p.waitForTimeout(1000); });
    await tryDo(p, async () => { await p.click('button:has-text("Approve") >> nth=0'); await p.waitForTimeout(1000); });
    await tryDo(p, () => p.click('#refreshAdmin, button:has-text("Refresh")'));
    await p.context().close();
  }

  await browser.close();
  fs.writeFileSync(process.argv[2], JSON.stringify([...seen].sort(), null, 0));
  console.log(seen.size, 'texts');
})();
