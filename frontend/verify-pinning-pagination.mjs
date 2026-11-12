// Historical layout verification retained for coverage traceability; see README.
throw new Error('Retired legacy browser verification; use the current ThreadPlane browser matrix.');

import puppeteer from 'puppeteer-core';
import path from 'node:path';
import { installBrowserGuard, assertSafeBackendOrigin, getGuardStats } from './test/verification-harness.mjs';

/*
 * =====================================================================================
 * Browser Verification Script for Issue #12: Resilient Dual-Section Pagination & Range Reconciliation
 * =====================================================================================
 * Validates dual-section deep pagination, large range reconciliation (>100 items),
 * and decoupled fault recovery in a real browser:
 * 1. Safe Preflight: backend at 8181 is confirmed safe mock (MockChatModel), zero external model generation.
 * 2. Fail-Closed Guard: strictly zero model generation requests allowed; all unknown GraphQL/API fail closed.
 * 3. Synthetic Fixtures: 120 unpinned and 105 pinned conversations with natural descending timestamps.
 * 4. Deep Paging: paging recent to 120 items and pinned to 105 items with independent offsets.
 * 5. Selection, Non-Empty Thread & Draft Preservation:
 *    - Deep conversation u-50 selected with non-empty USER and ASSISTANT messages.
 *    - Thread DOM text, message counts, and input draft recorded as authoritative baseline.
 * 6. Range Reconciliation on First Pin (u-0):
 *    - Reconciled display: 105 pinned, 119 recent, and pinnedHasMore=true (server 106).
 *    - All batch queries strictly respect server cap limit <= 100 with exact offsets (0, 100, 105 peek).
 *    - DOM IDs verified 1-by-1 against state pool sorted by updated_at DESC, id DESC.
 * 7. Controlled Browser Stale Injection Barrier:
 *    - In-flight loadMorePinned (offset 105, limit 21) trapped at controlled barrier.
 *    - Snapshots HTTP 200 payload BEFORE waiting.
 *    - Pinning u-1 invalidates pagination requests and triggers range refetch with failure injected.
 *    - Barrier released: unconditionally responds HTTP 200 with snapshot payload.
 *    - Awaits network body delivery + double animation frame rendering in browser.
 *    - Asserts late 200 response discarded, no duplicate items appended, loading cleared.
 * 8. Fault Injection: Pin Mutation Succeeded on Server, Refetch Failed:
 *    - Server updates u-1 to pinned (server has 107 pinned, 118 recent).
 *    - Retained display: 105 pinned, 119 recent.
 *    - DOM IDs before and after failure are 100% IDENTICAL item-by-item.
 *    - Active thread messages, header title, and draft 100% IDENTICAL.
 *    - Error banner displayed, pin buttons locked.
 * 9. Retry Refetch:
 *    - Query-only (strictly 0 mutations).
 *    - Displays 105 pinned (with u-1 after u-0), 118 recent (u-2..u-119), and pinnedHasMore=true.
 * 10. Load More to Authoritative 107:
 *    - Pinned section loadMore loads remaining 2 items to reach full authoritative 107 items.
 * 11. Screenshots saved to /tmp/bot-ui-12-*.png.
 */

const CHROME_PATH = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const BASE_URL = process.env.VERIFICATION_FRONTEND_URL || 'http://localhost:4300';
const BACKEND_URL = process.env.VERIFICATION_BACKEND_URL || 'http://localhost:8181';

const SCREENSHOTS = {
  initial: '/tmp/bot-ui-12-01-initial.png',
  deepPaged: '/tmp/bot-ui-12-02-deep-paged.png',
  pinnedReconciled: '/tmp/bot-ui-12-03-pinned-range-reconciled.png',
  refetchFailed: '/tmp/bot-ui-12-04-refetch-failed-banner.png',
  retrySuccess: '/tmp/bot-ui-12-05-retry-refetch-success.png',
  authoritative107: '/tmp/bot-ui-12-06-authoritative-107.png'
};

const TEST_DRAFT = 'Draft content in u-50 across deep pagination and range reconciliation 繁體中文測試';

// Helper to sort pools strictly by updated_at DESC, id DESC
function sortPool(items) {
  return [...items].sort((a, b) => {
    const tA = new Date(a.updatedAt).getTime();
    const tB = new Date(b.updatedAt).getTime();
    if (tB !== tA) return tB - tA;
    return b.id.localeCompare(a.id);
  });
}

// In-memory synthetic pools (105 pinned and 120 unpinned)
const createSyntheticPools = () => {
  const baseTime = Date.now();

  // Pinned pool: 105 items (p-0 through p-104)
  const pinned = Array.from({ length: 105 }, (_, i) => ({
    id: `p-${i}`,
    botId: 'bot-1',
    title: `Pinned Conversation #${i + 1}`,
    pinned: true,
    createdAt: new Date(baseTime - 10000 - i * 1000).toISOString(),
    updatedAt: new Date(baseTime - 10000 - i * 1000).toISOString()
  }));

  // Unpinned pool: 120 items (u-0 through u-119)
  // u-0 has newest timestamp (baseTime)
  // u-1 has second newest timestamp (baseTime - 1000)
  // u-2..u-119 have older timestamps
  const unpinned = Array.from({ length: 120 }, (_, i) => {
    let t = baseTime - 200000 - i * 1000;
    if (i === 0) t = baseTime;
    if (i === 1) t = baseTime - 1000;
    return {
      id: `u-${i}`,
      botId: 'bot-1',
      title: `Recent Conversation #${i + 1}`,
      pinned: false,
      createdAt: new Date(t).toISOString(),
      updatedAt: new Date(t).toISOString()
    };
  });

  const all = [...pinned, ...unpinned];
  return {
    all,
    pinned: sortPool(pinned),
    unpinned: sortPool(unpinned)
  };
};

async function run() {
  console.log('--- Starting Issue #12 Pagination & Range Reconciliation Browser Verification ---');
  console.log(`Frontend URL: ${BASE_URL}`);
  console.log(`Backend URL:  ${BACKEND_URL}`);

  // 1. Safety Preflight
  await assertSafeBackendOrigin(BACKEND_URL);
  console.log('✓ Backend verified safe mock origin (8181).');

  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: true,
    args: ['--no-sandbox', '--disable-gpu', '--window-size=1280,800']
  });

  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 800 });
  page.on('console', msg => console.log('[BROWSER CONSOLE]', msg.text()));
  page.on('pageerror', err => console.log('[BROWSER PAGEERROR]', err.message));

  // 2. State & Fault Tracking
  const state = createSyntheticPools();
  let faultInjectRefetchFailure = false;

  // Controlled Stale Pagination Barrier with Pre-snapshotted HTTP 200 Payload
  let stalePaginationBarrier = null;
  let resolveStalePaginationBarrier = null;
  let isStalePaginationHeld = false;
  let stalePaginationResponseDelivered = false;

  const queryCalls = [];
  const mutationCalls = [];

  // Install fail-closed browser guard with cooperative GraphQL interceptor
  const guard = await installBrowserGuard(page, { mode: 'fail-closed' });
  console.log('✓ In-page fail-closed guard installed.');

  guard.addRequestHandler(async (req) => {
    const url = req.url();
    if (url.includes('/graphql') && req.method() === 'POST') {
      const bodyText = req.postData() || '{}';
      let body = {};
      try {
        body = JSON.parse(bodyText);
      } catch {}

      // Handle Bots query
      if (body.query?.includes('bots')) {
        req.respond({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: { bots: [{ id: 'bot-1', name: 'General Assistant', createdAt: '2026-01-01' }] }
          })
        });
        return;
      }

      // Handle setConversationPinned mutation
      if (body.query?.includes('setConversationPinned')) {
        const { id, pinned } = body.variables;
        mutationCalls.push({ id, pinned });

        const item = state.all.find(c => c.id === id);
        if (item) {
          item.pinned = pinned;
          // Natural chronological sequence preserved: updatedAt is unchanged
        }
        state.pinned = sortPool(state.all.filter(c => c.pinned));
        state.unpinned = sortPool(state.all.filter(c => !c.pinned));

        req.respond({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: { setConversationPinned: item || { id, pinned } }
          })
        });
        return;
      }

      // Handle GetConversations query
      if (body.query?.includes('conversations')) {
        const { pinned, limit, offset } = body.variables;
        queryCalls.push({ pinned, limit, offset });

        // Controlled Stale Pagination Barrier: hold in-flight pinned loadMore response (limit 21, offset 105)
        if (stalePaginationBarrier && pinned && limit === 21 && offset === 105 && !isStalePaginationHeld) {
          isStalePaginationHeld = true;
          // Snapshot valid HTTP 200 payload BEFORE waiting at barrier!
          const stalePayload = {
            data: {
              conversations: state.pinned.slice(offset, offset + limit)
            }
          };

          await stalePaginationBarrier;

          // Unconditionally deliver HTTP 200 snapshot payload regardless of subsequent fault injection
          req.respond({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify(stalePayload)
          });
          stalePaginationResponseDelivered = true;
          return;
        }

        if (faultInjectRefetchFailure) {
          req.respond({
            status: 500,
            contentType: 'application/json',
            body: JSON.stringify({ errors: [{ message: 'Simulated refetch network failure' }] })
          });
          return;
        }

        const pool = pinned ? state.pinned : state.unpinned;
        const slice = pool.slice(offset, offset + limit);

        req.respond({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: { conversations: slice }
          })
        });
        return;
      }

      // Handle single conversation query (GetConversation) with non-empty thread messages for u-50
      if (body.query?.includes('conversation(')) {
        const id = body.variables?.id;
        const conv = state.all.find(c => c.id === id) || {
          id,
          botId: 'bot-1',
          title: `Conversation ${id}`,
          pinned: false,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString()
        };
        const messages = id === 'u-50' ? [
          {
            id: 'msg-u50-asst-1',
            runId: 'run-u50-1',
            role: 'ASSISTANT',
            content: 'Greetings! This thread message history must remain intact across pagination and pinning.',
            interrupted: false,
            createdAt: new Date(Date.now() - 3500000).toISOString()
          },
          {
            id: 'msg-u50-user-1',
            runId: 'run-u50-1',
            role: 'USER',
            content: 'Hello! This is a test user message in u-50 conversation thread.',
            interrupted: false,
            createdAt: new Date(Date.now() - 3600000).toISOString()
          }
        ] : [];

        req.respond({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              conversation: {
                id: conv.id,
                botId: conv.botId || 'bot-1',
                title: conv.title,
                pinned: !!conv.pinned,
                createdAt: conv.createdAt,
                updatedAt: conv.updatedAt,
                messages,
                runs: []
              }
            }
          })
        });
        return;
      }

      // Unknown GraphQL query/mutation: strictly fail closed
      console.warn(`[Guard] Unknown GraphQL request blocked: ${body.query?.slice(0, 50)}`);
      req.abort('failed');
      return;
    }

    // Fail closed on unknown /api requests
    if (url.includes('/api/')) {
      console.warn(`[Guard] Unknown API request blocked: ${url}`);
      req.abort('failed');
      return;
    }
  });

  try {
    // 3. Navigate to Frontend
    console.log(`Navigating to ${BASE_URL}...`);
    await page.goto(BASE_URL, { waitUntil: 'networkidle0' });
    await page.waitForSelector('.sidebar', { timeout: 10000 });

    // 4. Initial Dual Section Render (20 / 20)
    await page.waitForSelector('.pinned-list-container .conversation-item');
    await page.waitForSelector('.unpinned-list-container .conversation-item');

    const initPinnedIds = await page.$$eval('.pinned-list-container .conversation-item', els => els.map(e => e.dataset.id));
    const initUnpinnedIds = await page.$$eval('.unpinned-list-container .conversation-item', els => els.map(e => e.dataset.id));

    const expectedInitPinnedIds = state.pinned.slice(0, 20).map(c => c.id);
    const expectedInitUnpinnedIds = state.unpinned.slice(0, 20).map(c => c.id);

    assertIdListMatch('Initial Pinned', initPinnedIds, expectedInitPinnedIds);
    assertIdListMatch('Initial Recent', initUnpinnedIds, expectedInitUnpinnedIds);
    console.log(`✓ Initial load: 20 pinned, 20 recent items verified 1-by-1 against state pool.`);
    await page.screenshot({ path: SCREENSHOTS.initial, fullPage: true });

    // 5. Deep Paging Recent to all 120 items
    console.log('Paging recent conversations past 100 (up to 120 items)...');
    for (let i = 0; i < 5; i++) {
      await page.waitForSelector('[data-testid="load-more-conv-btn"]', { timeout: 5000 });
      await page.click('[data-testid="load-more-conv-btn"]');
      const expectedCount = 20 + (i + 1) * 20;
      await page.waitForFunction(
        (exp) => document.querySelectorAll('.unpinned-list-container .conversation-item').length === exp,
        { timeout: 5000 },
        expectedCount
      );
    }
    const deepUnpinnedIds = await page.$$eval('.unpinned-list-container .conversation-item', els => els.map(e => e.dataset.id));
    assertIdListMatch('Deep Paged Recent (120)', deepUnpinnedIds, state.unpinned.slice(0, 120).map(c => c.id));
    console.log(`✓ Recent conversations paged to 120 items; all 120 IDs match state pool.`);

    // 6. Deep Paging Pinned to all 105 items
    console.log('Paging pinned conversations past 100 (up to 105 items)...');
    for (let i = 0; i < 5; i++) {
      await page.waitForSelector('[data-testid="load-more-pinned-btn"]', { timeout: 5000 });
      await page.click('[data-testid="load-more-pinned-btn"]');
      const expectedCount = i === 4 ? 105 : 20 + (i + 1) * 20;
      await page.waitForFunction(
        (exp) => document.querySelectorAll('.pinned-list-container .conversation-item').length === exp,
        { timeout: 5000 },
        expectedCount
      );
    }
    const deepPinnedIds = await page.$$eval('.pinned-list-container .conversation-item', els => els.map(e => e.dataset.id));
    assertIdListMatch('Deep Paged Pinned (105)', deepPinnedIds, state.pinned.slice(0, 105).map(c => c.id));
    console.log(`✓ Pinned conversations paged to 105 items; all 105 IDs match state pool.`);
    await page.screenshot({ path: SCREENSHOTS.deepPaged, fullPage: true });

    // 7. Selection, Non-Empty Thread & Draft Preservation
    console.log('Selecting deep conversation u-50 and setting draft...');
    await page.evaluate(() => {
      const item = document.querySelector('.unpinned-list-container .conversation-item[data-id="u-50"]');
      if (item) {
        item.scrollIntoView({ block: 'center' });
        item.click();
      }
    });
    await page.waitForFunction(
      () => document.querySelector('.header-title')?.textContent?.includes('Recent Conversation #51'),
      { timeout: 10000 }
    );

    // Wait for non-empty USER and ASSISTANT messages to render in thread DOM
    await page.waitForSelector('copilot-chat-user-message', { timeout: 5000 });
    await page.waitForSelector('copilot-chat-assistant-message', { timeout: 5000 });

    const baselineThread = await getThreadSnapshot(page);
    console.log(`✓ Non-empty thread verified for u-50: ${baselineThread.userMessages.length} user msg, ${baselineThread.assistantMessages.length} asst msg.`);
    if (baselineThread.userMessages.length === 0 || baselineThread.assistantMessages.length === 0) {
      throw new Error('u-50 must have non-empty user and assistant messages!');
    }

    const textareaSelector = 'textarea';
    await page.type(textareaSelector, TEST_DRAFT);
    const setDraft = await page.$eval(textareaSelector, el => el.value);
    console.log(`✓ Draft established for u-50: "${setDraft}"`);

    // 8. Range Reconciliation on First Pin (u-0)
    console.log('\n--- Pinning Conversation u-0 & Reconciling Range ---');
    queryCalls.length = 0;
    mutationCalls.length = 0;

    await page.evaluate(() => {
      const btn = document.querySelector('[data-testid="pin-btn-u-0"] button') || document.querySelector('[data-testid="pin-btn-u-0"]');
      if (btn) {
        btn.scrollIntoView({ block: 'center' });
        btn.click();
      }
    });

    // Wait until u-0 appears in pinned list
    await page.waitForFunction(
      () => document.querySelector('.pinned-list-container .conversation-item[data-id="u-0"]') !== null,
      { timeout: 5000 }
    );

    // Assert strictly capped limit <= 100 for all queries
    console.log(`Analyzing reconciliation queries (total ${queryCalls.length} queries issued)...`);
    for (const q of queryCalls) {
      if (q.limit > 100) {
        throw new Error(`Server cap violated! Query requested limit=${q.limit} (> 100)`);
      }
    }
    console.log('✓ All reconciliation queries strictly respected server cap limit <= 100.');

    // Assert exact batch offsets and limits
    const pinnedQueries = queryCalls.filter(q => q.pinned);
    const unpinnedQueries = queryCalls.filter(q => !q.pinned);

    // Pinned target 105: batches offset 0 (limit 100), offset 100 (limit 5), peek offset 105 (limit 1)
    if (!pinnedQueries.some(q => q.offset === 0 && q.limit === 100) ||
        !pinnedQueries.some(q => q.offset === 100 && q.limit === 5) ||
        !pinnedQueries.some(q => q.offset === 105 && q.limit === 1)) {
      throw new Error(`Pinned queries did not match expected batch ranges: ${JSON.stringify(pinnedQueries)}`);
    }

    // Unpinned target 120: batches offset 0 (limit 100), offset 100 (limit 20)
    if (!unpinnedQueries.some(q => q.offset === 0 && q.limit === 100) ||
        !unpinnedQueries.some(q => q.offset === 100 && q.limit === 20)) {
      throw new Error(`Unpinned queries did not match expected batch ranges: ${JSON.stringify(unpinnedQueries)}`);
    }
    console.log('✓ Exact query batch offsets (0, 100, 105 peek) verified.');

    // Reconciled display: 105 pinned (u-0, p-0..p-103) and 119 recent (u-1..u-119)
    const recPinnedIds = await page.$$eval('.pinned-list-container .conversation-item', els => els.map(e => e.dataset.id));
    const recUnpinnedIds = await page.$$eval('.unpinned-list-container .conversation-item', els => els.map(e => e.dataset.id));

    assertIdListMatch('Reconciled Pinned (105)', recPinnedIds, state.pinned.slice(0, 105).map(c => c.id));
    assertIdListMatch('Reconciled Recent (119)', recUnpinnedIds, state.unpinned.slice(0, 119).map(c => c.id));
    console.log(`✓ Reconciled counts: pinned=105 (server 106), recent=119 (server 119).`);

    // Verify pinnedHasMore is true (server has 106, 105 displayed)
    const hasMorePinnedAfterPin0 = await page.$('[data-testid="load-more-pinned-btn"]') !== null;
    if (!hasMorePinnedAfterPin0) {
      throw new Error('pinnedHasMore must be true after u-0 pin (server has 106 items)!');
    }
    console.log('✓ pinnedHasMore verified true (server has 106 total pinned).');

    // Assert draft, active selection header, and thread messages strictly preserved
    const activeHeader = await page.$eval('.header-title', el => el.textContent?.trim());
    const preservedDraft = await page.$eval(textareaSelector, el => el.value);
    if (!activeHeader.includes('Recent Conversation #51')) {
      throw new Error(`Selection lost! Expected '#51', got '${activeHeader}'`);
    }
    if (preservedDraft !== TEST_DRAFT) {
      throw new Error(`Draft corrupted! Expected '${TEST_DRAFT}', got '${preservedDraft}'`);
    }
    const threadAfterPin0 = await getThreadSnapshot(page);
    assertThreadMatch('Post-Pin0 Thread', threadAfterPin0, baselineThread);
    console.log('✓ Selection, draft, and thread messages preserved 100% intact after range reconciliation.');
    await page.screenshot({ path: SCREENSHOTS.pinnedReconciled, fullPage: true });

    // 9. Controlled Browser Stale Injection Barrier (Trapping loadMorePinned, Snapshotting 200, Released into Failure)
    console.log('\n--- Testing Controlled Browser Stale Injection Barrier (Snapshot HTTP 200) ---');
    // Arm controlled barrier for pinned pagination (limit 21, offset 105)
    isStalePaginationHeld = false;
    stalePaginationResponseDelivered = false;
    stalePaginationBarrier = new Promise((resolve) => {
      resolveStalePaginationBarrier = resolve;
    });

    console.log('Triggering loadMore pinned conversations (trapped at controlled barrier)...');
    await page.click('[data-testid="load-more-pinned-btn"]');
    const waitStart = Date.now();
    while (!isStalePaginationHeld && Date.now() - waitStart < 5000) {
      await new Promise(r => setTimeout(r, 50));
    }
    if (!isStalePaginationHeld) {
      throw new Error('Timed out waiting for isStalePaginationHeld barrier');
    }
    console.log('✓ In-flight loadMorePinned request held at controlled barrier (HTTP 200 payload snapshotted).');

    // Verify loading state is active in browser
    await page.waitForFunction(() => {
      const host = document.querySelector('[data-testid="load-more-pinned-btn"]');
      const btn = host?.querySelector('button');
      const text = document.querySelector('.load-more-pinned-container .action-btn-text')?.textContent?.trim();
      return btn?.disabled === true || text === 'Loading...';
    }, { timeout: 3000 });
    console.log('✓ Load more pinned button in active loading state.');

    // While loadMorePinned is trapped in barrier, perform pin mutation on u-1 with refetch failure injected!
    console.log('Pinning conversation u-1 with refetch failure injected while loadMorePinned is trapped...');
    const preFailPinnedIds = await page.$$eval('.pinned-list-container .conversation-item', els => els.map(e => e.dataset.id));
    const preFailUnpinnedIds = await page.$$eval('.unpinned-list-container .conversation-item', els => els.map(e => e.dataset.id));

    faultInjectRefetchFailure = true;
    queryCalls.length = 0;
    mutationCalls.length = 0;

    await page.evaluate(() => {
      const btn = document.querySelector('[data-testid="pin-btn-u-1"] button') || document.querySelector('[data-testid="pin-btn-u-1"]');
      if (btn) {
        btn.scrollIntoView({ block: 'center' });
        btn.click();
      }
    });

    // Wait for pin refresh error banner to appear
    await page.waitForSelector('[data-testid="pin-refresh-error"]', { timeout: 5000 });
    const bannerText = await page.$eval('[data-testid="pin-refresh-error"] .pin-error-text', el => el.textContent?.trim());
    console.log(`✓ Pin refresh error banner rendered: "${bannerText}"`);
    if (!bannerText.includes('已儲存釘選狀態，但列表重新載入失敗')) {
      throw new Error(`Unexpected error text: ${bannerText}`);
    }

    // Verify accessibility role and container class
    const bannerRole = await page.$eval('[data-testid="pin-refresh-error"]', el => el.getAttribute('role'));
    if (bannerRole !== 'alert') {
      throw new Error(`Expected role="alert" on pin-refresh-error banner, got "${bannerRole}"`);
    }
    const hasPinBannerClass = await page.$eval('[data-testid="pin-refresh-error"]', el => el.classList.contains('pin-error-banner'));
    if (!hasPinBannerClass) {
      throw new Error('Expected pin-refresh-error banner to have class "pin-error-banner"');
    }
    console.log('✓ Accessibility role="alert" and class "pin-error-banner" verified.');

    // Verify sidebar remains deeply scrolled (scrollTop > 0)
    const sidebarScrollTop = await page.$eval('.sidebar', el => el.scrollTop);
    console.log(`Sidebar scrollTop during failure: ${sidebarScrollTop}px`);
    if (sidebarScrollTop <= 0) {
      throw new Error(`Expected sidebar to remain deeply scrolled (scrollTop > 0), but got ${sidebarScrollTop}`);
    }

    // Assert pin error banner and native retry button are fully visible within viewport WITHOUT scrollIntoView
    const bannerBox1280 = await page.$eval('[data-testid="pin-refresh-error"]', el => {
      const rect = el.getBoundingClientRect();
      return { top: rect.top, bottom: rect.bottom, left: rect.left, right: rect.right, width: rect.width, height: rect.height };
    });
    const retryBtnBox1280 = await page.$eval('[data-testid="pin-refresh-retry-btn"]', el => {
      const rect = el.getBoundingClientRect();
      return { top: rect.top, bottom: rect.bottom, left: rect.left, right: rect.right, width: rect.width, height: rect.height };
    });
    const vp1280 = page.viewport();
    console.log(`1280x800 Viewport: banner top=${bannerBox1280.top}, bottom=${bannerBox1280.bottom}, left=${bannerBox1280.left}, right=${bannerBox1280.right}`);
    console.log(`1280x800 Viewport: retry btn top=${retryBtnBox1280.top}, bottom=${retryBtnBox1280.bottom}, left=${retryBtnBox1280.left}, right=${retryBtnBox1280.right}`);

    if (bannerBox1280.top < 0 || bannerBox1280.bottom > vp1280.height || bannerBox1280.left < 0 || bannerBox1280.right > vp1280.width) {
      throw new Error(`Pin refresh error banner is outside the 1280 visible viewport! ${JSON.stringify(bannerBox1280)}`);
    }
    if (retryBtnBox1280.top < 0 || retryBtnBox1280.bottom > vp1280.height || retryBtnBox1280.left < 0 || retryBtnBox1280.right > vp1280.width) {
      throw new Error(`Pin refresh retry button is outside the 1280 visible viewport! ${JSON.stringify(retryBtnBox1280)}`);
    }
    console.log('✓ 1280x800: Pin refresh error banner and retry button verified fully within viewport while sidebar is deeply scrolled.');

    // NOW release the controlled barrier: unconditionally returns HTTP 200 snapshotted payload
    console.log('Releasing controlled barrier: stale pagination response unconditionally delivers HTTP 200 snapshot...');
    resolveStalePaginationBarrier();
    stalePaginationBarrier = null;

    // Await actual network response completion
    const waitDelivery = Date.now();
    while (!stalePaginationResponseDelivered && Date.now() - waitDelivery < 5000) {
      await new Promise(r => setTimeout(r, 50));
    }
    if (!stalePaginationResponseDelivered) {
      throw new Error('Timed out waiting for stalePaginationResponseDelivered');
    }
    console.log('✓ Stale HTTP 200 pagination response delivered to browser network layer.');

    // Await double animation frame + microtask turn in browser to allow Angular to process response if not guarded
    await page.evaluate(async () => {
      await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
      await Promise.resolve();
    });

    // Verify pin buttons are disabled during pending refresh
    const pinBtnDisabled = await page.$eval('[data-testid="pin-btn-u-2"] button', b => b.disabled);
    if (!pinBtnDisabled) {
      throw new Error('Pin buttons must be disabled while refresh is pending!');
    }
    console.log('✓ Pin buttons successfully locked while refresh is pending.');

    // Verify lists retained 105 / 119 and DOM IDs are 100% IDENTICAL before and after failure
    const postFailPinnedIds = await page.$$eval('.pinned-list-container .conversation-item', els => els.map(e => e.dataset.id));
    const postFailUnpinnedIds = await page.$$eval('.unpinned-list-container .conversation-item', els => els.map(e => e.dataset.id));

    assertIdListMatch('Post-Fail Pinned (Retained)', postFailPinnedIds, preFailPinnedIds);
    assertIdListMatch('Post-Fail Recent (Retained)', postFailUnpinnedIds, preFailUnpinnedIds);
    console.log('✓ Atomic failure guard verified: lists retained 105/119, DOM IDs 100% identical before and after failure.');
    console.log('✓ Stale guard proven: late HTTP 200 response strictly discarded, zero stale items appended.');

    // Verify thread messages, header title, and draft 100% identical
    const threadAfterFail = await getThreadSnapshot(page);
    assertThreadMatch('Post-Fail Thread', threadAfterFail, baselineThread);
    const draftAfterFail = await page.$eval(textareaSelector, el => el.value);
    if (draftAfterFail !== TEST_DRAFT) {
      throw new Error('Draft corrupted after refetch failure!');
    }
    console.log('✓ Thread messages, header title, and draft 100% preserved after failure.');

    // Capture failure screenshots: default 1280x800 and mobile 390x844
    await page.screenshot({ path: SCREENSHOTS.refetchFailed, fullPage: false });
    await page.screenshot({ path: '/tmp/bot-ui-12-04-refetch-failed-banner-1280.png', fullPage: false });
    console.log('✓ Captured 1280 failure screenshots (viewport).');

    // Switch to 390x844 mobile viewport, assert visibility, and capture screenshot
    await page.setViewport({ width: 390, height: 844 });
    await page.evaluate(async () => {
      await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
    });

    const bannerBox390 = await page.$eval('[data-testid="pin-refresh-error"]', el => {
      const rect = el.getBoundingClientRect();
      return { top: rect.top, bottom: rect.bottom, left: rect.left, right: rect.right, width: rect.width, height: rect.height };
    });
    const retryBtnBox390 = await page.$eval('[data-testid="pin-refresh-retry-btn"]', el => {
      const rect = el.getBoundingClientRect();
      return { top: rect.top, bottom: rect.bottom, left: rect.left, right: rect.right, width: rect.width, height: rect.height };
    });
    console.log(`390x844 Viewport: banner top=${bannerBox390.top}, bottom=${bannerBox390.bottom}, left=${bannerBox390.left}, right=${bannerBox390.right}`);
    console.log(`390x844 Viewport: retry btn top=${retryBtnBox390.top}, bottom=${retryBtnBox390.bottom}, left=${retryBtnBox390.left}, right=${retryBtnBox390.right}`);

    if (bannerBox390.top < 0 || bannerBox390.bottom > 844 || bannerBox390.left < 0 || bannerBox390.right > 390) {
      throw new Error(`Pin refresh error banner is outside 390x844 viewport! ${JSON.stringify(bannerBox390)}`);
    }
    if (retryBtnBox390.top < 0 || retryBtnBox390.bottom > 844 || retryBtnBox390.left < 0 || retryBtnBox390.right > 390) {
      throw new Error(`Pin refresh retry button is outside 390x844 viewport! ${JSON.stringify(retryBtnBox390)}`);
    }
    await page.screenshot({ path: '/tmp/bot-ui-12-04-refetch-failed-banner-390.png', fullPage: false });
    console.log('✓ Captured 390 failure screenshot (viewport) and verified visible.');

    // Restore viewport to 1280x800 for subsequent steps
    await page.setViewport({ width: 1280, height: 800 });
    await page.evaluate(async () => {
      await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
    });

    // 10. Retry Refetch: Query-Only (0 Mutations)
    console.log('\n--- Retrying Failed Refetch ---');
    faultInjectRefetchFailure = false;
    queryCalls.length = 0;
    mutationCalls.length = 0;

    await page.click('[data-testid="pin-refresh-retry-btn"] button');

    // Wait for banner to disappear
    await page.waitForFunction(
      () => document.querySelector('[data-testid="pin-refresh-error"]') === null,
      { timeout: 5000 }
    );

    // Verify ZERO mutations sent during retry
    if (mutationCalls.length !== 0) {
      throw new Error(`Retrying refresh unexpectedly sent ${mutationCalls.length} mutations! Must be query-only.`);
    }
    console.log('✓ Retry refresh executed strictly 0 mutations (query-only).');

    // Display after retry must be 105 pinned, 118 recent, and pinnedHasMore=true
    const postRetryPinnedIds = await page.$$eval('.pinned-list-container .conversation-item', els => els.map(e => e.dataset.id));
    const postRetryUnpinnedIds = await page.$$eval('.unpinned-list-container .conversation-item', els => els.map(e => e.dataset.id));

    assertIdListMatch('Post-Retry Pinned (105)', postRetryPinnedIds, state.pinned.slice(0, 105).map(c => c.id));
    assertIdListMatch('Post-Retry Recent (118)', postRetryUnpinnedIds, state.unpinned.slice(0, 118).map(c => c.id));

    // Verify u-1 is placed right after u-0
    if (postRetryPinnedIds[0] !== 'u-0' || postRetryPinnedIds[1] !== 'u-1') {
      throw new Error(`Chronological sequence violated! Expected [u-0, u-1], got [${postRetryPinnedIds[0]}, ${postRetryPinnedIds[1]}]`);
    }
    console.log('✓ Chronological sequence verified: u-1 correctly placed after u-0 in pinned list.');

    // Verify pinnedHasMore is true (server has 107 total pinned)
    const hasMorePinnedVisible = await page.$('[data-testid="load-more-pinned-btn"]') !== null;
    if (!hasMorePinnedVisible) {
      throw new Error('pinnedHasMore must be true after retry refresh (server has 107 total pinned)!');
    }
    console.log('✓ pinnedHasMore verified true (server has 107 total pinned).');

    // Verify thread messages, draft and selection still intact
    const postRetryDraft = await page.$eval(textareaSelector, el => el.value);
    if (postRetryDraft !== TEST_DRAFT) {
      throw new Error('Draft corrupted after retry refresh!');
    }
    const threadAfterRetry = await getThreadSnapshot(page);
    assertThreadMatch('Post-Retry Thread', threadAfterRetry, baselineThread);
    console.log('✓ Thread messages, selection, and draft preserved intact after retry refresh.');
    await page.screenshot({ path: SCREENSHOTS.retrySuccess, fullPage: true });

    // 11. Load More Pinned to reach authoritative 107 items
    console.log('\n--- Loading More Pinned to Reach Authoritative 107 Items ---');
    await page.click('[data-testid="load-more-pinned-btn"]');
    await page.waitForFunction(
      () => document.querySelectorAll('.pinned-list-container .conversation-item').length === 107,
      { timeout: 5000 }
    );
    const fullPinnedIds = await page.$$eval('.pinned-list-container .conversation-item', els => els.map(e => e.dataset.id));
    assertIdListMatch('Full Pinned (107)', fullPinnedIds, state.pinned.map(c => c.id));
    console.log('✓ Full 107 pinned conversations loaded and verified 1-by-1 against state pool.');

    // Verify thread messages, draft, and header still intact after full load
    const threadAfterFull = await getThreadSnapshot(page);
    assertThreadMatch('Full-Load Thread', threadAfterFull, baselineThread);
    const draftAfterFull = await page.$eval(textareaSelector, el => el.value);
    if (draftAfterFull !== TEST_DRAFT) {
      throw new Error('Draft corrupted after full load!');
    }
    console.log('✓ Thread messages and draft 100% intact after full load.');

    // Verify load-more-pinned button is now gone
    const hasMorePinnedAfter = await page.$('[data-testid="load-more-pinned-btn"]') !== null;
    if (hasMorePinnedAfter) {
      throw new Error('load-more-pinned button should be hidden after loading all 107 items!');
    }
    console.log('✓ load-more-pinned button hidden now that all 107 items are loaded.');
    await page.screenshot({ path: SCREENSHOTS.authoritative107, fullPage: true });

    // 12. Guard Integrity Check
    const stats = getGuardStats();
    console.log(`✓ Guard statistics: blockedCalls=${stats.blockedCalls}, allowedNonGen=${stats.allowedNonGenerationCalls}`);
    if (stats.blockedCalls > 0) {
      throw new Error('Generation calls were unexpectedly attempted during verification!');
    }

    console.log('\n=============================================================');
    console.log('🎉 Issue #12 Pagination & Range Reconciliation Verified 100%!');
    console.log('=============================================================');
  } finally {
    await browser.close();
  }
}

function assertIdListMatch(name, actual, expected) {
  if (actual.length !== expected.length) {
    throw new Error(`[${name}] Length mismatch: actual=${actual.length}, expected=${expected.length}`);
  }
  for (let i = 0; i < actual.length; i++) {
    if (actual[i] !== expected[i]) {
      throw new Error(`[${name}] Mismatch at index ${i}: actual="${actual[i]}", expected="${expected[i]}"`);
    }
  }
}

async function getThreadSnapshot(page) {
  return page.evaluate(() => {
    const userEls = Array.from(document.querySelectorAll('copilot-chat-user-message'));
    const asstEls = Array.from(document.querySelectorAll('copilot-chat-assistant-message'));
    return {
      userMessages: userEls.map(e => e.textContent?.trim() || ''),
      assistantMessages: asstEls.map(e => e.textContent?.trim() || '')
    };
  });
}

function assertThreadMatch(name, actual, expected) {
  if (actual.userMessages.length !== expected.userMessages.length ||
      actual.assistantMessages.length !== expected.assistantMessages.length) {
    throw new Error(`[${name}] Thread message count mismatch: actual=(${actual.userMessages.length}, ${actual.assistantMessages.length}), expected=(${expected.userMessages.length}, ${expected.assistantMessages.length})`);
  }
  for (let i = 0; i < actual.userMessages.length; i++) {
    if (actual.userMessages[i] !== expected.userMessages[i]) {
      throw new Error(`[${name}] User message content corrupted at index ${i}`);
    }
  }
  for (let i = 0; i < actual.assistantMessages.length; i++) {
    if (actual.assistantMessages[i] !== expected.assistantMessages[i]) {
      throw new Error(`[${name}] Assistant message content corrupted at index ${i}`);
    }
  }
}

run().catch((err) => {
  console.error('\n❌ Browser Verification Failed:', err);
  process.exit(1);
});
