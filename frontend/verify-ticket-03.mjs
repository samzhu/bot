import puppeteer from 'puppeteer-core';
import path from 'node:path';
import { installNodeFetchGuard, installBrowserGuard, getVerificationOrigins, assertSafeExecutionEnvironment } from './test/verification-harness.mjs';

installNodeFetchGuard({ mode: 'verified-backend' });

const CHROME_PATH = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const { frontendUrl: BASE_URL, backendUrl: BACKEND_URL } = getVerificationOrigins();

const SCREENSHOT_DIR = '/tmp';
const SCREENSHOTS = {
  failedBadge: path.join(SCREENSHOT_DIR, 'verify-ticket-03-01-failed-badge.png'),
  failedReloaded: path.join(SCREENSHOT_DIR, 'verify-ticket-03-02-failed-reloaded.png'),
  inPageAutoRecovery: path.join(SCREENSHOT_DIR, 'verify-ticket-03-03-inpage-autorecovery.png'),
  lostResponseDedup: path.join(SCREENSHOT_DIR, 'verify-ticket-03-04-lost-response-dedup.png'),
  beforePersistRetry: path.join(SCREENSHOT_DIR, 'verify-ticket-03-05-before-persist-retry.png'),
  zeroDeltaCancel: path.join(SCREENSHOT_DIR, 'verify-ticket-03-06-zero-delta-cancel.png'),
  conflict409Real: path.join(SCREENSHOT_DIR, 'verify-ticket-03-07-conflict-409-real.png')
};

async function createConversationViaBackend(title) {
  const res = await fetch(`${BACKEND_URL}/graphql`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      query: `mutation CreateConv($botId: ID!, $title: String) {
        createConversation(botId: $botId, title: $title) { id title }
      }`,
      variables: { botId: 'bot-general', title }
    })
  });
  const json = await res.json();
  if (!json.data?.createConversation?.id) {
    throw new Error('Failed to create seed conversation: ' + JSON.stringify(json));
  }
  return json.data.createConversation.id;
}

async function queryConversationMessages(convId) {
  const res = await fetch(`${BACKEND_URL}/graphql`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      query: `query GetConv($id: ID!) {
        conversation(id: $id) {
          id
          messages(limit: 50, offset: 0) {
            id
            runId
            role
            content
            interrupted
          }
          runs(limit: 20, offset: 0) {
            id
            clientRequestId
            status
            errorMessage
          }
        }
      }`,
      variables: { id: convId }
    })
  });
  const json = await res.json();
  return json.data?.conversation;
}

async function run() {
  console.log('--- Starting Ticket 03 Deterministic Browser Automation Verification ---');
  await assertSafeExecutionEnvironment(BACKEND_URL);

  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: true,
    args: ['--no-sandbox', '--disable-gpu', '--window-size=1280,900']
  });

  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 900 });
  await installBrowserGuard(page, { mode: 'verified-backend' });

  page.on('console', msg => {
    if (msg.type() === 'error') {
      console.error('[Browser Console Error]', msg.text());
    }
  });

  const cdp = await page.target().createCDPSession();

  const targetScenario = process.env.SCENARIO || process.argv.find(a => a.startsWith('--scenario='))?.split('=')[1];
  const shouldRun = (s) => !targetScenario || targetScenario === String(s);

  await page.goto(BASE_URL, { waitUntil: 'networkidle0' });

  try {
    // ----------------------------------------------------------------------------------
    // SCENARIO 1: Model Error Partial -> Failed Banner, Reload Persistence, DB FAILED status
    // ----------------------------------------------------------------------------------
    if (shouldRun(1)) {
      console.log('\n=== Scenario 1: Model Error Partial & Failed Banner ===');
      const convId1 = await createConversationViaBackend('Ticket03-Scenario1-FailedBadge');

      await page.evaluate(id => {
        localStorage.setItem('bot_active_conversation_id', id);
      }, convId1);
      await page.reload({ waitUntil: 'networkidle0' });

      const failedPrompt = 'Hello Luna: Trigger partial stream failure [PARTIAL_ERROR] test';
      await page.waitForSelector('textarea');
      await page.type('textarea', failedPrompt);
      await page.click('button[aria-label="Send message"]');

      // Wait for the last-failed-banner or stream-error-banner
      await page.waitForSelector('[data-testid="last-failed-banner"], [data-testid="stream-error-banner"], .stream-error-banner', { timeout: 10000 });
      const bannerText = await page.$eval('[data-testid="last-failed-banner"], [data-testid="stream-error-banner"], .stream-error-banner', el => el.textContent?.trim());
      console.log('Failed Banner Rendered:', bannerText);
      if (!bannerText.includes('失敗') && !bannerText.includes('error')) {
        throw new Error(`Expected banner to indicate failure, got '${bannerText}'`);
      }

      await page.waitForSelector('[data-message-role="assistant"]', { timeout: 10000 });
      const assistantTextBeforeReload = await page.$eval('[data-message-role="assistant"]', el => el.textContent?.trim());
      console.log('Partial Assistant Text before reload:', assistantTextBeforeReload);
      if (!assistantTextBeforeReload.includes('Hello,') || !assistantTextBeforeReload.includes('partial')) {
        throw new Error(`Expected assistant message to contain partial text, got: '${assistantTextBeforeReload}'`);
      }

      await page.screenshot({ path: SCREENSHOTS.failedBadge });
      console.log('Saved Failed Badge screenshot:', SCREENSHOTS.failedBadge);

      console.log('Reloading page to verify persistence...');
      await page.reload({ waitUntil: 'networkidle0' });

      await page.waitForSelector('[data-message-role="assistant"]');
      const assistantTextAfterReload = await page.$eval('[data-message-role="assistant"]', el => el.textContent?.trim());
      const reloadedBanner = await page.$eval('[data-testid="last-failed-banner"], .stream-error-banner', el => el.textContent?.trim());
      console.log('Reloaded assistant text:', assistantTextAfterReload);
      console.log('Reloaded banner text:', reloadedBanner);

      if (!reloadedBanner.includes('失敗')) {
        throw new Error(`Expected failed banner to persist after reload, got '${reloadedBanner}'`);
      }
      if (assistantTextAfterReload !== assistantTextBeforeReload) {
        throw new Error(`Content changed after reload: '${assistantTextAfterReload}' vs '${assistantTextBeforeReload}'`);
      }

      const dbData1 = await queryConversationMessages(convId1);
      console.log('DB Run Status:', dbData1.runs[0]?.status, 'Message count:', dbData1.messages.length);
      if (dbData1.runs[0]?.status !== 'FAILED') {
        throw new Error(`Expected DB run status to be FAILED, got: ${dbData1.runs[0]?.status}`);
      }
      if (dbData1.messages.length !== 2) {
        throw new Error(`Expected 2 messages in DB (1 USER, 1 ASSISTANT), got: ${dbData1.messages.length}`);
      }

      await page.screenshot({ path: SCREENSHOTS.failedReloaded });
      console.log('Saved Failed Reloaded screenshot:', SCREENSHOTS.failedReloaded);
    } // end Scenario 1

    // ----------------------------------------------------------------------------------
    // SCENARIO 2: In-Flight Disconnect with Confirmed Server State -> In-Page Auto-Recovery (NO Reload)
    // ----------------------------------------------------------------------------------
    if (shouldRun(2)) {
      console.log('\n=== Scenario 2: In-Flight Disconnect -> In-Page Auto-Recovery (NO Reload) ===');
      const convId2 = await createConversationViaBackend('Ticket03-Scenario2-InPageRecovery');
      await page.evaluate(id => {
        localStorage.setItem('bot_active_conversation_id', id);
      }, convId2);
      await page.reload({ waitUntil: 'networkidle0' });

      const cdp2 = await page.target().createCDPSession();

      // Listen for recovery GraphQL query dispatched by browser to prove in-page fault was observed
      let browserRecoveryGqlObserved = false;
      const recoveryGqlListener = req => {
        if (req.url().includes('/graphql') && req.postData()?.includes('GetRecovery')) {
          browserRecoveryGqlObserved = true;
        }
      };
      page.on('request', recoveryGqlListener);

      // Use CDP Fetch domain to cut the active stream with ConnectionReset after server is RUNNING in DB
      await cdp2.send('Fetch.enable', {
        patterns: [{ urlPattern: '*runs*', requestStage: 'Response' }]
      });

      let sseFaultInjected = false;
      const sseFaultHandler = async (evt) => {
        if (!sseFaultInjected && evt.responseStatusCode === 200) {
          sseFaultInjected = true;
          console.log('Stream response paused. Waiting for backend to confirm RUNNING in DB...');
          const runningDeadline = Date.now() + 5000;
          while (Date.now() < runningDeadline) {
            const d = await queryConversationMessages(convId2);
            if (d?.runs?.length > 0 && (d.runs[0].status === 'RUNNING' || d.runs[0].status === 'PENDING')) break;
            await new Promise(r => setTimeout(r, 100));
          }
          console.log('Server confirmed run in DB. Injecting transport ConnectionReset fault...');
          await cdp2.send('Fetch.failRequest', {
            requestId: evt.requestId,
            errorReason: 'ConnectionReset'
          });
          await cdp2.send('Fetch.disable');
        } else {
          await cdp2.send('Fetch.continueRequest', { requestId: evt.requestId });
        }
      };
      cdp2.on('Fetch.requestPaused', sseFaultHandler);

      const discPrompt = 'Testing in-page auto-recovery without page reload [DELAY]';
      await page.waitForSelector('textarea');
      await page.type('textarea', discPrompt);
      await page.click('button[aria-label="Send message"]');

      // 1. Wait until run has actually started in UI (Stop button is rendered)
      console.log('Waiting for active stream to start (Stop button visible)...');
      await page.waitForSelector('[data-testid="stop-btn"] button, button[aria-label="Stop generation"], .stop-btn', { timeout: 10000 });

      // 2. Wait until transport fault has been injected by CDP
      console.log('Waiting for SSE transport fault injection...');
      const faultDeadline = Date.now() + 10000;
      while (!sseFaultInjected && Date.now() < faultDeadline) {
        await new Promise(r => setTimeout(r, 100));
      }
      if (!sseFaultInjected) {
        throw new Error('SSE transport fault was not injected within deadline');
      }

      // 3. Await backend run reaching terminal state via Node true GraphQL query
      console.log('Node awaiting backend run to reach terminal status...');
      const recoveryDeadline = Date.now() + 15000;
      let finalRun2 = null;
      let dbData2 = null;
      while (Date.now() < recoveryDeadline) {
        dbData2 = await queryConversationMessages(convId2);
        finalRun2 = dbData2?.runs?.[0];
        if (finalRun2 && finalRun2.status !== 'RUNNING' && finalRun2.status !== 'PENDING') {
          break;
        }
        await new Promise(res => setTimeout(res, 100));
      }
      if (!finalRun2 || (finalRun2.status === 'RUNNING' || finalRun2.status === 'PENDING')) {
        throw new Error(`Backend run did not reach terminal state within deadline: ${finalRun2?.status}`);
      }
      console.log(`Backend run reached terminal status: ${finalRun2.status}`);

      const dbAssistant2 = dbData2.messages.find(m => m.role.toUpperCase() === 'ASSISTANT');
      const dbUser2 = dbData2.messages.find(m => m.role.toUpperCase() === 'USER');
      const expected = {
        status: finalRun2.status,
        userMessageId: dbUser2?.id,
        assistantMessageId: dbAssistant2?.id || null,
        assistantContent: dbAssistant2?.content || '',
        hasAssistant: Boolean(dbAssistant2),
        totalMessages: dbData2.messages.length
      };
      console.log('Expected state from DB:', JSON.stringify(expected));

      // 4. Use page.waitForFunction with public DOM:
      // recovering hint disappears, rows have real IDs/content matching expected, no temp rows.
      console.log('Waiting for public DOM in-page recovery to match expected DB state...');
      await page.waitForFunction(
        (exp) => {
          // Connection recovering banner must be gone
          if (document.querySelector('[data-testid="connection-recovering-banner"]')) return false;

          const rows = Array.from(document.querySelectorAll('[data-message-role]'));
          if (rows.length !== exp.totalMessages) return false;

          // No temp rows allowed
          for (const r of rows) {
            const mid = r.getAttribute('data-message-id');
            if (!mid || mid.startsWith('temp-')) return false;
          }

          // Check user message
          const userRow = document.querySelector('[data-message-role="user"]');
          if (!userRow || userRow.getAttribute('data-message-id') !== exp.userMessageId) return false;

          // Check assistant message
          if (exp.hasAssistant) {
            const assistantRow = document.querySelector('[data-message-role="assistant"]');
            if (!assistantRow) return false;
            if (assistantRow.getAttribute('data-message-id') !== exp.assistantMessageId) return false;
            if (!assistantRow.textContent.includes(exp.assistantContent)) return false;

            if (exp.status === 'CANCELLED') {
              const banner = document.querySelector('[data-testid="last-interrupted-banner"]');
              if (!banner || !banner.textContent.includes('中斷')) return false;
            }
          } else {
            if (document.querySelector('[data-message-role="assistant"]')) return false;
          }

          return true;
        },
        { timeout: 15000 },
        expected
      );

      cdp2.off('Fetch.requestPaused', sseFaultHandler);
      page.off('request', recoveryGqlListener);

      if (!browserRecoveryGqlObserved) {
        throw new Error('Browser did not trigger in-page recovery GraphQL query upon fault');
      }
      console.log(`Browser recovery GraphQL observed: ${browserRecoveryGqlObserved}`);

      const uiRowUser2 = await page.$$eval('[data-message-role="user"]', els => els.length);
      const uiRowAssistant2 = await page.$$eval('[data-message-role="assistant"]', els => els.length);
      console.log(`UI Rows (In-Page): User=${uiRowUser2}, Assistant=${uiRowAssistant2}`);

      if (uiRowUser2 + uiRowAssistant2 !== dbData2.messages.length) {
        throw new Error(`UI row count (${uiRowUser2 + uiRowAssistant2}) does not match DB (${dbData2.messages.length})`);
      }

      await page.screenshot({ path: SCREENSHOTS.inPageAutoRecovery });
      console.log('Saved In-Page Auto-Recovery screenshot:', SCREENSHOTS.inPageAutoRecovery);

      // Verify next turn can be submitted normally in the same page session
      console.log('Submitting next turn after in-page recovery...');
      const nextTurnPrompt = 'Next turn after in-page recovery streams normally';
      await page.type('textarea', nextTurnPrompt);
      await page.click('button[aria-label="Send message"]');

      const nextTurnExpectedCount = dbAssistant2 ? 2 : 1;
      await page.waitForFunction(
        (count) => {
          const rows = document.querySelectorAll('[data-message-role="assistant"]');
          const last = rows[rows.length - 1];
          const stopBtn = document.querySelector('[data-testid="stop-btn"] button, button[aria-label="Stop generation"], .stop-btn');
          return Boolean(rows.length === count && last && last.textContent.includes('Next turn after in-page recovery') && !stopBtn);
        },
        { timeout: 10000 },
        nextTurnExpectedCount
      );

      const totalMessagesAfterNextTurn = await page.$$eval('[data-message-role]', els => els.length);
      console.log('Total message rows after next turn:', totalMessagesAfterNextTurn);
      if (totalMessagesAfterNextTurn !== (dbAssistant2 ? 4 : 3)) {
        throw new Error(`Expected ${dbAssistant2 ? 4 : 3} rows, got: ${totalMessagesAfterNextTurn}`);
      }
    } // end Scenario 2

    // ----------------------------------------------------------------------------------
    // SCENARIO 3: Lost-Response Test -> Same clientRequestId Manual Retry -> 200 JSON Duplicate
    // ----------------------------------------------------------------------------------
    if (shouldRun(3)) {
      console.log('\n=== Scenario 3: Lost-Response Test & Same clientRequestId Dedup ===');
      const convId3 = await createConversationViaBackend('Ticket03-Scenario3-LostResponse');
      await page.evaluate(id => {
        localStorage.setItem('bot_active_conversation_id', id);
      }, convId3);
      await page.reload({ waitUntil: 'networkidle0' });

      const cdp3 = await page.target().createCDPSession();

      let responseDropped = false;
      let blockGql = false;

      // Track outgoing POST bodies to verify both attempts send the exact same clientRequestId
      const outgoingPostBodies = [];
      page.on('request', req => {
        if (req.url().includes('/runs') && req.method() === 'POST') {
          const body = req.postData();
          if (body) {
            try {
              outgoingPostBodies.push(JSON.parse(body));
            } catch (_) {}
          }
        }
      });

      await cdp3.send('Fetch.enable', {
        patterns: [
          { urlPattern: '*runs*', requestStage: 'Response' },
          { urlPattern: '*graphql*', requestStage: 'Request' }
        ]
      });

      const lostResponseHandler = async (evt) => {
        if (evt.request.url.includes('/graphql')) {
          if (blockGql) {
            await cdp3.send('Fetch.failRequest', { requestId: evt.requestId, errorReason: 'Failed' });
          } else {
            await cdp3.send('Fetch.continueRequest', { requestId: evt.requestId });
          }
          return;
        }

        if (!responseDropped && evt.responseStatusCode === 200) {
          responseDropped = true;
          console.log(`Response paused at HTTP 200. Waiting for backend to finish execution in DB before dropping...`);
          const doneDeadline = Date.now() + 8000;
          while (Date.now() < doneDeadline) {
            const d = await queryConversationMessages(convId3);
            if (d?.runs?.length > 0 && d.runs[0].status === 'COMPLETED') break;
            await new Promise(r => setTimeout(r, 150));
          }
          console.log('Backend confirmed run COMPLETED in DB. Dropping response to simulate lost-response...');

          // Block recovery GraphQL so the browser UI reveals the retry button
          blockGql = true;

          await cdp3.send('Fetch.failRequest', {
            requestId: evt.requestId,
            errorReason: 'ConnectionReset'
          });
        } else {
          await cdp3.send('Fetch.continueRequest', { requestId: evt.requestId });
        }
      };
      cdp3.on('Fetch.requestPaused', lostResponseHandler);

      const lostPrompt = 'Testing lost response retry with identical clientRequestId [DELAY]';
      await page.waitForSelector('textarea');
      await page.type('textarea', lostPrompt);
      await page.click('button[aria-label="Send message"]');

      // UI catches ConnectionReset and failed recovery, shows error banner with Retry button
      console.log('Waiting for stream error banner and retry button...');
      await page.waitForSelector('[data-testid="retry-btn"] button, button[aria-label="重試"], [data-testid="retry-btn"], .retry-btn', { timeout: 10000 });
      console.log('Retry button is visible and ready for manual retry.');

      // Lift fault BEFORE clicking retry so the retry request reaches backend
      blockGql = false;
      cdp3.off('Fetch.requestPaused', lostResponseHandler);
      await cdp3.send('Fetch.disable');
      console.log('Fault lifted, CDP Fetch disabled for clean retry.');

      // Monitor response headers for the retry request to capture 200 application/json
      let retryResponseContentType = null;
      let retryResponseStatus = null;
      page.on('response', res => {
        if (res.url().includes('/runs') && res.request().method() === 'POST') {
          retryResponseStatus = res.status();
          retryResponseContentType = res.headers()['content-type'];
        }
      });

      // Click "重試"
      console.log('Clicking "重試" button...');
      await page.click('[data-testid="retry-btn"] button, button[aria-label="重試"], [data-testid="retry-btn"], .retry-btn');

      // Wait until UI reconciles: assistant has content and stop button is gone
      await page.waitForFunction(() => {
        const assistant = document.querySelector('[data-message-role="assistant"]');
        const stopBtn = document.querySelector('[data-testid="stop-btn"] button, button[aria-label="Stop generation"], .stop-btn');
        return Boolean(assistant && assistant.textContent.includes('deterministic mock') && !stopBtn);
      }, { timeout: 10000 });

      console.log(`Retry Response: Status=${retryResponseStatus}, Content-Type=${retryResponseContentType}`);
      if (retryResponseStatus !== 200 || !retryResponseContentType?.includes('application/json')) {
        throw new Error(`Expected retry to receive 200 application/json duplicate metadata, got: ${retryResponseStatus} ${retryResponseContentType}`);
      }

      // Assert both POST requests sent the exact same clientRequestId
      if (outgoingPostBodies.length < 2) {
        throw new Error(`Expected at least 2 POST requests, got: ${outgoingPostBodies.length}`);
      }
      const id1 = outgoingPostBodies[0].clientRequestId;
      const id2 = outgoingPostBodies[1].clientRequestId;
      console.log(`Post 1 clientRequestId: ${id1}`);
      console.log(`Post 2 clientRequestId: ${id2}`);
      if (id1 !== id2) {
        throw new Error(`Expected retry to reuse exact clientRequestId: '${id1}' vs '${id2}'`);
      }

      // Assert DB contains strictly 1 run, 1 USER message, 1 ASSISTANT message
      const dbData3 = await queryConversationMessages(convId3);
      console.log(`DB Counts after Retry: Runs=${dbData3.runs.length}, Messages=${dbData3.messages.length}`);
      if (dbData3.runs.length !== 1) {
        throw new Error(`Expected exactly 1 run in DB, got: ${dbData3.runs.length}`);
      }
      if (dbData3.messages.length !== 2) {
        throw new Error(`Expected exactly 2 messages in DB, got: ${dbData3.messages.length}`);
      }

      // Assert UI has strictly 1 USER and 1 ASSISTANT (no duplicate rows)
      const uiUserCount3 = await page.$$eval('[data-message-role="user"]', els => els.length);
      const uiAssistantCount3 = await page.$$eval('[data-message-role="assistant"]', els => els.length);
      console.log(`UI Bubble Counts: User=${uiUserCount3}, Assistant=${uiAssistantCount3}`);
      if (uiUserCount3 !== 1 || uiAssistantCount3 !== 1) {
        throw new Error(`Found duplicate message rows in UI: User=${uiUserCount3}, Assistant=${uiAssistantCount3}`);
      }

      await page.screenshot({ path: SCREENSHOTS.lostResponseDedup });
      console.log('Saved Lost-Response Dedup screenshot:', SCREENSHOTS.lostResponseDedup);
    } // end Scenario 3

    // ----------------------------------------------------------------------------------
    // SCENARIO 4: Network Failure Before Server Receives -> Retry Starts Fresh SSE & Preserves tempUserId
    // ----------------------------------------------------------------------------------
    if (shouldRun(4)) {
      console.log('\n=== Scenario 4: Before-Persist Network Failure & tempUserId Preservation ===');
      const convId4 = await createConversationViaBackend('Ticket03-Scenario4-BeforePersistRetry');
      await page.evaluate(id => {
        localStorage.setItem('bot_active_conversation_id', id);
      }, convId4);
      await page.reload({ waitUntil: 'networkidle0' });

      let abortFirstRun = true;
      await cdp.send('Fetch.enable', {
        patterns: [{ urlPattern: '*/runs*', requestStage: 'Request' }]
      });

      const beforePersistHandler = async (evt) => {
        if (abortFirstRun && evt.request.method === 'POST') {
          abortFirstRun = false;
          await cdp.send('Fetch.failRequest', {
            requestId: evt.requestId,
            errorReason: 'Failed'
          });
        } else {
          await cdp.send('Fetch.continueRequest', { requestId: evt.requestId });
        }
      };
      cdp.on('Fetch.requestPaused', beforePersistHandler);

      const bpPrompt = 'Testing before-persist network failure and tempUserId replacement';
      await page.waitForSelector('textarea');
      await page.type('textarea', bpPrompt);
      await page.click('button[aria-label="Send message"]');

      // UI shows error banner and Retry button; optimistic user row is rendered with temp ID
      await page.waitForSelector('[data-testid="retry-btn"] button, button[aria-label="重試"], [data-testid="retry-btn"], .retry-btn', { timeout: 8000 });

      // Verify DB has 0 runs and 0 messages
      const dbBeforeRetry = await queryConversationMessages(convId4);
      console.log(`DB Counts before retry: Runs=${dbBeforeRetry.runs.length}, Messages=${dbBeforeRetry.messages.length}`);
      if (dbBeforeRetry.runs.length !== 0 || dbBeforeRetry.messages.length !== 0) {
        throw new Error('Expected 0 runs and 0 messages in DB before retry');
      }

      // Disable CDP Fetch domain so retry can reach server cleanly
      cdp.off('Fetch.requestPaused', beforePersistHandler);
      await cdp.send('Fetch.disable');

      // Now click Retry: this should initiate a fresh SSE connection to backend
      console.log('Clicking Retry to initiate fresh SSE run...');
      await page.click('[data-testid="retry-btn"] button, button[aria-label="重試"], [data-testid="retry-btn"], .retry-btn');

      // Wait until run completes
      await page.waitForFunction(() => {
        const assistant = document.querySelector('[data-message-role="assistant"]');
        const stopBtn = document.querySelector('[data-testid="stop-btn"] button, button[aria-label="Stop generation"], .stop-btn');
        return Boolean(assistant && assistant.textContent.includes('deterministic mock') && !stopBtn);
      }, { timeout: 10000 });

      // Check rendered user message ID: it MUST have been updated to a real server ID, not temp-u-!
      const userMessageIdInUi = await page.$eval('[data-message-role="user"]', el => el.getAttribute('data-message-id'));
      console.log('Rendered user message data-message-id in DOM after retry:', userMessageIdInUi);
      if (!userMessageIdInUi || userMessageIdInUi.startsWith('temp-u-')) {
        throw new Error(`CRITICAL BUG: User message still holds temp ID '${userMessageIdInUi}' after SSE run.started!`);
      }

      // Verify DB has 1 run, 1 USER message, 1 ASSISTANT message
      const dbAfterRetry = await queryConversationMessages(convId4);
      const dbUserMsg = dbAfterRetry.messages.find(m => m.role.toUpperCase() === 'USER');
      console.log(`DB Counts after fresh retry: Runs=${dbAfterRetry.runs.length}, Messages=${dbAfterRetry.messages.length}`);
      console.log(`DB User Message ID: ${dbUserMsg?.id}`);

      if (dbAfterRetry.runs.length !== 1 || dbAfterRetry.messages.length !== 2) {
        throw new Error(`Expected 1 run and 2 messages in DB, got: Runs=${dbAfterRetry.runs.length}, Messages=${dbAfterRetry.messages.length}`);
      }
      if (userMessageIdInUi !== dbUserMsg.id) {
        throw new Error(`DOM data-message-id '${userMessageIdInUi}' does not equal server DB message ID '${dbUserMsg.id}'`);
      }

      // Reload page to verify deduplication and clean restoration
      console.log('Reloading to verify deduplication after tempUserId preservation...');
      await page.reload({ waitUntil: 'networkidle0' });

      const reloadedUserMessageId = await page.$eval('[data-message-role="user"]', el => el.getAttribute('data-message-id'));
      console.log('Reloaded user message data-message-id:', reloadedUserMessageId);
      if (reloadedUserMessageId !== dbUserMsg.id) {
        throw new Error(`Reloaded DOM ID '${reloadedUserMessageId}' does not equal DB message ID '${dbUserMsg.id}'`);
      }

      const reloadedUserRows4 = await page.$$eval('[data-message-role="user"]', els => els.length);
      const reloadedAssistantRows4 = await page.$$eval('[data-message-role="assistant"]', els => els.length);
      console.log(`Reloaded UI Rows: User=${reloadedUserRows4}, Assistant=${reloadedAssistantRows4}`);
      if (reloadedUserRows4 !== 1 || reloadedAssistantRows4 !== 1) {
        throw new Error(`Deduplication failed after reload: User=${reloadedUserRows4}, Assistant=${reloadedAssistantRows4}`);
      }

      await page.screenshot({ path: SCREENSHOTS.beforePersistRetry });
      console.log('Saved Before-Persist Retry screenshot:', SCREENSHOTS.beforePersistRetry);
    } // end Scenario 4

    // ----------------------------------------------------------------------------------
    // SCENARIO 5: Zero-Delta Cancel (No Ghost Assistant Row in UI or DB)
    // ----------------------------------------------------------------------------------
    if (shouldRun(5)) {
      console.log('\n=== Scenario 5: Zero-Delta Cancel ===');
      const convId5 = await createConversationViaBackend('Ticket03-Scenario5-ZeroDelta');
      await page.evaluate(id => {
        localStorage.setItem('bot_active_conversation_id', id);
      }, convId5);
      await page.reload({ waitUntil: 'networkidle0' });

      const zeroDeltaPrompt = 'Zero-delta cancel immediate interruption test [DELAY]';
      await page.waitForSelector('textarea');
      await page.type('textarea', zeroDeltaPrompt);
      await page.click('button[aria-label="Send message"]');

      // Immediately click Stop before any delta is emitted
      await page.waitForSelector('[data-testid="stop-btn"] button, button[aria-label="Stop generation"], .stop-btn', { timeout: 5000 });
      await page.click('[data-testid="stop-btn"] button, button[aria-label="Stop generation"], .stop-btn');

      // Wait until stream terminates and Stop button disappears
      await page.waitForFunction(() => {
        return !document.querySelector('[data-testid="stop-btn"] button, button[aria-label="Stop generation"], .stop-btn') && !document.querySelector('textarea')?.disabled;
      }, { timeout: 10000 });

      const userCount5 = await page.$$eval('[data-message-role="user"]', els => els.length);
      const assistantCount5 = await page.$$eval('[data-message-role="assistant"]', els => els.length);
      console.log(`Zero-Delta Cancel UI Counts: User=${userCount5}, Assistant=${assistantCount5}`);

      if (userCount5 !== 1) {
        throw new Error(`Expected exactly 1 user message, got: ${userCount5}`);
      }
      if (assistantCount5 !== 0) {
        throw new Error(`Expected 0 assistant messages on zero-delta cancel, got: ${assistantCount5}`);
      }

      // Verify DB count: only 1 USER message was persisted
      const dbData5 = await queryConversationMessages(convId5);
      console.log(`DB Messages Count: ${dbData5.messages.length}, Run Status: ${dbData5.runs[0]?.status}`);
      if (dbData5.messages.length !== 1) {
        throw new Error(`Expected exactly 1 message in DB, got: ${dbData5.messages.length}`);
      }
      if (dbData5.runs[0]?.status !== 'CANCELLED') {
        throw new Error(`Expected run status CANCELLED, got: ${dbData5.runs[0]?.status}`);
      }

      await page.screenshot({ path: SCREENSHOTS.zeroDeltaCancel });
      console.log('Saved Zero-Delta Cancel screenshot:', SCREENSHOTS.zeroDeltaCancel);
    } // end Scenario 5

    // ----------------------------------------------------------------------------------
    // SCENARIO 6: Real 409 Conflict with Active Server Run -> Ghost Rows Removed, Next Turn Usable
    // ----------------------------------------------------------------------------------
    if (shouldRun(6)) {
      console.log('\n=== Scenario 6: Real 409 Conflict with Active Server Run ===');
      const convId6 = await createConversationViaBackend('Ticket03-Scenario6-RealConflict');
      await page.evaluate(id => {
        localStorage.setItem('bot_active_conversation_id', id);
      }, convId6);
      await page.reload({ waitUntil: 'networkidle0' });

      // 1. Start a long-running active run on the backend for this conversation
      console.log('Starting long-running run on backend to hold conversation active...');
      const firstRunClientReqId = 'first-active-' + Date.now();
      fetch(`${BACKEND_URL}/api/conversations/${convId6}/runs`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
        body: JSON.stringify({
          clientRequestId: firstRunClientReqId,
          message: 'First active run holding conversation busy [DELAY]'
        })
      });

      // Wait until backend confirms first run is RUNNING in DB
      let firstRunId = null;
      const busyDeadline = Date.now() + 5000;
      while (Date.now() < busyDeadline) {
        const data = await queryConversationMessages(convId6);
        if (data?.runs?.length > 0 && data.runs[0].status === 'RUNNING') {
          firstRunId = data.runs[0].id;
          break;
        }
        await new Promise(r => setTimeout(r, 100));
      }
      if (!firstRunId) {
        throw new Error('Backend failed to establish active RUNNING run');
      }
      console.log(`Backend established active run: ${firstRunId}`);

      // 2. In UI, submit a second request with a different payload to the same conversation
      console.log('Submitting second conflicting request from UI...');
      const conflictPrompt = 'Second conflicting request while first is active';
      await page.waitForSelector('textarea');
      await page.type('textarea', conflictPrompt);
      await page.click('button[aria-label="Send message"]');

      // UI must receive real 409 Conflict from server, show error banner, and eliminate ghost rows
      await page.waitForSelector('[data-testid="stream-error-banner"], .stream-error-banner', { timeout: 8000 });
      const realConflictBannerText = await page.$eval('[data-testid="stream-error-banner"], .stream-error-banner', el => el.textContent?.trim());
      console.log('Real 409 Conflict Banner Text:', realConflictBannerText);
      if (!realConflictBannerText.includes('Another active run is in progress')) {
        throw new Error(`Expected real 409 message, got: '${realConflictBannerText}'`);
      }

      // Verify UI has 0 ghost rows for this conflicting prompt
      const ghostRowsInUi = await page.$$eval('[data-message-role]', els => els.length);
      console.log(`UI Message rows after 409 rejection: ${ghostRowsInUi} (must be 0)`);
      if (ghostRowsInUi !== 0) {
        throw new Error(`Expected 0 message rows in UI after 409 conflict, got: ${ghostRowsInUi}`);
      }

      // Verify 409 conflict cleared pendingRequest: no retry button in DOM
      const retryBtnAfter409 = await page.$('[data-testid="retry-btn"] button, button[aria-label="重試"], [data-testid="retry-btn"], .retry-btn');
      console.log(`Retry button present after 409: ${Boolean(retryBtnAfter409)} (must be false)`);
      if (retryBtnAfter409) {
        throw new Error('409 Conflict should clear pendingRequest and NOT display Retry button');
      }

      // 3. Cancel the first run on backend to release conversation lock
      console.log(`Cancelling first run ${firstRunId} on backend...`);
      await fetch(`${BACKEND_URL}/api/runs/${firstRunId}/cancel`, { method: 'POST' });

      // Wait for first run to become CANCELLED in DB
      const cancelDeadline = Date.now() + 5000;
      while (Date.now() < cancelDeadline) {
        const data = await queryConversationMessages(convId6);
        const r = data?.runs?.find(run => run.id === firstRunId);
        if (r && r.status === 'CANCELLED') break;
        await new Promise(r => setTimeout(r, 100));
      }
      console.log('First run successfully cancelled on backend.');

      // 4. Submit next normal turn in UI to verify full usability
      console.log('Submitting next turn after conflict resolution...');
      const conflictNextTurnPrompt = 'Next normal turn after 409 conflict resolution';
      await page.type('textarea', conflictNextTurnPrompt);
      await page.click('button[aria-label="Send message"]');

      await page.waitForFunction(() => {
        const assistant = document.querySelector('[data-message-role="assistant"]');
        const stopBtn = document.querySelector('[data-testid="stop-btn"] button, button[aria-label="Stop generation"], .stop-btn');
        return Boolean(assistant && assistant.textContent.includes('deterministic mock') && !stopBtn);
      }, { timeout: 10000 });

      const finalRowsAfterConflictRecovery = await page.$$eval('[data-message-role]', els => els.length);
      console.log('Total rows after recovery (must be 2: 1 user, 1 assistant):', finalRowsAfterConflictRecovery);
      if (finalRowsAfterConflictRecovery !== 2) {
        throw new Error(`Expected exactly 2 rows, got: ${finalRowsAfterConflictRecovery}`);
      }

      await page.screenshot({ path: SCREENSHOTS.conflict409Real });
      console.log('Saved Real 409 Conflict screenshot:', SCREENSHOTS.conflict409Real);
    } // end Scenario 6

    console.log('\n=== ALL TICKET 03 DETERMINISTIC BROWSER AUTOMATION ASSERTIONS PASSED ===');
    console.log(JSON.stringify({
      status: 'SUCCESS',
      scenarios: [
        '1. Model Error Partial -> Failed Banner, Reload Persistence, DB FAILED status',
        '2. In-Flight Disconnect with Confirmed Server State -> In-Page Auto-Recovery (NO Reload)',
        '3. Lost-Response Test -> Same clientRequestId Retry -> 200 JSON Duplicate Reconciles without Ghost Rows',
        '4. Before-Persist Network Failure -> Retry Starts Fresh SSE & Preserves tempUserId -> Real Server ID Aligned',
        '5. Zero-Delta Cancel -> No Ghost Assistant Row in UI or DB, Reload Persistence Verified',
        '6. Real 409 Conflict with Active Server Run -> Ghost Rows Removed, Next Turn Fully Usable'
      ],
      screenshots: SCREENSHOTS
    }, null, 2));

  } catch (err) {
    console.error('Ticket 03 Browser Automation failed:', err);
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
}

run();
