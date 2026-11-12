import assert from 'node:assert/strict';
import { join } from 'node:path';
import { startVerification, browserUrl, backendUrl } from './threadplane-support.mjs';

const suite = await startVerification('bot');
const { page, guard, graphql, check, finish, artifactsDir } = suite;
const tag = `B28-${Date.now().toString(36)}`;
const pageTag = process.env.BOT_PAGINATION_TAG || tag;
const t = id => `[data-testid="${id}"]`;
let mutations = 0, failRead = false, reads = 0, injected = 0, botId;
const fields = 'id name description instructions target executionMode connectionStatus canChat';
const set = (selector, value) => page.$eval(selector, (e, v) => { e.value = v; e.dispatchEvent(new Event('input', { bubbles: true })); }, value);
const shot = name => page.screenshot({ path: join(artifactsDir, name), fullPage: true });
try {
  const safe = await check('SAFE_MOCK 三欄與 proxy', async () => {
    for (const url of [backendUrl(), browserUrl()]) {
      const response = await fetch(new URL('/api/internal/verification/status', url));
      assert.equal(response.status, 200);
      const status = await response.json();
      assert.equal(status.status, 'SAFE_MOCK'); assert.equal(status.mock, true); assert.equal(status.modelClass, 'MockChatModel');
    }
    return true;
  });
  if (!safe) throw new Error('SAFE_MOCK verification failed; no Bot mutations attempted');
  guard.addRequestHandler(req => {
    if (!req.url().includes('/graphql') || req.method() !== 'POST') return;
    let query = ''; try { query = JSON.parse(req.postData() || '{}').query || ''; } catch { return; }
    if (/mutation\s+(Create|Update)Bot/.test(query)) mutations++;
    if (failRead && /bots\s*\(/.test(query) && ++reads === 2) {
      failRead = false; injected++; return req.respond({ status: 500, contentType: 'application/json', body: '{}' });
    }
  });
  await check('桌面 Bot 導覽與原生狀態選擇', async () => {
    await page.setViewport({ width: 1280, height: 800 });
    await page.goto(browserUrl(), { waitUntil: 'networkidle2' });
    await page.waitForSelector(t('nav-bots')); await page.click(t('nav-bots')); await page.waitForSelector(t('create-bot-modal-btn'));
    const trigger = await page.$('chat-select[data-testid="status-filter-select"] button'); assert(trigger);
    await trigger.click(); await page.waitForSelector('[role="option"]');
    assert.deepEqual(await page.$$eval('[role="option"]', els => els.map(e => e.textContent.trim())), ['全部狀態', '未連線']);
    await page.evaluate(() => [...document.querySelectorAll('[role="option"]')].find(e => e.textContent.trim() === '未連線').click());
    await page.waitForFunction(() => document.querySelector('chat-select[data-testid="status-filter-select"] button')?.textContent.includes('未連線'));
  });
  await check('真 API 建立、表單驗證、注入讀取失敗與只讀重試', async () => {
    await page.click(t('create-bot-modal-btn')); await page.waitForSelector(`${t('create-bot-dialog')}[open]`);
    assert.equal(await page.evaluate(() => document.activeElement?.getAttribute('data-testid')), 'bot-name-input');
    assert.equal(await page.$eval(t('submit-bot-btn'), e => e.disabled), true); await shot('desktop-dialog.png');
    await set(t('bot-name-input'), `${tag}-main`); await set(t('bot-desc-input'), 'synthetic purpose'); await set(t('bot-instructions-input'), '  synthetic instruction\n line two  ');
    failRead = true; reads = 0; const before = mutations; await page.click(t('submit-bot-btn'));
    await page.waitForSelector(t('bot-store-error'), { timeout: 10000 }); assert.equal(injected, 1); assert.equal(mutations, before + 1);
    await page.click(`${t('bot-store-error')} button`); await page.waitForFunction(() => !document.querySelector('[data-testid="bot-store-error"]'));
    assert.equal(mutations, before + 1);
    const data = await graphql(`query($s:String){bots(search:$s,target:GKE_REMOTE,limit:20,offset:0){${fields}}}`, { s: tag });
    const bot = data.bots.find(b => b.name === `${tag}-main`); assert(bot); botId = bot.id;
    assert.equal(bot.instructions, '  synthetic instruction\n line two  '); assert.equal(bot.connectionStatus, 'NOT_CONNECTED');
  });
  await check('真 API 搜尋、修改與真實 Bot 狀態', async () => {
    assert(botId); await set(t('bot-search-input'), tag);
    await page.waitForSelector(t(`bot-card-${botId}`));
    const text = await page.$eval(t(`bot-card-${botId}`), e => e.textContent);
    assert.match(text, /模擬|未連線/); assert(!text.includes('synthetic instruction')); assert(!text.includes('General Assistant'));
    await page.click(t(`edit-bot-btn-${botId}`)); await page.waitForSelector(`${t('create-bot-dialog')}[open]`);
    await set(t('bot-desc-input'), 'updated synthetic purpose'); const before = mutations; await page.click(t('submit-bot-btn'));
    await page.waitForFunction(() => !document.querySelector('[data-testid="create-bot-dialog"][open]')); assert.equal(mutations, before + 1);
    const data = await graphql(`query($s:String){bots(search:$s,target:GKE_REMOTE,limit:20,offset:0){${fields}}}`, { s: tag });
    assert.equal(data.bots.find(b => b.id === botId)?.description, 'updated synthetic purpose');
  });
  await check('真 API 分頁及 owner/default 範圍', async () => {
    if (!process.env.BOT_PAGINATION_TAG) for (let i = 0; i < 21; i++) await graphql('mutation($input:CreateBotInput!){createBot(input:$input){id}}', { input: { name: `${pageTag}-page-${String(i).padStart(2, '0')}`, description: 'synthetic pagination fixture', instructions: '' } });
    await set(t('bot-search-input'), `${pageTag}-page-`);
    await page.waitForFunction(() => !document.querySelector('[data-testid="next-page-btn"]')?.disabled);
    const first = await page.$$eval('[data-testid^="bot-card-"]', els => els.map(e => e.getAttribute('data-testid')));
    assert.equal(first.length, 20); await page.click(t('next-page-btn'));
    await page.waitForFunction(() => document.querySelector('[data-testid="current-page-indicator"]')?.textContent.includes('2'));
    await page.waitForFunction(() => document.querySelectorAll('[data-testid^="bot-card-"]').length === 1);
    const second = await page.$$eval('[data-testid^="bot-card-"]', els => els.map(e => e.getAttribute('data-testid')));
    assert.equal(second.length, 1); assert(second.every(id => !first.includes(id)));
    const data = await graphql('query($s:String){bots(search:$s,target:GKE_REMOTE,limit:100,offset:0){id target}}', { s: `${pageTag}-page-` });
    assert.equal(data.bots.length, 21); assert(data.bots.every(b => b.target === 'GKE_REMOTE'));
    await shot('desktop-manager.png');
  });
  await check('375px dialog 焦點與 Escape', async () => {
    await page.setViewport({ width: 375, height: 812 }); await page.goto(browserUrl(), { waitUntil: 'networkidle2' });
    await page.click('button[aria-label="開啟導覽"]'); await page.waitForSelector(t('nav-bots'), { visible: true }); await page.waitForFunction(() => { const r = document.querySelector('[data-testid="nav-bots"]')?.getBoundingClientRect(); return r && r.left >= 0 && r.right <= innerWidth; }); await page.click(t('nav-bots'));
    const drawerOpen = await page.$eval('chat-sidenav', e => e.getAttribute('data-open') === 'true');
    if (drawerOpen) {
      const closeSelector = 'chat-sidenav button[aria-label="Close conversations"]';
      await page.waitForFunction(sel => { const e = document.querySelector(sel); if (!e) return false; const r = e.getBoundingClientRect(); const style = getComputedStyle(e); return r.width > 0 && r.height > 0 && r.left >= 0 && r.right <= innerWidth && style.visibility === 'visible'; }, {}, closeSelector);
      await page.click(closeSelector);
      await page.waitForFunction(() => document.querySelector('chat-sidenav')?.getAttribute('data-open') === 'false');
    }
    await page.waitForSelector(t('create-bot-modal-btn'), { visible: true });
    await page.waitForFunction(sel => { const e = document.querySelector(sel); if (!e) return false; const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0 && document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2) === e || (r.width > 0 && r.height > 0 && e.contains(document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2))); }, {}, t('create-bot-modal-btn'));
    await page.click(t('create-bot-modal-btn'));
    await page.waitForSelector(`${t('create-bot-dialog')}[open]`); assert.equal(await page.evaluate(() => document.activeElement?.getAttribute('data-testid')), 'bot-name-input');
    await shot('mobile-dialog.png'); await page.keyboard.press('Escape'); await page.waitForFunction(() => !document.querySelector('[data-testid="create-bot-dialog"][open]'));
    await page.waitForFunction(() => document.activeElement?.getAttribute('data-testid') === 'create-bot-modal-btn'); await shot('mobile-manager.png');
  });
} finally {
  console.log(`Bot fixture tag: ${tag}; artifacts: ${artifactsDir}`);
  await finish();
}
