// Issue #20 browser verification: 獨立 Bot 管理清單（PDF 第 57、66 頁；保留 #19 認可的「對話列表／Bot 管理」Tabs）。
// 前提：前端 4300（proxy 到後端 8181，Fake 模型）已啟動。不觸發真模型／GCP；只透過公開 GraphQL 建立合成 Bot。
import puppeteer from 'puppeteer-core';
import { assertSafeBackendOrigin } from '../test/verification-harness.mjs';

const CHROME_PATH = process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const FRONTEND_URL = process.env.VERIFICATION_FRONTEND_URL || 'http://localhost:4300';
const BACKEND_URL = process.env.VERIFICATION_BACKEND_URL || 'http://localhost:8181';
const SHOT = (n) => `/tmp/issue20-${n}.png`;
const RUN = Date.now().toString(36);
const TAG = `E2E20${RUN}`;
const INSTR_ONLY = `InstrOnly${RUN}`;
const LONG_NAME = (`${TAG}-LONG-` + 'VeryLongUnbrokenBotNameSegment'.repeat(6)).slice(0, 100);

const results = [];
function record(step, passed, details = '') {
  results.push({ step, passed, details });
  console.log(`${passed ? '✅ PASS' : '❌ FAIL'} - ${step}${details ? ` (${details})` : ''}`);
}
function assert(cond, msg) {
  if (!cond) throw new Error(msg);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const T = (id) => `[data-testid="${id}"]`;

async function gql(query, variables = {}) {
  const res = await fetch(`${BACKEND_URL}/graphql`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query, variables })
  });
  const result = await res.json();
  assert(res.ok && !result.errors, `GraphQL failed: ${JSON.stringify(result.errors ?? res.status)}`);
  return result;
}

async function seedBots() {
  const make = (name, description, instructions) =>
    gql('mutation($i:CreateBotInput!){ createBot(input:$i){ id name } }', { i: { name, description, instructions } });
  // 23 筆（>20）以驗證跨頁；最後建立的最新，排序為 updated_at DESC。
  for (let i = 1; i <= 21; i++) {
    await make(`${TAG}-Bot-${String(i).padStart(2, '0')}`, `${TAG} 用途 ${i}`, `一般指示 ${i}`);
  }
  await make(LONG_NAME, `${TAG} 長名稱用途 ` + '很長的用途說明'.repeat(20), '指示');
  // 指示全文含獨有字串，但名稱／用途不含：搜尋不得命中。
  await make(`${TAG}-InstrHolder`, `${TAG} 指示持有者`, `${INSTR_ONLY} 這段只存在於工作指示`);
}

async function setValue(page, sel, value) {
  await page.waitForSelector(sel);
  await page.evaluate((s, v) => {
    const el = document.querySelector(s);
    el.focus();
    el.value = v;
    el.dispatchEvent(new Event('input', { bubbles: true }));
  }, sel, value);
}

async function openManager(page) {
  await page.goto(FRONTEND_URL, { waitUntil: 'networkidle2' });
  await page.waitForSelector(T('bot-manager-nav-btn'));
  await page.click(T('bot-manager-nav-btn'));
  await page.waitForSelector(T('create-bot-modal-btn'));
  await page.waitForSelector(`${T('bot-pagination')}, ${T('bots-empty-state')}`);
}

const cardCount = (page) => page.$$eval('[data-testid^="bot-card-"]', (els) => els.length);
const cardNames = (page) => page.$$eval('[data-testid^="bot-card-"] .card-name', (els) => els.map((e) => e.textContent.trim()));
async function waitFor(fn, msg, timeout = 8000) {
  const start = Date.now();
  while (Date.now() - start < timeout) {
    if (await fn()) return;
    await sleep(100);
  }
  throw new Error(`timeout: ${msg}`);
}

async function layoutReport(page) {
  return page.evaluate(() => {
    const vw = window.innerWidth;
    const cards = [...document.querySelectorAll('[data-testid^="bot-card-"]')];
    const outside = cards.filter((c) => {
      const r = c.getBoundingClientRect();
      return r.left < 0 || r.right > vw + 1;
    }).length;
    const overflowing = cards.filter((c) => c.scrollWidth > c.clientWidth + 1).length;
    const btnOutside = [...document.querySelectorAll('.card-action-btn')].filter((b) => {
      const r = b.getBoundingClientRect();
      const c = b.closest('article').getBoundingClientRect();
      return r.right > c.right + 1 || r.left < c.left - 1;
    }).length;
    const tb = ['.search-box', '.target-filter-box', '.primary-action-btn'].map((s) => {
      const r = document.querySelector(s).getBoundingClientRect();
      return r.left >= 0 && r.right <= vw + 1 && r.width > 0;
    });
    const container = document.querySelector('.bot-manager-container');
    return {
      vw,
      cards: cards.length,
      outside,
      overflowing,
      btnOutside,
      toolbarVisible: tb.every(Boolean),
      containerHScroll: container.scrollWidth > container.clientWidth + 1,
      pageHScroll: document.documentElement.scrollWidth > vw
    };
  });
}

async function run() {
  await assertSafeBackendOrigin(BACKEND_URL);
  await seedBots();
  const def = await gql('query{ defaultLocalBot { id name target } }');
  const defaultName = def.data?.defaultLocalBot?.name;
  assert(defaultName, 'defaultLocalBot missing before UI check');

  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage']
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 800 });

  const botsQueries = [];
  const mutations = [];
  let slowSearch = null;
  await page.setRequestInterception(true);
  page.on('request', async (req) => {
    if (req.url().includes('/graphql') && req.method() === 'POST') {
      const body = JSON.parse(req.postData() || '{}');
      if (/mutation/.test(body.query || '')) mutations.push(body.query);
      if (/bots\s*\(/.test(body.query || '')) {
        botsQueries.push(body.variables);
        if (slowSearch && body.variables?.search === slowSearch) {
          await sleep(1500);
        }
      }
    }
    req.continue();
  });

  try {
    // 1. 標題／操作文案（PDF p57），預設助理不混入、管理查詢固定 GKE_REMOTE
    await openManager(page);
    assert((await page.$eval('h1', (e) => e.textContent.trim())) === '機器人', 'h1 != 機器人');
    assert((await page.$eval(T('create-bot-modal-btn'), (e) => e.textContent.trim())) === '新增 Bot', '新增 Bot 文案');
    assert((await page.$eval(T('bot-search-input'), (e) => e.placeholder)) === '搜尋機器人名稱或用途', 'placeholder');
    // App 初始化也會查預設聊天設定；此處只驗證進入 manager 後最後送出的清單查詢。
    assert(botsQueries.at(-1)?.target === 'GKE_REMOTE', 'manager query target not fixed to GKE_REMOTE');
    await setValue(page, T('bot-search-input'), TAG);
    await waitFor(async () => (await cardCount(page)) === 20, '第一頁 20 筆');
    const names1 = await cardNames(page);
    assert(!names1.includes(defaultName), `default assistant "${defaultName}" mixed into list`);
    const bodyText = await page.evaluate(() => document.body.innerText);
    assert(!/GKE_REMOTE|\bLOCAL\b|NOT_CONNECTED|建立於|新增 GKE 代理|開啟草稿/.test(await page.$eval('main', (e) => e.innerText)), '主區出現 enum／舊文案');
    assert(!bodyText.includes(INSTR_ONLY), '指示全文出現在清單');
    await page.screenshot({ path: SHOT('01-desktop-list') });
    record('桌面：機器人標題、新增 Bot、搜尋；預設助理與 enum 不入清單；查詢固定 GKE_REMOTE', true, `${names1.length} cards`);

    // 2. 卡片內容與主要操作
    const firstCard = await page.$eval('[data-testid^="bot-card-"]', (e) => e.innerText);
    assert(/開始對話/.test(firstCard) && /設定/.test(firstCard), '卡片缺開始對話／設定');
    assert(!/最近使用|專屬記憶|\d+\s*個機器人/.test(bodyText), '出現虛構資料或當頁總量');
    record('卡片：名稱／用途／開始對話／設定；無虛構最近使用、記憶、總量', true);

    // 3. 狀態篩選僅「全部狀態／未連線」
    const opts = await page.$$eval(`${T('status-filter-select')} option`, (os) => os.map((o) => o.textContent.trim()));
    assert(JSON.stringify(opts) === JSON.stringify(['全部狀態', '未連線']), `options=${opts}`);
    assert(!(await page.$(T('target-filter-select'))), '仍有 target 選單');
    await page.select(T('status-filter-select'), 'NOT_CONNECTED');
    await waitFor(async () => (await cardCount(page)) === 20, '未連線篩選後仍為第一頁 20 筆');
    assert(botsQueries.at(-1).target === 'GKE_REMOTE' && botsQueries.at(-1).offset === 0, '狀態篩選後查詢不正確');
    record('狀態選單只含 全部狀態／未連線，篩選回第一頁且範圍不變', true);

    // 4. 跨頁：第二頁 3 筆、改搜尋回第一頁、排序決定性
    await page.click(T('next-page-btn'));
    await waitFor(async () => (await cardCount(page)) === 3, '第二頁 3 筆');
    const names2 = await cardNames(page);
    assert(names2.every((n) => !names1.includes(n)), '跨頁重複');
    assert((await page.$eval(T('current-page-indicator'), (e) => e.textContent)).includes('2'), '頁碼');
    assert(await page.$eval(T('next-page-btn'), (b) => b.disabled), '最後一頁 next 未停用');
    await page.screenshot({ path: SHOT('02-page2') });
    await setValue(page, T('bot-search-input'), `${TAG}-Bot-0`);
    await waitFor(async () => (await page.$eval(T('current-page-indicator'), (e) => e.textContent)).includes('1'), '搜尋回第一頁');
    assert(botsQueries.at(-1).offset === 0, '搜尋後 offset 未歸零');
    record('分頁跨頁無重複、末頁停用下一頁、變更搜尋回第一頁', true, `page2=${names2.length}`);

    // 5. 搜尋比對名稱／用途，不比對指示
    await setValue(page, T('bot-search-input'), INSTR_ONLY);
    await waitFor(async () => !!(await page.$(T('bots-empty-state'))), '指示專用字串應為空清單');
    await page.screenshot({ path: SHOT('03-empty') });
    await setValue(page, T('bot-search-input'), `${TAG} 指示持有者`);
    await waitFor(async () => (await cardCount(page)) === 1, '用途搜尋命中 1 筆');
    record('搜尋不比對工作指示；用途可搜尋；空清單提示', true);

    // 6. 過期回應防護：慢回應不覆蓋新結果
    slowSearch = `${TAG}-Bot-01`;
    await setValue(page, T('bot-search-input'), slowSearch);
    await sleep(100);
    slowSearch = null;
    await setValue(page, T('bot-search-input'), `${TAG} 指示持有者`);
    await sleep(2200);
    const afterStale = await cardNames(page);
    assert(afterStale.length === 1 && afterStale[0] === `${TAG}-InstrHolder`, `stale overwrite: ${afterStale}`);
    record('較舊的慢回應不覆蓋較新搜尋結果', true);

    // 7. 設定：開啟右側編輯器，指示全文在 editor；開始對話：切回聊天並帶入 Bot
    const holderId = await page.$eval('[data-testid^="bot-card-"]', (e) => e.getAttribute('data-testid').replace('bot-card-', ''));
    await page.click(T(`edit-bot-btn-${holderId}`));
    await page.waitForSelector(`${T('create-bot-dialog')}[open]`);
    const instrVal = await page.$eval(T('bot-instructions-input'), (e) => e.value);
    assert(instrVal.includes(INSTR_ONLY), 'editor 未帶入指示全文');
    await page.screenshot({ path: SHOT('04-settings-editor') });
    await page.click(T('cancel-dialog-btn'));
    await waitFor(async () => !(await page.$(`${T('create-bot-dialog')}[open]`)), '關閉編輯器');
    assert(mutations.length === 0, `管理清單操作不應送出 mutation: ${mutations.length}`);
    await page.click(T(`open-draft-btn-${holderId}`));
    await waitFor(async () => !(await page.$(T('create-bot-modal-btn'))), '開始對話後離開管理頁');
    assert((await page.evaluate(() => document.body.innerText)).includes(`${TAG}-InstrHolder`), '聊天情境未顯示 Bot 名稱');
    await page.screenshot({ path: SHOT('05-start-chat') });
    record('設定開啟編輯器（指示全文在 editor、無 mutation）；開始對話帶入正確 Bot', true);

    // 8. 桌面長名稱
    await openManager(page);
    await setValue(page, T('bot-search-input'), LONG_NAME);
    await waitFor(async () => (await cardCount(page)) === 1, '長名稱卡片');
    let rep = await layoutReport(page);
    assert(rep.outside === 0 && rep.overflowing === 0 && rep.btnOutside === 0 && !rep.pageHScroll, `desktop long name layout ${JSON.stringify(rep)}`);
    await page.screenshot({ path: SHOT('06-desktop-long-name') });

    // 9. 375px：長名稱、列表、分頁、入口可見，且非僅靠水平捲軸
    await page.setViewport({ width: 375, height: 800 });
    await sleep(200);
    rep = await layoutReport(page);
    assert(rep.outside === 0 && rep.overflowing === 0 && rep.btnOutside === 0, `375 long name layout ${JSON.stringify(rep)}`);
    assert(rep.toolbarVisible && !rep.containerHScroll && !rep.pageHScroll, `375 toolbar/scroll ${JSON.stringify(rep)}`);
    await page.screenshot({ path: SHOT('07-375-long-name') });
    await setValue(page, T('bot-search-input'), TAG);
    await waitFor(async () => (await cardCount(page)) === 20, '375 第一頁');
    rep = await layoutReport(page);
    assert(rep.outside === 0 && rep.overflowing === 0 && rep.btnOutside === 0 && !rep.containerHScroll, `375 list ${JSON.stringify(rep)}`);
    const pagerVisible = await page.$eval(T('bot-pagination'), (n) => {
      n.scrollIntoView();
      const r = n.getBoundingClientRect();
      return r.left >= 0 && r.right <= window.innerWidth && r.bottom <= window.innerHeight && r.height > 0;
    });
    assert(pagerVisible, '375 分頁不在可視範圍');
    await page.screenshot({ path: SHOT('08-375-pagination') });
    const createVisible = await page.$eval(T('create-bot-modal-btn'), (b) => {
      b.scrollIntoView();
      const r = b.getBoundingClientRect();
      return r.left >= 0 && r.right <= window.innerWidth && r.width > 0;
    });
    assert(createVisible, '375 新增 Bot 入口不可見');
    record('375px：長名稱、搜尋／狀態／新增、卡片操作與分頁皆在可視範圍，無水平捲軸', true);

    // 10. 預設助理未被修改
    const def2 = await gql('query{ defaultLocalBot { id name target } }');
    assert(def2.data?.defaultLocalBot?.id === def.data.defaultLocalBot.id, 'defaultLocalBot 變動');
    record('defaultLocalBot 保持不變', true);
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
