// Historical layout verification retained for coverage traceability; see README.
throw new Error('Retired legacy browser verification; use the current ThreadPlane browser matrix.');

import puppeteer from 'puppeteer-core';
import http from 'node:http';
import fs from 'node:fs';

const CHROME_PATH = process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const FRONTEND_URL = process.env.VERIFICATION_FRONTEND_URL || 'http://localhost:4300';
const BACKEND_URL = process.env.VERIFICATION_BACKEND_URL || 'http://localhost:8181';

const SCREENSHOTS = {
  bot1Draft: '/tmp/issue18-01-bot1-draft.png',
  bot2DraftIsolated: '/tmp/issue18-02-bot2-draft-isolated.png',
  newChatDefault: '/tmp/issue18-03-new-chat-default.png',
  bot1DraftRestored: '/tmp/issue18-04-bot1-draft-restored.png',
  simulationSubmitChat: '/tmp/issue18-05-simulation-submit-chat.png',
  cancelDraftRestored: '/tmp/issue18-06-cancel-draft-restored.png',
  errorDraftRestored: '/tmp/issue18-07-error-draft-restored.png',
  offModeDisabled: '/tmp/issue18-08-off-mode-disabled.png',
  reloadClean: '/tmp/issue18-09-reload-clean.png',
  overflow1280: '/tmp/issue18-overflow-1280.png',
  overflow375: '/tmp/issue18-overflow-375.png'
};

const results = [];

function recordResult(stepName, passed, details = '') {
  results.push({ stepName, passed, details });
  const statusMark = passed ? '✅ PASS' : '❌ FAIL';
  console.log(`${statusMark} - ${stepName} ${details ? '(' + details + ')' : ''}`);
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

async function checkUrlReady(url, maxTimeoutMs = 30000) {
  const startTime = Date.now();
  while (Date.now() - startTime < maxTimeoutMs) {
    try {
      const isReady = await new Promise((resolve) => {
        const req = http.get(url, (res) => {
          resolve(res.statusCode >= 200 && res.statusCode < 400);
        });
        req.on('error', () => resolve(false));
        req.setTimeout(2000, () => {
          req.destroy();
          resolve(false);
        });
      });
      if (isReady) return true;
    } catch {
      // retry
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  return false;
}

async function setInputValue(page, selector, value) {
  await page.waitForSelector(selector, { timeout: 5000 });
  await page.evaluate((sel, val) => {
    const el = document.querySelector(sel);
    if (el) {
      el.focus();
      el.value = val;
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    }
  }, selector, value);
}

async function getInputValue(page, selector) {
  await page.waitForSelector(selector, { timeout: 5000 });
  return await page.evaluate((sel) => {
    const el = document.querySelector(sel);
    return el ? el.value : '';
  }, selector);
}

async function queryBackendGraphQL(query, variables = {}) {
  const res = await fetch(`${BACKEND_URL}/graphql`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query, variables })
  });
  if (!res.ok) {
    throw new Error(`GraphQL query to ${BACKEND_URL} failed with status ${res.status}`);
  }
  return await res.json();
}

export async function runVerification() {
  console.log('=== Issue #18 Bot Drafts Independent Verification ===');
  console.log(`Checking Frontend (${FRONTEND_URL}) and Backend (${BACKEND_URL}) readiness...`);

  let currentStep = 'Service Readiness Check';

  const frontendReady = await checkUrlReady(FRONTEND_URL);
  if (!frontendReady) {
    recordResult(currentStep, false, `Frontend at ${FRONTEND_URL} not ready within timeout`);
    printSummary();
    return false;
  }

  const backendReady = await checkUrlReady(`${BACKEND_URL}/actuator/health`) || await checkUrlReady(`${BACKEND_URL}/graphql`);
  if (!backendReady) {
    recordResult(currentStep, false, `Backend at ${BACKEND_URL} not ready within timeout`);
    printSummary();
    return false;
  }

  recordResult(currentStep, true, 'Frontend 4300 and Backend 8181 are active');

  console.log(`Launching Chrome headless at: ${CHROME_PATH}`);
  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage', '--window-size=1280,900']
  });

  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 900 });

  // Network tracking state
  const interceptedCreateMutations = [];
  const interceptedCreatedConvs = [];
  const interceptedRunRequests = [];
  const interceptedCancelRequests = [];
  let targetedOffBotId = null;

  await page.setRequestInterception(true);
  page.on('request', async (req) => {
    const url = req.url();
    const method = req.method();

    if (url.includes('/graphql') && method === 'POST') {
      try {
        const postData = JSON.parse(req.postData() || '{}');
        const query = postData.query || '';

        if (query.includes('createConversation')) {
          interceptedCreateMutations.push(postData);
        }

        // Intercept GraphQL queries to proxy backend and mutate targetedOffBotId into genuine OFF mode
        if (targetedOffBotId && (query.includes('bots') || query.includes('bot('))) {
          const proxyResp = await fetch(`${BACKEND_URL}/graphql`, {
            method: 'POST',
            headers: req.headers(),
            body: req.postData()
          });
          const json = await proxyResp.json();

          if (json.data?.bots) {
            for (const b of json.data.bots) {
              if (b.id === targetedOffBotId) {
                b.canChat = false;
                b.executionMode = 'UNAVAILABLE';
                b.statusReason = 'GKE 專案與叢集尚未配置';
              }
            }
          }
          if (json.data?.bot && json.data.bot.id === targetedOffBotId) {
            json.data.bot.canChat = false;
            json.data.bot.executionMode = 'UNAVAILABLE';
            json.data.bot.statusReason = 'GKE 專案與叢集尚未配置';
          }

          req.respond({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify(json)
          });
          return;
        }
      } catch (err) {
        console.error('Request interception error on /graphql:', err);
      }
    }

    if (url.includes('/runs') && method === 'POST') {
      try {
        if (url.endsWith('/cancel')) {
          interceptedCancelRequests.push({ url, timestamp: Date.now() });
        } else {
          const body = JSON.parse(req.postData() || '{}');
          interceptedRunRequests.push({ url, body, timestamp: Date.now() });
        }
      } catch (err) {
        console.error('Request interception error on /runs:', err);
      }
    }

    req.continue();
  });

  page.on('response', async (res) => {
    try {
      const url = res.url();
      if (url.includes('/graphql') && res.request().method() === 'POST') {
        const json = await res.clone().json().catch(() => null);
        if (json?.data?.createConversation?.id) {
          interceptedCreatedConvs.push(json.data.createConversation);
        }
      }
    } catch {
      // ignore
    }
  });

  const textareaSelector = 'textarea[data-testid="copilot-chat-textarea"], textarea[copilotChatTextarea], textarea';
  const sendBtnContainerSelector = '[data-testid="send-message-btn"]';
  const stopBtnSelector = '[data-testid="stop-btn"] button, [data-testid="stop-btn"], [data-testid="stop-message-btn"] button, [data-testid="stop-message-btn"]';

  try {
    // ---------------------------------------------------------------------------------
    // STEP 1: Discovery of GKE Bots
    // ---------------------------------------------------------------------------------
    currentStep = 'Bot Discovery';
    await page.goto(FRONTEND_URL, { waitUntil: 'networkidle0' });
    await page.waitForSelector('[data-testid="bot-manager-nav-btn"]', { timeout: 10000 });
    await page.click('[data-testid="bot-manager-nav-btn"]');
    await page.waitForSelector('.bot-manager-container', { timeout: 5000 });

    const backendBotsResp = await queryBackendGraphQL(`
      query {
        bots(target: GKE_REMOTE, limit: 10) {
          id
          name
          target
          executionMode
          canChat
        }
      }
    `);
    const availableGkeBots = backendBotsResp.data?.bots || [];
    assert(availableGkeBots.length >= 2, `Require at least 2 GKE_REMOTE bots in backend, found ${availableGkeBots.length}`);

    const bot1 = availableGkeBots[0];
    const bot2 = availableGkeBots[1];
    recordResult(currentStep, true, `Bot 1: ${bot1.id} (${bot1.name}), Bot 2: ${bot2.id} (${bot2.name})`);

    // ---------------------------------------------------------------------------------
    // STEP 2: Open Bot 1 Draft (Zero Create & Set Raw Draft with Leading/Trailing Spaces & Newlines)
    // ---------------------------------------------------------------------------------
    currentStep = 'Open Bot 1 Draft (Zero Create & Set Raw Draft)';
    interceptedCreateMutations.length = 0;

    const openDraft1Btn = `[data-testid="open-draft-btn-${bot1.id}"]`;
    await page.waitForSelector(openDraft1Btn, { timeout: 5000 });
    await page.click(openDraft1Btn);

    // Wait for Angular change detection to update header-bot-name to bot1.name
    await page.waitForFunction((expectedName) => {
      const el = document.querySelector('[data-testid="header-bot-name"]');
      return el && el.textContent?.trim() === expectedName;
    }, { timeout: 5000 }, bot1.name);

    const hasSimBanner1 = await page.$eval('[data-testid="simulation-banner"]', el => !!el).catch(() => false);
    assert(hasSimBanner1, 'simulation-banner not found for GKE bot in SIMULATION mode');
    assert(interceptedCreateMutations.length === 0, `Expected 0 create mutations, got ${interceptedCreateMutations.length}`);

    const rawDraft1 = '\n  Bot 1 Draft text:\n  first line indented  \n  second line trailing spaces   \n';
    await setInputValue(page, textareaSelector, rawDraft1);

    const inputVal1 = await getInputValue(page, textareaSelector);
    assert(inputVal1 === rawDraft1, `Expected textarea value to match rawDraft1 exactly (${rawDraft1.length} chars), got ${inputVal1.length} chars`);

    await page.screenshot({ path: SCREENSHOTS.bot1Draft });
    recordResult(currentStep, true, `name=${bot1.name}, mutations=${interceptedCreateMutations.length}, rawLength=${rawDraft1.length}`);

    // ---------------------------------------------------------------------------------
    // STEP 2b: Long Bot Name Viewport Overflow & Layout Boundary Check (1280px & 375px)
    // ---------------------------------------------------------------------------------
    currentStep = 'Long Bot Name Viewport Overflow & Layout Boundary Check (1280px & 375px)';

    let overflow1280;
    let overflow375;
    try {
      // 1280px check
      overflow1280 = await page.evaluate(() => {
        const doc = document.documentElement;
        return {
          scrollWidth: doc.scrollWidth,
          clientWidth: doc.clientWidth,
          hasOverflow: doc.scrollWidth > doc.clientWidth
        };
      });
      await page.screenshot({ path: SCREENSHOTS.overflow1280 });

      // 375px mobile check
      await page.setViewport({ width: 375, height: 667 });
      await new Promise(r => setTimeout(r, 400));

      overflow375 = await page.evaluate(() => {
        const doc = document.documentElement;
        const sidebar = document.querySelector('.sidebar');
        const header = document.querySelector('.header') || document.querySelector('header');
        const banner = document.querySelector('[data-testid="simulation-banner"]') || document.querySelector('.simulation-banner');
        const badge = document.querySelector('[data-testid="header-execution-mode"]') || document.querySelector('.header-mode-badge');

        const s = sidebar?.getBoundingClientRect();
        const h = header?.getBoundingClientRect();
        const b = banner?.getBoundingClientRect();
        const bg = badge?.getBoundingClientRect();

        const tolerance = 2; // px margin tolerance

        const sidebarBelow = (s && h) ? s.bottom <= h.top + tolerance : true;
        const headerBelow = (h && b) ? h.bottom <= b.top + tolerance : true;
        const badgeInHeader = (h && bg) ? (
          bg.top >= h.top - tolerance &&
          bg.bottom <= h.bottom + tolerance &&
          bg.left >= h.left - tolerance &&
          bg.right <= h.right + tolerance
        ) : false;

        return {
          scrollWidth: doc.scrollWidth,
          clientWidth: doc.clientWidth,
          hasOverflow: doc.scrollWidth > doc.clientWidth,
          headerScrollWidth: header?.scrollWidth,
          headerClientWidth: header?.clientWidth,
          sidebarBottom: s?.bottom,
          headerTop: h?.top,
          headerBottom: h?.bottom,
          bannerTop: b?.top,
          badgeTop: bg?.top,
          badgeBottom: bg?.bottom,
          sidebarBelow,
          headerBelow,
          badgeInHeader
        };
      });
      await page.screenshot({ path: SCREENSHOTS.overflow375 });
    } finally {
      // Always restore 1280px viewport before continuing remaining flow
      await page.setViewport({ width: 1280, height: 900 });
      await new Promise(r => setTimeout(r, 400));
    }

    assert(!overflow1280.hasOverflow,
      `Horizontal overflow at 1280px: scrollWidth=${overflow1280.scrollWidth} > clientWidth=${overflow1280.clientWidth}`);
    assert(!overflow375.hasOverflow,
      `Horizontal overflow at 375px: scrollWidth=${overflow375.scrollWidth} > clientWidth=${overflow375.clientWidth} (header: ${overflow375.headerScrollWidth}/${overflow375.headerClientWidth})`);
    assert(overflow375.sidebarBelow,
      `Mobile layout error: sidebar overlaps header (sidebar.bottom=${overflow375.sidebarBottom} > header.top=${overflow375.headerTop})`);
    assert(overflow375.headerBelow,
      `Mobile layout error: header overlaps banner (header.bottom=${overflow375.headerBottom} > banner.top=${overflow375.bannerTop})`);
    assert(overflow375.badgeInHeader,
      `Mobile layout error: badge overflows header bounds (badge: top=${overflow375.badgeTop}, bottom=${overflow375.badgeBottom} vs header: top=${overflow375.headerTop}, bottom=${overflow375.headerBottom})`);

    recordResult(currentStep, true,
      `1280px=${overflow1280.clientWidth}px, 375px=${overflow375.clientWidth}px, sidebarBelow=${overflow375.sidebarBelow}, headerBelow=${overflow375.headerBelow}, badgeInHeader=${overflow375.badgeInHeader}`);

    // ---------------------------------------------------------------------------------
    // STEP 3: Switch to Bot Manager -> Open Bot 2 Draft (Zero Create & Strict Isolation)
    // ---------------------------------------------------------------------------------
    currentStep = 'Open Bot 2 Draft (Zero Create & Strict Isolation)';
    interceptedCreateMutations.length = 0;
    await page.click('[data-testid="bot-manager-nav-btn"]');
    await page.waitForSelector('.bot-manager-container', { timeout: 5000 });

    const openDraft2Btn = `[data-testid="open-draft-btn-${bot2.id}"]`;
    await page.waitForSelector(openDraft2Btn, { timeout: 5000 });
    await page.click(openDraft2Btn);

    // Wait for Angular change detection to update header-bot-name to bot2.name
    await page.waitForFunction((expectedName) => {
      const el = document.querySelector('[data-testid="header-bot-name"]');
      return el && el.textContent?.trim() === expectedName;
    }, { timeout: 5000 }, bot2.name);

    assert(interceptedCreateMutations.length === 0, `Expected 0 create mutations, got ${interceptedCreateMutations.length}`);

    // Bot 2 canvas must be clean and not contaminated by Bot 1 draft
    const initialBot2Draft = await getInputValue(page, textareaSelector);
    assert(initialBot2Draft === '', `Expected clean draft for Bot 2, got "${initialBot2Draft}"`);

    const rawDraft2 = '\n  Bot 2 Isolated Raw Draft:\n  Item A with spaces   \n  Item B indented  \n';
    await setInputValue(page, textareaSelector, rawDraft2);

    const inputVal2 = await getInputValue(page, textareaSelector);
    assert(inputVal2 === rawDraft2, `Expected textarea value to match rawDraft2 exactly (${rawDraft2.length} chars), got ${inputVal2.length} chars`);

    await page.screenshot({ path: SCREENSHOTS.bot2DraftIsolated });
    recordResult(currentStep, true, `name=${bot2.name}, initialClean=true, mutations=${interceptedCreateMutations.length}, rawLength=${rawDraft2.length}`);

    // ---------------------------------------------------------------------------------
    // STEP 4: Switch to + New Chat (Default Local Bot Draft Isolated)
    // ---------------------------------------------------------------------------------
    currentStep = 'Switch to + New Chat (Default Local Bot Draft Isolated)';
    interceptedCreateMutations.length = 0;
    await page.click('[data-testid="new-chat-btn"] button');

    // Wait for Angular change detection to update header-bot-target to [LOCAL]
    await page.waitForFunction(() => {
      const el = document.querySelector('[data-testid="header-bot-target"]');
      return el && el.textContent?.trim() === '[LOCAL]';
    }, { timeout: 5000 });

    assert(interceptedCreateMutations.length === 0, `Expected 0 create mutations, got ${interceptedCreateMutations.length}`);

    const defaultDraftText = await getInputValue(page, textareaSelector);
    assert(defaultDraftText === '', `Expected clean local draft, got "${defaultDraftText}"`);

    const rawDefaultDraft = '\n  Local Default Bot Draft:\n  indented line   \n';
    await setInputValue(page, textareaSelector, rawDefaultDraft);

    const inputValDefault = await getInputValue(page, textareaSelector);
    assert(inputValDefault === rawDefaultDraft, `Expected textarea value to match rawDefaultDraft exactly (${rawDefaultDraft.length} chars), got ${inputValDefault.length} chars`);

    await page.screenshot({ path: SCREENSHOTS.newChatDefault });
    recordResult(currentStep, true, `target=[LOCAL], initialClean=true, mutations=${interceptedCreateMutations.length}`);

    // ---------------------------------------------------------------------------------
    // STEP 5: Return to Bot 1 Draft -> Verify Exact Raw Content Restored
    // ---------------------------------------------------------------------------------
    currentStep = 'Restore Bot 1 Draft without Cross-Contamination';
    interceptedCreateMutations.length = 0;
    await page.click('[data-testid="bot-manager-nav-btn"]');
    await page.waitForSelector('.bot-manager-container', { timeout: 5000 });
    await page.click(openDraft1Btn);

    // Wait for header to display bot1.name
    await page.waitForFunction((expectedName) => {
      const el = document.querySelector('[data-testid="header-bot-name"]');
      return el && el.textContent?.trim() === expectedName;
    }, { timeout: 5000 }, bot1.name);

    assert(interceptedCreateMutations.length === 0, `Expected 0 create mutations, got ${interceptedCreateMutations.length}`);

    await page.waitForFunction((sel, expected) => {
      const el = document.querySelector(sel);
      return el && el.value === expected;
    }, { timeout: 5000 }, textareaSelector, rawDraft1);

    const restoredDraft1 = await getInputValue(page, textareaSelector);
    assert(restoredDraft1 === rawDraft1, `Restored draft mismatch. Expected exact raw length ${rawDraft1.length}, got ${restoredDraft1.length}`);

    await page.screenshot({ path: SCREENSHOTS.bot1DraftRestored });
    recordResult(currentStep, true, `exactRawMatched=true, length=${restoredDraft1.length}`);

    // ---------------------------------------------------------------------------------
    // STEP 6: SIMULATION Mode: First Submit Creates Conversation Once,
    // Real Fake Assistant Response with User Marker, Terminal GraphQL Verification
    // ---------------------------------------------------------------------------------
    currentStep = 'SIMULATION Mode Submit & Terminal Provenance';
    interceptedCreateMutations.length = 0;
    interceptedCreatedConvs.length = 0;
    interceptedRunRequests.length = 0;

    const uniqueMarker = `marker_${Date.now()}_${Math.random().toString(36).substring(2, 8)}`;
    const simSubmitText = `\n  First submission for GKE bot with ${uniqueMarker}  \n  trailing query line   \n`;
    await setInputValue(page, textareaSelector, simSubmitText);

    // Verify send button is enabled
    const isSendButtonEnabled = await page.evaluate((sel) => {
      const btn = document.querySelector(`${sel} button`) || document.querySelector(sel);
      return btn && !btn.disabled && !btn.hasAttribute('disabled');
    }, sendBtnContainerSelector);
    assert(isSendButtonEnabled, 'Send button is disabled in SIMULATION mode');

    // Click Send
    await page.click(sendBtnContainerSelector);

    // Wait for real copilot-chat-assistant-message containing deterministic mock reply and user unique marker
    await page.waitForFunction((marker) => {
      const assistantNodes = document.querySelectorAll('copilot-chat-assistant-message, .assistant-message, .message.assistant');
      for (const node of assistantNodes) {
        const text = node.textContent || '';
        if (text.includes('deterministic mock streaming response') && text.includes(marker)) {
          return true;
        }
      }
      return false;
    }, { timeout: 15000 }, uniqueMarker);

    // Wait until stop button disappears (terminal status reached)
    await page.waitForFunction((sel) => {
      const stopBtn = document.querySelector(sel);
      return !stopBtn;
    }, { timeout: 10000 }, stopBtnSelector);

    // Assert request counts: exactly 1 createConversation, exactly 1 run request
    assert(interceptedCreateMutations.length === 1, `Expected exactly 1 createConversation mutation, got ${interceptedCreateMutations.length}`);
    assert(interceptedRunRequests.length === 1, `Expected exactly 1 run request, got ${interceptedRunRequests.length}`);

    // Verify draft is cleared upon successful submit & completed run
    const currentInputAfterSuccess = await getInputValue(page, textareaSelector);
    assert(currentInputAfterSuccess === '', `Expected empty draft after completed run, got "${currentInputAfterSuccess}"`);

    // Verify backend GraphQL conversation provenance
    const convIdMatch = interceptedRunRequests[0]?.url?.match(/\/api\/conversations\/([^/]+)\/runs/);
    const createdConvId = convIdMatch ? convIdMatch[1] : null;
    assert(createdConvId, 'Failed to capture created conversation ID from /api/conversations/:id/runs request URL');

    const backendConvCheck = await queryBackendGraphQL(`
      query CheckConv($id: ID!) {
        conversation(id: $id) {
          id
          botId
          runs(limit: 5, offset: 0) {
            id
            status
            executionMode
          }
        }
      }
    `, { id: createdConvId });

    const convData = backendConvCheck.data?.conversation;
    assert(convData, `Conversation ${createdConvId} not found in backend GraphQL`);
    assert(convData.botId === bot1.id, `Expected conv.botId=${bot1.id}, got ${convData.botId}`);

    const latestRun = convData.runs?.[0];
    assert(latestRun, 'No run records found in created conversation');
    assert(latestRun.executionMode === 'SPRING_AI_SIMULATION', `Expected SPRING_AI_SIMULATION, got ${latestRun.executionMode}`);
    assert(latestRun.status === 'COMPLETED', `Expected COMPLETED, got ${latestRun.status}`);

    await page.screenshot({ path: SCREENSHOTS.simulationSubmitChat });
    recordResult(currentStep, true, `convId=${createdConvId}, botId=${convData.botId}, runStatus=${latestRun.status}, mode=${latestRun.executionMode}`);

    // ---------------------------------------------------------------------------------
    // STEP 7: In-Flight Cancel with [DELAY]: Click Stop Button, Capture Cancel API,
    // Verify CANCELLED Run in DB & Exact Raw Restore
    // ---------------------------------------------------------------------------------
    currentStep = 'Cancel In-Flight [DELAY] Run Restores Exact Raw Draft & Run CANCELLED';

    // Open Bot 2 draft
    await page.click('[data-testid="bot-manager-nav-btn"]');
    await page.waitForSelector('.bot-manager-container', { timeout: 5000 });
    await page.click(openDraft2Btn);

    await page.waitForFunction((expectedName) => {
      const el = document.querySelector('[data-testid="header-bot-name"]');
      return el && el.textContent?.trim() === expectedName;
    }, { timeout: 5000 }, bot2.name);

    interceptedCreateMutations.length = 0;
    interceptedCreatedConvs.length = 0;
    interceptedRunRequests.length = 0;
    interceptedCancelRequests.length = 0;

    const cancelRawPrompt = `\n  Task with intentional delay [DELAY] marker_${Date.now()}  \n  second line with spaces   \n`;
    await setInputValue(page, textareaSelector, cancelRawPrompt);

    // Click Send
    await page.click(sendBtnContainerSelector);

    // Because MockChatModel includes delaySubscription(400ms) + delayElements(50ms), wait for real Stop button and click it
    await page.waitForSelector(stopBtnSelector, { visible: true, timeout: 5000 });
    await page.click(stopBtnSelector);

    // Wait until Stop button disappears
    await page.waitForFunction((sel) => !document.querySelector(sel), { timeout: 8000 }, stopBtnSelector);

    // Verify cancel API was captured
    assert(interceptedCancelRequests.length >= 1, `Expected at least 1 cancel API request (/api/runs/{runId}/cancel), got ${interceptedCancelRequests.length}`);

    const cancelUrl = interceptedCancelRequests[0].url;
    const cancelMatch = cancelUrl.match(/\/api\/runs\/([^/]+)\/cancel/);
    const cancelledRunId = cancelMatch ? cancelMatch[1] : null;
    assert(cancelledRunId, `Failed to parse runId from cancel URL: ${cancelUrl}`);

    // Query backend GraphQL for run status
    const cancelledRunResp = await queryBackendGraphQL(`
      query CheckCancelledRun($id: ID!) {
        run(id: $id) {
          id
          conversationId
          status
          executionMode
        }
      }
    `, { id: cancelledRunId });

    const cancelledRunData = cancelledRunResp.data?.run;
    assert(cancelledRunData, `Run ${cancelledRunId} not found in backend GraphQL`);
    assert(cancelledRunData.status === 'CANCELLED', `Expected run.status === 'CANCELLED', got ${cancelledRunData.status}`);

    // Read textarea value: MUST EXACTLY MATCH cancelRawPrompt
    const restoredCancelDraft = await getInputValue(page, textareaSelector);
    assert(restoredCancelDraft === cancelRawPrompt,
      `Expected exact raw match (${cancelRawPrompt.length} chars), got "${restoredCancelDraft}" (${restoredCancelDraft.length} chars)`);

    await page.screenshot({ path: SCREENSHOTS.cancelDraftRestored });
    recordResult(currentStep, true, `cancelledRunId=${cancelledRunId}, runStatus=${cancelledRunData.status}, exactRawRestored=true`);

    // ---------------------------------------------------------------------------------
    // STEP 8: [ERROR] Failure Exact Raw Draft Restoration & Run FAILED
    // ---------------------------------------------------------------------------------
    currentStep = '[ERROR] Stream Failure Restores Exact Raw Draft & Run FAILED';
    interceptedRunRequests.length = 0;

    const errorRawPrompt = `\n  Task with intentional error [ERROR] marker_${Date.now()}  \n  indent line with spaces   \n`;
    await setInputValue(page, textareaSelector, errorRawPrompt);

    // Click Send
    await page.click(sendBtnContainerSelector);

    // Wait for error state: stream error banner appears AND stop button disappears (using stopBtnSelector)
    await page.waitForFunction((stopSel) => {
      const hasErrorBanner = document.querySelector('[data-testid="stream-error-banner"]') ||
                             document.querySelector('.stream-error-banner');
      const stopBtn = document.querySelector(stopSel);
      return !!hasErrorBanner && !stopBtn;
    }, { timeout: 10000 }, stopBtnSelector);

    // Query backend GraphQL for latest run in this conversation -> MUST BE FAILED
    const convErrorRunsResp = await queryBackendGraphQL(`
      query CheckConvRuns($id: ID!) {
        conversation(id: $id) {
          id
          runs(limit: 5, offset: 0) {
            id
            status
            executionMode
            errorMessage
          }
        }
      }
    `, { id: cancelledRunData.conversationId });

    const errorRuns = convErrorRunsResp.data?.conversation?.runs || [];
    const latestErrorRun = errorRuns[0];
    assert(latestErrorRun, 'Expected run record for error submission in conversation');
    assert(latestErrorRun.status === 'FAILED', `Expected error run.status === 'FAILED', got ${latestErrorRun.status}`);

    // Read textarea value: MUST EXACTLY MATCH errorRawPrompt
    const restoredErrorDraft = await getInputValue(page, textareaSelector);
    assert(restoredErrorDraft === errorRawPrompt,
      `Expected exact raw match (${errorRawPrompt.length} chars), got "${restoredErrorDraft}" (${restoredErrorDraft.length} chars)`);

    await page.screenshot({ path: SCREENSHOTS.errorDraftRestored });
    recordResult(currentStep, true, `errorRunId=${latestErrorRun.id}, runStatus=${latestErrorRun.status}, exactRawRestored=true`);

    // ---------------------------------------------------------------------------------
    // STEP 9: OFF Mode: Intercept GraphQL to Return canChat=false & UNAVAILABLE;
    // Check True Disabled Inner Button, Enter & Send Request Count = 0, Raw Input Preserved
    // ---------------------------------------------------------------------------------
    currentStep = 'OFF Mode: UNAVAILABLE Disables Send, Emits Zero Mutations, Preserves Raw Draft';

    // Switch to Chat view first (click New Chat) so navigating to Bot Manager triggers a clean mount
    await page.click('[data-testid="new-chat-btn"] button');
    await page.waitForFunction(() => {
      const el = document.querySelector('[data-testid="header-bot-target"]');
      return el && el.textContent?.trim() === '[LOCAL]';
    }, { timeout: 5000 });

    // Set targetedOffBotId to bot2 so request interception modifies its GraphQL response
    targetedOffBotId = bot2.id;

    // Navigate to Bot Manager to trigger fetchBots with intercepted OFF status
    await page.click('[data-testid="bot-manager-nav-btn"]');
    await page.waitForSelector('.bot-manager-container', { timeout: 5000 });

    // Open Bot 2 draft under OFF mode
    await page.click(openDraft2Btn);

    // Wait for disabled bot banner rendered
    await page.waitForSelector('[data-testid="disabled-bot-banner"]', { visible: true, timeout: 5000 });
    const disabledBannerText = await page.$eval('[data-testid="disabled-bot-banner"]', el => el.textContent?.trim());
    assert(disabledBannerText?.includes('未連線（停用）'), `Expected banner to contain "未連線（停用）", got ${disabledBannerText}`);

    // Verify inner native button is genuinely disabled
    const isInnerSendButtonDisabled = await page.evaluate((sel) => {
      const btn = document.querySelector(`${sel} button`) || document.querySelector(sel);
      return btn ? (btn.disabled === true || btn.hasAttribute('disabled')) : false;
    }, sendBtnContainerSelector);
    assert(isInnerSendButtonDisabled, 'Inner button is not disabled in OFF mode');

    // Set raw draft in OFF mode
    const offRawDraft = '\n  Draft entered while bot is in OFF mode\n  extra spaces   \n';
    await setInputValue(page, textareaSelector, offRawDraft);

    // Record request counts before Enter and Send clicks
    const createCountBefore = interceptedCreateMutations.length;
    const runCountBefore = interceptedRunRequests.length;

    // Attempt Enter keypress in textarea
    await page.focus(textareaSelector);
    await page.keyboard.press('Enter');

    // Attempt click on send button
    await page.evaluate((sel) => {
      const btn = document.querySelector(`${sel} button`) || document.querySelector(sel);
      btn?.click();
    }, sendBtnContainerSelector);

    await new Promise(r => setTimeout(r, 1000));

    // Assert request counts remain strictly 0 increase
    const createDelta = interceptedCreateMutations.length - createCountBefore;
    const runDelta = interceptedRunRequests.length - runCountBefore;
    assert(createDelta === 0 && runDelta === 0, `Expected 0 requests in OFF mode, got createDelta=${createDelta}, runDelta=${runDelta}`);

    // Assert textarea raw text is completely preserved
    const textAfterAttempt = await getInputValue(page, textareaSelector);
    assert(textAfterAttempt === offRawDraft, `Expected "${offRawDraft}", got "${textAfterAttempt}"`);

    await page.screenshot({ path: SCREENSHOTS.offModeDisabled });
    recordResult(currentStep, true,
      `bannerText="${disabledBannerText?.substring(0, 30)}...", innerBtnDisabled=true, createDelta=0, runDelta=0`);

    // Reset targetedOffBotId
    targetedOffBotId = null;

    // ---------------------------------------------------------------------------------
    // STEP 10: Refresh Isolation: In-Memory Drafts Do Not Persist Across Page Reload
    // ---------------------------------------------------------------------------------
    currentStep = 'Refresh Isolation: In-Memory Drafts Do Not Persist Across Reload';
    await page.reload({ waitUntil: 'networkidle0' });

    // Initial view should be New Chat with empty canvas
    await page.waitForSelector(textareaSelector, { timeout: 5000 });
    const canvasAfterReload = await getInputValue(page, textareaSelector);
    assert(canvasAfterReload === '', `Expected clean canvas after reload, got "${canvasAfterReload}"`);

    // Open Bot 1 draft: MUST BE CLEAN
    await page.waitForSelector('[data-testid="bot-manager-nav-btn"]', { timeout: 5000 });
    await page.click('[data-testid="bot-manager-nav-btn"]');
    await page.waitForSelector('.bot-manager-container', { timeout: 5000 });
    await page.click(openDraft1Btn);

    await page.waitForFunction((expectedName) => {
      const el = document.querySelector('[data-testid="header-bot-name"]');
      return el && el.textContent?.trim() === expectedName;
    }, { timeout: 5000 }, bot1.name);

    const bot1DraftAfterReload = await getInputValue(page, textareaSelector);
    assert(bot1DraftAfterReload === '', `Expected Bot 1 draft to be clean after reload, got "${bot1DraftAfterReload}"`);

    // Open Bot 2 draft: MUST BE CLEAN
    await page.click('[data-testid="bot-manager-nav-btn"]');
    await page.waitForSelector('.bot-manager-container', { timeout: 5000 });
    await page.click(openDraft2Btn);

    await page.waitForFunction((expectedName) => {
      const el = document.querySelector('[data-testid="header-bot-name"]');
      return el && el.textContent?.trim() === expectedName;
    }, { timeout: 5000 }, bot2.name);

    const bot2DraftAfterReload = await getInputValue(page, textareaSelector);
    assert(bot2DraftAfterReload === '', `Expected Bot 2 draft to be clean after reload, got "${bot2DraftAfterReload}"`);

    await page.screenshot({ path: SCREENSHOTS.reloadClean });
    recordResult(currentStep, true, 'Reload wiped all in-memory drafts across Bot 1, Bot 2, and New Chat');

    await browser.close();
    printSummary();
    return true;
  } catch (err) {
    recordResult(currentStep, false, err.message);
    try {
      await page.screenshot({ path: '/tmp/issue18-error-state.png' });
    } catch {}
    await browser.close();
    printSummary();
    return false;
  }
}

function printSummary() {
  console.log('\n========================================');
  console.log('       VERIFICATION SUMMARY REPORT      ');
  console.log('========================================');
  const total = results.length;
  const passed = results.filter(r => r.passed).length;
  const failed = results.filter(r => !r.passed).length;

  for (const r of results) {
    const mark = r.passed ? '✅' : '❌';
    console.log(`${mark} [${r.stepName}] ${r.details}`);
  }
  console.log('----------------------------------------');
  console.log(`TOTAL: ${total} | PASSED: ${passed} | FAILED: ${failed}`);
  console.log('========================================\n');
}

// Execute verification directly when run as script
if (process.argv[1] && process.argv[1].endsWith('issue18-bot-drafts.mjs')) {
  runVerification()
    .then((success) => {
      process.exit(success ? 0 : 1);
    })
    .catch((err) => {
      console.error('Fatal execution error:', err);
      process.exit(1);
    });
}
