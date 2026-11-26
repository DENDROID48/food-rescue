// End-to-end check of Rescue Radar (favourites, picks, alerts settings, push, install files).
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const AXE = fs.readFileSync(require.resolve('axe-core/axe.min.js'), 'utf8');

const BASE = 'http://localhost:8080/';
const OUT = process.argv[2] || path.join(__dirname, 'screenshots');
fs.mkdirSync(OUT, { recursive: true });
const results = [];
const consoleErrors = [];
let browser;

async function newPage(width, height, extra = {}) {
  const ctx = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: 1,
    geolocation: { latitude: 45.7537, longitude: 21.2257 }, permissions: ['geolocation'], ...extra });
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

/** Like page.waitForFunction, which the page's security policy blocks (it needs eval). */
async function waitFn(page, fn, arg, { timeout = 15000 } = {}) {
  const end = Date.now() + timeout;
  for (;;) {
    if (await page.evaluate(fn, arg)) return;
    if (Date.now() > end) throw new Error('Timed out waiting for: ' + String(fn).slice(0, 120));
    await page.waitForTimeout(100);
  }
}
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
async function axe(page, label) {
  await page.waitForTimeout(700);
  await page.evaluate(AXE); // not a script tag: the page's security policy blocks inline scripts
  const res = await page.evaluate(async () => (await axe.run(document, { runOnly: ['wcag2a', 'wcag2aa'] })).violations
    .map((v) => `${v.impact} ${v.id} (${v.nodes.length}): ${v.nodes.slice(0, 2).map((n) => n.target.join(' ')).join(' | ')}`));
  results.push([res.length ? 'WARN' : 'PASS', `axe WCAG A/AA on ${label}`, res.join(' ;; ')]);
}
const toastText = (page) => page.locator('#toasts .toast').last().innerText();
const closed = (page, id) => waitFn(page, (x) => !document.querySelector('#' + x).open, id);

(async () => {
  browser = await chromium.launch();

  await step('install files are served correctly', async () => {
    const p = await newPage(800, 600);
    const m = await p.request.get(BASE + 'manifest.webmanifest');
    expect(m.ok() && m.headers()['content-type'].startsWith('application/manifest+json'), 'manifest type: ' + m.headers()['content-type']);
    expect((await m.json()).icons.length === 2, 'manifest icons');
    const sw = await p.request.get(BASE + 'sw.js');
    expect(sw.ok() && /javascript/.test(sw.headers()['content-type']), 'sw type');
    for (const icon of ['icons/icon-192.png', 'icons/icon-512.png', 'icons/apple-touch-icon.png', 'icons/badge-96.png']) {
      expect((await p.request.get(BASE + icon)).ok(), icon);
    }
    await p.context().close();
  });

  // ---------------- store owner tags products and publishes ----------------
  const owner = await newPage(1280, 900);
  await step('owner sets food types on a product', async () => {
    await login(owner, 'owner@example.com');
    await owner.waitForSelector('#templateList [data-edit-template]');
    await owner.locator('#templateList [data-edit-template]').first().click();
    await owner.waitForSelector('#templateDialog[open]');
    expect(await owner.isChecked('#tplTags input[value="BAKERY"]'), 'BAKERY should be checked');
    await owner.check('#tplTags input[value="VEGETARIAN"]');
    await shot(owner, '70-template-food-types');
    await owner.click('#templateSubmit');
    await closed(owner, 'templateDialog');
  });
  await step('owner publishes today (fires favourite alerts)', async () => {
    await owner.click('#storeContent .panel-head [data-setup-today]');
    await owner.waitForSelector('#autopilotBar:not([hidden])');
    await owner.click('#autopilotFill');
    await owner.evaluate(() => {
      const now = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZone: 'Europe/Bucharest' }).format(new Date());
      for (const row of document.querySelectorAll('#setupList [data-setup-template]')) {
        const end = row.querySelector('[name=end]');
        if (!end.value || end.value <= now) { row.querySelector('[name=start]').value = now; end.value = '23:59'; }
      }
    });
    await owner.check('#setupConfirm');
    await owner.click('#setupPublish');
    await closed(owner, 'setupDialog');
  });

  // ---------------- customer ----------------
  const c = await newPage(1280, 900, { permissions: ['geolocation', 'notifications'] });
  await step('customer sees "For you" with reasons, and the favourite alert', async () => {
    await login(c, 'student@example.com');
    await c.waitForSelector('#nearby[aria-busy="false"]');
    await c.waitForSelector('#forYou:not([hidden]) .for-you-card');
    const first = await c.locator('#forYouList .for-you-card').first().innerText();
    expect(/One of your favourites/i.test(first) && /Sourdough/.test(first), 'first pick: ' + first);
    const reasons = await c.$$eval('#forYouList .for-you-reason', (r) => r.map((x) => x.textContent));
    // Every pick says why it was chosen (the demo student's history now outranks the interest reason).
    expect(reasons.length > 0 && reasons.every((r) => r.trim().length > 5), 'reasons: ' + reasons);
    // Picks already at the top of the list are left out: For you only points further down.
    const top = await c.$$eval('#nearby .food-card', (cards) => cards.slice(0, 2).map((x) => x.id.replace('food-', '')));
    const picked = await c.$$eval('#forYouList .for-you-card', (cards) => cards.map((x) => x.dataset.scrollTo));
    expect(!picked.some((id) => top.includes(id)), `pick ${picked} repeats a top card ${top}`);
    await c.waitForSelector('#inboxDot:not([hidden])');
    const star = c.locator('.food-card:has-text("Sourdough loaf") .star-btn').first();
    expect((await star.getAttribute('aria-pressed')) === 'true', 'sourdough star should be on');
    const tags = await c.locator('.food-card:has-text("Croissants") .food-tags').first().innerText();
    expect(/Bakery/.test(tags), 'tags on card: ' + tags);
    await shot(c, '71-for-you-desktop');
  });

  await step('follow a shop and filter by favourites', async () => {
    const follow = c.locator('.food-card:has-text("Fresh vegetable box") .follow-btn').first();
    await follow.click();
    await waitFn(c, () => [...document.querySelectorAll('.follow-btn')].some((b) => b.textContent === 'Following'));
    await c.waitForSelector('#toasts .toast:has-text("Following")');
    await c.click('#favOnly');
    const names = await c.$$eval('#nearby .food-card h3', (h) => h.map((x) => x.textContent));
    expect(names.length >= 2 && names.every((n) => /Sourdough|vegetable/i.test(n)), 'filtered: ' + names);
    await c.click('#favOnly');
  });

  await step('the inbox shows the favourite alert, and it leads to the offer', async () => {
    await c.click('#inboxBtn');
    await c.waitForSelector('#inboxDialog[open] .inbox-item');
    const item = await c.innerText('#inboxList .inbox-item');
    expect(/Your favourite at Corner Bakery/.test(item) && /Sourdough loaf is on offer/.test(item), 'alert: ' + item);
    await shot(c, '72-inbox-favourite');
    await c.click('#inboxList [data-open-alert]');
    await closed(c, 'inboxDialog');
    await c.waitForSelector('.food-card.is-highlighted');
  });

  await step('settings: interests, digest and quiet hours are saved', async () => {
    await c.click('#inboxBtn');
    await c.click('#inboxTabSettings');
    await c.waitForSelector('#interestChips input');
    expect(await c.isChecked('#interestChips input[value="BAKERY"]'), 'bakery interest');
    expect(await c.isChecked('#notifyFavourites'), 'favourite alerts on');
    const favs = await c.innerText('#favList');
    expect(/Sourdough loaf/.test(favs) && /Neighbourhood Market/.test(favs), 'fav list: ' + favs);
    await c.check('#interestChips input[value="FRUIT_VEG"]');
    await c.check('#digestOn');
    await c.fill('#digestTime', '17:30');
    await c.check('#quietOn');
    await shot(c, '73-settings-desktop');
    await c.click('#alertsForm [type=submit]');
    await c.waitForSelector('#toasts .toast:has-text("Settings saved")');
    const saved = await c.evaluate(() => api('/me/radar-profile'));
    expect(saved.digestTime.startsWith('17:30') && saved.quietStart.startsWith('22:00') && saved.interests.includes('FRUIT_VEG'),
      'saved: ' + JSON.stringify(saved));
  });

  await step('push: the device section explains the state and enabling never breaks the page', async () => {
    if (!(await c.isVisible('#pushStatus'))) {
      if (!(await c.isVisible('#inboxTabSettings'))) await c.click('#inboxBtn');
      await c.click('#inboxTabSettings');
    }
    await waitFn(c, () => document.querySelector('#pushStatus').textContent !== 'Checking…');
    const status = await c.innerText('#pushStatus');
    expect(status.length > 10, 'status: ' + status);
    const permission = await c.evaluate(() => ('Notification' in window ? Notification.permission : 'unsupported'));
    if (permission === 'denied' || permission === 'unsupported') {
      // The browser blocks notifications: the button is hidden and the text says why.
      expect(await c.isHidden('#pushEnable') && /blocked|can’t show/.test(status), 'blocked state: ' + status);
      results.push(['INFO', 'push in headless Chromium', 'notifications ' + permission + ': ' + status]);
      return;
    }
    await c.click('#pushEnable');
    await c.waitForTimeout(3000);
    const after = await c.innerText('#pushStatus');
    const on = await c.isVisible('#pushTest');
    results.push(['INFO', 'push in headless Chromium', on ? 'subscribed: ' + after : 'not available here: ' + after]);
    expect(on || /Couldn’t turn notifications on|can’t show/.test(after), 'unexpected: ' + after);
    const sw = await c.evaluate(async () => !!(await navigator.serviceWorker.getRegistration('/')));
    expect(sw, 'service worker not registered');
    await c.keyboard.press('Escape');
  });

  await step('a notification link (?alert=id) opens that alert', async () => {
    const id = await c.evaluate(() => inbox.items[0].id);
    await c.goto(BASE + '?alert=' + id);
    await c.waitForSelector('.food-card.is-highlighted', { timeout: 8000 });
    expect(new URL(c.url()).search === '', 'query should be cleared: ' + c.url());
  });

  await c.click('#inboxBtn');
  await c.click('#inboxTabSettings');
  await c.waitForSelector('#interestChips input');
  await axe(c, 'alert settings');
  await c.keyboard.press('Escape');
  await axe(c, 'discover with For you');

  // ---------------- customer, phone ----------------
  const phone = await newPage(390, 844);
  await step('phone: For you row scrolls sideways without page overflow', async () => {
    await login(phone, 'student@example.com');
    await phone.waitForSelector('#forYou:not([hidden]) .for-you-card');
    await noOverflow(phone, 'discover phone');
    await phone.locator('#forYou').scrollIntoViewIfNeeded();
    await shot(phone, '74-for-you-phone');
    await phone.click('#inboxBtn');
    await phone.click('#inboxTabSettings');
    await phone.waitForSelector('#interestChips input');
    await noOverflow(phone, 'settings phone');
    await shot(phone, '75-settings-phone');
  });

  await step('signing out releases this browser', async () => {
    await phone.keyboard.press('Escape');
    await phone.click('#logout');
    await phone.waitForSelector('#view-auth:not([hidden])');
    expect(await phone.isHidden('#forYou'), 'For you should hide');
  });

  await step('a new customer gets no picks yet (nothing personal known)', async () => {
    const p = await newPage(1280, 900);
    await login(p, 'beneficiary@example.com');
    await p.waitForSelector('#nearby[aria-busy="false"]');
    await p.waitForTimeout(500);
    expect(await p.isHidden('#forYou'), 'For you should be hidden');
  });

  await browser.close();
  for (const r of results) console.log(r.join(' | '));
  console.log(`\n${results.filter((r) => r[0] === 'PASS').length}/${results.filter((r) => r[0] !== 'INFO').length} passed`);
  console.log('console errors:', consoleErrors.length ? '\n' + consoleErrors.join('\n') : 'none');
})();
