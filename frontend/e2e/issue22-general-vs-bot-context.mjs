// Historical layout verification retained for coverage traceability; see README.
throw new Error('Retired legacy browser verification; use the current ThreadPlane browser matrix.');

import puppeteer from 'puppeteer-core';
import { assertSafeBackendOrigin } from '../test/verification-harness.mjs';

/*
 * Issue #22 browser verification: general chat vs specified Bot context.
 * Read-only against backend: the long Bot name is injected by rewriting GraphQL *responses* in the
 * browser (no DB mutation, no run/create calls, no paid model). Layout is judged by element
 * bounding rects (header / sidebar / banner / chat input), never by document.scrollWidth alone.
 */
const CHROME_PATH = process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const FRONTEND_URL = process.env.VERIFICATION_FRONTEND_URL || 'http://localhost:4300';
const LONG_NAME = '非常非常長的指定助理名稱 Extremely Long Specified Assistant Name For Layout Verification 0123456789';

const results = [];
const record = (name, passed, details = '') => {
  results.push({ name, passed, details });
  console.log(`${passed ? '✅ PASS' : '❌ FAIL'} - ${name}${details ? ` (${details})` : ''}`);
};

const overlaps = (a, b) => a && b && a.width > 0 && a.height > 0 && b.width > 0 && b.height > 0 &&
  a.left < b.right - 0.5 && a.right > b.left + 0.5 && a.top < b.bottom - 0.5 && a.bottom > b.top + 0.5;

async function rects(page) {
  return await page.evaluate(() => {
    const r = (sel) => {
      const el = document.querySelector(sel);
      if (!el) return null;
      const b = el.getBoundingClientRect();
      return { left: b.left, right: b.right, top: b.top, bottom: b.bottom, width: b.width, height: b.height };
    };
    return {
      vw: window.innerWidth,
      sidebar: r('.sidebar'),
      header: r('.header'),
      title: r('[data-testid="chat-header-title"]'),
      meta: r('[data-testid="header-bot-meta"]'),
      name: r('[data-testid="header-bot-name"]'),
      badge: r('[data-testid="header-execution-mode"]'),
      actions: r('.header-actions'),
      banner: r('[data-testid="simulation-banner"]') || r('[data-testid="disabled-bot-banner"]'),
      chat: r('.chat-view-container'),
      textarea: r('textarea')
    };
  });
}

function checkLayout(label, R, expectBanner) {
  const within = (b) => !b || (b.left >= -0.5 && b.right <= R.vw + 0.5);
  const parts = { header: R.header, title: R.title, meta: R.meta, name: R.name, badge: R.badge, actions: R.actions, banner: R.banner, chat: R.chat, textarea: R.textarea };
  const problems = [];
  for (const [k, v] of Object.entries(parts)) if (!within(v)) problems.push(`${k} outside viewport`);
  // header must not overlap sidebar (side-by-side on desktop, stacked on mobile) or banner
  if (overlaps(R.header, R.sidebar)) problems.push('header overlaps sidebar');
  if (R.banner && overlaps(R.header, R.banner)) problems.push('header overlaps banner');
  if (R.banner && overlaps(R.banner, R.sidebar)) problems.push('banner overlaps sidebar');
  if (R.banner && overlaps(R.banner, R.textarea)) problems.push('banner overlaps textarea');
  if (R.header && R.chat && overlaps(R.header, R.chat)) problems.push('header overlaps chat view');
  if (R.title && R.meta && overlaps(R.title, R.meta)) problems.push('title overlaps bot meta');
  if (R.meta && R.actions && overlaps(R.meta, R.actions)) problems.push('bot meta overlaps header actions');
  if (R.name && R.badge && overlaps(R.name, R.badge)) problems.push('name overlaps badge');
  if (R.textarea && R.sidebar && overlaps(R.textarea, R.sidebar)) problems.push('textarea overlaps sidebar');
  if (expectBanner && !R.banner) problems.push('expected banner missing');
  record(label, problems.length === 0, problems.join('; ') || 'no rect overlaps / overflow');
}

export async function run() {
  await assertSafeBackendOrigin(process.env.VERIFICATION_BACKEND_URL || 'http://localhost:8181');
  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage']
  });
  const page = await browser.newPage();
  const mutations = [];

  await page.setRequestInterception(true);
  page.on('request', (req) => {
    const url = req.url();
    if (req.method() === 'POST') {
      if (url.includes('/graphql') && /createConversation/.test(req.postData() || '')) mutations.push('createConversation');
      if (/\/runs(\/|$)/.test(url)) mutations.push('run');
    }
    req.continue();
  });

  // Response rewriting via CDP-free approach: patch fetch in page so backend data stays untouched.
  await page.evaluateOnNewDocument((longName) => {
    const orig = window.fetch.bind(window);
    window.__e2e = { rewrite: false, offId: null, simId: null, dropDefault: false };
    window.fetch = async (...args) => {
      const res = await orig(...args);
      const url = typeof args[0] === 'string' ? args[0] : args[0]?.url;
      if (!url || !url.includes('/graphql') || !window.__e2e.rewrite) return res;
      let json;
      try { json = await res.clone().json(); } catch { return res; }
      if (window.__e2e.dropDefault && json?.data && 'defaultLocalBot' in json.data) {
        json.data.defaultLocalBot = null;
        window.__e2e.defaultDropped = true;
      }
      const patch = (b) => {
        if (!b || b.id === json?.data?.defaultLocalBot?.id) return;
        b.name = longName;
        if (b.id === window.__e2e.offId) { b.canChat = false; b.executionMode = 'UNAVAILABLE'; }
        if (b.id === window.__e2e.simId) { b.canChat = true; b.executionMode = 'SPRING_AI_SIMULATION'; }
      };
      (json?.data?.bots || []).forEach(patch);
      if (json?.data?.bot) patch(json.data.bot);
      return new Response(JSON.stringify(json), { status: res.status, headers: { 'Content-Type': 'application/json' } });
    };
  }, LONG_NAME);

  try {
    for (const vp of [{ w: 1280, h: 900 }, { w: 375, h: 812 }]) {
      await page.setViewport({ width: vp.w, height: vp.h });
      await page.goto(FRONTEND_URL, { waitUntil: 'networkidle0' });
      await page.waitForSelector('[data-testid="new-chat-btn"]', { timeout: 15000 });
      await page.click('[data-testid="new-chat-btn"]');
      await new Promise((r) => setTimeout(r, 300));

      // General chat: no seed card / labels
      const general = await page.evaluate(() => ({
        seedCard: !!document.querySelector('.seed-bot-card'),
        meta: !!document.querySelector('[data-testid="header-bot-meta"]'),
        text: document.body.innerText,
        tabs: !!document.querySelector('[data-testid="nav-chat-view-btn"]') && !!document.querySelector('[data-testid="bot-manager-nav-btn"]')
      }));
      record(`[${vp.w}] general chat hides Seed Bot / bot meta / LOCAL / GKE_REMOTE`,
        !general.seedCard && !general.meta && !/Seed Bot|GKE_REMOTE|NOT_APPLICABLE|\bLOCAL\b/.test(general.text),
        JSON.stringify({ seedCard: general.seedCard, meta: general.meta }));
      record(`[${vp.w}] 對話列表／Bot 管理 tabs preserved`, general.tabs);
      checkLayout(`[${vp.w}] general chat layout rects`, await rects(page), false);

      // Specified bot via Bot 管理 → open draft, with long name injected
      await page.evaluate(() => { window.__e2e.rewrite = true; });
      await page.click('[data-testid="bot-manager-nav-btn"]');
      await page.waitForSelector('[data-testid^="open-draft-btn-"]', { timeout: 10000 });
      const ids = await page.$$eval('[data-testid^="open-draft-btn-"]', (els) => els.map((e) => e.getAttribute('data-testid').replace('open-draft-btn-', '')));
      let covered = 0;
      for (const mode of ['sim', 'off']) {
        mutations.length = 0;
        const id = ids[mode === 'sim' ? 0 : Math.min(1, ids.length - 1)];
        await page.evaluate((i, m) => { window.__e2e.offId = m === 'off' ? i : null; window.__e2e.simId = m === 'sim' ? i : null; }, id, mode);
        // reload store through bot manager navigation so rewritten data is used
        await page.goto(FRONTEND_URL, { waitUntil: 'networkidle0' });
        await page.evaluate((i, m) => { window.__e2e.rewrite = true; window.__e2e.offId = m === 'off' ? i : null; window.__e2e.simId = m === 'sim' ? i : null; }, id, mode);
        await page.waitForSelector('[data-testid="bot-manager-nav-btn"]');
        await page.click('[data-testid="bot-manager-nav-btn"]');
        await page.waitForSelector(`[data-testid="open-draft-btn-${id}"]`, { timeout: 10000 });
        await page.click(`[data-testid="open-draft-btn-${id}"]`);
        await page.waitForSelector('[data-testid="header-bot-name"]', { timeout: 5000 }).catch(() => {});
        const ctx = await page.evaluate(() => ({
          name: document.querySelector('[data-testid="header-bot-name"]')?.textContent?.trim(),
          badge: document.querySelector('[data-testid="header-execution-mode"]')?.textContent?.trim() ?? null,
          meta: document.querySelector('[data-testid="header-bot-meta"]')?.textContent ?? '',
          titleText: document.querySelector('[data-testid="chat-header-title"]')?.textContent ?? ''
        }));
        const wantBadge = mode === 'sim' ? '模擬' : '停用';
        record(`[${vp.w}] ${mode} bot header: name once + short badge, no enum/target`,
          !!ctx.name && ctx.badge === wantBadge && !/GKE_REMOTE|NOT_CONNECTED|SPRING_AI|UNAVAILABLE|\[/.test(ctx.meta) && !ctx.titleText.includes(LONG_NAME.slice(0, 10)),
          JSON.stringify({ badge: ctx.badge }));
        record(`[${vp.w}] ${mode} bot draft open => zero createConversation/run`, mutations.length === 0, mutations.join(','));
        checkLayout(`[${vp.w}] ${mode} bot long-name layout rects`, await rects(page), true);
        await page.screenshot({ path: `/tmp/issue22-${vp.w}-${mode}.png` });
        covered++;

        if (mode === 'off') {
          // typing + Enter in OFF mode must not clear raw input nor mutate
          const sel = 'textarea';
          await page.focus(sel);
          await page.keyboard.type('  off draft keep  ');
          await page.keyboard.press('Enter');
          await new Promise((r) => setTimeout(r, 300));
          const val = await page.$eval(sel, (e) => e.value);
          record(`[${vp.w}] OFF Enter keeps raw draft and zero mutation`, val === '  off draft keep  ' && mutations.length === 0, JSON.stringify(val));
        }

        // New Chat returns to general context
        await page.click('[data-testid="new-chat-btn"]');
        await new Promise((r) => setTimeout(r, 300));
        const back = await page.evaluate(() => !document.querySelector('[data-testid="header-bot-meta"]') && !document.querySelector('[data-testid="simulation-banner"]') && !document.querySelector('[data-testid="disabled-bot-banner"]'));
        record(`[${vp.w}] ${mode}: New Chat clears specified bot context`, back);
      }
      record(`[${vp.w}] covered sim and off`, covered === 2);

      // Missing defaultLocalBot (simulated query result): explicit banner, send disabled, no list[0] fallback
      await page.goto(FRONTEND_URL, { waitUntil: 'networkidle0' });
      await page.evaluate(() => { window.__e2e.rewrite = true; window.__e2e.dropDefault = true; });
      await page.click('[data-testid="bot-manager-nav-btn"]');
      // Wait for the rewritten response before navigation: dropping the flag early restores the default.
      await page.waitForFunction(() => window.__e2e.defaultDropped === true);
      await page.click('[data-testid="nav-chat-view-btn"]');
      await page.waitForSelector('[data-testid="new-chat-btn"]');
      await page.click('[data-testid="new-chat-btn"]');
      await page.waitForSelector('[data-testid="default-bot-missing-banner"]');
      mutations.length = 0;
      const miss = await page.evaluate(() => ({
        banner: document.querySelector('[data-testid="default-bot-missing-banner"]')?.textContent ?? null,
        sendDisabled: (() => { const h = document.querySelector('[data-testid="send-message-btn"]'); const b = h?.querySelector('button'); return !!(h?.disabled ?? b?.disabled ?? h?.hasAttribute('disabled')); })()
      }));
      record(`[${vp.w}] missing default: banner shown and send disabled`, miss.banner !== null && miss.sendDisabled, JSON.stringify(miss).slice(0, 120));
      record(`[${vp.w}] missing default: zero mutation`, mutations.length === 0, mutations.join(','));
      checkLayout(`[${vp.w}] missing-default layout rects`, await rects(page), false);
    }
  } catch (err) {
    record('Unexpected error', false, err.message);
  } finally {
    await browser.close();
  }

  const failed = results.filter((r) => !r.passed).length;
  console.log(`\nTOTAL ${results.length} | PASSED ${results.length - failed} | FAILED ${failed}`);
  return failed === 0;
}

if (process.argv[1] && process.argv[1].endsWith('issue22-general-vs-bot-context.mjs')) {
  run().then((ok) => process.exit(ok ? 0 : 1)).catch((e) => { console.error(e); process.exit(1); });
}
