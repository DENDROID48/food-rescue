// Browser checks for AI-assisted listings (Phase 4) and the Impact page with streaks and
// achievements (Phase 5). Needs the app on localhost:8080 with fresh dev data, and internet access
// for the free image model (downloaded from cdn.jsdelivr.net by the browser).
// Usage: node ai-impact.js [screenshot dir]
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const AXE = fs.readFileSync(require.resolve('axe-core/axe.min.js'), 'utf8');

const BASE = 'http://localhost:8080/';

/** Like page.waitForFunction, which the page's security policy blocks (it needs eval). */
async function waitFn(page, fn, arg, { timeout = 15000 } = {}) {
  const end = Date.now() + timeout;
  for (;;) {
    if (await page.evaluate(fn, arg)) return;
    if (Date.now() > end) throw new Error('Timed out waiting for: ' + String(fn).slice(0, 120));
    await page.waitForTimeout(100);
  }
}
const OUT = process.argv[2] || path.join(__dirname, 'screenshots');
const PHOTOS = path.join(__dirname, '..', 'src', 'main', 'resources', 'static', 'photos');
fs.mkdirSync(OUT, { recursive: true });
const results = [];
const consoleErrors = [];
let browser;

/** A plain grey 224x224 PNG: a photo with no food in it. */
function greyPng() {
  const crc = (b) => { let c = ~0; for (const x of b) { c ^= x; for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xEDB88320 & -(c & 1)); } return ~c >>> 0; };
  const chunk = (t, d) => { const l = Buffer.alloc(4); l.writeUInt32BE(d.length); const td = Buffer.concat([Buffer.from(t), d]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); };
  const size = 224;
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(size, 0); ihdr.writeUInt32BE(size, 4); ihdr[8] = 8; ihdr[9] = 2;
  const row = Buffer.concat([Buffer.from([0]), Buffer.alloc(size * 3, 0x9a)]);
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(Buffer.concat(Array(size).fill(row)))), chunk('IEND', Buffer.alloc(0))]);
}

async function newPage(width, height) {
  const ctx = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: 1 });
  ctx.setDefaultTimeout(15000);
  const page = await ctx.newPage();
  page.on('console', (m) => { if (m.type() === 'error' && !/push service|Registration failed/i.test(m.text())) consoleErrors.push(`[${width}px] ${m.text()}`); });
  page.on('pageerror', (e) => consoleErrors.push(`[${width}px] pageerror: ${e.message}`));
  return page;
}
function record(status, name, detail = '') {
  results.push([status, name]);
  console.log(`${status.padEnd(4)}  ${name}${detail ? '\n      ' + detail : ''}`);
}
async function step(page, name, fn) {
  try { await fn(); record('PASS', name); }
  catch (e) {
    record('FAIL', name, e.message.split('\n').filter(Boolean).slice(0, 2).join(' / '));
    await page.evaluate(() => document.querySelectorAll('dialog[open]').forEach((d) => d.close())).catch(() => {});
  }
}
function expect(cond, msg) { if (!cond) throw new Error(msg); }
async function shot(page, name, full = false) { await page.waitForTimeout(400); await page.screenshot({ path: path.join(OUT, name + '.png'), fullPage: full }); }
async function noOverflow(page, label) {
  const { sw, iw } = await page.evaluate(() => ({ sw: document.documentElement.scrollWidth, iw: window.innerWidth }));
  expect(sw <= iw, `${label}: horizontal overflow ${sw} > ${iw}`);
}
async function login(page, email) {
  await page.goto(BASE);
  await page.waitForSelector('#view-auth:not([hidden])');
  await page.fill('#loginEmail', email);
  await page.fill('#loginPassword', 'demo1234');
  await page.click('#loginForm [type=submit]');
}
async function axe(page, label) {
  await page.waitForTimeout(500);
  await page.evaluate(AXE); // not a script tag: the page's security policy blocks inline scripts
  const res = await page.evaluate(async () => (await axe.run(document, { runOnly: ['wcag2a', 'wcag2aa'] })).violations
    .map((v) => `${v.impact} ${v.id} (${v.nodes.length}): ${v.nodes.slice(0, 2).map((n) => n.target.join(' ')).join(' | ')}`));
  record(res.length ? 'WARN' : 'PASS', `axe WCAG A/AA on ${label}`, res.join(' ;; '));
}
/** Asks for a suggestion and waits for it; the first time includes downloading the model. */
async function suggest(page, prefix) {
  await page.click(`#${prefix}Suggest`);
  await page.waitForSelector(`#${prefix}Suggestion:not([hidden])`);
  await page.waitForSelector(`#${prefix}Suggestion [data-dismiss-suggestion], #${prefix}Suggestion .form-alert`, { timeout: 90000 });
  const alert = await page.locator(`#${prefix}Suggestion .form-alert`).count();
  expect(!alert, 'suggestion failed: ' + (alert ? await page.innerText(`#${prefix}Suggestion`) : ''));
}
async function impactFigures(page) {
  return page.evaluate(async () => (await fetch('/api/v1/me/impact',
    { headers: { Authorization: 'Bearer ' + JSON.parse(localStorage.getItem('foodrescue.token')) } })).json());
}

(async () => {
  browser = await chromium.launch();

  // ---------------- Phase 4: AI-assisted listings ----------------
  const o = await newPage(1280, 900);
  await step(o, 'listing: "Suggest details" appears only once there is a photo', async () => {
    await login(o, 'owner@example.com');
    await o.waitForSelector('#storeContent:not([hidden]) .stat');
    await o.click('#newTemplate');
    await o.waitForSelector('#templateDialog[open]');
    expect(await o.isHidden('#tplSuggest'), 'button shown without a photo');
    await o.setInputFiles('#tplPhotoInput', path.join(PHOTOS, 'bread-480.webp'));
    await o.waitForSelector('#tplPhotoPreview img');
    expect(await o.isVisible('#tplSuggest'), 'button hidden after choosing a photo');
  });
  await step(o, 'listing: bread photo gives a Bakery suggestion, and nothing is filled in until the owner agrees', async () => {
    await suggest(o, 'tpl');
    const text = (await o.innerText('#tplSuggestion')).replace(/\s+/g, ' ');
    expect(/Name Bread/.test(text) && /Bakery/.test(text), 'suggestion: ' + text);
    expect(/French loaf \(\d+%\)/.test(text), 'what the model saw is missing: ' + text);
    expect(/never fills in allergens/.test(text), 'safety note missing');
    expect((await o.inputValue('#tplName')) === '' && (await o.inputValue('#tplDescription')) === '', 'form filled before approval');
    await shot(o, '90-ai-suggestion');
  });
  await step(o, 'listing: "Use these" fills name, description and food type, never a dietary label', async () => {
    await o.click('#tplSuggestion [data-use-suggestion]');
    expect((await o.inputValue('#tplName')) === 'Bread', 'name: ' + await o.inputValue('#tplName'));
    expect((await o.inputValue('#tplDescription')).includes('Ask us about ingredients and allergens'), 'description: ' + await o.inputValue('#tplDescription'));
    expect(await o.isChecked('#tplTags input[value="BAKERY"]'), 'Bakery not ticked');
    expect(!(await o.isChecked('#tplTags input[value="VEGETARIAN"]')) && !(await o.isChecked('#tplTags input[value="VEGAN"]')), 'dietary label ticked');
    expect((await o.innerText('#tplSuggestion')).includes('Filled in'), 'no confirmation');
    await shot(o, '91-ai-used');
  });
  await axe(o, 'product editor with a suggestion');
  await step(o, 'listing: choosing another photo clears the old suggestion', async () => {
    await o.setInputFiles('#tplPhotoInput', path.join(PHOTOS, 'fruit-480.webp'));
    await o.waitForSelector('#tplSuggestion', { state: 'hidden' });
    await o.keyboard.press('Escape');
  });
  await step(o, "listing: in the one-off editor the owner's own name is kept", async () => {
    await o.click('#newItem');
    await o.waitForSelector('#itemDialog[open]');
    await o.fill('#itemName', 'Sourdough loaf');
    await o.setInputFiles('#itemPhotoInput', path.join(PHOTOS, 'bread-480.webp'));
    await suggest(o, 'item');
    await o.click('#itemSuggestion [data-use-suggestion]');
    expect((await o.inputValue('#itemName')) === 'Sourdough loaf', 'name replaced: ' + await o.inputValue('#itemName'));
    expect((await o.inputValue('#itemDescription')).startsWith('Sourdough loaf left over from today'), 'description: ' + await o.inputValue('#itemDescription'));
  });
  await step(o, 'listing: a photo without food gives no suggestion', async () => {
    await o.setInputFiles('#itemPhotoInput', { name: 'grey.png', mimeType: 'image/png', buffer: greyPng() });
    await suggest(o, 'item');
    const text = await o.innerText('#itemSuggestion');
    expect(/doesn't look like a food/.test(text), 'expected no suggestion, got: ' + text.replace(/\s+/g, ' '));
    expect(!(await o.locator('#itemSuggestion [data-use-suggestion]').count()), '"Use these" offered without a suggestion');
    await o.keyboard.press('Escape');
  });
  await step(o, 'listing: on a phone the suggestion is just as good and fits', async () => {
    const p = await newPage(390, 844);
    await login(p, 'owner@example.com');
    await p.waitForSelector('#storeContent:not([hidden]) .stat');
    await p.click('#newTemplate');
    await p.waitForSelector('#templateDialog[open]');
    await p.setInputFiles('#tplPhotoInput', path.join(PHOTOS, 'pizza-480.webp'));
    await suggest(p, 'tpl');
    // The preview is a small thumbnail on a phone; the model must still see the full photo.
    const text = (await p.innerText('#tplSuggestion')).replace(/\s+/g, ' ');
    expect(/Name Pizza/.test(text) && /Ready meals/.test(text), 'phone suggestion: ' + text);
    await noOverflow(p, 'phone suggestion');
    await p.locator('#tplSuggestion').scrollIntoViewIfNeeded();
    await shot(p, '92-ai-phone');
    await p.context().close();
  });

  // ---------------- Phase 5: impact, streaks, achievements ----------------
  const c = await newPage(1280, 900);
  let before;
  await step(c, 'impact: figures, streak, challenge and nine achievements', async () => {
    await login(c, 'student@example.com');
    await c.waitForSelector('#nearby[aria-busy="false"]');
    await c.click('#mainNav [data-view="impact"]');
    await c.waitForSelector('#impactBody[aria-busy="false"] .achievement');
    before = await impactFigures(c);
    const tiles = await c.$$eval('.impact-stats .stat', (s) => s.map((x) => x.innerText.replace(/\s+/g, ' ')));
    expect(tiles.length === 4 && tiles[0].startsWith(`Food rescued ${before.units} items`), 'tiles: ' + tiles);
    expect(tiles[2] === `Rescues ${before.rescues}`, 'rescues tile: ' + tiles[2]);
    expect(await c.locator('.achievement').count() === 9, 'achievements');
    expect(await c.locator('.achievement.is-unlocked').count() === before.achievements.filter((a) => a.unlocked).length, 'unlocked count');
    expect(/weeks? in a row/.test(await c.innerText('.streak-card')), 'streak');
    expect(/Rescue 400 items together in \w+/.test(await c.innerText('#impactBody')), 'challenge');
    expect(await c.getAttribute('.achievement:not(.is-unlocked) [role="progressbar"]', 'aria-valuenow') !== null, 'progress bar');
    await shot(c, '93-impact-desktop', true);
  });
  await axe(c, 'Impact page');
  await step(c, 'impact: achievements unlocked since the last visit are marked new', async () => {
    await c.evaluate(() => localStorage.setItem(`foodrescue.seenAchievements.${me.id}`, JSON.stringify(['FIRST_RESCUE'])));
    await c.click('#mainNav [data-view="shop"]');
    await c.click('#mainNav [data-view="impact"]');
    await c.waitForSelector('#toasts .toast:has-text("new achievement")');
    const fresh = await c.locator('.achievement .badge:has-text("New")').count();
    expect(fresh === before.achievements.filter((a) => a.unlocked).length - 1, 'new badges: ' + fresh);
  });
  await step(c, 'impact: a collected order counts straight away (reserve, then the shop confirms the code)', async () => {
    const order = await c.evaluate(async () => {
      const auth = { Authorization: 'Bearer ' + JSON.parse(localStorage.getItem('foodrescue.token')), 'Content-Type': 'application/json' };
      const items = await (await fetch('/api/v1/food-items/nearby?lat=45.7537&lng=21.2257&radiusKm=5', { headers: auth })).json();
      const item = items.find((i) => i.quantityAvailable > 0 && i.storeName === 'Corner Bakery');
      const res = await fetch(`/api/v1/food-items/${item.id}/reservations`, { method: 'POST', headers: auth, body: JSON.stringify({ quantity: 1 }) });
      return res.json();
    });
    expect(order.pickupCode, 'no order: ' + JSON.stringify(order));
    const ownerPage = await newPage(1280, 900);
    await login(ownerPage, 'owner@example.com');
    await ownerPage.waitForSelector('#storeContent:not([hidden]) .stat');
    await ownerPage.fill('#pickupCode', order.pickupCode);
    await ownerPage.click('#pickupForm [type=submit]');
    await ownerPage.waitForSelector('.pickup-result');
    await ownerPage.context().close();
    await c.click('#mainNav [data-view="shop"]');
    await c.click('#mainNav [data-view="impact"]');
    await waitFn(c, (n) => document.querySelectorAll('.impact-stats .stat')[2]?.innerText.includes(String(n)), before.rescues + 1);
    expect((await c.innerText('.streak-card')).includes('This week already counts'), 'this week should count now');
  });

  const ph = await newPage(390, 844);
  await step(ph, 'impact on a phone: fits, and the bottom bar keeps its three buttons on one row', async () => {
    await login(ph, 'student@example.com');
    await ph.waitForSelector('#nearby[aria-busy="false"]');
    const tops = await ph.$$eval('#bottomNav button', (b) => b.map((x) => Math.round(x.getBoundingClientRect().top)));
    expect(tops.length === 3 && new Set(tops).size === 1, 'bottom bar rows: ' + tops);
    await ph.click('#bottomNav [data-view="impact"]');
    await ph.waitForSelector('#impactBody[aria-busy="false"] .achievement');
    await noOverflow(ph, 'phone impact');
    await shot(ph, '94-impact-phone', true);
  });
  await step(ph, 'returning customer on a phone: short heading, and past orders come 10 at a time', async () => {
    await ph.click('#bottomNav [data-view="shop"]');
    expect(await ph.locator('#view-shop .hero.is-compact').count() === 1, 'hero is not compact');
    expect(await ph.isHidden('#heroLocate'), 'big welcome buttons still shown');
    // The search is one line until opened (choosing a place closes it again too).
    expect(await ph.isHidden('#placeQuery'), 'search form open on a phone');
    expect((await ph.textContent('#placeSummary')).includes('within 3 km'), 'summary does not say the distance');
    await ph.click('#placeSummary');
    expect(await ph.isVisible('#placeQuery'), 'search form did not open');
    expect(await ph.getAttribute('#placeSummary', 'aria-expanded') === 'true', 'aria-expanded not true');
    await ph.click('label.chip:has-text("5 km")');
    await waitFn(ph, () => document.querySelector('#placeSummary').textContent.includes('within 5 km'));
    await ph.click('#placeSummary'); // "Done"
    await ph.waitForSelector('#placeForm.is-collapsed');
    expect(await ph.isHidden('#placeQuery'), 'search form did not close');
    await ph.click('#bottomNav [data-view="orders"]');
    await ph.waitForSelector('#historyMore');
    expect(await ph.locator('#orderHistory .history-row').count() === 10, 'not 10 past orders');
    await ph.click('#historyMore');
    expect(await ph.locator('#orderHistory .history-row').count() === 20, 'not 20 after "Show older orders"');
    await noOverflow(ph, 'phone orders');
  });
  await step(ph, 'back button and reload: a reload keeps the tab, back closes a dialog first, then goes to the last tab', async () => {
    await ph.click('#bottomNav [data-view="impact"]');
    await ph.waitForSelector('#impactBody .achievement');
    expect(ph.url().endsWith('#impact'), 'address is ' + ph.url());
    await ph.reload();
    await ph.waitForSelector('#view-impact:not([hidden]) #impactBody .achievement');
    await ph.click('#inboxBtn');
    await ph.waitForSelector('#inboxDialog[open]');
    await ph.goBack();
    await ph.waitForSelector('#inboxDialog[open]', { state: 'detached' });
    expect(await ph.isVisible('#view-impact'), 'back closed the dialog but left the Impact page');
    await ph.goBack(); // to My orders, the tab before
    await ph.waitForSelector('#view-orders:not([hidden])');
    await ph.goForward();
    await ph.waitForSelector('#view-impact:not([hidden])');
    // A dialog closed with its own button leaves no extra step behind.
    await ph.click('#inboxBtn');
    await ph.waitForSelector('#inboxDialog[open]');
    await ph.click('#inboxDialog [data-close]');
    await ph.waitForTimeout(300);
    await ph.goBack();
    await ph.waitForSelector('#view-orders:not([hidden])');
  });

  const owner = await newPage(1280, 900);
  await step(owner, 'impact is for customers: shop owners do not get the page', async () => {
    await login(owner, 'owner@example.com');
    await owner.waitForSelector('#storeContent:not([hidden]) .stat');
    expect(!(await owner.locator('[data-view="impact"]').count()), 'owner sees the Impact page');
  });

  await browser.close();
  const count = (s) => results.filter((x) => x[0] === s).length;
  console.log(`\n${count('PASS')} passed, ${count('FAIL')} failed, ${count('WARN')} accessibility warnings`);
  console.log('Console errors: ' + (consoleErrors.length ? '\n  ' + [...new Set(consoleErrors)].join('\n  ') : 'none'));
  process.exitCode = count('FAIL') ? 1 : 0;
})();
