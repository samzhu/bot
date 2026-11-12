// Issue #21 browser verification: Bot 新增／設定改用右側表單。
// PDF 第 66 頁：「頁首『＋ 新增 Bot』開啟右側建立表單，取消或 X 收起」；#19：設定共用同一右側面板。
// 前提：前端 4300、後端 8181（Fake 模型）已啟動且 manager 已嵌入 app-bot-editor。不觸發真模型／GCP。
import puppeteer from 'puppeteer-core';
import { assertSafeBackendOrigin } from '../test/verification-harness.mjs';

const CHROME_PATH = process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const FRONTEND_URL = process.env.VERIFICATION_FRONTEND_URL || 'http://localhost:4300';
const BACKEND_URL = process.env.VERIFICATION_BACKEND_URL || 'http://localhost:8181';
const SHOT = (n) => `/tmp/issue21-${n}.png`;
const RUN = Date.now().toString(36);

import fs from 'node:fs';
const LOG = '/tmp/issue21-e2e.log';
fs.writeFileSync(LOG, `issue21 e2e ${new Date().toISOString()}\n`);
const results = [];
function record(step, passed, details = '') {
  results.push({ step, passed, details });
  const line = `${passed ? '✅ PASS' : '❌ FAIL'} - ${step}${details ? ` (${details})` : ''}`;
  console.log(line);
  fs.appendFileSync(LOG, line + '\n');
}
function assert(cond, msg) {
  if (!cond) throw new Error(msg);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function gql(query, variables = {}) {
  const res = await fetch(`${BACKEND_URL}/graphql`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query, variables })
  });
  return res.json();
}
async function findBots(search) {
  const json = await gql(
    'query($s:String){ bots(search:$s, limit:50, offset:0){ id name description instructions } }',
    { s: search }
  );
  return json.data?.bots ?? [];
}

const T = (id) => `[data-testid="${id}"]`;
async function setValue(page, sel, value) {
  await page.waitForSelector(sel);
  await page.evaluate((s, v) => {
    const el = document.querySelector(s);
    el.focus();
    el.value = v;
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }, sel, value);
}
const val = (page, sel) => page.$eval(sel, (e) => e.value);
const activeTestId = (page) => page.evaluate(() => document.activeElement?.getAttribute('data-testid') ?? null);
const dialogOpen = (page) => page.$eval(T('create-bot-dialog'), (d) => d.open);

async function panelGeometry(page) {
  return page.$eval(T('create-bot-dialog'), (d) => {
    const r = d.getBoundingClientRect();
    const body = d.querySelector('.editor-body');
    const header = d.querySelector('.editor-header').getBoundingClientRect();
    const footer = d.querySelector('.editor-footer').getBoundingClientRect();
    return {
      left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: r.width,
      vw: window.innerWidth, vh: window.innerHeight,
      bodyScrollable: body.scrollHeight > body.clientHeight,
      bodyOverflowY: getComputedStyle(body).overflowY,
      headerBottom: header.bottom, footerTop: footer.top, footerBottom: footer.bottom,
      pageHScroll: document.documentElement.scrollWidth > window.innerWidth
    };
  });
}

const overlap = (a, b) => a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom;
const rectOf = (page, sel) => page.$eval(sel, (e) => {
  const r = e.getBoundingClientRect();
  return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: r.width, height: r.height };
});

async function openManager(page) {
  await page.goto(FRONTEND_URL, { waitUntil: 'networkidle2' });
  await page.waitForSelector(T('bot-manager-nav-btn'));
  await page.click(T('bot-manager-nav-btn'));
  await page.waitForSelector(T('create-bot-modal-btn'));
}

async function run() {
  await assertSafeBackendOrigin(BACKEND_URL);
  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage']
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 800 });

  const mutations = [];
  let failNextMutation = false;
  let failNextBotsQuery = false;
  let failNextMutationMessage = '模擬保存失敗 (e2e)';
  await page.setRequestInterception(true);
  page.on('request', (req) => {
    if (req.url().includes('/graphql') && req.method() === 'POST') {
      const q = JSON.parse(req.postData() || '{}').query || '';
      if (/mutation\s+(Create|Update)Bot/.test(q)) {
        mutations.push(q.includes('CreateBot') ? 'create' : 'update');
        if (failNextMutation) {
          failNextMutation = false;
          return req.respond({ status: 200, contentType: 'application/json',
            body: JSON.stringify({ errors: [{ message: failNextMutationMessage }] }) });
        }
      } else if (failNextBotsQuery && /bots\s*\(/.test(q)) {
        failNextBotsQuery = false;
        return req.respond({ status: 500, contentType: 'application/json', body: '{}' });
      }
    }
    req.continue();
  });

  const name = `E2E21-${RUN}`;
  const desc = '  第一行\n    縮排第二行  ';
  const instr = '\n  指示 A\n\t指示 B\n';

  try {
    // 1. 桌面：右側貼邊、保留背景、標題與標籤（PDF p66）
    await openManager(page);
    await page.click(T('create-bot-modal-btn'));
    await page.waitForSelector(`${T('create-bot-dialog')}[open]`);
    await sleep(100);
    let g = await panelGeometry(page);
    assert(Math.abs(g.right - g.vw) <= 1, `panel not flush right: ${g.right}/${g.vw}`);
    assert(g.left > g.vw / 2 && g.width <= 520, `panel not a right-side narrow panel: left=${g.left} width=${g.width}`);
    assert(g.top <= 1 && Math.abs(g.bottom - g.vh) <= 1, 'panel not full height');
    const title = await page.$eval(T('bot-editor-title'), (e) => e.textContent.trim());
    assert(title === '新增 Bot', `title=${title}`);
    assert((await activeTestId(page)) === 'bot-name-input', `initial focus=${await activeTestId(page)}`);
    const bgVisible = await page.evaluate(() => {
      const el = document.querySelector('[data-testid="create-bot-modal-btn"]');
      return el.getBoundingClientRect().width > 0;
    });
    assert(bgVisible, 'manager background not visible');
    await page.screenshot({ path: SHOT('01-desktop-create') });
    record('桌面新增 Bot 右側面板（PDF p66）', true, 'flush right, full height, 焦點在名稱');

    // 2. 遮罩點擊不關閉
    await page.mouse.click(100, 400);
    await sleep(100);
    assert(await dialogOpen(page), 'backdrop click closed panel');
    record('遮罩點擊不關閉', true);

    // 3. 取消／X／Escape：無 mutation、焦點返回觸發鈕
    const before = mutations.length;
    for (const how of ['cancel', 'x', 'escape']) {
      if (!(await dialogOpen(page))) {
        await page.click(T('create-bot-modal-btn'));
        await page.waitForSelector(`${T('create-bot-dialog')}[open]`);
      }
      await setValue(page, T('bot-name-input'), `discard-${how}`);
      if (how === 'cancel') await page.click(T('cancel-dialog-btn'));
      if (how === 'x') await page.click(T('dialog-close-x-btn'));
      if (how === 'escape') await page.keyboard.press('Escape');
      await sleep(150);
      assert(!(await dialogOpen(page)), `${how}: still open`);
      assert((await activeTestId(page)) === 'create-bot-modal-btn', `${how}: focus=${await activeTestId(page)}`);
    }
    assert(mutations.length === before, 'close paths issued a mutation');
    record('取消／X／Escape 無 mutation 且焦點返回「新增 Bot」', true);

    // 4. 校驗：純空白、101 字、100 字含前後空白可保存
    await page.click(T('create-bot-modal-btn'));
    await page.waitForSelector(`${T('create-bot-dialog')}[open]`);
    await setValue(page, T('bot-name-input'), '   ');
    await page.focus(T('bot-desc-input'));
    assert(await page.$eval(T('submit-bot-btn'), (b) => b.disabled), '空白名稱仍可送出');
    await setValue(page, T('bot-name-input'), 'x'.repeat(101));
    assert(await page.$eval(T('submit-bot-btn'), (b) => b.disabled), '101 字名稱仍可送出');
    record('名稱純空白與 101 字被前端拒絕', true);

    // 5. 保存失敗：面板保持、輸入保留、無 saved
    await setValue(page, T('bot-name-input'), name);
    await setValue(page, T('bot-desc-input'), desc);
    await setValue(page, T('bot-instructions-input'), instr);
    failNextMutation = true;
    await page.click(T('submit-bot-btn'));
    await page.waitForSelector(T('form-error-banner'));
    assert(await dialogOpen(page), '失敗後面板關閉');
    assert((await val(page, T('bot-name-input'))) === name, '失敗後名稱遺失');
    assert((await val(page, T('bot-desc-input'))) === desc, '失敗後用途遺失');
    assert((await val(page, T('bot-instructions-input'))) === instr, '失敗後指示遺失');
    assert((await findBots(name)).length === 0, '失敗請求不應寫入');
    record('保存失敗：面板開啟、錯誤可見、輸入保留、零寫入', true);

    // 6. 重試成功；refetch 失敗時只顯示可重試錯誤，不重複建立
    failNextBotsQuery = true;
    const m0 = mutations.filter((m) => m === 'create').length;
    await page.click(T('submit-bot-btn'));
    await page.waitForFunction((s) => !document.querySelector(s)?.open, {}, T('create-bot-dialog'));
    await sleep(300);
    assert(mutations.filter((m) => m === 'create').length === m0 + 1, 'create 次數異常');
    assert((await activeTestId(page)) === 'create-bot-modal-btn', `保存後焦點=${await activeTestId(page)}`);
    const storeErr = await page.$(T('bot-store-error'));
    record('保存成功後關閉；refetch 失敗顯示重試錯誤且未重送 create', !!storeErr, storeErr ? '' : '未見清單錯誤橫幅');
    assert(storeErr, 'refetch 失敗未顯示錯誤');
    const created = await findBots(name);
    assert(created.length === 1, `重複或缺少 Bot: ${created.length}`);
    assert(created[0].name === name && created[0].description === desc && created[0].instructions === instr,
      '後端內容與原始輸入不符（空白／換行）');
    record('PostgreSQL 持久化：名稱 trim、用途／指示原始空白換行保留', true);

    // 7. 重新整理後設定：回填完整內容並更新
    await openManager(page);
    await setValue(page, T('bot-search-input'), name);
    await sleep(500);
    const id = created[0].id;
    await page.waitForSelector(T(`edit-bot-btn-${id}`));
    await page.click(T(`edit-bot-btn-${id}`));
    await page.waitForSelector(`${T('create-bot-dialog')}[open]`);
    // showModal 同步開啟，Signal Forms 與標題則於 Angular 下一輪 render 更新。
    await page.waitForFunction((s) => document.querySelector(s)?.textContent.trim() === '設定 Bot', {}, T('bot-editor-title'));
    assert((await page.$eval(T('bot-editor-title'), (e) => e.textContent.trim())) === '設定 Bot', '設定標題錯誤');
    assert((await val(page, T('bot-name-input'))) === name, '設定未回填名稱');
    assert((await val(page, T('bot-desc-input'))) === desc, '設定未回填用途');
    assert((await val(page, T('bot-instructions-input'))) === instr, '設定未回填指示');
    await page.screenshot({ path: SHOT('02-desktop-edit') });
    await setValue(page, T('bot-name-input'), `${name}-v2`);
    await page.click(T('submit-bot-btn'));
    await page.waitForFunction((s) => !document.querySelector(s)?.open, {}, T('create-bot-dialog'));
    assert((await activeTestId(page)) === `edit-bot-btn-${id}`, `設定關閉後焦點=${await activeTestId(page)}`);
    const updated = (await findBots(`${name}-v2`))[0];
    assert(updated && updated.id === id && updated.description === desc, '更新未持久化或影響其他欄位');
    record('設定共用右側面板：回填、更新、焦點返回「設定」', true);


    // 7b. 空列表：搜尋無結果 → 空狀態真實顯示，且仍可從空狀態開啟新增面板
    await setValue(page, T('bot-search-input'), `zz-no-match-${RUN}`);
    await page.waitForSelector(T('bots-empty-state'));
    const emptyText = await page.$eval(T('bots-empty-state'), (e) => e.textContent.trim());
    assert(emptyText.includes('沒有符合條件'), `空狀態文案不符: ${emptyText}`);
    assert((await page.$$('[data-testid^="bot-card-"]')).length === 0, '空列表仍有卡片');
    await page.click(T('create-bot-modal-btn'));
    await page.waitForSelector(`${T('create-bot-dialog')}[open]`);
    assert((await val(page, T('bot-name-input'))) === '', '空列表開啟的新增表單不是空白');
    await page.click(T('cancel-dialog-btn'));
    await sleep(100);
    assert(!(await dialogOpen(page)), '空列表下取消未關閉');
    record('空列表：空狀態顯示且仍可開啟／取消新增面板', true);

    // 7c. 列表 API 錯誤：顯示錯誤橫幅與重試鈕，重試後恢復，且過程無 create/update mutation
    const mBefore = mutations.length;
    failNextBotsQuery = true;
    await setValue(page, T('bot-search-input'), name);
    await page.waitForSelector(T('bot-store-error'));
    const errText = await page.$eval(T('bot-store-error'), (e) => e.textContent.trim());
    assert(errText.includes('重試載入') && errText.length > '重試載入'.length, `API 錯誤橫幅無實際訊息: ${errText}`);
    await page.evaluate(() => [...document.querySelectorAll('.retry-link-btn')].find((b) => b.textContent.includes('重試載入')).click());
    await page.waitForFunction((s) => !document.querySelector(s), {}, T('bot-store-error'));
    await page.waitForSelector(T(`bot-card-${id}`));
    assert(mutations.length === mBefore, '重試查詢期間出現 mutation');
    record('列表 API 錯誤：顯示訊息與重試，重試只查詢並恢復', true);

    // 7d. 長名（trim 後 100 字、含前後空白）可建立，並在清單卡片不溢出
    const longName = `L${RUN}`.padEnd(100, 'ｗ');
    await page.click(T('create-bot-modal-btn'));
    await page.waitForSelector(`${T('create-bot-dialog')}[open]`);
    await setValue(page, T('bot-name-input'), `  ${longName}  `);
    await page.click(T('submit-bot-btn'));
    await page.waitForFunction((s) => !document.querySelector(s)?.open, {}, T('create-bot-dialog'));
    const longBots = await findBots(`L${RUN}`);
    assert(longBots.length === 1 && longBots[0].name === longName && longBots[0].name.length === 100,
      `長名未以 trim 後 100 字保存: ${longBots.map((b) => b.name.length)}`);
    record('100 字長名（含前後空白）保存為 trim 後 100 字', true);

    // 8. 375px：長名卡片、錯誤橫幅、全寬、獨立捲動、區塊不重疊（真實 bounding box）
    await page.setViewport({ width: 375, height: 667 });
    await openManager(page);
    await setValue(page, T('bot-search-input'), `L${RUN}`);
    await page.waitForSelector(T(`bot-card-${longBots[0].id}`));
    const cardMetrics = await page.$eval(T(`bot-card-${longBots[0].id}`), (c) => {
      const r = c.getBoundingClientRect();
      return { right: r.right, vw: window.innerWidth, over: c.scrollWidth > c.clientWidth + 1,
               pageH: document.documentElement.scrollWidth > window.innerWidth };
    });
    assert(!cardMetrics.over && cardMetrics.right <= cardMetrics.vw + 1 && !cardMetrics.pageH, `長名卡片溢出: ${JSON.stringify(cardMetrics)}`);
    record('375px 長名卡片不溢出、無水平捲軸', true);

    await page.click(T('create-bot-modal-btn'));
    await page.waitForSelector(`${T('create-bot-dialog')}[open]`);
    await setValue(page, T('bot-name-input'), longName);
    await setValue(page, T('bot-desc-input'), '長文字'.repeat(600));
    failNextMutationMessage = '模擬很長的伺服器錯誤訊息 ' + 'ERR'.repeat(120);
    failNextMutation = true;
    await page.click(T('submit-bot-btn'));
    await page.waitForSelector(T('form-error-banner'));
    await sleep(100);
    g = await panelGeometry(page);
    assert(g.left <= 1 && Math.abs(g.right - g.vw) <= 1, `375 面板非全寬: ${g.left}-${g.right}`);
    assert(g.bodyOverflowY === 'auto' && g.bodyScrollable, '表單內容未獨立捲動');
    assert(g.footerBottom <= g.vh + 1 && g.top <= 1, '底部操作被裁切或面板偏移');
    assert(!g.pageHScroll, '出現水平捲軸');
    const title375 = await rectOf(page, T('bot-editor-title'));
    const closeX = await rectOf(page, T('dialog-close-x-btn'));
    const cancel = await rectOf(page, T('cancel-dialog-btn'));
    const submitR = await rectOf(page, T('submit-bot-btn'));
    const headerR = await rectOf(page, '.editor-header');
    const footerR = await rectOf(page, '.editor-footer');
    const bodyR = await rectOf(page, '.editor-body');
    assert(!overlap(title375, closeX), '標題與 X 重疊');
    assert(!overlap(cancel, submitR), '取消與保存按鈕重疊');
    assert(!overlap(headerR, footerR) && !overlap(headerR, bodyR) && !overlap(bodyR, footerR), '標頭／內容／底部區塊重疊');
    for (const [n, r] of Object.entries({ closeX, cancel, submitR })) {
      assert(r.left >= 0 && r.right <= g.vw && r.top >= 0 && r.bottom <= g.vh && r.width > 0 && r.height > 0, `${n} 不在可視範圍: ${JSON.stringify(r)}`);
    }
    // 錯誤橫幅在內容區內捲動可見，且不侵入底部操作列
    await page.$eval(T('form-error-banner'), (e) => e.scrollIntoView({ block: 'center' }));
    const banner = await rectOf(page, T('form-error-banner'));
    assert(banner.width > 0 && banner.right <= g.vw + 1, '錯誤橫幅被裁切或溢出寬度');
    assert(!overlap(banner, footerR) && !overlap(banner, headerR), '錯誤橫幅與標頭／底部重疊');
    // 長錯誤訊息完整保留在 DOM，且保存後輸入仍在
    const bannerText = await page.$eval(T('form-error-banner'), (e) => e.textContent.trim());
    assert(bannerText.endsWith('ERR'.repeat(120)), '長錯誤訊息被截斷');
    assert((await val(page, T('bot-name-input'))) === longName, '失敗後長名輸入遺失');
    await page.screenshot({ path: SHOT('03-375-error') });
    record('375px 全寬、獨立捲動、長錯誤訊息與頭／身／尾區塊真實不重疊', true);
    await page.click(T('cancel-dialog-btn'));
  } catch (err) {
    record('驗證中斷', false, err.message);
    await page.screenshot({ path: SHOT('failure') }).catch(() => {});
  } finally {
    await browser.close();
  }

  const failed = results.filter((r) => !r.passed);
  console.log(`\n${results.length - failed.length}/${results.length} passed`);
  return failed.length === 0;
}

run().then((ok) => process.exit(ok ? 0 : 1));
