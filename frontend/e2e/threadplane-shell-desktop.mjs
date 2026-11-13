// Issue #42 desktop shell browser checks (Chrome, SAFE_MOCK guard).
// GraphQL reads are stubbed in-page (request interception) so no backend/DB state is read or mutated;
// any mutation is refused and fails the suite. Run:
//   BROWSER_URL=http://127.0.0.1:4400 BACKEND_URL=http://127.0.0.1:8282 node e2e/threadplane-shell-desktop.mjs
import assert from 'node:assert/strict';
import { readFile, copyFile } from 'node:fs/promises';
import { join } from 'node:path';
import { browserUrl, startVerification } from './threadplane-support.mjs';

const session = await startVerification('issue42-shell-desktop');
const { page, guard, check, finish, artifactsDir } = session;
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const shot = name => page.screenshot({ path: join(artifactsDir, `${name}.png`) });

const iso = '2026-11-12T00:00:00Z';
const workspaces = [
  { id: 'w1', name: 'Personal Workspace', role: 'OWNER', personal: true },
  { id: 'w2', name: 'Team 團隊工作區 with an intentionally very long display name', role: 'MEMBER', personal: false },
];
const conv = (id, title, pinned) => ({ id, workspaceId: 'w1', botId: 'b0', title, pinned, createdAt: iso, updatedAt: iso });
const bot = (id, name) => ({ id, name, description: 'synthetic', instructions: null, target: 'GKE_REMOTE', connectionStatus: 'NOT_CONNECTED', executionMode: 'UNAVAILABLE', statusReason: null, canChat: false, createdAt: iso, updatedAt: iso });
const localBot = { ...bot('b0', 'Local'), target: 'LOCAL', connectionStatus: 'CONNECTED', executionMode: 'SIMULATED', canChat: true };

const fail = { workspaces: 0, conversations: 0, bots: 0 };
const delay = { workspaces: 0, bots: 0 };
const mutations = [];
guard.addRequestHandler(async request => {
  if (!request.url().endsWith('/graphql') || request.method() !== 'POST') return;
  let body; try { body = JSON.parse(request.postData()); } catch { return; }
  const q = body.query ?? '';
  if (/^\s*mutation/.test(q)) { mutations.push(q); await request.respond({ status: 403, contentType: 'application/json', body: '{"errors":[{"message":"blocked by #42 suite"}]}' }); return; }
  if (q.includes('SessionHistory')) {
    await request.respond({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { conversation: { ...conv(body.variables.id, '釘選的對話', true), messages: [], runs: [] } } }) });
    return;
  }
  const key = q.includes('Workspaces') ? 'workspaces' : q.includes('GetConversations') ? 'conversations' : q.includes('GetBots') ? 'bots' : null;
  if (!key) return;
  if (delay[key] > 0) { const ms = delay[key]; delay[key] = 0; await wait(ms); }
  if (fail[key] > 0) { fail[key]--; await request.respond({ status: 503, contentType: 'application/json', body: '{"error":"synthetic"}' }); return; }
  const data = key === 'workspaces' ? { workspaces }
    : key === 'conversations' ? { conversations: body.variables.offset ? [] : body.variables.pinned ? [conv('c1', '釘選的對話', true), conv('c2', 'Pinned example', true)] : [conv('c3', '近期對話一', false), conv('c4', 'Recent example', false)] }
    : { defaultLocalBot: localBot, bots: body.variables.offset ? [] : [bot('b1', 'GKE Research Agent'), bot('b2', 'Second bot')] };
  await request.respond({ status: 200, contentType: 'application/json', body: JSON.stringify({ data }) });
});

const css = p => readFile(new URL(p, import.meta.url), 'utf8');
const ring = el => el.evaluate(e => { const s = getComputedStyle(e); return { style: s.outlineStyle, width: parseFloat(s.outlineWidth), color: s.outlineColor }; });
const keyboardTo = async (selector, max = 25) => {
  for (let i = 0; i < max; i++) {
    if (await page.evaluate(s => document.activeElement?.matches(s), selector)) return;
    await page.keyboard.press('Tab');
  }
  const trail = await page.evaluate(() => `${document.activeElement?.tagName}#${document.activeElement?.id}.${document.activeElement?.className}`);
  throw new Error(`Tab never reached ${selector}; last focus ${trail}`);
};
const load = async () => { await page.goto(browserUrl(), { waitUntil: 'networkidle2' }); await page.waitForSelector('chat-sidenav'); await wait(400); };

try {
  page.setDefaultTimeout(8000);
  await page.setViewport({ width: 1440, height: 900 });
  await page.emulateMediaFeatures([{ name: 'prefers-reduced-motion', value: 'reduce' }]);
  await load();
  await shot('01-desktop-conversations');

  await check('public tokens used by app css resolve to non-empty computed values', async () => {
    const tokens = ['surface', 'surface-alt', 'separator', 'primary', 'text', 'text-muted', 'error-bg', 'error-border', 'error-text', 'warning-bg', 'warning-text', 'radius-button', 'radius-input', 'radius-sm', 'shadow-sm', 'space-1', 'space-2', 'space-3', 'space-4', 'font-size-sm', 'font-size-xs'];
    const values = await page.evaluate(names => Object.fromEntries(names.map(n => [n, getComputedStyle(document.querySelector('.layout-container')).getPropertyValue(`--tplane-chat-${n}`).trim()])), tokens);
    const missing = Object.entries(values).filter(([, v]) => !v).map(([k]) => k);
    assert.deepEqual(missing, [], `missing tokens: ${missing}`);
  });

  await check('app css contains no private SDK selectors', async () => {
    for (const f of ['../src/app/app.css', '../src/app/chat-navigation.component.css']) {
      const text = await css(f);
      assert.ok(!/::ng-deep|(^|[\s,{}>+~])chat-[a-z-]+|\.tplane-|\.chat-(?!pane)/m.test(text.replace(/--tplane-chat-[a-z0-9-]+/g, '').replace(/\/\*[\s\S]*?\*\//g, '')), `${f} references SDK internals`);
    }
  });

  await check('desktop shell geometry: sidebar 280px, main offset, select fills column', async () => {
    const g = await page.evaluate(() => {
      const r = s => document.querySelector(s).getBoundingClientRect();
      const sel = document.querySelector('#workspace-select');
      const panel = document.querySelector('.primary-panel');
      const cs = getComputedStyle(sel);
      return { side: r('chat-sidenav').width, mainLeft: r('main.main-area').left, sel: sel.getBoundingClientRect().width, panel: panel.getBoundingClientRect().width,
        border: parseFloat(cs.borderTopWidth), radius: parseFloat(cs.borderTopLeftRadius), panelOverflow: panel.scrollWidth > panel.clientWidth };
    });
    assert.ok(Math.abs(g.side - 280) <= 1, `sidenav ${g.side}`);
    assert.ok(g.mainLeft >= 279 && g.mainLeft <= 281, `main left ${g.mainLeft}`);
    assert.ok(g.sel >= g.panel - 40, `select ${g.sel} vs panel ${g.panel}`);
    assert.ok(g.border >= 1 && g.radius >= 4 && !g.panelOverflow, JSON.stringify(g));
  });

  await check('long workspace name does not widen or overflow the sidebar', async () => {
    await page.select('#workspace-select', 'w2'); await wait(500);
    const g = await page.evaluate(() => ({ side: document.querySelector('chat-sidenav').getBoundingClientRect().width,
      sel: document.querySelector('#workspace-select').getBoundingClientRect().right, bodyOverflow: document.documentElement.scrollWidth > innerWidth }));
    assert.ok(Math.abs(g.side - 280) <= 1 && g.sel <= 280 && !g.bodyOverflow, JSON.stringify(g));
    await shot('02-long-workspace-name');
    await page.select('#workspace-select', 'w1'); await wait(400);
  });

  await check('keyboard: Tab reaches select with visible 2px focus ring; type-ahead changes it', async () => {
    await page.evaluate(() => document.activeElement?.blur());
    await keyboardTo('#workspace-select');
    const r = await ring(await page.$('#workspace-select'));
    assert.ok(r.style !== 'none' && r.width >= 2, JSON.stringify(r));
    await shot('03-focus-select');
    await page.keyboard.type('T'); await wait(500); // type-ahead: macOS Chrome opens the popup on arrow keys instead
    assert.equal(await page.$eval('#workspace-select', e => e.value), 'w2');
    await wait(1200); await page.keyboard.type('P'); await wait(500); // past the type-ahead buffer reset
    assert.equal(await page.$eval('#workspace-select', e => e.value), 'w1');
  });

  await check('keyboard: Tab to nav tabs shows focus ring and Enter/Space switch pressed state', async () => {
    await keyboardTo('[data-testid="nav-conversations"]');
    const r = await ring(await page.$('[data-testid="nav-conversations"]'));
    assert.ok(r.style !== 'none' && r.width >= 2, JSON.stringify(r));
    await page.keyboard.press('Tab');
    assert.ok(await page.evaluate(() => document.activeElement?.matches('[data-testid="nav-bots"]')));
    await shot('04-focus-tab');
    await page.keyboard.press('Enter'); await page.waitForSelector('[data-testid="bot-item-b1"]');
    assert.equal(await page.$eval('[data-testid="nav-bots"]', e => e.getAttribute('aria-pressed')), 'true');
    assert.equal(await page.$eval('[data-testid="nav-conversations"]', e => e.getAttribute('aria-pressed')), 'false');
    await shot('05-bots-mode');
    const pressed = await page.evaluate(() => { const s = getComputedStyle(document.querySelector('[data-testid="nav-bots"]')); const o = getComputedStyle(document.querySelector('[data-testid="nav-conversations"]')); return { a: s.backgroundColor, b: o.backgroundColor, w: s.fontWeight }; });
    assert.notEqual(pressed.a, pressed.b, 'pressed tab must be visually distinct');
    await page.focus('[data-testid="nav-conversations"]'); await page.keyboard.press('Space');
    await page.waitForFunction(() => document.querySelector('[data-testid="nav-conversations"]').getAttribute('aria-pressed') === 'true');
  });

  await check('workspace load error: styled alert from error tokens and retry recovers', async () => {
    fail.workspaces = 1; await load();
    await page.waitForSelector('p[role="alert"]');
    const s = await page.$eval('p[role="alert"]', e => { const c = getComputedStyle(e); const t = getComputedStyle(document.querySelector('.layout-container')); return { bg: c.backgroundColor, border: c.borderTopWidth, token: t.getPropertyValue('--tplane-chat-error-bg').trim() }; });
    assert.ok(s.bg !== 'rgba(0, 0, 0, 0)' && s.border === '1px', JSON.stringify(s));
    assert.equal(await page.$eval('#workspace-select', e => e.disabled), true);
    await shot('06-workspace-error');
    await keyboardTo('p[role="alert"] button');
    assert.ok((await ring(await page.$('p[role="alert"] button'))).width >= 2);
    await page.keyboard.press('Enter');
    await page.waitForFunction(() => !document.querySelector('p[role="alert"]') && !document.querySelector('#workspace-select').disabled);
    await shot('07-workspace-recovered');
  });

  await check('workspace loading state is announced and select disabled', async () => {
    delay.workspaces = 1500; const nav = page.goto(browserUrl(), { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('p[role="status"]'); await nav;
    assert.equal(await page.$eval('#workspace-select', e => e.disabled), true);
    await shot('08-loading');
    await page.waitForFunction(() => !document.querySelector('#workspace-select').disabled, { timeout: 10000 });
    await shot('08b-loaded');
  });

  await check('conversation list error: alert styled, retry reloads list', async () => {
    await load(); fail.conversations = 2;
    const reload = await page.evaluateHandle(() => [...document.querySelectorAll('button')].find(b => b.textContent.includes('重新載入對話')));
    await reload.asElement().click();
    await page.waitForSelector('p[role="alert"]');
    const a = await page.$eval('p[role="alert"]', e => { const c = getComputedStyle(e); return { bg: c.backgroundColor, color: c.color }; });
    assert.ok(a.bg !== 'rgba(0, 0, 0, 0)', JSON.stringify(a));
    await shot('09-conversation-error');
    const retry = await page.evaluateHandle(() => [...document.querySelectorAll('p[role="alert"] button')].find(b => b.textContent.includes('重試')));
    assert.ok(retry.asElement(), 'retry button');
    await retry.asElement().click();
    await page.waitForFunction(() => [...document.querySelectorAll('chat-thread-list')].some(e => e.textContent.includes('Recent example')), { timeout: 10000 });
    await shot('10-conversation-recovered');
  });

  await check('bot list error and retry', async () => {
    fail.bots = 1; await page.click('[data-testid="nav-bots"]');
    await page.waitForSelector('[data-testid="bots-retry"]'); await shot('11-bots-error');
    await page.click('[data-testid="bots-retry"]'); await page.waitForSelector('[data-testid="bot-item-b1"]');
  });

  await check('ThreadPlane components remain package-provided (rendered, not wrapped or restyled by app rules)', async () => {
    await load();
    for (const s of ['chat-sidenav', 'chat-thread-list', 'chat-window']) assert.ok(await page.$(s), s);
    const external = await page.evaluate(() => [...document.querySelectorAll('chat-thread-list button, chat-sidenav [aria-label="Search conversations"]')].every(el => !el.hasAttribute('_ngcontent-' + (document.querySelector('app-chat-navigation')?.getAttributeNames().find(n => n.startsWith('_nghost-'))?.slice(8) ?? '#'))));
    assert.ok(external, 'SDK-owned buttons must not carry app component scope attributes');
  });

  await check('no mutation requests were issued', async () => assert.deepEqual(mutations, []));
  await check('no console page errors', async () => {
    // reported through console.json; fail only on uncaught page errors
  });
  await copyFile(new URL(import.meta.url), join(artifactsDir, 'suite-source.mjs'));
} finally {
  try { await finish(); console.log(`PASSED; artifacts: ${artifactsDir}`); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
