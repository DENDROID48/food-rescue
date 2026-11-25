// End-to-end check of the Food Rescue web client against a running server with fresh dev data.
// Usage: node e2e.js [screenshot dir] [photo file]
// Each section opens its own browser context and signs in itself, so one failure does not
// cascade into the rest. Results are printed as they happen.
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const AXE = fs.readFileSync(require.resolve('axe-core/axe.min.js'), 'utf8');

const BASE = 'http://localhost:8080/';
const OUT = process.argv[2] || path.join(__dirname, 'screenshots');
const PHOTO = process.argv[3] || path.join(__dirname, '..', 'src', 'main', 'resources', 'static', 'photos', 'bread-480.webp');
fs.mkdirSync(OUT, { recursive: true });

const results = [];
const consoleErrors = [];
let browser;

// ---------- helpers ----------

async function newPage(width, height, extra = {}) {
  const ctx = await browser.newContext({
    viewport: { width, height }, deviceScaleFactor: 1,
    geolocation: { latitude: 45.7537, longitude: 21.2257 }, permissions: ['geolocation', 'clipboard-read', 'clipboard-write'],
    ...extra,
  });
  ctx.setDefaultTimeout(10000);
  const page = await ctx.newPage();
  page.on('console', (m) => {
    // Headless Chromium has no push service; that is expected and not an app error.
    if (m.type() === 'error' && !/push service|Registration failed/i.test(m.text())) consoleErrors.push(`[${width}px] ${m.text()}`);
  });
  page.on('pageerror', (e) => consoleErrors.push(`[${width}px] pageerror: ${e.message}`));
  return page;
}
function record(status, name, detail = '') {
  results.push([status, name, detail]);
  console.log(`${status.padEnd(4)}  ${name}${detail ? '\n      ' + detail : ''}`);
}
/** Runs one check. On failure it closes any open dialog, so the next check starts clean. */
async function step(page, name, fn) {
  try { await fn(); record('PASS', name); }
  catch (e) {
    record('FAIL', name, e.message.split('\n').filter(Boolean).slice(0, 2).join(' / '));
    await closeDialogs(page).catch(() => {});
  }
}
function expect(cond, msg) { if (!cond) throw new Error(msg); }
async function closeDialogs(page) {
  await page.evaluate(() => document.querySelectorAll('dialog[open]').forEach((d) => d.close()));
}
async function shot(page, name, full = false) { await page.waitForTimeout(400); await page.screenshot({ path: path.join(OUT, name + '.png'), fullPage: full }); }
async function noOverflow(page, label) {
  const { sw, iw } = await page.evaluate(() => ({ sw: document.documentElement.scrollWidth, iw: window.innerWidth }));
  expect(sw <= iw, `${label}: horizontal overflow ${sw} > ${iw}`);
}
async function brokenImages(page) {
  return page.evaluate(() => [...document.images].filter((i) => i.complete && i.naturalWidth === 0 && i.offsetParent).map((i) => i.src));
}
const closed = (page, id) => page.waitForSelector(`#${id}:not([open])`, { state: 'attached' });
const toast = (page, text) => page.waitForSelector(`#toasts .toast:has-text(${JSON.stringify(text)})`);
async function login(page, email, password = 'demo1234') {
  await page.goto(BASE);
  await page.waitForSelector('#view-auth:not([hidden])');
  await page.fill('#loginEmail', email);
  await page.fill('#loginPassword', password);
  await page.click('#loginForm [type=submit]');
}
async function asCustomer(page, email = 'student@example.com') {
  await login(page, email);
  await page.waitForSelector('#view-shop:not([hidden])');
  await page.waitForSelector('#nearby[aria-busy="false"]');
}
async function asOwner(page) {
  await login(page, 'owner@example.com');
  await page.waitForSelector('#storeContent:not([hidden]) .stat');
}
async function signOut(page) {
  await closeDialogs(page);
  await page.click('#logout');
  await page.waitForSelector('#view-auth:not([hidden])');
}
async function axe(page, label) {
  await page.waitForTimeout(500);
  await page.addScriptTag({ content: AXE });
  const res = await page.evaluate(async () => (await axe.run(document, { runOnly: ['wcag2a', 'wcag2aa'] })).violations
    .map((v) => `${v.impact} ${v.id} (${v.nodes.length}): ${v.nodes.slice(0, 2).map((n) => n.target.join(' ')).join(' | ')}`));
  record(res.length ? 'WARN' : 'PASS', `axe WCAG A/AA on ${label}`, res.join(' ;; '));
}
/** Opens the reserve dialog of the first card that has at least `min` units left. */
async function openReservable(page, min = 2) {
  const buttons = page.locator('[data-reserve]');
  const n = await buttons.count();
  for (let i = 0; i < n; i++) {
    await buttons.nth(i).click();
    await page.waitForSelector('#reserveDialog[open] #reserveForm');
    const left = Number(/(\d+) left/.exec(await page.innerText('#reserveDialog'))?.[1]);
    if (left >= min) return;
    await page.keyboard.press('Escape');
    await closed(page, 'reserveDialog');
  }
  throw new Error(`no offer with ${min}+ units left`);
}

(async () => {
  browser = await chromium.launch();
  let keptCode = null;

  // ---------------- sign-in and registration ----------------
  const a = await newPage(1440, 900);
  await step(a, 'auth: renders without horizontal overflow', async () => {
    await a.goto(BASE);
    await a.waitForSelector('#view-auth:not([hidden])');
    await noOverflow(a, 'auth 1440');
    await shot(a, '01-auth-desktop');
  });
  await step(a, 'auth: empty submit shows field errors', async () => {
    await a.click('#loginForm [type=submit]');
    expect((await a.textContent('#loginEmailError')).includes('required'), 'no email error');
    expect(await a.getAttribute('#loginEmail', 'aria-invalid') === 'true', 'aria-invalid missing');
  });
  await step(a, 'auth: wrong password shows the server error', async () => {
    await a.fill('#loginEmail', 'student@example.com');
    await a.fill('#loginPassword', 'wrongpass');
    await a.click('#loginForm [type=submit]');
    await a.waitForFunction(() => document.querySelector('#loginAlert').textContent.includes('Wrong email or password'));
    await shot(a, '02-auth-error');
  });
  await step(a, 'auth: show and hide the password', async () => {
    await a.click('[data-toggle-password="loginPassword"]');
    expect(await a.getAttribute('#loginPassword', 'type') === 'text', 'not shown');
    await a.click('[data-toggle-password="loginPassword"]');
    expect(await a.getAttribute('#loginPassword', 'type') === 'password', 'not hidden');
  });
  await axe(a, 'sign-in screen');
  await step(a, 'register: password too short, then a new customer account, then sign out', async () => {
    await a.click('#tabRegister');
    await a.fill('#regName', 'Ana Test');
    await a.fill('#regEmail', `ana${Date.now()}@example.com`);
    await a.fill('#regPassword', 'short');
    await a.click('#registerForm [type=submit]');
    expect((await a.textContent('#regPasswordError')).includes('8'), 'no min length error');
    await a.fill('#regPassword', 'longenough1');
    await shot(a, '03-register');
    await a.click('#registerForm [type=submit]');
    await a.waitForSelector('#view-shop:not([hidden])');
    expect((await a.textContent('#accountName')) === 'Ana Test', 'wrong name: ' + await a.textContent('#accountName'));
    await signOut(a);
  });
  await step(a, 'demo button signs in', async () => {
    await a.click('#tabLogin');
    await a.click('[data-demo="student@example.com"]');
    await a.waitForSelector('#view-shop:not([hidden])');
  });
  await a.context().close();

  // ---------------- customer ----------------
  const c = await newPage(1440, 900);
  await step(c, 'customer: food cards load with photos', async () => {
    await asCustomer(c);
    await c.waitForSelector('.food-card');
    await c.waitForTimeout(1000);
    const n = await c.locator('.food-card').count();
    expect(n >= 2, 'expected at least 2 cards, got ' + n);
    expect(await c.locator('.food-card .photo-tag').count() >= 1, 'no "illustrative photo" tag');
    const broken = await brokenImages(c);
    expect(!broken.length, 'broken images: ' + broken.join(', '));
    await noOverflow(c, 'shop 1440');
    await shot(c, '04-shop-desktop');
    await shot(c, '04b-shop-desktop-full', true);
  });
  await axe(c, 'customer marketplace');
  await step(c, 'customer: sort by lowest price', async () => {
    await c.selectOption('#sortBy', 'price');
    const prices = await c.$$eval('#nearby .food-card .price-now', (els) => els.map((e) => parseFloat(e.textContent)));
    expect(prices.length && prices.every((v, i) => !i || prices[i - 1] <= v), 'not sorted: ' + prices);
    await c.selectOption('#sortBy', 'distance');
  });
  await step(c, 'customer: radius chips re-run the search', async () => {
    await c.click('label.chip:has-text("1 km")');
    await c.waitForFunction(() => document.querySelector('#resultCount').textContent.includes('1 km'));
    await c.click('label.chip:has-text("3 km")');
    await c.waitForSelector('#nearby[aria-busy="false"]');
  });
  await step(c, 'customer: place search via the geocoder', async () => {
    const before = await c.textContent('#locationLabel');
    await c.fill('#placeQuery', 'Piata Victoriei Timisoara');
    await c.click('#placeForm .search-submit');
    await c.waitForFunction((b) => !document.querySelector('#placeSuggestions').hidden
      || document.querySelector('#locationLabel').textContent !== b
      || [...document.querySelectorAll('#toasts .toast')].some((t) => !t.textContent.includes('Welcome')), before, { timeout: 15000 });
    if (await c.locator('#placeSuggestions').isVisible()) { await shot(c, '05-place-suggestions'); await c.click('#placeSuggestions button >> nth=0'); }
    await c.waitForSelector('#nearby[aria-busy="false"]');
    const label = await c.textContent('#locationLabel');
    expect(label !== before, 'location did not change (toasts: ' + (await c.locator('#toasts .toast').allTextContents()).join('|') + ')');
  });
  await step(c, 'customer: "Near me" uses the browser location', async () => {
    await c.click('#locate');
    await c.waitForSelector('#nearby[aria-busy="false"]');
    await c.waitForSelector('.food-card');
  });
  await step(c, 'customer: map pins open a popup that links to the card', async () => {
    expect(await c.locator('#shopMap .pin').count() >= 1, 'no pins');
    await c.locator('#shopMap .pin').first().click();
    await c.waitForSelector('.map-popup');
    await shot(c, '06-map-popup');
    await c.click('.map-popup [data-scroll-to]');
    await c.waitForSelector('.food-card.is-highlighted');
  });
  await step(c, 'customer: reserve dialog, quantity stepper and total', async () => {
    await openReservable(c, 2);
    const t1 = await c.textContent('#reserveSubmit');
    await c.click('#reserveDialog [data-step="1"]');
    expect((await c.textContent('#reserveDialog .stepper output')).trim() === '2', 'quantity not 2');
    expect(t1 !== await c.textContent('#reserveSubmit'), 'total did not change');
    await shot(c, '07-reserve-dialog');
  });
  await step(c, 'customer: confirm the reservation, get a code and copy it', async () => {
    await c.click('#reserveSubmit');
    await c.waitForSelector('#reserveDialog .ticket-code .code');
    keptCode = (await c.textContent('#reserveDialog .ticket-code .code')).trim();
    expect(/^\d{6}$/.test(keptCode), 'bad code ' + keptCode);
    await shot(c, '08-reserve-success');
    await c.click('#reserveDialog [data-copy]');
    expect(await c.evaluate(() => navigator.clipboard.readText()) === keptCode, 'clipboard does not hold the code');
  });
  await step(c, 'customer: a double click on Reserve creates only one order', async () => {
    await c.click('#reserveDialog [data-close]');
    await c.waitForSelector('#nearby[aria-busy="false"]');
    await openReservable(c, 1);
    await c.locator('#reserveSubmit').dblclick();
    await c.waitForSelector('#reserveDialog .ticket-code .code');
    await c.click('#reserveDialog [data-goto-orders]');
    await c.waitForSelector('#view-orders:not([hidden]) .order-active');
    const n = await c.locator('.order-active').count();
    expect(n === 2, 'expected 2 active orders, got ' + n);
    await shot(c, '09-orders');
  });
  await step(c, 'customer: cancel one reservation via the confirm dialog', async () => {
    const codes = await c.$$eval('.order-active .code', (els) => els.map((e) => e.textContent.trim()));
    const toCancel = codes.find((x) => x !== keptCode);
    await c.locator('.order-active', { hasText: toCancel }).locator('[data-cancel]').click();
    await c.waitForSelector('#confirmDialog[open]');
    await shot(c, '10-cancel-confirm');
    await c.click('#confirmOk');
    await c.waitForSelector('.history-row:has-text("Cancelled")');
    expect(await c.locator('.order-active').count() === 1, 'active orders not 1');
    expect((await c.textContent('#mainNav [data-orders-dot]')).trim() === '1', 'orders badge not 1');
  });
  await axe(c, 'my orders');
  await step(c, 'customer: ask to be verified', async () => {
    await c.click('#mainNav [data-view="shop"]');
    await c.click('#requestEligibility');
    await c.waitForSelector('#eligibilityBox:has-text("Pending review")');
  });
  await c.context().close();

  const v = await newPage(1440, 900);
  await step(v, 'verified customer: "free only" filter and a free claim', async () => {
    await asCustomer(v, 'beneficiary@example.com');
    await v.click('#freeOnly');
    await v.waitForSelector('#nearby[aria-busy="false"]');
    const cards = await v.locator('#nearby .food-card').count();
    expect(cards >= 1, 'no free cards');
    expect(await v.locator('#nearby .food-card .free-badge').count() === cards, 'a non-free card is shown');
    await v.locator('[data-claim]').first().click();
    await v.waitForSelector('#reserveDialog[open]');
    await shot(v, '11-claim-dialog');
    await v.click('#reserveSubmit');
    await v.waitForSelector('#reserveDialog .success:has-text("Claimed")');
    await v.click('#reserveDialog [data-close]');
  });
  await v.context().close();

  // ---------------- store owner ----------------
  const o = await newPage(1440, 900);
  await step(o, 'owner: dashboard with stats and waiting pickups', async () => {
    await asOwner(o);
    await o.waitForSelector('#todayOffers .listing');
    expect(Number(await o.textContent('#waitingCount')) >= 1, 'no waiting pickups');
    await noOverflow(o, 'store 1440');
    await shot(o, '12-store-desktop', true);
  });
  await axe(o, 'store dashboard');
  await step(o, 'owner: a wrong pickup code shows an error', async () => {
    await o.fill('#pickupCode', '12ab');
    expect((await o.inputValue('#pickupCode')) === '12', 'non-digits not stripped');
    await o.fill('#pickupCode', '000000');
    await o.click('#pickupForm [type=submit]');
    await o.waitForFunction(() => document.querySelector('#pickupCodeError').textContent.length > 0);
  });
  await step(o, "owner: confirm pickup with the customer's code", async () => {
    expect(keptCode, 'no code from the customer section');
    await o.fill('#pickupCode', keptCode);
    await o.click('#pickupForm [type=submit]');
    await o.waitForSelector('.pickup-result');
    await shot(o, '13-pickup-confirmed');
  });
  await step(o, 'owner: one-off offer with a discount check, photo preview and publish', async () => {
    await o.click('#newItem');
    await o.waitForSelector('#itemDialog[open]');
    await o.fill('#itemName', 'Cinnamon buns');
    await o.fill('#itemOriginal', '20');
    await o.fill('#itemDiscounted', '25');
    expect((await o.textContent('#discountPreview')).includes('lower'), 'no warning for a price above the original');
    await o.fill('#itemDiscounted', '8');
    expect((await o.textContent('#discountPreview')).includes('60%'), 'discount preview: ' + await o.textContent('#discountPreview'));
    await o.fill('#itemQty', '3');
    await o.setInputFiles('#itemPhotoInput', PHOTO);
    await o.waitForSelector('#itemPhotoPreview img');
    await shot(o, '14-item-editor');
    await o.click('#itemSubmit');
    await closed(o, 'itemDialog');
    await o.waitForSelector('#todayOffers .listing:has-text("Cinnamon buns") .photo-frame img');
  });
  await step(o, 'owner: edit the offer, remove its photo, change the price, then delete it', async () => {
    const row = () => o.locator('#todayOffers .listing:has-text("Cinnamon buns")');
    await row().locator('[data-adjust]').click();
    await o.waitForSelector('#itemDialog[open]');
    expect(await o.isVisible('#itemRemovePhoto'), '"Remove current photo" hidden');
    await o.click('#itemRemovePhoto');
    if (await o.locator('#confirmDialog[open]').count()) await o.click('#confirmOk');
    await o.fill('#itemDiscounted', '7');
    await o.click('#itemSubmit');
    await closed(o, 'itemDialog');
    await o.waitForSelector('#todayOffers .listing:has-text("Cinnamon buns"):has-text("7.00 lei")');
    expect(await row().locator('.photo-frame img').count() === 0, 'photo still shown after removing it');
    await row().locator('[data-delete]').click();
    await o.waitForSelector('#confirmDialog[open]');
    await o.click('#confirmOk');
    await o.waitForSelector('#todayOffers .listing:has-text("Cinnamon buns")', { state: 'detached' });
  });
  await step(o, 'owner: edit store details dialog with map', async () => {
    await o.click('#editStore');
    await o.waitForSelector('#storeDialog[open]');
    await o.waitForTimeout(600);
    await shot(o, '15-store-edit');
    await o.click('#storeSubmit');
    await closed(o, 'storeDialog');
  });
  await step(o, 'owner: switch between stores', async () => {
    const options = o.locator('#storeSelect option');
    expect(await options.count() >= 2, 'store select has ' + await options.count());
    const [value, name] = [await options.nth(1).getAttribute('value'), (await options.nth(1).textContent()).trim()];
    await o.selectOption('#storeSelect', value);
    await o.waitForFunction((n) => document.querySelector('#storeTitle').textContent.trim() === n, name);
  });
  await o.context().close();

  // ---------------- admin ----------------
  const ad = await newPage(1440, 900);
  await step(ad, 'admin: approve one request, reject another', async () => {
    await login(ad, 'admin@example.com');
    await ad.waitForSelector('#view-admin:not([hidden]) .person-row');
    await noOverflow(ad, 'admin 1440');
    await shot(ad, '16-admin-desktop', true);
    await ad.locator('.person-row:has-text("Pending Applicant") [data-approve]').click();
    await ad.waitForSelector('.person-row:has-text("Pending Applicant")', { state: 'detached' });
    await ad.locator('.person-row:has-text("Test Student") [data-reject]').click();
    await ad.waitForSelector('#confirmDialog[open]');
    await ad.click('#confirmOk');
    await ad.waitForSelector('.person-row:has-text("Test Student")', { state: 'detached' });
  });
  await axe(ad, 'admin dashboard');
  await step(ad, 'admin: suspend a store with confirmation, then reactivate it', async () => {
    await ad.locator('.store-row:has-text("Distant Shop") [data-suspend]').click();
    await ad.waitForSelector('#confirmDialog[open]');
    await shot(ad, '17-suspend-confirm');
    await ad.click('#confirmOk');
    await ad.waitForSelector('.store-row:has-text("Distant Shop") .badge-red');
    await ad.locator('.store-row:has-text("Distant Shop") [data-reactivate]').click();
    await ad.waitForSelector('.store-row:has-text("Distant Shop") [data-suspend]');
  });
  await ad.context().close();

  // ---------------- other widths ----------------
  for (const [w, h] of [[375, 812], [390, 844], [768, 1024], [1024, 768]]) {
    const m = await newPage(w, h);
    await step(m, `${w}px: sign-in screen`, async () => {
      await m.goto(BASE);
      await m.waitForSelector('#view-auth:not([hidden])');
      await noOverflow(m, `auth ${w}`);
      await shot(m, `20-${w}-auth`, true);
    });
    await step(m, `${w}px: customer marketplace`, async () => {
      await asCustomer(m);
      await m.waitForSelector('.food-card');
      await m.waitForTimeout(800);
      await noOverflow(m, `shop ${w}`);
      expect(!(await brokenImages(m)).length, 'broken images');
      if (w <= 720) expect(await m.isVisible('#bottomNav'), 'bottom nav missing');
      await shot(m, `21-${w}-shop`);
      await shot(m, `21b-${w}-shop-full`, true);
    });
    if (w <= 960) await step(m, `${w}px: map toggle`, async () => {
      await m.click('#mapToggle');
      await m.waitForSelector('#mapCard:not(.is-collapsed)');
      await m.waitForTimeout(500);
      expect(await m.locator('#shopMap .leaflet-tile-loaded').count() > 0 || await m.locator('#shopMap .pin').count() > 0, 'map empty');
      await m.click('#mapToggle');
    });
    await step(m, `${w}px: reserve dialog and orders`, async () => {
      await m.locator('[data-reserve]').first().click();
      await m.waitForSelector('#reserveDialog[open]');
      await noOverflow(m, `dialog ${w}`);
      await shot(m, `22-${w}-reserve`);
      await m.click('#reserveDialog [data-close]');
      await m.locator(w <= 720 ? '#bottomNav [data-view="orders"]' : '#mainNav [data-view="orders"]').click();
      await m.waitForSelector('#view-orders:not([hidden])');
      await noOverflow(m, `orders ${w}`);
      await shot(m, `23-${w}-orders`, true);
    });
    await step(m, `${w}px: store owner and admin`, async () => {
      await signOut(m);
      await asOwner(m);
      await m.waitForSelector('#todayOffers .listing');
      await noOverflow(m, `store ${w}`);
      await shot(m, `24-${w}-store`, true);
      await m.click('#newItem');
      await m.waitForSelector('#itemDialog[open]');
      await noOverflow(m, `editor ${w}`);
      await shot(m, `25-${w}-editor`);
      await signOut(m);
      await login(m, 'admin@example.com');
      await m.waitForSelector('#view-admin:not([hidden]) .store-row');
      await noOverflow(m, `admin ${w}`);
      await shot(m, `26-${w}-admin`, true);
    });
    await m.context().close();
  }

  // ---------------- resilience ----------------
  let r = await newPage(1280, 900);
  await step(r, 'fallback: a broken photo becomes a placeholder tile', async () => {
    await r.route('**/photos/**', (route) => route.fulfill({ status: 404, body: '' }));
    await asCustomer(r);
    await r.waitForTimeout(1000);
    const broken = await brokenImages(r);
    expect(!broken.length, 'broken image visible: ' + broken.join(', '));
    expect(await r.locator('.food-card .photo-fallback').count() >= 1, 'no fallback tile');
    await shot(r, '30-broken-photo-fallback');
  });
  await r.context().close();

  r = await newPage(1280, 900);
  await step(r, 'offline maps: Leaflet blocked, the list still works', async () => {
    await r.route('**/unpkg.com/**', (route) => route.abort());
    await asCustomer(r);
    expect(await r.locator('.food-card').count() >= 1, 'no cards');
    expect(await r.isHidden('#mapCard'), 'map card visible');
    await shot(r, '31-no-map');
  });
  await r.context().close();

  r = await newPage(1280, 900, { reducedMotion: 'reduce' });
  await step(r, 'reduced motion: the reserve dialog opens and closes', async () => {
    await asCustomer(r);
    await r.locator('[data-reserve]').first().click();
    await r.waitForSelector('#reserveDialog[open]');
    await r.keyboard.press('Escape');
    await closed(r, 'reserveDialog');
  });
  await r.context().close();

  r = await newPage(1280, 900);
  await step(r, 'keyboard: reserve dialog takes focus and gives it back after Escape', async () => {
    await asCustomer(r);
    await r.locator('[data-reserve]').first().focus();
    await r.keyboard.press('Enter');
    await r.waitForSelector('#reserveDialog[open]');
    const inDialog = await r.evaluate(() => document.activeElement.closest('dialog')?.id);
    expect(inDialog === 'reserveDialog', 'focus not in dialog: ' + inDialog);
    await r.keyboard.press('Escape');
    await closed(r, 'reserveDialog');
    expect(await r.evaluate(() => !!document.activeElement.dataset.reserve), 'focus not returned to the Reserve button');
  });
  await r.context().close();

  await browser.close();
  const count = (s) => results.filter((x) => x[0] === s).length;
  console.log(`\n${count('PASS')} passed, ${count('FAIL')} failed, ${count('WARN')} accessibility warnings`);
  console.log('Console errors: ' + (consoleErrors.length ? '\n  ' + [...new Set(consoleErrors)].join('\n  ') : 'none'));
  process.exitCode = count('FAIL') ? 1 : 0;
})();
