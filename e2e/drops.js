// End-to-end check of Store Autopilot and Rescue Drops against a freshly started dev server.
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
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
fs.mkdirSync(OUT, { recursive: true });

const results = [];
const consoleErrors = [];
let browser;

async function newPage(width, height) {
  const ctx = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: 1,
    geolocation: { latitude: 45.7537, longitude: 21.2257 }, permissions: ['geolocation'] });
  const page = await ctx.newPage();
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(`[${width}px] ${m.text()}`); });
  page.on('pageerror', (e) => consoleErrors.push(`[${width}px] pageerror: ${e.message}`));
  return page;
}
async function step(name, fn) {
  try { await fn(); results.push(['PASS', name]); }
  catch (e) { results.push(['FAIL', name, e.message.split('\n').filter(Boolean).slice(0, 3).join(' / ')]); }
}
function expect(cond, msg) { if (!cond) throw new Error(msg); }
async function shot(page, name, full = false) { await page.waitForTimeout(450); await page.screenshot({ path: path.join(OUT, name + '.png'), fullPage: full }); }
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
async function asOwner(page) {
  await login(page, 'owner@example.com');
  await page.waitForSelector('#storeContent:not([hidden])');
  await page.waitForSelector('#todayOffers .listing, #todayOffers .empty');
}
async function axe(page, label) {
  await page.waitForTimeout(700);
  await page.evaluate(AXE); // not a script tag: the page's security policy blocks inline scripts
  const res = await page.evaluate(async () => (await axe.run(document, { runOnly: ['wcag2a', 'wcag2aa'] })).violations
    .map((v) => `${v.impact} ${v.id} (${v.nodes.length}): ${v.nodes.slice(0, 2).map((n) => n.target.join(' ')).join(' | ')}`));
  results.push([res.length ? 'WARN' : 'PASS', `axe WCAG A/AA on ${label}`, res.join(' ;; ')]);
}
const toastText = (page) => page.locator('#toasts .toast').last().innerText();

/** Rows whose pickup time is already over today (depends on when the test runs) get an end of 23:59. */
async function fixPastWindows(page) {
  await page.evaluate(() => {
    for (const row of document.querySelectorAll('#setupList [data-setup-template]')) {
      const end = row.querySelector('[name=end]');
      const now = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZone: 'Europe/Bucharest' }).format(new Date());
      if (!end.value || end.value <= now) {
        row.querySelector('[name=start]').value = now;
        end.value = '23:59';
      }
    }
  });
}

process.on('unhandledRejection', (e) => {
  for (const r of results) console.log(r.join(' | '));
  console.log('CRASH | ' + e.message.split('\n').filter(Boolean).slice(0, 4).join(' / '));
  console.log('console errors:', consoleErrors.length ? '\n' + consoleErrors.join('\n') : 'none');
  process.exit(1);
});
(async () => {
  browser = await chromium.launch();

  // ---------------- store owner, desktop ----------------
  const owner = await newPage(1280, 900);
  await step('owner dashboard loads', async () => { await asOwner(owner); await shot(owner, '50-owner-dashboard'); });

  await step('Autopilot suggests from history in the set-up sheet', async () => {
    await owner.click('#storeContent .panel-head [data-setup-today]');
    await owner.waitForSelector('#autopilotBar:not([hidden])', { timeout: 5000 });
    const text = await owner.innerText('#autopilotText');
    expect(/Suggestions for 3 products/.test(text), 'bar text: ' + text);
    const hints = await owner.$$eval('#setupList .autopilot-hint', (h) => h.map((x) => x.innerText));
    expect(hints.length === 3, 'hints: ' + hints.length);
    expect(hints.some((h) => /Sold out on \d+ of the last 14 days/.test(h)), 'sourdough sold-out reason missing: ' + hints.join(' | '));
    expect(await owner.$('#setupList .price-chip'), 'expected a price suggestion for croissants');
    await shot(owner, '51-autopilot-hints');
  });

  await step('nothing is filled in until the owner asks', async () => {
    const values = await owner.$$eval('#setupList input[id^=qty-]', (i) => i.map((x) => x.value));
    expect(values.every((v) => v === ''), 'quantities pre-filled: ' + values);
    expect(await owner.isDisabled('#setupPublish'), 'publish should be disabled');
  });

  await step('fill in suggestions, apply a lower price, confirm and publish', async () => {
    await owner.click('#autopilotFill');
    const values = await owner.$$eval('#setupList input[id^=qty-]', (i) => i.map((x) => Number(x.value)));
    expect(values.every((v) => v > 0), 'not all filled: ' + values);
    await owner.click('#setupList .price-chip');
    expect((await owner.innerText('#setupSummary')).includes('1 lower price for today'), 'summary: ' + await owner.innerText('#setupSummary'));
    await fixPastWindows(owner);
    await shot(owner, '52-autopilot-filled');
    await owner.click('#setupPublish');
    expect((await owner.innerText('#setupAlert')).includes('confirm'), 'publish without confirmation should be refused');
    await owner.check('#setupConfirm');
    await owner.click('#setupPublish');
    await owner.waitForSelector('#setupDialog:not([open])', { state: 'attached' });
    expect((await toastText(owner)).includes('3 offers published'), 'toast: ' + await toastText(owner));
    await waitFn(owner, () => document.querySelectorAll('#todayOffers .listing').length >= 4);
    const croissant = await owner.locator('#todayOffers .listing', { hasText: 'Croissants' }).first().innerText();
    expect(/5\.50 lei/.test(croissant), 'approved croissant price not used: ' + croissant);
  });

  await step('launch a Rescue Drop of a saved product in a few taps', async () => {
    await owner.click('#storeContent [data-open-drop="product"]');
    await owner.waitForSelector('#dropDialog[open]');
    await waitFn(owner, () => document.querySelector('#dropAudience').textContent.length > 0);
    expect((await owner.innerText('#dropAudience')).includes('1 person has alerts on'), 'audience: ' + await owner.innerText('#dropAudience'));
    await owner.selectOption('#dropTemplate', { label: 'Sourdough loaf' });
    await owner.click('#dropProduct [data-bump="1"][data-target="dropQty"]');
    await owner.fill('#dropPrice', '4.50');
    await owner.dispatchEvent('#dropPrice', 'input');
    await owner.click('label.chip:has(input[value="30"])');
    expect((await owner.innerText('#dropSummary')) === '4 × Sourdough loaf at 4.50 lei for 30 min', 'summary: ' + await owner.innerText('#dropSummary'));
    await shot(owner, '53-drop-product');
    await owner.click('#dropSubmit');
    expect((await owner.innerText('#dropAlert')).includes('on the shelf'), 'launch without confirmation should be refused');
    await owner.check('#dropConfirm');
    await owner.click('#dropSubmit');
    await owner.waitForSelector('#dropDialog:not([open])', { state: 'attached' });
    const t = await toastText(owner);
    // Publishing the daily offers already sent the student a favourite alert about this shop, and
    // nobody is alerted about the same shop twice within 2 hours (AlertService.SAME_STORE_GAP).
    expect(/Rescue Drop is live until \d\d:\d\d\. Nobody nearby was due an alert\./.test(t), 'toast: ' + t);
    await owner.waitForSelector('#todayOffers .listing.is-drop');
  });

  await step('bundle leftovers into surprise bags', async () => {
    await owner.click('#storeContent [data-open-drop="product"]');
    await owner.click('#dropTabBundle');
    const rows = await owner.$$('#bundleList [data-bundle-offer]');
    expect(rows.length >= 3, 'bundle rows: ' + rows.length);
    // Take 3 units from the first two offers (beyond the server's proposal, if any).
    await owner.$$eval('#bundleList input', (inputs) => inputs.forEach((i) => { i.value = 0; }));
    await owner.click('#bundleList [data-bundle-offer]:nth-child(1) [data-bump="1"]');
    await owner.click('#bundleList [data-bundle-offer]:nth-child(1) [data-bump="1"]');
    await owner.click('#bundleList [data-bundle-offer]:nth-child(2) [data-bump="1"]');
    const value = await owner.innerText('#bundleValue');
    expect(value.startsWith('3 units in 1 bag'), 'value text: ' + value);
    const price = Number(await owner.inputValue('#bundlePrice'));
    expect(price > 0, 'auto price: ' + price);
    await shot(owner, '54-drop-bundle');
    await owner.check('#dropConfirm');
    await owner.click('#dropSubmit');
    await owner.waitForSelector('#dropDialog:not([open])', { state: 'attached' });
    // The second drop from the same store within 2 hours does not alert the same person again.
    expect((await toastText(owner)).includes('Nobody nearby was due an alert'), 'toast: ' + await toastText(owner));
    await waitFn(owner, () => document.querySelectorAll('#todayOffers .listing.is-drop').length === 2);
    await shot(owner, '55-owner-after-drops', true);
  });

  await step('owner dashboard has no horizontal overflow', () => noOverflow(owner, 'owner desktop'));
  await owner.click('#storeContent [data-open-drop="product"]');
  await axe(owner, 'Rescue Drop dialog');
  await owner.keyboard.press('Escape');
  await owner.click('#storeContent .panel-head [data-setup-today]');
  await owner.waitForSelector('#setupDialog[open]');
  await axe(owner, 'set-up sheet');
  await owner.keyboard.press('Escape');

  // ---------------- customer, phone ----------------
  const phone = await newPage(390, 844);
  await step('customer sees the alert badge and the live drops', async () => {
    await login(phone, 'student@example.com');
    await phone.waitForSelector('#nearby[aria-busy="false"]');
    await phone.waitForSelector('#inboxDot:not([hidden])', { timeout: 5000 });
    expect((await phone.innerText('#inboxDot')) === '1', 'unread: ' + await phone.innerText('#inboxDot'));
    const strip = await phone.innerText('#dropStrip');
    expect(/2 Rescue Drops live nearby/.test(strip), 'strip: ' + strip);
    const first = await phone.locator('#nearby .food-card').first();
    expect(await first.evaluate((c) => c.classList.contains('is-drop')), 'drops should come first');
    expect((await first.innerText()).includes('Rescue Drop'), 'drop badge missing');
    await noOverflow(phone, 'customer phone');
    await shot(phone, '56-customer-drops-phone');
  });

  await step('customer opens the alert and lands on the drop', async () => {
    await phone.click('#inboxBtn');
    await phone.waitForSelector('#inboxDialog[open] .inbox-item');
    const item = await phone.innerText('#inboxList .inbox-item');
    expect(/Your favourite at Corner Bakery/.test(item) && /Sourdough loaf is on offer/.test(item), 'alert: ' + item);
    await shot(phone, '57-customer-inbox-phone');
    await phone.click('#inboxList [data-open-alert]');
    await phone.waitForSelector('#inboxDialog:not([open])', { state: 'attached' });
    await phone.waitForSelector('.food-card.is-highlighted');
    expect(await phone.isHidden('#inboxDot'), 'badge should clear after reading');
  });
  await phone.click('#inboxBtn');
  await phone.waitForSelector('#inboxDialog[open]');
  await axe(phone, 'customer alerts inbox (phone)');
  await phone.keyboard.press('Escape');

  await step('another customer turns drop alerts on', async () => {
    const p2 = await newPage(390, 844);
    await login(p2, 'applicant@example.com');
    await p2.waitForSelector('#nearby[aria-busy="false"]');
    await p2.click('#inboxBtn');
    await p2.waitForSelector('#inboxDialog[open]');
    await p2.click('#inboxTabSettings');
    await waitFn(p2, () => document.querySelector('#alertsPlace').textContent !== 'this place');
    expect(!(await p2.isChecked('#alertsOn')), 'should start off');
    await p2.check('#alertsOn');
    await p2.selectOption('#alertsRadius', '2');
    await p2.click('#alertsForm [type=submit]');
    // Drop alerts and Radar preferences are saved together since the Radar phase.
    await p2.waitForSelector('#toasts .toast:has-text("Settings saved.")');
    const saved = await p2.evaluate(async () => (await fetch('/api/v1/me/drop-alerts',
      { headers: { Authorization: 'Bearer ' + JSON.parse(localStorage.getItem('foodrescue.token')) } })).json());
    expect(saved.enabled === true && saved.radiusKm === 2, 'saved: ' + JSON.stringify(saved));
    await shot(p2, '58-alert-settings-phone');
  });

  // ---------------- store owner, phone ----------------
  const ownerPhone = await newPage(390, 844);
  await step('owner on a phone: alerts, set-up sheet and drop dialog fit', async () => {
    await asOwner(ownerPhone);
    await noOverflow(ownerPhone, 'owner phone dashboard');
    await shot(ownerPhone, '59-owner-phone', true);
    await ownerPhone.click('#storeContent [data-open-drop="product"]');
    await ownerPhone.waitForSelector('#dropDialog[open]');
    await noOverflow(ownerPhone, 'drop dialog phone');
    await shot(ownerPhone, '60-drop-dialog-phone');
  });

  await browser.close();
  for (const r of results) console.log(r.join(' | '));
  console.log(`\n${results.filter((r) => r[0] === 'PASS').length}/${results.length} passed`);
  console.log('console errors:', consoleErrors.length ? '\n' + consoleErrors.join('\n') : 'none');
})();
