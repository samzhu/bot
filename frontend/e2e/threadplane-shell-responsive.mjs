// Issue #43 responsive shell browser matrix (Chrome). Guards: (1) startVerification asserts BOTH BACKEND_URL and BROWSER_URL
// report SAFE_MOCK via /api/internal/verification/status, (2) the browser request guard, (3) GraphQL reads are stubbed in-page
// and every mutation is refused and fails the suite. No backend/DB state is read or written. Exit code is non-zero on any failure.
//   BROWSER_URL=http://127.0.0.1:4400 BACKEND_URL=http://127.0.0.1:8282 node e2e/threadplane-shell-responsive.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { browserUrl, startVerification } from './threadplane-support.mjs';

const { page, guard, check, finish, artifactsDir } = await startVerification('issue43-shell-responsive');
const wait = ms => new Promise(r => setTimeout(r, ms));
const shot = n => page.screenshot({ path: join(artifactsDir, `${n}.png`) });
const iso = '2026-11-12T00:00:00Z';
const LONG = 'L'.repeat(180);
const workspaces = [
  { id: 'w1', name: 'Personal Workspace', role: 'OWNER', personal: true },
  { id: 'w2', name: 'Team 團隊工作區 with an intentionally very long display name', role: 'MEMBER', personal: false },
];
const conv = (id, title, pinned) => ({ id, workspaceId: 'w1', botId: 'b0', title, pinned, createdAt: iso, updatedAt: iso });
const bot = (id, name) => ({ id, name, description: 'synthetic', instructions: null, target: 'GKE_REMOTE', connectionStatus: 'NOT_CONNECTED', executionMode: 'UNAVAILABLE', statusReason: null, canChat: false, createdAt: iso, updatedAt: iso });
const localBot = { ...bot('b0', 'Local'), target: 'LOCAL', connectionStatus: 'CONNECTED', executionMode: 'SIMULATED', canChat: true };
const manyConversations = pinned => Array.from({ length: 20 }, (_, i) => conv(`${pinned ? 'p' : 'r'}${i}`, i === 0 ? `${LONG} 超長標題` : `${pinned ? '釘選' : '近期'}對話 ${i}`, pinned));
const messages = [
  { id: 'm1', runId: 'r1', role: 'USER', content: `長內容 ${LONG}${LONG}`, interrupted: false, createdAt: iso },
  { id: 'm2', runId: 'r1', role: 'ASSISTANT', content: `${Array.from({ length: 12 }, (_, i) => `段落 ${i} ${'內容 '.repeat(30)}`).join('\n\n')}\n\n\`\`\`\n${'const veryLongCodeLine = '.repeat(12)}\n\`\`\`\n\n${'https://example.com/' + 'a'.repeat(160)}`, interrupted: false, createdAt: iso },
];

const fail = { workspaces: 0, conversations: 0, bots: 0 };
const mutations = [];
const delay = { workspaces: 0, bots: 0 };
let empty = false;
guard.addRequestHandler(async request => {
  if (!request.url().endsWith('/graphql') || request.method() !== 'POST') return;
  let body; try { body = JSON.parse(request.postData()); } catch { return; }
  const q = body.query ?? '';
  const ok = data => request.respond({ status: 200, contentType: 'application/json', body: JSON.stringify({ data }) });
  if (/^\s*mutation/.test(q)) { mutations.push(q); await request.respond({ status: 403, contentType: 'application/json', body: '{"errors":[{"message":"blocked by #43 suite"}]}' }); return; }
  if (q.includes('SessionHistory')) return ok({ conversation: { ...conv(body.variables.id, 'Long history', true), messages, runs: [{ id: 'r1', status: 'COMPLETED', errorMessage: null }] } });
  const key = q.includes('Workspaces') ? 'workspaces' : q.includes('GetConversations') ? 'conversations' : q.includes('GetBots') ? 'bots' : null;
  if (!key) return;
  if (delay[key]) { const ms = delay[key]; delay[key] = 0; await wait(ms); }
  if (fail[key] > 0) { fail[key]--; await request.respond({ status: 503, contentType: 'application/json', body: '{"error":"synthetic"}' }); return; }
  if (key === 'workspaces') return ok({ workspaces });
  if (key === 'conversations') return ok({ conversations: body.variables.offset || empty ? [] : manyConversations(!!body.variables.pinned) });
  return ok({ defaultLocalBot: localBot, bots: body.variables.offset || empty ? [] : [bot('b1', `GKE Research Agent ${LONG}`), bot('b2', 'Second bot')] });
});

const load = async () => { await page.goto(browserUrl(), { waitUntil: 'networkidle2' }); await page.waitForSelector('chat-sidenav'); await wait(500); };
const overflow = () => page.evaluate(() => ({ doc: document.documentElement.scrollWidth - innerWidth, body: document.body.scrollWidth - innerWidth }));
const ring = sel => page.$eval(sel, e => { const s = getComputedStyle(e); return { style: s.outlineStyle, width: parseFloat(s.outlineWidth) }; });
const activeInDrawer = () => page.evaluate(() => !!document.activeElement?.closest('.drawer-focus'));
const isOpen = () => page.evaluate(() => document.querySelector('.drawer-focus')?.getAttribute('role') === 'dialog');
const openDrawer = async () => { await page.click('.mobile-navigation'); await page.waitForFunction(() => document.querySelector('.drawer-focus')?.getAttribute('role') === 'dialog'); await wait(400); };

const VIEWPORTS = [
  { name: 'mobile-375', width: 375, height: 667, mode: 'drawer' },
  { name: 'mobile-320', width: 320, height: 568, mode: 'drawer' },
  { name: 'edge-767', width: 767, height: 900, mode: 'drawer' },
  { name: 'edge-768', width: 768, height: 900, mode: 'expanded' },
  { name: 'desktop-1440', width: 1440, height: 900, mode: 'expanded' },
];

try {
  page.setDefaultTimeout(8000);
  await page.emulateMediaFeatures([{ name: 'prefers-reduced-motion', value: 'reduce' }]);
  await check('app css has no private SDK selectors', async () => {
    for (const f of ['../src/app/app.css', '../src/app/chat-navigation.component.css']) {
      const t = (await readFile(new URL(f, import.meta.url), 'utf8')).replace(/--tplane-chat-[a-z0-9-]+/g, '').replace(/\/\*[\s\S]*?\*\//g, '');
      assert.ok(!/::ng-deep|(^|[\s,{}>+~])chat-[a-z-]+|\.tplane-/m.test(t), f);
    }
  });

  for (const vp of VIEWPORTS) {
    await page.setViewport({ width: vp.width, height: vp.height });
    await load();
    await check(`${vp.name}: ${vp.mode} mode, no horizontal overflow, chat fits viewport`, async () => {
      const g = await page.evaluate(() => {
        const j = x => x && ({ left: x.left, right: x.right, top: x.top, bottom: x.bottom, width: x.width, height: x.height });
        const r = s => j(document.querySelector(s)?.getBoundingClientRect());
        const input = j(document.querySelector('chat-input, chat-window textarea, chat-window [contenteditable], chat-window input')?.getBoundingClientRect());
        return { main: r('main.main-area'), input, drawerAttr: document.querySelector('.drawer-focus')?.hasAttribute('inert'), mobileBtn: getComputedStyle(document.querySelector('.mobile-navigation')).display, side: r('chat-sidenav') };
      });
      const o = await overflow();
      assert.ok(o.doc <= 0 && o.body <= 0, JSON.stringify(o));
      assert.ok(g.main.right <= vp.width + 1 && g.main.width > 0, JSON.stringify(g.main));
      if (g.input) assert.ok(g.input.right <= vp.width + 1 && g.input.bottom <= vp.height + 1, JSON.stringify(g.input));
      if (vp.mode === 'drawer') { assert.equal(g.mobileBtn, 'block'); assert.equal(g.drawerAttr, true, 'closed drawer must be inert'); assert.ok(g.main.left <= 1, `main left ${g.main.left}`); }
      else { assert.equal(g.mobileBtn, 'none'); assert.ok(Math.abs(g.side.width - 280) <= 1 && g.main.left >= 279 && g.main.left <= 281, JSON.stringify({ s: g.side.width, m: g.main.left })); }
    });
    await shot(`${vp.name}-01-chat-long-content`);
  }

  await page.setViewport({ width: 375, height: 667 });
  await load();
  await openDrawer();
  const firstThread = await page.evaluateHandle(() => [...document.querySelectorAll('chat-thread-list button')].find(b => b.textContent.includes('近期對話 1')));
  assert.ok(firstThread.asElement(), 'synthetic conversation available'); await firstThread.asElement().click();
  await page.waitForFunction(() => document.querySelector('chat-window')?.textContent.includes('長內容'));
  await shot('375-chat-loaded-long-content');
  await check('375: keyboard reaches chat composer and edits a synthetic draft', async () => {
    await page.focus('.mobile-navigation');
    for (let i = 0; i < 30 && !(await page.evaluate(() => document.activeElement?.matches('chat-input textarea'))); i++) await page.keyboard.press('Tab');
    assert.ok(await page.evaluate(() => document.activeElement?.matches('chat-input textarea')), 'Tab reaches composer');
    await page.keyboard.type('synthetic draft');
    assert.equal(await page.$eval('chat-input textarea', e => e.value), 'synthetic draft');
    await shot('375-chat-composer-keyboard');
    await page.keyboard.down('Meta'); await page.keyboard.press('A'); await page.keyboard.up('Meta'); await page.keyboard.press('Backspace');
  });
  await check('375: long chat content scrolls inside chat, not the page', async () => {
    const r = await page.evaluate(() => {
      const scrollers = [...document.querySelectorAll('chat-window *')].filter(e => e.scrollHeight > e.clientHeight + 20 && /auto|scroll/.test(getComputedStyle(e).overflowY));
      return { n: scrollers.length, page: document.documentElement.scrollHeight - innerHeight };
    });
    assert.ok(r.n >= 1 && r.page <= 1, JSON.stringify(r));
  });
  await check('375: drawer opens with dialog semantics, fits viewport, focus enters drawer', async () => {
    await page.focus('.mobile-navigation'); await openDrawer();
    const g = await page.evaluate(() => ({ r: (x => ({ left: x.left, right: x.right, width: x.width }))(document.querySelector('chat-sidenav').getBoundingClientRect()), modal: document.querySelector('.drawer-focus').getAttribute('aria-modal'), label: document.querySelector('.drawer-focus').getAttribute('aria-label') }));
    assert.ok(g.r.left >= -1 && g.r.right <= 376, JSON.stringify(g.r));
    assert.equal(g.modal, 'true'); assert.ok(g.label);
    assert.equal(await page.$eval('main.main-area', e => e.inert), true);
    assert.equal(await page.$eval('.mobile-navigation', e => e.getAttribute('aria-expanded')), 'true');
    assert.ok(await activeInDrawer(), 'focus must move into drawer');
    await shot('375-02-drawer-open');
    assert.deepEqual(await overflow(), { doc: 0, body: 0 });
  });
  await check('375: Tab/Shift+Tab stay trapped in drawer; controls show 2px focus ring', async () => {
    for (let i = 0; i < 30; i++) { await page.keyboard.press('Tab'); assert.ok(await activeInDrawer(), `Tab ${i} escaped`); }
    for (let i = 0; i < 8; i++) { await page.keyboard.down('Shift'); await page.keyboard.press('Tab'); await page.keyboard.up('Shift'); assert.ok(await activeInDrawer(), `Shift+Tab ${i} escaped`); }
    await page.focus('#workspace-select'); const r = await ring('#workspace-select'); assert.ok(r.style !== 'none' && r.width >= 2, JSON.stringify(r));
    await shot('375-03-drawer-focus-select');
  });
  await check('375: drawer content scrolls internally with long lists; no horizontal overflow', async () => {
    const r = await page.evaluate(() => { const n = document.querySelector('.navigation'); return { scroll: n.scrollHeight > n.clientHeight, ox: getComputedStyle(n).overflowY, w: n.scrollWidth - n.clientWidth }; });
    assert.ok(r.scroll && /auto|scroll/.test(r.ox) && r.w <= 1, JSON.stringify(r));
    await page.$eval('.navigation', e => { e.scrollTop = e.scrollHeight; }); await shot('375-04-drawer-scrolled');
  });
  await check('375: Escape closes drawer and returns focus to the trigger', async () => {
    await page.keyboard.press('Escape'); await page.waitForFunction(() => document.querySelector('.drawer-focus')?.getAttribute('role') !== 'dialog'); await wait(200);
    assert.ok(await page.evaluate(() => document.activeElement?.classList.contains('mobile-navigation')), 'focus returns to trigger');
    assert.equal(await page.$eval('main.main-area', e => e.inert), false);
  });
  await check('375: scrim click closes drawer; selecting a conversation closes it', async () => {
    await openDrawer(); await page.mouse.click(372, 330); await page.waitForFunction(() => document.querySelector('.drawer-focus')?.getAttribute('role') !== 'dialog');
    await openDrawer();
    const t = await page.evaluateHandle(() => [...document.querySelectorAll('chat-thread-list button')].find(b => b.textContent.includes('近期對話 3')));
    assert.ok(t.asElement(), 'thread button'); await t.asElement().click();
    await page.waitForFunction(() => document.querySelector('.drawer-focus')?.getAttribute('role') !== 'dialog');
  });
  await check('375: Bot mode with long bot name stays within drawer', async () => {
    await openDrawer(); await page.click('[data-testid="nav-bots"]'); await page.waitForSelector('[data-testid="bot-item-b1"]');
    const r = await page.evaluate(() => { const b = document.querySelector('[data-testid="bot-item-b1"]').getBoundingClientRect(); return { right: b.right, ow: document.querySelector('.navigation').scrollWidth - document.querySelector('.navigation').clientWidth }; });
    assert.ok(r.right <= 376 && r.ow <= 1, JSON.stringify(r)); await shot('375-05-bots-long-name');
    await page.keyboard.press('Escape');
  });

  await check('375: workspace error alert + retry in drawer (keyboard operable)', async () => {
    fail.workspaces = 1; await load(); await page.focus('.mobile-navigation'); await openDrawer();
    await page.waitForSelector('p[role="alert"]');
    const r = await page.$eval('p[role="alert"]', e => { const b = e.getBoundingClientRect(); return { right: b.right, left: b.left, bg: getComputedStyle(e).backgroundColor }; });
    assert.ok(r.left >= 0 && r.right <= 376 && r.bg !== 'rgba(0, 0, 0, 0)', JSON.stringify(r));
    await shot('375-06-workspace-error');
    await page.focus('p[role="alert"] button'); assert.ok((await ring('p[role="alert"] button')).width >= 2);
    await page.keyboard.press('Enter'); await page.waitForFunction(() => !document.querySelector('p[role="alert"]') && !document.querySelector('#workspace-select').disabled);
    await shot('375-07-workspace-recovered'); await page.keyboard.press('Escape');
  });
  await check('375: conversation error + retry', async () => {
    await load(); await openDrawer(); fail.conversations = 2;
    const rl = await page.evaluateHandle(() => [...document.querySelectorAll('button')].find(b => b.textContent.includes('重新載入對話'))); await rl.asElement().click();
    await page.waitForSelector('p[role="alert"]'); await shot('375-08-conversation-error');
    const o = await overflow(); assert.ok(o.doc <= 0);
    const rb = await page.evaluateHandle(() => [...document.querySelectorAll('p[role="alert"] button')].find(b => b.textContent.includes('重試')));
    await rb.asElement().click(); await page.waitForFunction(() => !document.querySelector('p[role="alert"]'));
  });
  await check('375: disabled bot banner is visible and fits', async () => {
    fail.bots = 0; await load(); await openDrawer();
    await page.click('[data-testid="nav-bots"]'); await page.waitForSelector('[data-testid="bot-item-b2"]');
    await page.click('[data-testid="bot-item-b2"]');
    await page.waitForSelector('[data-testid="disabled-bot-banner"]', { visible: true });
    const r = await page.$eval('[data-testid="disabled-bot-banner"]', e => e.getBoundingClientRect().right);
    assert.ok(r <= 376, `banner right ${r}`); await shot('375-09-main-banner');
  });

  for (const vp of [{ name: 'mobile', width: 375, height: 667 }, { name: 'desktop', width: 1440, height: 900 }]) {
    await page.setViewport({ width: vp.width, height: vp.height });
    await check(`${vp.name}: loading and empty states are visible and fit`, async () => {
      empty = true; fail.bots = 0; delay.workspaces = 1500;
      const navigation = page.goto(browserUrl(), { waitUntil: 'domcontentloaded' });
      await navigation; if (vp.name === 'mobile') await openDrawer();
      await page.waitForSelector('p[role="status"]', { visible: true });
      await shot(`${vp.name}-loading`);
      await page.waitForFunction(() => !document.querySelector('#workspace-select').disabled);
      await page.waitForFunction(() => [...document.querySelectorAll('p')].some(e => e.textContent.includes('尚無近期對話')));
      const emptyText = await page.evaluate(() => [...document.querySelectorAll('p')].find(e => e.textContent.includes('尚無近期對話'))?.getBoundingClientRect().right);
      assert.ok(emptyText > 0 && emptyText <= vp.width + 1, `empty right ${emptyText}`);
      await shot(`${vp.name}-empty-conversations`);
      await page.click('[data-testid="nav-bots"]');
      await page.waitForFunction(() => [...document.querySelectorAll('p')].some(e => e.textContent.includes('目前沒有 Bot')));
      await shot(`${vp.name}-empty-bots`);
      empty = false;
    });
    empty = false;
  }

  await check('keyboard on desktop: tabs reachable and operable via Enter/Space', async () => {
    await page.setViewport({ width: 1440, height: 900 }); await load();
    await page.focus('[data-testid="nav-bots"]'); await page.keyboard.press('Enter'); await page.waitForSelector('[data-testid="bot-item-b1"]');
    await page.focus('[data-testid="nav-conversations"]'); await page.keyboard.press('Space');
    await page.waitForFunction(() => document.querySelector('[data-testid="nav-conversations"]').getAttribute('aria-pressed') === 'true');
    await shot('desktop-02-long-lists');
  });
  await check('viewport crossing 767→768 resets drawer to expanded sidebar', async () => {
    await page.setViewport({ width: 375, height: 667 }); await wait(400);
    await page.setViewport({ width: 900, height: 700 }); await wait(500);
    const g = await page.evaluate(() => ({ role: document.querySelector('.drawer-focus').getAttribute('role'), w: document.querySelector('chat-sidenav').getBoundingClientRect().width }));
    assert.equal(g.role, null); assert.ok(Math.abs(g.w - 280) <= 1, JSON.stringify(g));
  });
  await check('no mutation requests issued', async () => assert.deepEqual(mutations, []));
} finally {
  try { await finish(); console.log(`PASSED; artifacts: ${artifactsDir}`); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
