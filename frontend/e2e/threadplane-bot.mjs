import assert from 'node:assert/strict';
import { join } from 'node:path';
import { startVerification, browserUrl, backendUrl } from './threadplane-support.mjs';

const suite = await startVerification('bot');
const { page, guard, graphql, check, finish, artifactsDir } = suite;
const tag = `B28-${Date.now().toString(36)}`;
const pageTag = process.env.BOT_PAGINATION_TAG || tag;
const t = id => `[data-testid="${id}"]`;
let mutations = 0, failRead = false, reads = 0, injected = 0, botId;
let holdMutation = false, failMutation = false, releaseMutation;
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
  guard.addRequestHandler(async req => {
    if (!req.url().includes('/graphql') || req.method() !== 'POST') return;
    let query = ''; try { query = JSON.parse(req.postData() || '{}').query || ''; } catch { return; }
    if (/mutation\s+(Create|Update)Bot/.test(query)) {
      mutations++;
      if (failMutation) { failMutation = false; return req.respond({status:200,contentType:'application/json',body:JSON.stringify({errors:[{message:'Injected save failure'}]})}); }
      if (holdMutation) { await new Promise(resolve => { releaseMutation = resolve; }); holdMutation = false; return req.continue(); }
    }
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
  for (const [label, width, height] of [['desktop', 1280, 800], ['mobile', 375, 812]]) {
    await check(`${label} dialog 鍵盤焦點循環`, async () => {
      await page.setViewport({width, height});
      await page.click(t('create-bot-modal-btn')); await page.waitForSelector(`${t('create-bot-dialog')}[open]`);
      try {
        await page.waitForFunction(() => document.activeElement?.getAttribute('data-testid') === 'bot-name-input');
        // Native modal dialogs make background controls inert; Chromium may pass
        // through browser chrome (reported as BODY) between in-dialog stops.
        const background = t('create-bot-modal-btn');
        await page.$eval(background, e => { e.dataset.inertClicks = '0'; e.addEventListener('click', () => e.dataset.inertClicks = String(Number(e.dataset.inertClicks) + 1), {once:true}); e.focus(); });
        assert.notEqual(await page.evaluate(() => document.activeElement?.getAttribute('data-testid')), 'create-bot-modal-btn');
        const box = await (await page.$(background)).boundingBox(); assert(box);
        await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
        assert.equal(await page.$eval(background, e => e.dataset.inertClicks), '0');
        assert.equal(await page.$eval(t('create-bot-dialog'), e => e.open), true);
        await page.focus(t('bot-name-input'));
        const pressDirection = async direction => { if (direction === 'Shift+Tab') await page.keyboard.down('Shift'); try { await page.keyboard.press('Tab'); } finally { if (direction === 'Shift+Tab') await page.keyboard.up('Shift'); } };
        for (const direction of ['Tab', 'Shift+Tab']) {
          let bodyPending = false;
          for (let i = 0; i < 12; i++) {
            await pressDirection(direction);
            const state = await page.evaluate(() => ({inside: document.querySelector('dialog[open]')?.contains(document.activeElement), tag: document.activeElement?.tagName}));
            assert(state.inside || state.tag === 'BODY', `${direction} reached background ${state.tag}`);
            if (bodyPending) assert(state.inside, `${direction} did not return to dialog after BODY`);
            bodyPending = state.tag === 'BODY';
          }
          if (bodyPending) { await pressDirection(direction); assert(await page.evaluate(() => document.querySelector('dialog[open]')?.contains(document.activeElement)), `${direction} did not return to dialog`); }
        }

      } finally {
        await page.keyboard.press('Escape');
        await page.waitForFunction(() => !document.querySelector('[data-testid="create-bot-dialog"][open]'));
      }
    });
    await check(`${label} 表單邊界、鍵盤、取消與延遲保存`, async () => {
      await page.setViewport({width, height});
      if (width === 375) {
        // The previous mobile check has already opened the manager.
        await page.waitForSelector(t('create-bot-modal-btn'), {visible:true});
      }
      const create = t('create-bot-modal-btn');
      await page.click(create); await page.waitForSelector(`${t('create-bot-dialog')}[open]`);
      await page.waitForFunction(() => document.activeElement?.getAttribute('data-testid') === 'bot-name-input');
      const name=t('bot-name-input'), desc=t('bot-desc-input'), instructions=t('bot-instructions-input'), submit=t('submit-bot-btn');
      await set(name, '   '); await page.waitForFunction(sel => document.querySelector(sel).disabled, {}, submit);
      await set(name, 'a'.repeat(101)); await page.waitForFunction(sel => document.querySelector(sel).disabled, {}, submit);
      await set(name, `  ${'a'.repeat(100)}  `); await page.waitForFunction(sel => !document.querySelector(sel).disabled, {}, submit);
      await set(desc, 'd'.repeat(2000)); await set(instructions, 'i'.repeat(10000)); await page.waitForFunction(sel => !document.querySelector(sel).disabled, {}, submit);
      await set(desc, 'd'.repeat(2001)); await page.waitForFunction(sel => document.querySelector(sel).disabled, {}, submit);
      await set(desc, 'd'); await set(instructions, 'i'.repeat(10001)); await page.waitForFunction(sel => document.querySelector(sel).disabled, {}, submit);
      await set(instructions, 'i'); await page.waitForFunction(sel => !document.querySelector(sel).disabled, {}, submit);
      const beforeCancel = mutations; await page.click(t('cancel-dialog-btn'));
      await page.waitForFunction(() => !document.querySelector('[data-testid="create-bot-dialog"][open]'));
      await page.waitForFunction(() => document.activeElement?.getAttribute('data-testid') === 'create-bot-modal-btn'); assert.equal(mutations,beforeCancel);
      await page.click(create); await page.waitForSelector(`${t('create-bot-dialog')}[open]`);
      await set(name, `${tag}-${label}-held`); holdMutation=true; const before=mutations;
      await page.click(submit); await page.waitForFunction(sel => document.querySelector(sel).disabled, {}, submit);
      assert.equal(await page.$eval(t('cancel-dialog-btn'), e=>e.disabled),true);
      assert.equal(await page.$eval(t('dialog-close-x-btn'), e=>e.disabled),true);
      await page.keyboard.press('Escape'); assert.equal(await page.$eval(t('create-bot-dialog'), e=>e.open),true);
      await page.$eval('dialog form', e=>e.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})));
      assert.equal(mutations,before+1); assert(releaseMutation); releaseMutation(); releaseMutation=undefined;
      await page.waitForFunction(() => !document.querySelector('[data-testid="create-bot-dialog"][open]'));
      await page.waitForFunction(() => document.activeElement?.getAttribute('data-testid') === 'create-bot-modal-btn'); assert.equal(mutations,before+1);
      await page.click(create); await page.waitForSelector(`${t('create-bot-dialog')}[open]`);
      await set(name, `${tag}-${label}-failure`); await set(desc, ' preserve raw '); failMutation=true;
      const beforeFailure=mutations; await page.click(submit); await page.waitForSelector(t('form-error-banner'),{visible:true});
      assert.match(await page.$eval(t('form-error-banner'),e=>e.textContent),/Injected save failure/);
      assert.equal(await page.$eval(desc,e=>e.value),' preserve raw '); assert.equal(mutations,beforeFailure+1);
      await shot(`${label}-save-failure.png`); await page.keyboard.press('Escape');
      await page.waitForFunction(() => !document.querySelector('[data-testid="create-bot-dialog"][open]'));
      await page.waitForFunction(() => document.activeElement?.getAttribute('data-testid') === 'create-bot-modal-btn');
    });
    if (releaseMutation) { releaseMutation(); releaseMutation=undefined; holdMutation=false; }
  }
} finally {
  if (releaseMutation) releaseMutation();
  console.log(`Bot fixture tag: ${tag}; artifacts: ${artifactsDir}`);
  await finish();
}
