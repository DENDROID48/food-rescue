// Browser checks for the no-show rule (missed pickups) and administrators blocking accounts.
// Uses the demo customer late@example.com, who has three missed pickups.
// Needs the app on localhost:8080 with fresh dev data.  Usage: node people.js [screenshot dir]
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

async function newPage(width, height, lang) {
  const ctx = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: 1 });
  ctx.setDefaultTimeout(15000);
  if (lang) await ctx.addInitScript(`localStorage.setItem('foodrescue.language', ${JSON.stringify(JSON.stringify(lang))})`);
  const page = await ctx.newPage();
  // Expected: the refused second reservation (409), the blocked sign-in (403) and the ended login (401).
  page.on('console', (m) => { if (m.type() === 'error' && !/status of (401|403|409)/.test(m.text())) consoleErrors.push(`[${width}px] ${m.text()}`); });
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
async function login(page, email) {
  await page.goto(BASE);
  await page.waitForSelector('#view-auth:not([hidden])');
  await page.fill('#loginEmail', email);
  await page.fill('#loginPassword', 'demo1234');
  await page.click('#loginForm [type=submit]');
}

(async () => {
  browser = await chromium.launch();

  // ---------------- the no-show rule ----------------
  const c = await newPage(1280, 900);
  await step(c, 'a customer with 3 missed pickups sees the limit on Discover and in My orders', async () => {
    await login(c, 'late@example.com');
    await c.waitForSelector('#standingShop:not([hidden])');
    const shop = await c.textContent('#standingShop');
    expect(/One reservation at a time until/.test(shop) && /You missed 3 pickups/.test(shop), 'Discover notice: ' + shop);
    await c.click('#mainNav [data-view="orders"]');
    await c.waitForSelector('#standingNote:not([hidden])');
    await shot(c, 'people-01-limited');
  });
  await step(c, 'they can hold one reservation, and a second is refused with the reason', async () => {
    await c.click('#mainNav [data-view="shop"]');
    await c.waitForSelector('#nearby[aria-busy="false"] [data-reserve]');
    await c.click('[data-reserve] >> nth=0');
    await c.waitForSelector('#reserveDialog[open]');
    await c.click('#reserveDialog .btn-primary');
    await c.waitForSelector('#reserveDialog .code');
    await c.keyboard.press('Escape');
    await c.click('[data-reserve] >> nth=1');
    await c.waitForSelector('#reserveDialog[open]');
    await c.click('#reserveDialog .btn-primary');
    await c.waitForSelector('text=one reservation at a time');
    await c.keyboard.press('Escape');
  });
  await axe(c, 'Discover with the missed-pickups notice');

  const ro = await newPage(390, 844, 'ro');
  await step(ro, 'the notice is translated (Romanian)', async () => {
    await login(ro, 'late@example.com');
    await ro.waitForSelector('#view-shop:not([hidden])');
    await ro.evaluate(async () => {
      const h = { Authorization: 'Bearer ' + JSON.parse(localStorage.getItem('foodrescue.token')), 'Content-Type': 'application/json' };
      const me = await (await fetch('/api/v1/users/me', { headers: h })).json();
      await fetch('/api/v1/users/me', { method: 'PUT', headers: h, body: JSON.stringify({ fullName: me.fullName, email: me.email, language: 'ro' }) });
    });
    await ro.reload();
    await ro.waitForSelector('#standingShop:not([hidden])');
    await ro.waitForFunction(() => /O singură rezervare odată/.test(document.querySelector('#standingShop').textContent));
    await shot(ro, 'people-02-limited-ro');
  });
  await ro.context().close();

  // ---------------- blocking ----------------
  const a = await newPage(1280, 900);
  await step(a, 'the admin finds people, with their missed pickups', async () => {
    await login(a, 'admin@example.com');
    await a.waitForSelector('#peopleList .person-row');
    await a.fill('#peopleQuery', 'LATE@');
    await a.click('#peopleSearch [type=submit]');
    await a.waitForFunction(() => document.querySelectorAll('#peopleList .person-row').length === 1);
    expect((await a.textContent('#peopleList')).includes('3 missed pickups'), 'missed pickups not shown');
    expect(!(await a.locator('#peopleList [data-block]').count()) === false, 'no Block button');
  });
  await step(a, 'blocking asks for a note, then shows the account as blocked', async () => {
    await a.click('#peopleList [data-block]');
    await a.waitForSelector('#blockDialog[open]');
    expect((await a.textContent('#blockEffect')).includes('open reservations are cancelled'), 'effect not explained');
    await shot(a, 'people-03-block-dialog');
    await a.fill('#blockReason', 'Three no-shows this month');
    await a.click('#blockForm [type=submit]');
    await a.waitForSelector('.toast:has-text("Account blocked")');
    await a.waitForSelector('#peopleList .badge-red:has-text("Blocked")');
    expect((await a.textContent('#peopleList')).includes('Three no-shows this month'), 'note not shown');
    expect(await a.locator('#peopleList [data-unblock]').count() === 1, 'no Unblock button');
  });
  await axe(a, 'Admin with People');
  await step(a, 'administrators have no Block button', async () => {
    await a.fill('#peopleQuery', 'admin@');
    await a.click('#peopleSearch [type=submit]');
    await a.waitForFunction(() => document.querySelectorAll('#peopleList .person-row').length === 1);
    expect(!(await a.locator('#peopleList [data-block]').count()), 'admin can be blocked');
  });

  await step(c, 'the blocked customer is signed out, and signing in explains why', async () => {
    await c.reload();
    await c.waitForSelector('#view-auth:not([hidden])');
    await login(c, 'late@example.com');
    await c.waitForSelector('#loginAlert:has-text("This account is blocked")');
    // Their reservation went back on sale.
    const left = await c.evaluate(async () => (await (await fetch('/api/v1/food-items/nearby?lat=45.7537&lng=21.2257&radiusKm=3')).json())
      .reduce((n, i) => n + i.quantityAvailable, 0));
    expect(left === 9, 'stock not returned: ' + left);
  });

  await step(a, 'unblocking lets them sign in again', async () => {
    await a.fill('#peopleQuery', 'late@');
    await a.click('#peopleSearch [type=submit]');
    await a.waitForSelector('#peopleList [data-unblock]');
    await a.click('#peopleList [data-unblock]');
    await a.waitForSelector('.toast:has-text("Account unblocked")');
    await login(c, 'late@example.com');
    await c.waitForSelector('#view-shop:not([hidden])');
  });

  const ph = await newPage(390, 844);
  await step(ph, 'People fits on a phone', async () => {
    await login(ph, 'admin@example.com');
    await ph.waitForSelector('#peopleList .person-row');
    await ph.locator('.people-panel').scrollIntoViewIfNeeded();
    const sw = await ph.evaluate(() => document.documentElement.scrollWidth);
    expect(sw <= 390, 'overflow ' + sw);
    await shot(ph, 'people-04-phone', true);
  });

  await browser.close();
  const count = (s) => results.filter((x) => x[0] === s).length;
  console.log(`\n${count('PASS')} passed, ${count('FAIL')} failed, ${count('WARN')} accessibility warnings`);
  console.log('Console errors: ' + (consoleErrors.length ? '\n  ' + [...new Set(consoleErrors)].join('\n  ') : 'none'));
  process.exitCode = count('FAIL') ? 1 : 0;
})();
