// Browser check of what changed on Nov 19-24: photos on disk, distances via Locatable, expiry via Expirable.
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path'), zlib = require('zlib');
const UPLOADS = process.argv[2] || path.join(__dirname, '..', 'uploads');
const OUT = process.argv[3] || path.join(__dirname, 'screenshots');
const BASE = 'http://localhost:8080/';
const results = [], consoleErrors = [];
const step = async (n, f) => { try { await f(); results.push(['PASS', n]); } catch (e) { results.push(['FAIL', n, e.message.split('\n').filter(Boolean).slice(0, 8).join(' / ')]); } };
const expect = (c, m) => { if (!c) throw new Error(m); };
function png() { // a real 40x40 green PNG
  const crc = (b) => { let c = ~0; for (const x of b) { c ^= x; for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xEDB88320 & -(c & 1)); } return ~c >>> 0; };
  const chunk = (t, d) => { const l = Buffer.alloc(4); l.writeUInt32BE(d.length); const td = Buffer.concat([Buffer.from(t), d]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(40, 0); ihdr.writeUInt32BE(40, 4); ihdr[8] = 8; ihdr[9] = 2;
  const row = Buffer.concat([Buffer.from([0]), Buffer.alloc(120, Buffer.from([0x16, 0x85, 0x5a]))]);
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(Buffer.concat(Array(40).fill(row)))), chunk('IEND', Buffer.alloc(0))]);
}
async function login(page, email) {
  await page.goto(BASE); await page.waitForSelector('#view-auth:not([hidden])');
  await page.fill('#loginEmail', email); await page.fill('#loginPassword', 'demo1234'); await page.click('#loginForm [type=submit]');
}
(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const o = await ctx.newPage();
  o.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  const image = png(); fs.writeFileSync(path.join(OUT, 'green.png'), image);
  let tplId;

  await step('owner adds a product photo: saved as a file and shown from it', async () => {
    await login(o, 'owner@example.com');
    await o.waitForSelector('#storeContent:not([hidden])');
    const edit = o.locator('[data-edit-template]').first();
    if (!(await edit.isVisible())) { await o.click('#storeContent .panel-head [data-setup-today]'); }
    await o.locator('[data-edit-template]').first().click();
    await o.waitForSelector('#templateDialog[open]');
    tplId = await o.locator('[data-edit-template]').first().getAttribute('data-edit-template');
    await o.setInputFiles('#tplPhotoInput', path.join(OUT, 'green.png'));
    await o.click('#templateForm [type=submit]');
    await o.waitForSelector('#templateDialog:not([open])', { state: 'attached' });
    const file = path.join(UPLOADS, 'templates', tplId);
    expect(fs.existsSync(file), 'no file ' + file);
    expect(Buffer.compare(fs.readFileSync(file), image) === 0, 'file differs from the upload');
    const res = await o.request.get(BASE + 'api/v1/template-photos/' + tplId);
    expect(res.ok() && res.headers()['content-type'] === 'image/png', 'served ' + res.status());
    expect(Buffer.compare(await res.body(), image) === 0, 'served bytes differ');
  });

  await step('the photo shows in the editor when it is opened again', async () => {
    await o.keyboard.press('Escape').catch(() => {});
    if (await o.isVisible('#setupDialog[open]')) await o.keyboard.press('Escape');
    await o.locator(`[data-edit-template="${tplId}"]`).first().click();
    await o.waitForSelector('#templateDialog[open]');
    await o.waitForFunction(() => [...document.querySelectorAll('#templateDialog img')].some((i) => i.complete && i.naturalWidth === 40), null, { timeout: 10000 });
    await o.screenshot({ path: path.join(OUT, 'photo-in-editor.png') });
  });

  await step('removing the photo deletes the file', async () => {
    await o.click('#tplRemovePhoto');
    await o.waitForSelector('#confirmDialog[open]');
    await o.locator('#confirmDialog').getByRole('button', { name: 'Remove photo' }).click();
    await o.waitForSelector('#toasts .toast:has-text("Photo removed")');
    await o.waitForTimeout(500);
    expect(!fs.existsSync(path.join(UPLOADS, 'templates', tplId)), 'file still there');
    const res = await o.request.get(BASE + 'api/v1/template-photos/' + tplId);
    expect(res.status() === 404, 'expected 404, got ' + res.status());
  });

  const c = await ctx.browser().newContext({ viewport: { width: 390, height: 844 }, geolocation: { latitude: 45.7579, longitude: 21.2290 }, permissions: ['geolocation'] });
  const cp = await c.newPage();
  cp.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  await step('customer: nearby offers show distances, nearest first, none expired', async () => {
    await login(cp, 'student@example.com');
    await cp.waitForSelector('#nearby[aria-busy="false"]');
    const data = await cp.evaluate(async () => (await fetch('/api/v1/food-items/nearby?lat=45.7579&lng=21.2290&radiusKm=3')).json());
    expect(data.length > 0, 'no offers');
    const d = data.map((i) => i.distanceKm);
    expect(d.every((x, k) => k === 0 || x >= d[k - 1]), 'not sorted: ' + d);
    expect(d.every((x) => x <= 3), 'outside radius: ' + d);
    expect(data.every((i) => new Date(i.expiresAt) > new Date()), 'expired offer listed');
    const cards = await cp.$$eval('.food-card', (cs) => cs.map((x) => x.innerText));
    expect(cards.length > 0 && cards.every((t) => /\d+ m\b|\d+(\.\d+)? km/.test(t)), 'distance text: ' + cards[0]);
    await cp.screenshot({ path: path.join(OUT, 'nearby-phone.png') });
  });

  await browser.close();
  for (const r of results) console.log(r.join(' | '));
  console.log(`\n${results.filter((r) => r[0] === 'PASS').length}/${results.length} passed`);
  console.log('console errors:', consoleErrors.length ? '\n' + consoleErrors.join('\n') : 'none');
})();
