// Test-owned browser fixture; GraphQL responses below are synthetic and mutations are refused.
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
  { id: 'w1', name: 'Personal Workspace', role: 'MANAGER', personal: true },
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
  if (q.includes('GetDefaultLocalBot')) {
    await request.respond({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { defaultLocalBot: localBot } }) });
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
const load = async () => { await page.goto(browserUrl(), { waitUntil: 'networkidle2' }); await page.waitForSelector('app-chat-navigation'); await wait(400); };

let primaryError;
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
    for (const f of ['../src/app/app.css', '../src/app/shell/chat-navigation.component.css']) {
      const text = await css(f);
      assert.ok(!/::ng-deep|(^|[\s,{}>+~])chat-[a-z-]+|\.tplane-|\.chat-(?!pane)/m.test(text.replace(/--tplane-chat-[a-z0-9-]+/g, '').replace(/\.chat-view\b/g, '').replace(/\/\*[\s\S]*?\*\//g, '')), `${f} references SDK internals`);
    }
  });

  // Geometry, collapse and peek are verified by app-shell-layout.mjs at 1280×800.
  await check('workspace panel keeps long names within its boundary', async () => {
    await page.click('nav[aria-label="主要功能"] button[aria-label="工作區"]');
    await page.waitForSelector('#workspace-select', { visible: true });
    const bounds = await page.evaluate(() => {
      const panel = document.querySelector('#navigation-context').getBoundingClientRect();
      const select = document.querySelector('#workspace-select').getBoundingClientRect();
      return { panel: panel.toJSON(), select: select.toJSON(), overflow: document.documentElement.scrollWidth > innerWidth };
    });
    assert.ok(bounds.select.left >= bounds.panel.left && bounds.select.right <= bounds.panel.right && !bounds.overflow, JSON.stringify(bounds));
    await page.select('#workspace-select', 'w2');
    await page.waitForFunction(() => !document.querySelector('#workspace-select'));
    assert.ok((await page.$eval('main .workspace-name', e => e.textContent)).includes('Team'));
    await shot('02-long-workspace-name');
  });

  await check('keyboard opens workspace panel and switches workspace', async () => {
    await page.focus('nav[aria-label="主要功能"] button[aria-label="工作區"]');
    assert.ok((await ring(await page.$('nav[aria-label="主要功能"] button[aria-label="工作區"]'))).width >= 2);
    await page.keyboard.press('Enter');
    await page.waitForSelector('#workspace-select', { visible: true });
    await page.focus('#workspace-select');
    assert.ok((await ring(await page.$('#workspace-select'))).width >= 2);
    await page.select('#workspace-select', 'w1');
    await page.waitForFunction(() => !document.querySelector('#workspace-select'));
    assert.ok((await page.$eval('main .workspace-name', e => e.textContent)).includes('Personal'));
    await shot('03-focus-select');
  });

  await check('keyboard switches Bot and conversation features', async () => {
    await page.focus('nav[aria-label="主要功能"] button[aria-label="Bot"]');
    assert.ok((await ring(await page.$('nav[aria-label="主要功能"] button[aria-label="Bot"]'))).width >= 2);
    await page.keyboard.press('Enter'); await page.waitForSelector('[data-testid="bot-item-b1"]');
    assert.equal(await page.$eval('nav[aria-label="主要功能"] button[aria-label="Bot"]', e => e.getAttribute('aria-pressed')), 'true');
    await page.focus('nav[aria-label="主要功能"] button[aria-label="對話"]'); await page.keyboard.press('Space');
    await page.waitForFunction(() => document.querySelector('nav[aria-label="主要功能"] button[aria-label="對話"]').getAttribute('aria-pressed') === 'true');
    await page.waitForSelector('section[aria-label="近期對話"]');
  });

  await check('workspace failure offers keyboard retry', async () => {
    fail.workspaces = 1; await load();
    await page.click('nav[aria-label="主要功能"] button[aria-label="工作區"]');
    await page.waitForSelector('[aria-label="工作區選取"] [role="alert"] button');
    const retry = '[aria-label="工作區選取"] [role="alert"] button';
    await page.focus(retry); assert.ok((await ring(await page.$(retry))).width >= 2);
    await page.keyboard.press('Enter');
    await page.waitForSelector('#workspace-select', { visible: true });
    await shot('07-workspace-recovered');
  });

  await check('workspace loading is announced', async () => {
    delay.workspaces = 1500;
    const nav = page.goto(browserUrl(), { waitUntil: 'domcontentloaded' });
    await nav;
    await page.click('nav[aria-label="主要功能"] button[aria-label="工作區"]');
    await page.waitForSelector('[aria-label="工作區選取"] [role="status"]');
    await page.waitForSelector('#workspace-select', { visible: true, timeout: 10000 });
  });

  await check('conversation list failure exposes retry and recovers', async () => {
    fail.conversations = 2; await load();
    await page.waitForFunction(() => [...document.querySelectorAll('#navigation-context [role="alert"]')].some(e => e.textContent.includes('對話列表載入失敗')));
    const retry = await page.evaluateHandle(() => [...document.querySelectorAll('#navigation-context [role="alert"] button')].find(b => b.textContent.includes('重試載入')));
    assert.ok(retry.asElement()); await retry.asElement().click();
    await page.waitForFunction(() => [...document.querySelectorAll('chat-thread-list')].some(e => e.textContent.includes('Recent example')));
  });

  await check('bot list error and retry', async () => {
    await load(); fail.bots = 1;
    await page.click('nav[aria-label="主要功能"] button[aria-label="Bot"]');
    await page.waitForSelector('[data-testid="bots-retry"]');
    await page.click('[data-testid="bots-retry"]'); await page.waitForSelector('[data-testid="bot-item-b1"]');
  });

  await check('ThreadPlane components render through public elements', async () => {
    await load();
    for (const selector of ['app-chat-navigation', 'chat-thread-list', 'chat-window']) assert.ok(await page.$(selector), selector);
  });

  await check('no mutation requests were issued', async () => assert.deepEqual(mutations, []));
  await copyFile(new URL(import.meta.url), join(artifactsDir, 'suite-source.mjs'));
} catch (error) {
  primaryError = error;
} finally {
  try { await finish(primaryError); console.log(`PASSED; artifacts: ${artifactsDir}`); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
