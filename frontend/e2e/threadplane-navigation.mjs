import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { backendUrl, browserUrl, startVerification } from './threadplane-support.mjs';

// Synthetic fixtures are intentionally retained; no database cleanup is performed.
const session = await startVerification('final-navigation');
const { page, guard, graphql, check, finish, artifactsDir } = session;
// Browser has one local identity: cross-owner isolation is verified by the backend real-PostgreSQL multi-owner tests, not this suite.
const tag = process.env.NAV_FIXTURE_TAG || `final-nav-${Date.now()}`;
let injection = null;
let delaySearch = false;
let delayedStarted = false;
const requests = [];
guard.addRequestHandler(async request => {
  if (!request.url().endsWith('/graphql') || request.method() !== 'POST') return;
  let body;
  try { body = JSON.parse(request.postData()); } catch { return; }
  if (!body.query) return;
  if (/SearchConversations|GetConversations|SetConversationPinned/.test(body.query)) {
    requests.push({ query: body.query, variables: body.variables });
  }
  if (delaySearch && body.query.includes('SearchConversations')) {
    delaySearch = false; delayedStarted = true;
    await new Promise(resolve => setTimeout(resolve, 1200));
    if (!request.isInterceptResolutionHandled()) await request.continue();
    return;
  }
  if (injection?.(body)) {
    injection = null;
    await request.respond({ status: 503, contentType: 'application/json', body: '{"error":"synthetic injected failure"}' });
  }
});
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const selectText = async (selector, text) => {
  const handle = await page.evaluateHandle((s, t) => [...document.querySelectorAll(s)].find(e => e.textContent.includes(t)), selector, text);
  const element = handle.asElement();
  assert.ok(element, `Missing ${selector}: ${text}`);
  await element.click();
  await handle.dispose();
};
const openSearch = async () => { await page.click('button[aria-label="Search conversations"]'); await page.waitForSelector('[role="combobox"]'); };
const setSearch = async text => page.$eval('[role="combobox"]', (el, value) => { el.value = value; el.dispatchEvent(new Event('input', { bubbles: true })); }, text);
const closeSearch = async () => { await page.keyboard.press('Escape'); await page.waitForFunction(() => !document.querySelector('[role="combobox"]')); };
const querySeen = (text) => requests.some(r => r.query.includes('SearchConversations') && r.variables.search === text);
let bot;
try {
  bot = (await graphql('query { defaultLocalBot { id } }')).defaultLocalBot;
  assert.ok(bot?.id, 'defaultLocalBot required');
  const create = 'mutation($bot: ID!, $title: String) { createConversation(botId: $bot, title: $title) { id title } }';
  // Enough records in each section for a real next page and a 20-result search cap.
  for (let i = 0; !process.env.NAV_FIXTURE_TAG && i < 44; i++) {
    const title = `${tag} ${String(i).padStart(2, '0')}${i === 0 ? ' literal%_\\繁體' : ''}`;
    const created = (await graphql(create, { bot: bot.id, title })).createConversation;
    if (i < 22) await graphql('mutation($id: ID!, $pinned: Boolean!) { setConversationPinned(id: $id, pinned: $pinned) { id } }', { id: created.id, pinned: true });
  }
  if (!process.env.NAV_FIXTURE_TAG) {
    const syntheticBot = (await graphql('mutation($input: CreateBotInput!) { createBot(input: $input) { id } }', { input: { name: `${tag}-bot`, instructions: 'Synthetic navigation verification' } })).createBot;
    await graphql(create, { bot: syntheticBot.id, title: `${tag} bot-conversation` });
  }
  console.log(`Browser: ${browserUrl()}; backend: ${backendUrl()}; synthetic fixtures: ${tag}; artifacts: ${artifactsDir}`);
  await page.setViewport({ width: 1280, height: 800 });
  await page.goto(browserUrl(), { waitUntil: 'networkidle2' });
  await check('desktop native navigation and 20-result search limit', async () => {
    for (const s of ['chat-window', 'chat-sidenav', 'chat-thread-list']) assert.ok(await page.$(s), s);
    await openSearch(); await setSearch(tag);
    await page.waitForFunction(() => document.querySelectorAll('[role="option"]').length === 20, { timeout: 10000 });
    const search = requests.findLast(r => r.query.includes('SearchConversations') && r.variables.search === tag);
    assert.deepEqual(search.variables, { search: tag, limit: 20, offset: 0 });
    assert.equal((await graphql('query($s: String) { conversations(search: $s, limit: 20, offset: 0) { id } }', { s: tag })).conversations.length, 20);
    await closeSearch();
  });
  await check('real search includes Bot and pinned and recent conversations', async () => {
    for (const suffix of ['bot-conversation', '00 literal', '43']) {
      await openSearch(); await setSearch(`${tag} ${suffix}`);
      await page.waitForFunction(value => [...document.querySelectorAll('[role="option"]')].some(e => e.textContent.includes(value)), { timeout: 10000 }, `${tag} ${suffix}`);
      await closeSearch();
    }
  });
  await check('real literal unicode title search', async () => {
    await openSearch(); await setSearch('%_\\繁體');
    await page.waitForFunction(t => [...document.querySelectorAll('[role="option"]')].some(e => e.textContent.includes(t)), { timeout: 10000 }, tag);
    assert.ok(querySeen('%_\\繁體'));
    await closeSearch();
  });
  await check('injected search failure and same query retry', async () => {
    await openSearch(); injection = b => b.query.includes('SearchConversations');
    await setSearch(tag); await page.waitForSelector('.search-alert [type="button"]', { timeout: 10000 });
    assert.equal(await page.$('[role="combobox"]'), null);
    const before = requests.filter(r => r.query.includes('SearchConversations') && r.variables.search === tag).length;
    await page.click('.search-alert button'); await page.waitForSelector('[role="combobox"]', { timeout: 10000 });
    assert.ok(requests.filter(r => r.query.includes('SearchConversations') && r.variables.search === tag).length > before);
    await closeSearch();
  });
  await check('injected late search cannot reopen or steal focus after close', async () => {
    await openSearch(); delayedStarted = false; delaySearch = true; await setSearch(tag);
    for (let i = 0; i < 30 && !delayedStarted; i++) await wait(100);
    assert.equal(delayedStarted, true, 'injected delay must start');
    await closeSearch(); const focus = await page.evaluate(() => document.activeElement?.getAttribute('aria-label'));
    await wait(1500); assert.equal(await page.$('[role="combobox"]'), null);
    assert.equal(await page.evaluate(() => document.activeElement?.getAttribute('aria-label')), focus);
  });
  await check('101 validation retains query and corrected retry', async () => {
    await openSearch(); await setSearch('x'.repeat(101));
    await page.waitForSelector('.search-alert input');
    assert.equal(await page.$eval('.search-alert input', e => e.value.length), 101);
    assert.equal(await page.$('[role="combobox"]'), null);
    await page.$eval('.search-alert input', (e, value) => { e.value = value; e.dispatchEvent(new Event('input', { bubbles: true })); }, tag);
    await page.click('.search-alert button'); await page.waitForSelector('[role="combobox"]', { timeout: 10000 });
    assert.equal(await page.$eval('[role="combobox"]', e => e.value), tag); await closeSearch();
  });
  await check('injected pending retry cannot reopen with edited query', async () => {
    await openSearch(); await setSearch('x'.repeat(101)); await page.waitForSelector('.search-alert input');
    await page.$eval('.search-alert input', (e, value) => { e.value=value; e.dispatchEvent(new Event('input', { bubbles:true })); }, tag);
    delayedStarted = false; delaySearch = true; await page.click('.search-alert button');
    for (let i = 0; i < 30 && !delayedStarted; i++) await wait(100);
    assert.equal(delayedStarted, true);
    await page.$eval('.search-alert input', e => { e.value='changed-query'; e.dispatchEvent(new Event('input', { bubbles:true })); });
    await wait(1500); assert.equal(await page.$('[role="combobox"]'), null);
    assert.equal(await page.$eval('.search-alert input', e => e.value), 'changed-query');
    await page.click('.search-alert button'); await page.waitForSelector('[role="combobox"]', { timeout: 10000 }); await closeSearch();
  });
  await check('100 characters sent and empty query not sent', async () => {
    await openSearch(); const count = requests.length; await setSearch('   '); await wait(500); assert.equal(requests.length, count);
    const text = 'x'.repeat(100); await setSearch(text); await page.waitForFunction(() => document.body.innerText.includes('No conversations match'), { timeout: 10000 });
    assert.ok(querySeen(text)); await closeSearch();
  });
  await check('pinned pagination injected failure preserves offset and items', async () => {
    const section = 'section[aria-label="釘選對話"]';
    const old = await page.$$eval(`${section} chat-thread-list button[title]`, es => es.map(e => e.title));
    injection = b => b.query.includes('GetConversations') && b.variables.pinned === true && b.variables.offset > 0;
    await selectText(`${section} button`, '載入更多釘選對話'); await page.waitForSelector(`${section} [data-testid="page-retry"]`);
    const failed = requests.findLast(r => r.query.includes('GetConversations') && r.variables.pinned === true && r.variables.offset > 0);
    assert.deepEqual(await page.$$eval(`${section} chat-thread-list button[title]`, es => es.map(e => e.title)), old);
    await page.click(`${section} [data-testid="page-retry"]`); await page.waitForFunction((s, n) => document.querySelectorAll(s).length > n, { timeout: 10000 }, `${section} chat-thread-list button[title]`, old.length);
    const retried = requests.findLast(r => r.query.includes('GetConversations') && r.variables.pinned === true && r.variables.offset > 0);
    assert.equal(retried.variables.offset, failed.variables.offset);
  });
  await check('recent pagination injected failure preserves offset and items', async () => {
    const section = 'section[aria-label="近期對話"]';
    const old = await page.$$eval(`${section} chat-thread-list button[title]`, es => es.map(e => e.title));
    injection = b => b.query.includes('GetConversations') && b.variables.pinned === false && b.variables.offset > 0;
    await selectText(`${section} button`, '載入更多對話'); await page.waitForSelector(`${section} [data-testid="page-retry"]`);
    const failed = requests.findLast(r => r.query.includes('GetConversations') && r.variables.pinned === false && r.variables.offset > 0);
    assert.deepEqual(await page.$$eval(`${section} chat-thread-list button[title]`, es => es.map(e => e.title)), old);
    await page.click(`${section} [data-testid="page-retry"]`); await page.waitForFunction((s, n) => document.querySelectorAll(s).length > n, { timeout: 10000 }, `${section} chat-thread-list button[title]`, old.length);
    const retried = requests.findLast(r => r.query.includes('GetConversations') && r.variables.pinned === false && r.variables.offset > 0);
    assert.equal(retried.variables.offset, failed.variables.offset);
  });
  await check('collapsed sidenav expands to Bot mode', async () => {
    await page.click('button[aria-label="Collapse sidenav"]');
    await page.waitForSelector('chat-sidenav[data-mode="collapsed"]');
    await page.click('button[aria-label="Expand sidenav"]');
    await page.waitForSelector('chat-sidenav[data-mode="expanded"]');
    await page.click('[data-testid="nav-bots"]');
    await page.waitForFunction(() => document.querySelector('[data-testid="nav-bots"]')?.getAttribute('aria-pressed') === 'true');
    await page.click('[data-testid="nav-conversations"]');
    await page.waitForSelector('section[aria-label="近期對話"] chat-thread-list li');
  });
  await check('pin mutation uses authoritative GraphQL and moves thread', async () => {
    const section = 'section[aria-label="近期對話"]';
    const item = await page.$(`${section} chat-thread-list li`);
    assert.ok(item);
    const title = await item.$eval('button[title]', e => e.title);
    await item.evaluate(e => e.scrollIntoView({ block: 'center' }));
    await item.hover();
    await item.$eval('button[aria-label="More actions"]', e => e.click());
    await page.waitForSelector('[role="menuitem"]', { visible: true });
    await selectText('[role="menuitem"]', 'Pin');
    await page.waitForFunction((t) => [...document.querySelectorAll('section[aria-label="釘選對話"] button[title]')].some(e => e.title === t), { timeout: 10000 }, title);
    assert.ok(requests.some(r => r.query.includes('SetConversationPinned') && r.variables.pinned === true));
  });
  await page.setViewport({ width: 375, height: 500 }); await page.reload({ waitUntil: 'networkidle2' });
  await check('mobile drawer inert, scrolling and keyboard palette', async () => {
    assert.equal(await page.$eval('.drawer-focus', e => e.hasAttribute('inert')), true);
    await page.click('button[aria-label="開啟導覽"]'); await page.waitForSelector('[role="dialog"][aria-label="側欄導覽"]');
    const info = await page.$eval('[sidenavSections]', e => { e.scrollTop = e.scrollHeight; const b = [...e.querySelectorAll('button')].find(x => x.textContent.includes('載入更多對話')); const r=e.getBoundingClientRect(), br=b?.getBoundingClientRect(); return { top:e.scrollTop, scroll:e.scrollHeight, height:e.clientHeight, visible:!!br && br.top>=r.top && br.bottom<=r.bottom }; });
    assert.ok(info.scroll > info.height && info.top > 0 && info.visible, JSON.stringify(info));
    await openSearch(); await page.focus('[role="combobox"]'); await page.keyboard.down('Shift'); await page.keyboard.press('Tab'); await page.keyboard.up('Shift');
    assert.equal(await page.evaluate(() => document.activeElement?.getAttribute('aria-label')), 'Close');
    await page.keyboard.press('Tab'); assert.equal(await page.evaluate(() => document.activeElement?.getAttribute('role')), 'combobox');
    await page.keyboard.down('Shift'); await page.keyboard.press('Tab'); await page.keyboard.up('Shift'); await closeSearch();
    await page.waitForFunction(() => document.activeElement?.getAttribute('aria-label') === 'Search conversations');
    await page.keyboard.press('Escape'); await page.waitForFunction(() => document.querySelector('.drawer-focus')?.hasAttribute('inert'));
    assert.equal(await page.evaluate(() => document.activeElement?.getAttribute('aria-label')), '開啟導覽');
  });
  await check('mobile backdrop closes drawer and Bot reachable', async () => {
    await page.click('button[aria-label="開啟導覽"]'); await page.waitForSelector('[role="dialog"][aria-label="側欄導覽"]'); await wait(400); await page.click('[data-testid="nav-bots"]');
    await page.waitForFunction(() => document.querySelector('[data-testid="nav-bots"]')?.getAttribute('aria-pressed') === 'true');
    await page.mouse.click(365, 250); await page.waitForFunction(() => document.querySelector('.drawer-focus')?.hasAttribute('inert'));
  });
  await check('375px standard height drawer', async () => {
    await page.setViewport({ width: 375, height: 812 }); await page.reload({ waitUntil: 'networkidle2' });
    await page.click('button[aria-label="開啟導覽"]'); await page.waitForSelector('[role="dialog"][aria-label="側欄導覽"]');
    await page.waitForFunction(() => document.querySelector('.drawer-focus')?.contains(document.activeElement));
    await page.keyboard.press('Escape'); await page.waitForFunction(() => document.querySelector('.drawer-focus')?.hasAttribute('inert'));
    assert.equal(await page.evaluate(() => document.activeElement?.getAttribute('aria-label')), '開啟導覽');
    await page.click('button[aria-label="開啟導覽"]'); await page.waitForSelector('[role="dialog"][aria-label="側欄導覽"]');
    await page.focus('[data-testid="nav-conversations"]');
    await page.$eval('button[aria-label="開啟導覽"]', e => { window.__navFocusCount = 0; const focus = e.focus.bind(e); e.focus = (...args) => { window.__navFocusCount++; focus(...args); }; });
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => document.querySelector('.drawer-focus')?.hasAttribute('inert'));
    await wait(100); assert.equal(await page.evaluate(() => document.activeElement?.getAttribute('aria-label')), '開啟導覽');
    assert.equal(await page.evaluate(() => window.__navFocusCount), 1, 'child Escape must restore focus exactly once');
  });
} finally {
  try {
    await writeFile(join(artifactsDir, 'requested-urls.json'), JSON.stringify({ browserUrl: browserUrl(), backendUrl: backendUrl(), fixtureTag: tag, ownerIsolation: 'Browser uses one local identity; cross-owner isolation is covered by backend real-PostgreSQL tests.' }, null, 2) + '\n');
  } finally {
    await finish();
  }
}
