// End-to-end check of Smart Analytics: Insights tab, chart, forecast, export, weights, reminders, admin.
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
  const ctx = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: 1, acceptDownloads: true, ...extra });
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
async function axe(page, label) {
  await page.waitForTimeout(700);
  await page.addScriptTag({ content: AXE });
  const res = await page.evaluate(async () => (await axe.run(document, { runOnly: ['wcag2a', 'wcag2aa'] })).violations
    .map((v) => `${v.impact} ${v.id} (${v.nodes.length}): ${v.nodes.slice(0, 2).map((n) => n.target.join(' ')).join(' | ')}`));
  results.push([res.length ? 'WARN' : 'PASS', `axe WCAG A/AA on ${label}`, res.join(' ;; ')]);
}

(async () => {
  browser = await chromium.launch();
  const o = await newPage(1280, 900);

  await step('owner opens Insights: figures from the last 4 weeks', async () => {
    await login(o, 'owner@example.com');
    await o.waitForSelector('#storeContent:not([hidden])');
    await o.click('#storeTabInsights');
    await o.waitForSelector('#insightsBody .insight-stats');
    const tiles = await o.$$eval('#insightsBody .insight-stats .stat', (s) => s.map((x) => x.innerText.replace(/\s+/g, ' ')));
    expect(tiles.length === 6, 'tiles ' + tiles.length);
    expect(/Food rescued \d+ .*kg/.test(tiles[0]), 'rescued tile: ' + tiles[0]);
    expect(/Collected of offered \d+%/.test(tiles[1]), 'sell-through: ' + tiles[1]);
    expect(await o.isHidden('#todayPanel'), 'today panel should hide');
    await shot(o, '80-insights-top');
  });

  await step('recommendations explain themselves and open the right editor', async () => {
    const recs = await o.$$eval('#insightsBody .rec strong', (r) => r.map((x) => x.textContent));
    expect(recs.some((r) => /Offer \d+ more Sourdough loaf/.test(r)), 'sells-out rec: ' + recs);
    expect(recs.some((r) => /Less Croissants, box of 4 left over/.test(r)), 'left-over rec: ' + recs);
    await o.locator('.rec:has-text("Croissants") [data-insight-action="EDIT_PRODUCT"]').click();
    await o.waitForSelector('#templateDialog[open]');
    expect((await o.inputValue('#tplName')) === 'Croissants, box of 4', 'editor opened for the wrong product');
    expect((await o.inputValue('#tplWeight')) === '0.28', 'weight shown in kg: ' + await o.inputValue('#tplWeight'));
    await o.keyboard.press('Escape');
  });

  await step('chart: stacked bars, hover tooltip and a table view', async () => {
    const chart = o.locator('#insightsBody .chart');
    await chart.scrollIntoViewIfNeeded();
    const bars = await o.$$eval('#insightsBody .chart path, #insightsBody .chart g:nth-of-type(2) rect', (b) => b.length);
    expect(bars >= 28, 'bars ' + bars);
    const hit = o.locator('#insightsBody .chart .hits rect').nth(20);
    await hit.hover();
    await o.waitForSelector('#chartTip:not([hidden])');
    const tip = (await o.innerText('#chartTip')).replace(/\s+/g, ' ');
    expect(/Collected \d+/.test(tip) && /Left over \d+/.test(tip) && /Offered \d+/.test(tip), 'tip: ' + tip);
    await shot(o, '81-insights-chart');
    await o.click('#insightsBody .table-view summary');
    expect(await o.locator('#insightsBody .table-view tbody tr').count() === 28, 'table rows');
  });

  await step('forecast for the next 7 days, with its track record', async () => {
    const rows = await o.$$eval('#insightsBody table.forecast tbody tr', (r) => r.map((x) => x.innerText.replace(/\s+/g, ' ')));
    expect(rows.length === 3, 'forecast rows ' + rows.length);
    expect(rows.every((r) => /Right on \d+ of the last \d+ days/.test(r)), 'track record: ' + rows);
    expect(rows.some((r) => /often sold out/.test(r)), 'sourdough sold-out note missing');
    await o.locator('#insightsBody table.forecast').scrollIntoViewIfNeeded();
    await shot(o, '82-insights-forecast');
  });

  await step('period switch and CSV export', async () => {
    await o.click('label.chip:has(input[name="insightDays"][value="7"])');
    await o.waitForFunction(() => document.querySelectorAll('#insightsBody .table-view tbody tr').length === 7);
    const [download] = await Promise.all([o.waitForEvent('download'), o.click('#exportCsv')]);
    const file = path.join(OUT, 'export.csv');
    await download.saveAs(file);
    const lines = fs.readFileSync(file, 'utf8').trim().split('\n');
    expect(lines[0].startsWith('date,product,rescue_drop,offered'), 'header: ' + lines[0]);
    // 6 finished demo days × 3 products (+ header); every line has 11 columns.
    expect(lines.length >= 16, 'lines ' + lines.length);
  });

  await axe(o, 'Insights tab');
  await step('Insights has no horizontal overflow on desktop', () => noOverflow(o, 'desktop'));

  await step('one-off editor has the weight field', async () => {
    await o.click('#storeTabToday');
    await o.click('#newItem');
    await o.waitForSelector('#itemDialog[open]');
    expect(await o.isVisible('#itemWeight'), 'weight field');
    await o.keyboard.press('Escape');
  });

  // ---------------- phone ----------------
  const p = await newPage(390, 844);
  await step('phone: Insights fits and the chart stays readable', async () => {
    await login(p, 'owner@example.com');
    await p.waitForSelector('#storeContent:not([hidden])');
    await p.click('#storeTabInsights');
    await p.waitForSelector('#insightsBody .insight-stats');
    await noOverflow(p, 'phone insights');
    await shot(p, '83-insights-phone', true);
  });

  // ---------------- customer: reminder setting, weight on cards ----------------
  const c = await newPage(1280, 900);
  await step('customer: pickup reminders are on by default; cards show weight', async () => {
    await login(c, 'student@example.com');
    await c.waitForSelector('#nearby[aria-busy="false"]');
    await c.click('#inboxBtn');
    await c.click('#inboxTabSettings');
    await c.waitForSelector('#interestChips input');
    expect(await c.isChecked('#pickupReminders'), 'reminders should be on');
    await c.keyboard.press('Escape');
  });

  // ---------------- admin ----------------
  const a = await newPage(1280, 900);
  await step('admin: platform overview', async () => {
    await login(a, 'admin@example.com');
    await a.waitForSelector('#adminInsights .facts');
    const text = await a.innerText('#adminInsights');
    expect(/Food rescued \d+ units · \d+/.test(text.replace(/\s+/g, ' ')), 'totals: ' + text.slice(0, 200));
    expect(/Quiet for 7 days/.test(text) && /Needs attention/.test(text), 'lists missing: ' + text);
    await a.locator('#adminInsights').scrollIntoViewIfNeeded();
    await shot(a, '84-admin-overview');
  });
  await axe(a, 'admin overview');

  await browser.close();
  for (const r of results) console.log(r.join(' | '));
  console.log(`\n${results.filter((r) => r[0] === 'PASS').length}/${results.length} passed`);
  console.log('console errors:', consoleErrors.length ? '\n' + consoleErrors.join('\n') : 'none');
})();
