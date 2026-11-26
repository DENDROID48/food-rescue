// Browser checks for accounts: terms at sign-up, forgot password (the emailed link is read from
// the app's log, as the dev profile writes it there), account settings, password change,
// signing out other devices, downloading one's data and deleting the account.
// Needs the app on localhost:8080 with fresh dev data, started with its output in $SP_LOG
// (or pass the log file as the first argument).
// Usage: SP_LOG=app.log node account.js [screenshot dir]
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
const LOG = process.env.SP_LOG || process.argv[3];
const OUT = process.argv[2] || path.join(__dirname, 'screenshots');
fs.mkdirSync(OUT, { recursive: true });
const results = [];
const consoleErrors = [];
let browser;

async function newPage(width, height) {
  const ctx = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: 1, acceptDownloads: true });
  ctx.setDefaultTimeout(15000);
  const page = await ctx.newPage();
  page.on('console', (m) => {
    // Expected: the wrong passwords and signed-out logins these checks cause on purpose.
    if (m.type() === 'error' && !/status of (401|403|409)/.test(m.text())) consoleErrors.push(`[${width}px] ${m.text()}`);
  });
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
async function shot(page, name) { await page.waitForTimeout(300); await page.screenshot({ path: path.join(OUT, name + '.png') }); }
async function axe(page, label) {
  await page.waitForTimeout(400);
  await page.evaluate(AXE); // not a script tag: the page's security policy blocks inline scripts
  const res = await page.evaluate(async () => (await axe.run(document, { runOnly: ['wcag2a', 'wcag2aa'] })).violations
    .map((v) => `${v.impact} ${v.id} (${v.nodes.length}): ${v.nodes.slice(0, 2).map((n) => n.target.join(' ')).join(' | ')}`));
  record(res.length ? 'WARN' : 'PASS', `axe WCAG A/AA on ${label}`, res.join(' ;; '));
}
async function register(page, email, password) {
  await page.goto(BASE);
  await page.click('#tabRegister');
  await page.fill('#regName', 'Ioana Test');
  await page.fill('#regEmail', email);
  await page.fill('#regPassword', password);
  await page.check('#regTerms');
  await page.click('#registerForm [type=submit]');
  await page.waitForSelector('#view-shop:not([hidden])');
}
async function login(page, email, password) {
  await page.goto(BASE);
  await page.waitForSelector('#view-auth:not([hidden])');
  await page.fill('#loginEmail', email);
  await page.fill('#loginPassword', password);
  await page.click('#loginForm [type=submit]');
}
/** The newest reset link the dev profile wrote to the log for this address. */
async function resetLinkFor(email) {
  for (let i = 0; i < 20; i++) {
    const lines = fs.readFileSync(LOG, 'utf8').split('\n').filter((l) => l.includes(`Password reset link for ${email}:`));
    if (lines.length) return lines[lines.length - 1].split(': ').pop().trim();
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error('no reset link in the log');
}

(async () => {
  if (!LOG || !fs.existsSync(LOG)) { console.log('Set SP_LOG to the app log file'); process.exitCode = 1; return; }
  browser = await chromium.launch();
  const email = `ioana${Date.now()}@example.com`;

  const p = await newPage(390, 844);
  await step(p, 'sign-up records the language chosen on this device', async () => {
    await p.goto(BASE);
    await p.evaluate(() => localStorage.setItem('foodrescue.language', JSON.stringify('ro')));
    await register(p, email, 'first-pass-1');
    const me = await p.evaluate(async () => (await fetch('/api/v1/users/me',
      { headers: { Authorization: 'Bearer ' + JSON.parse(localStorage.getItem('foodrescue.token')) } })).json());
    expect(me.language === 'ro', 'language ' + me.language);
  });

  await step(p, 'account settings open from the avatar and save a new name', async () => {
    await p.click('#accountBtn');
    await p.waitForSelector('#accountDialog[open]');
    expect((await p.inputValue('#accEmail')) === email, 'email not filled in');
    expect(await p.isHidden('#accEmailPasswordField'), 'password asked without an email change');
    await shot(p, 'acc-01-dialog-phone');
    await p.fill('#accName', 'Ioana Pop');
    await p.click('#detailsForm [type=submit]');
    await p.waitForSelector('.toast:has-text("saved")');
    expect((await p.textContent('#accountAvatar')).trim() === 'IP', 'initials not updated');
  });
  await axe(p, 'account settings (phone)');

  await step(p, 'a new email needs the current password', async () => {
    await p.fill('#accEmail', 'other' + email);
    await p.waitForSelector('#accEmailPasswordField:not([hidden])');
    await p.click('#detailsForm [type=submit]');
    expect((await p.textContent('#accEmailPasswordError')).length > 0, 'no error without the password');
    await p.fill('#accEmailPassword', 'wrong-password');
    await p.click('#detailsForm [type=submit]');
    await waitFn(p, () => /not right/.test(document.querySelector('#accEmailPasswordError').textContent));
    await p.fill('#accEmail', email);
    await p.waitForSelector('#accEmailPasswordField', { state: 'hidden' });
  });

  await step(p, 'downloading my data gives a JSON file with my account', async () => {
    const [download] = await Promise.all([p.waitForEvent('download'), p.click('#exportData')]);
    const data = JSON.parse(fs.readFileSync(await download.path(), 'utf8'));
    expect(download.suggestedFilename() === 'food-rescue-my-data.json', 'file name ' + download.suggestedFilename());
    expect(data.account.email === email && Array.isArray(data.orders), 'export content');
  });

  // A second device, to see it signed out by the password change.
  const other = await newPage(1280, 900);
  await step(other, 'changing the password signs out the other device but keeps this one', async () => {
    await login(other, email, 'first-pass-1');
    await other.waitForSelector('#view-shop:not([hidden])');
    await p.fill('#accCurrentPassword', 'first-pass-1');
    await p.fill('#accNewPassword', 'second-pass-2');
    await p.click('#passwordForm [type=submit]');
    await p.waitForSelector('.toast:has-text("Password changed")');
    await p.click('#accountDialog [data-close]');
    await p.reload();
    await p.waitForSelector('#view-shop:not([hidden])');
    await other.reload();
    await other.waitForSelector('#view-auth:not([hidden])');
  });

  await step(other, 'forgot password: the emailed link lets you choose a new one and signs you in', async () => {
    await other.goto(BASE);
    await other.waitForSelector('#forgotOpen:not([hidden])');
    await other.fill('#loginEmail', email);
    await other.click('#forgotOpen');
    await other.waitForSelector('#forgotDialog[open]');
    expect((await other.inputValue('#forgotEmail')) === email, 'email not carried over');
    await other.click('#forgotSubmit');
    await other.waitForSelector('#forgotSent:not([hidden])');
    await shot(other, 'acc-02-forgot');
    const link = await resetLinkFor(email);
    expect(link.startsWith('http://localhost:8080/#reset/'), 'link ' + link);
    await other.goto(link);
    await other.waitForSelector('#resetDialog[open]');
    expect(!other.url().includes('reset'), 'token left in the address: ' + other.url());
    await other.fill('#resetPassword', 'third-pass-3');
    await other.click('#resetForm [type=submit]');
    await other.waitForSelector('#view-shop:not([hidden])');
    // The link works once.
    await other.goto(link);
    await other.waitForSelector('#resetDialog[open]');
    await other.fill('#resetPassword', 'fourth-pass-4');
    await other.click('#resetForm [type=submit]');
    await waitFn(other, () => /expired or was already used/.test(document.querySelector('#resetAlert').textContent));
    await other.keyboard.press('Escape');
  });
  await axe(other, 'reset dialog closed, signed in (desktop)');

  await step(p, 'deleting the account needs the password, then signs out for good', async () => {
    await p.reload();
    await p.waitForSelector('#view-auth:not([hidden])'); // the reset signed this phone out too
    await login(p, email, 'third-pass-3');
    await p.waitForSelector('#view-shop:not([hidden])');
    await p.click('#accountBtn');
    await p.click('#deleteStart');
    await p.waitForSelector('#deleteForm:not([hidden])');
    await p.fill('#deletePassword', 'wrong-password');
    await p.click('#deleteForm [type=submit]');
    await waitFn(p, () => /not right/.test(document.querySelector('#deletePasswordError').textContent));
    await p.fill('#deletePassword', 'third-pass-3');
    await p.click('#deleteForm [type=submit]');
    await p.waitForSelector('#view-auth:not([hidden])');
    await p.waitForSelector('.toast:has-text("deleted")');
    await login(p, email, 'third-pass-3');
    await waitFn(p, () => /Wrong email or password/.test(document.querySelector('#loginAlert').textContent));
  });

  await step(p, 'privacy and terms pages open from the footer', async () => {
    await p.click('.site-footer a[href="privacy.html"]');
    await p.waitForSelector('h1:has-text("Privacy policy")');
    const sw = await p.evaluate(() => document.documentElement.scrollWidth);
    expect(sw <= 390, 'privacy page overflows: ' + sw);
    await p.goto(BASE + 'terms.html');
    await p.waitForSelector('h1:has-text("Terms of use")');
  });
  await axe(p, 'privacy policy (phone)');

  await browser.close();
  const count = (s) => results.filter((x) => x[0] === s).length;
  console.log(`\n${count('PASS')} passed, ${count('FAIL')} failed, ${count('WARN')} accessibility warnings`);
  console.log('Console errors: ' + (consoleErrors.length ? '\n  ' + [...new Set(consoleErrors)].join('\n  ') : 'none'));
  process.exitCode = count('FAIL') ? 1 : 0;
})();
