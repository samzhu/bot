import puppeteer from 'puppeteer-core';
import path from 'node:path';
import { getVerificationOrigins } from './test/verification-harness.mjs';

/*
 * =====================================================================================
 * Architectural Verification Script: Draft Preservation & Delayed Conversation Creation
 * =====================================================================================
 * Validates frontend New Chat deferral and draft resilience:
 * 1. Repeated New Chat clicks do NOT issue createConversation mutations.
 * 2. Multi-line Chinese & English & spaces draft is preserved across conversation switch.
 * 3. Sending a message inside an existing conversation does NOT clear or destroy the saved newChatDraft.
 * 4. Blank / whitespace-only submit does NOT trigger conversation creation.
 * 5. Native Send button / Enter first submit enforces strict sequence: create -> run via controlled barrier.
 * 6. Rapid double submit during in-flight creation is strictly guarded (exactly 1 createConversation).
 * 7. createConversation failure via HTTP 500 preserves exact original draft (strict === equality).
 * 8. createConversation failure via HTTP 200 GraphQL errors preserves exact original draft (strict === equality).
 * 9. When startRun fails, GetRecovery returns runs=[] and Retry reuses established conversation ID & clientRequestId without duplicate creation.
 * 10. Post-retry '+ New Chat' click asserts textarea is strictly empty and createConversation count does not increase (guards against uncleaned isFromNewDraft regression).
 * 11. Late-arriving createConversation response does NOT hijack newly selected conversation; in-flight submission safely locks selection.
 *
 * MOCK INTEGRITY & DATA ISOLATION:
 * - Real backend requests (/api and /graphql) that are not matched by synthetic mocks HARD FAIL immediately.
 * - Only static web assets (.js, .css, .html, fonts, icons) are permitted to continue.
 * - Fixed synthetic fixtures only: zero real cloud model calls, zero real database writes.
 */

const CHROME_PATH = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const { frontendUrl: BASE_URL } = getVerificationOrigins();

const SCREENSHOT_DIR = '/tmp';
const SCREENSHOTS = {
  repeatedNewChat: path.join(SCREENSHOT_DIR, 'bot-draft-01-repeated-newchat.png'),
  multilinePreserved: path.join(SCREENSHOT_DIR, 'bot-draft-02-multiline-preserved.png'),
  roundtripRestored: path.join(SCREENSHOT_DIR, 'bot-draft-roundtrip-restored.png'),
  existingSendPreservesNewDraft: path.join(SCREENSHOT_DIR, 'bot-draft-03-existing-send-preserves-newdraft.png'),
  blankSubmitBlocked: path.join(SCREENSHOT_DIR, 'bot-draft-04-blank-submit-blocked.png'),
  firstSendSequence: path.join(SCREENSHOT_DIR, 'bot-draft-05-first-send-sequence.png'),
  doubleSubmitGuarded: path.join(SCREENSHOT_DIR, 'bot-draft-06-double-submit-guarded.png'),
  create500Restored: path.join(SCREENSHOT_DIR, 'bot-draft-07-create500-restored.png'),
  createGqlErrorRestored: path.join(SCREENSHOT_DIR, 'bot-draft-08-create-gql-error-restored.png'),
  runFailedRetry: path.join(SCREENSHOT_DIR, 'bot-draft-09-run-failed-retry.png'),
  postRetryNewChatCleared: path.join(SCREENSHOT_DIR, 'bot-draft-10-post-retry-newchat-cleared.png'),
  lateCreateGuarded: path.join(SCREENSHOT_DIR, 'bot-draft-11-late-create-guarded.png')
};

// Synthetic test fixtures (no user data, no private keys)
const SYNTHETIC_BOT = {
  id: 'bot-seed-draft-test',
  name: 'General Assistant',
  createdAt: '2026-11-10T00:00:00Z'
};

const SYNTHETIC_EXISTING_CONV = {
  id: 'conv-existing-draft-1',
  botId: 'bot-seed-draft-test',
  title: '既有對話討論串 A',
  pinned: false,
  createdAt: '2026-11-10T01:00:00Z',
  updatedAt: '2026-11-10T01:00:00Z'
};

const SYNTHETIC_EXISTING_MESSAGES = [
  {
    id: 'm-exist-1',
    conversationId: 'conv-existing-draft-1',
    runId: 'run-exist-1',
    role: 'USER',
    content: '這是既有對話中的使用者發言',
    interrupted: false,
    createdAt: '2026-11-10T01:00:01Z'
  },
  {
    id: 'm-exist-2',
    conversationId: 'conv-existing-draft-1',
    runId: 'run-exist-1',
    role: 'ASSISTANT',
    content: '這是既有對話中的助手完整回應',
    interrupted: false,
    createdAt: '2026-11-10T01:00:02Z'
  }
];

const MULTILINE_TEST_DRAFT =
  ' 第一行：繁體中文多行草稿測試。\n' +
  'Second line: English text with leading/trailing spaces   \n' +
  '第三行：符號混排 !@#$%^&*()_+ 測試換行\n' +
  '   \n' +
  '第五行：結尾空行驗證。';

async function run() {
  console.log('=== Starting Ticket Draft & Delayed Creation Verification (Strict Reconciled Mock) ===');

  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: true,
    args: ['--no-sandbox', '--disable-gpu', '--window-size=1280,800']
  });

  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 800 });

  // Mock State & Controlled Deferred Barriers
  let createConversationCallCount = 0;
  let startRunCallCount = 0;

  // Failure and delay simulation switches
  let createFailureMode = 'none'; // 'none' | 'http500' | 'gqlError'
  let shouldFailRun = false;
  let returnEmptyRunsOnRecovery = false;

  // Controlled deferred barriers for strict ordering
  let createBarrierPromise = null;
  let createBarrierResolve = null;

  let lastCreatedConvId = null;
  let lastSubmittedMessage = '';
  let lastClientRequestId = null;

  // Order tracking via lifecycle states
  let isCreateInProgress = false;
  let isCreateCompleted = false;

  const dispatchedEvents = [];

  // Enable request interception
  await page.setRequestInterception(true);

  page.on('request', async (req) => {
    const url = req.url();
    const method = req.method();

    // 1. Intercept GraphQL (/graphql)
    if (url.includes('/graphql')) {
      if (method !== 'POST') {
        console.error(`[HARD FAIL] Unexpected method ${method} on /graphql`);
        return req.respond({ status: 405, contentType: 'application/json', body: JSON.stringify({ error: 'Method Not Allowed' }) });
      }

      let postData = {};
      try {
        postData = JSON.parse(req.postData() || '{}');
      } catch (err) {
        console.error('[HARD FAIL] Failed to parse GraphQL body:', err);
        return req.respond({ status: 400, contentType: 'application/json', body: JSON.stringify({ error: 'Bad JSON' }) });
      }

      const query = postData.query || '';
      const variables = postData.variables || {};

      // A. Query: bots
      if (query.includes('bots(')) {
        return req.respond({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: { bots: [SYNTHETIC_BOT] } })
        });
      }

      // B. Query: conversations list
      if (query.includes('conversations(') && !query.includes('conversation(')) {
        const pinnedVar = variables.pinned;
        const limit = typeof variables.limit === 'number' ? variables.limit : 20;
        const offset = typeof variables.offset === 'number' ? variables.offset : 0;

        const allConvs = [SYNTHETIC_EXISTING_CONV];
        if (lastCreatedConvId) {
          allConvs.unshift({
            id: lastCreatedConvId,
            botId: SYNTHETIC_BOT.id,
            title: 'New Conversation',
            pinned: false,
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString()
          });
        }

        let filtered = allConvs;
        if (typeof pinnedVar === 'boolean') {
          filtered = allConvs.filter(c => c.pinned === pinnedVar);
        }

        const paged = filtered.slice(offset, offset + limit);
        return req.respond({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: { conversations: paged } })
        });
      }

      // C. Query: GetRecovery
      if (query.includes('GetRecovery')) {
        const targetId = variables.id;
        const runs = returnEmptyRunsOnRecovery
          ? [] // Allows app to see unpersisted run and render Retry button
          : [{ id: 'run-mock-1', clientRequestId: lastClientRequestId, status: 'COMPLETED', errorMessage: null }];

        return req.respond({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              conversation: {
                id: targetId,
                runs
              }
            }
          })
        });
      }

      // D. Query: GetAuthoritativeMessagesAndRun or single conversation details
      if (query.includes('conversation(')) {
        const targetId = variables.id || variables.convId;

        if (targetId === SYNTHETIC_EXISTING_CONV.id) {
          return req.respond({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
              data: {
                conversation: {
                  ...SYNTHETIC_EXISTING_CONV,
                  messages: SYNTHETIC_EXISTING_MESSAGES,
                  runs: [{ id: 'run-exist-1', status: 'COMPLETED', errorMessage: null }]
                }
              }
            })
          });
        }

        // Newly created synthetic conversation
        const messages = [
          {
            id: 'msg-user-1',
            conversationId: targetId,
            runId: 'run-mock-1',
            role: 'USER',
            content: lastSubmittedMessage || 'Synthetic user message',
            interrupted: false,
            createdAt: new Date().toISOString()
          },
          {
            id: 'msg-assistant-1',
            conversationId: targetId,
            runId: 'run-mock-1',
            role: 'ASSISTANT',
            content: '這是受控 Mock 的助理回覆。',
            interrupted: false,
            createdAt: new Date().toISOString()
          }
        ];

        return req.respond({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              conversation: {
                id: targetId,
                botId: SYNTHETIC_BOT.id,
                title: 'New Conversation',
                pinned: false,
                createdAt: new Date().toISOString(),
                updatedAt: new Date().toISOString(),
                messages,
                runs: [{ id: 'run-mock-1', status: 'COMPLETED', errorMessage: null }]
              }
            }
          })
        });
      }

      // E. Mutation: createConversation
      if (query.includes('createConversation(')) {
        createConversationCallCount++;
        isCreateInProgress = true;
        dispatchedEvents.push({ type: 'createConversation_started', count: createConversationCallCount });

        if (createBarrierPromise) {
          await createBarrierPromise;
        }

        if (createFailureMode === 'http500') {
          isCreateInProgress = false;
          return req.respond({
            status: 500,
            contentType: 'application/json',
            body: JSON.stringify({ error: 'Synthetic Database Connection Timeout on createConversation (500)' })
          });
        }

        if (createFailureMode === 'gqlError') {
          isCreateInProgress = false;
          return req.respond({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
              data: null,
              errors: [{ message: 'Synthetic GraphQL Business Rule Exception: Bot unavailable' }]
            })
          });
        }

        lastCreatedConvId = `conv-created-${Date.now()}`;
        isCreateCompleted = true;
        isCreateInProgress = false;
        dispatchedEvents.push({ type: 'createConversation_completed', convId: lastCreatedConvId });

        return req.respond({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              createConversation: {
                id: lastCreatedConvId,
                botId: variables.botId || SYNTHETIC_BOT.id,
                title: variables.title || 'New Conversation',
                pinned: false,
                createdAt: new Date().toISOString(),
                updatedAt: new Date().toISOString()
              }
            }
          })
        });
      }

      // E2. Mutation: setConversationPinned
      if (query.includes('setConversationPinned(')) {
        const targetId = variables.id;
        const targetPinned = Boolean(variables.pinned);
        return req.respond({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              setConversationPinned: {
                id: targetId,
                botId: SYNTHETIC_BOT.id,
                title: 'New Conversation',
                pinned: targetPinned,
                createdAt: new Date().toISOString(),
                updatedAt: new Date().toISOString()
              }
            }
          })
        });
      }

      // Unknown GraphQL query: HARD FAIL to prevent hitting real backend
      console.error(`[HARD FAIL] Unrecognized GraphQL query: ${query.substring(0, 100)}...`);
      return req.respond({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ error: `[HARD FAIL] Unmocked GraphQL operation: ${query.substring(0, 60)}` })
      });
    }

    // 2. Intercept REST SSE (/api/conversations/{id}/runs)
    if (url.includes('/api/conversations/') && url.includes('/runs')) {
      if (method !== 'POST') {
        console.error(`[HARD FAIL] Unexpected method ${method} on /runs`);
        return req.respond({ status: 405, contentType: 'application/json', body: JSON.stringify({ error: 'Method Not Allowed' }) });
      }

      startRunCallCount++;
      let postBody = {};
      try {
        postBody = JSON.parse(req.postData() || '{}');
      } catch (_) {}

      lastSubmittedMessage = postBody.message || '';
      lastClientRequestId = postBody.clientRequestId || null;

      dispatchedEvents.push({
        type: 'startRun_dispatched',
        url,
        clientRequestId: lastClientRequestId,
        createWasCompleted: isCreateCompleted
      });

      if (shouldFailRun) {
        return req.respond({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ error: 'Synthetic Model Upstream Failure (500)' })
        });
      }

      // Authoritative Spring AI / Herdr SSE wire contract
      const sseFrames = [
        'event: run.started',
        'data: {"seq":1,"runId":"run-mock-1","status":"RUNNING","userMessageId":"msg-user-1"}',
        '',
        'event: message.delta',
        'data: {"seq":2,"runId":"run-mock-1","delta":"這是一段受控 Mock 的流式回覆。"}',
        '',
        'event: run.completed',
        'data: {"seq":3,"runId":"run-mock-1","status":"COMPLETED","messageId":"msg-assistant-1"}',
        '',
        ''
      ].join('\n');

      return req.respond({
        status: 200,
        contentType: 'text/event-stream',
        headers: {
          'Cache-Control': 'no-cache',
          Connection: 'keep-alive'
        },
        body: sseFrames
      });
    }

    // 3. Catch-all for unknown /api/ requests: HARD FAIL
    if (url.includes('/api/')) {
      console.error(`[HARD FAIL] Unrecognized API request: ${method} ${url}`);
      return req.respond({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ error: `[HARD FAIL] Unmocked API endpoint: ${url}` })
      });
    }

    // 4. Static web assets pass through
    return req.continue();
  });

  try {
    console.log(`Navigating to ${BASE_URL}...`);
    await page.goto(BASE_URL, { waitUntil: 'networkidle0' });

    // Precise DOM Selectors
    const newChatBtnSelector = '[data-testid="new-chat-btn"] button, button[aria-label="+ New Chat"]';
    const textareaSelector = 'copilot-chat-input textarea, textarea[data-testid="chat-input"], .composer-textarea, textarea';
    const sendBtnSelector = 'copilot-chat-send-button button, button[aria-label="Send message"]';
    const exactExistingConvSelector = `[data-id="${SYNTHETIC_EXISTING_CONV.id}"]`;

    await page.waitForSelector(newChatBtnSelector, { timeout: 10000 });
    await page.waitForSelector(textareaSelector, { timeout: 10000 });

    // Helper: Native input value setter (avoids page.type Enter dispatch on newlines)
    const setNativeTextareaValue = async (value) => {
      await page.evaluate((sel, val) => {
        const el = document.querySelector(sel);
        if (el) {
          const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value')?.set;
          if (setter) {
            setter.call(el, val);
          } else {
            el.value = val;
          }
          el.dispatchEvent(new Event('input', { bubbles: true }));
          el.dispatchEvent(new Event('change', { bubbles: true }));
        }
      }, textareaSelector, value);
      // Settle Angular signal and change detection cycle
      await new Promise((r) => setTimeout(r, 80));
    };

    const clickSend = async () => {
      await page.waitForFunction(() => {
        const btn = document.querySelector('copilot-chat-send-button button, button[aria-label="Send message"]');
        return btn && !btn.disabled && btn.getAttribute('aria-disabled') !== 'true';
      }, { timeout: 5000 });
      await page.click(sendBtnSelector);
    };

    // ---------------------------------------------------------------------------------
    // TEST CASE 1: Repeated New Chat clicks do NOT issue createConversation mutation
    // ---------------------------------------------------------------------------------
    console.log('\n--- Test Case 1: Repeated New Chat clicks without input ---');
    const initialCreateCount = createConversationCallCount;

    for (let i = 1; i <= 4; i++) {
      await page.click(newChatBtnSelector);
      await new Promise((r) => setTimeout(r, 100));
    }

    if (createConversationCallCount !== initialCreateCount) {
      throw new Error(`TEST FAILED: Repeated "+ New Chat" clicks must NOT trigger createConversation! Expected ${initialCreateCount}, got ${createConversationCallCount}`);
    }
    console.log('PASS: Zero createConversation calls triggered by repeated New Chat clicks.');
    await page.screenshot({ path: SCREENSHOTS.repeatedNewChat });

    // ---------------------------------------------------------------------------------
    // TEST CASE 2: Multi-line Chinese & English & spaces draft preserved across conversation switch
    // ---------------------------------------------------------------------------------
    console.log('\n--- Test Case 2: Multi-line Chinese & English draft preservation across switch ---');
    console.log('Setting multi-line draft into New Chat input via native setter...');
    await setNativeTextareaValue(MULTILINE_TEST_DRAFT);

    const typedValue = await page.$eval(textareaSelector, (el) => el.value);
    if (typedValue !== MULTILINE_TEST_DRAFT) {
      throw new Error('TEST FAILED: Textarea failed to hold initial multi-line draft');
    }

    console.log(`Switching to existing conversation (${exactExistingConvSelector})...`);
    await page.waitForSelector(exactExistingConvSelector, { timeout: 5000 });
    await page.click(exactExistingConvSelector);

    // Wait until existing conversation messages render
    await page.waitForFunction(() => {
      const msgs = document.querySelectorAll('.message-row, [data-message-role]');
      return msgs.length >= 2;
    }, { timeout: 5000 });

    console.log('Switched to existing conversation. Now clicking "+ New Chat" to return...');
    await page.click(newChatBtnSelector);

    // Wait and verify New Chat restored the exact multi-line draft
    await page.waitForFunction(
      (sel, expected) => {
        const el = document.querySelector(sel);
        return el && el.value === expected;
      },
      { timeout: 5000 },
      textareaSelector,
      MULTILINE_TEST_DRAFT
    );

    const restoredValue = await page.$eval(textareaSelector, (el) => el.value);
    if (restoredValue !== MULTILINE_TEST_DRAFT) {
      throw new Error(`TEST FAILED: Restored draft mismatch!\nExpected:\n${MULTILINE_TEST_DRAFT}\nGot:\n${restoredValue}`);
    }
    console.log('PASS: Exact multi-line draft completely preserved across navigation!');
    await page.screenshot({ path: SCREENSHOTS.multilinePreserved });
    await page.screenshot({ path: SCREENSHOTS.roundtripRestored });

    // ---------------------------------------------------------------------------------
    // TEST CASE 3: Sending a message inside an existing conversation does NOT clear newChatDraft
    // ---------------------------------------------------------------------------------
    console.log('\n--- Test Case 3: Sending message in existing conversation preserves newChatDraft ---');
    // Ensure newChatDraft currently holds MULTILINE_TEST_DRAFT
    console.log('Navigating to existing conversation...');
    await page.click(exactExistingConvSelector);

    // Wait for existing conversation active state and messages to mount
    await page.waitForFunction(
      (sel) => {
        const item = document.querySelector(sel);
        const msgs = document.querySelectorAll('.message-row, [data-message-role]');
        return item && item.classList.contains('active') && msgs.length >= 2;
      },
      { timeout: 5000 },
      exactExistingConvSelector
    );

    // Send a message inside the existing conversation
    const EXISTING_CONV_MSG = '既有對話中的即時發言';
    await setNativeTextareaValue(EXISTING_CONV_MSG);
    await clickSend();

    // Wait for run completion inside existing conversation
    await page.waitForFunction(() => {
      const stopBtn = document.querySelector('[data-testid="stop-btn"] button, button[aria-label="Stop generation"]');
      return !stopBtn;
    }, { timeout: 10000 });

    console.log('Message sent and completed in existing conversation. Switching back to "+ New Chat"...');
    await page.click(newChatBtnSelector);

    // Assert that New Chat STILL holds the original MULTILINE_TEST_DRAFT!
    await page.waitForFunction(
      (sel, expected) => {
        const el = document.querySelector(sel);
        return el && el.value === expected;
      },
      { timeout: 5000 },
      textareaSelector,
      MULTILINE_TEST_DRAFT
    );

    const draftAfterExistingSend = await page.$eval(textareaSelector, (el) => el.value);
    if (draftAfterExistingSend !== MULTILINE_TEST_DRAFT) {
      throw new Error('TEST FAILED: Sending message in existing conversation wiped out unpersisted newChatDraft!');
    }
    console.log('PASS: newChatDraft is strictly isolated and immune to existing conversation message sends.');
    await page.screenshot({ path: SCREENSHOTS.existingSendPreservesNewDraft });

    // ---------------------------------------------------------------------------------
    // TEST CASE 4: Blank / whitespace-only submit does NOT create conversation
    // ---------------------------------------------------------------------------------
    console.log('\n--- Test Case 4: Blank / whitespace-only submit blocked ---');
    await setNativeTextareaValue('   \n\t   \n  ');

    const countBeforeBlankSubmit = createConversationCallCount;
    // Attempt keyboard Enter
    await page.focus(textareaSelector);
    await page.keyboard.press('Enter');
    await new Promise((r) => setTimeout(r, 200));

    if (createConversationCallCount !== countBeforeBlankSubmit) {
      throw new Error('TEST FAILED: Submitting whitespace-only input must NOT trigger createConversation!');
    }
    console.log('PASS: Whitespace submit cleanly blocked.');
    await page.screenshot({ path: SCREENSHOTS.blankSubmitBlocked });

    // CLEANUP TEXTAREA BEFORE NEXT CASE TO PREVENT POLLUTION
    console.log('Cleanly resetting textarea before valid submit tests...');
    await setNativeTextareaValue('');
    const cleanCheck = await page.$eval(textareaSelector, (el) => el.value);
    if (cleanCheck !== '') {
      throw new Error('Failed to cleanly empty textarea');
    }

    // ---------------------------------------------------------------------------------
    // TEST CASE 5: Native Send / Enter first submit -> strict sequence via barrier
    // ---------------------------------------------------------------------------------
    console.log('\n--- Test Case 5: First submit strictly ordered (create -> run via controlled barrier) ---');
    const VALID_FIRST_MESSAGE = '這是驗證首次送出嚴格順序 (create -> run) 的發言';
    await page.focus(textareaSelector);
    await page.type(textareaSelector, VALID_FIRST_MESSAGE);

    // Set up controlled barrier on createConversation
    let resolveBarrier;
    createBarrierPromise = new Promise((resolve) => {
      resolveBarrier = resolve;
    });

    isCreateInProgress = false;
    isCreateCompleted = false;
    dispatchedEvents.length = 0;

    const countBeforeFirstSendCreate = createConversationCallCount;
    const countBeforeFirstSendRun = startRunCallCount;

    console.log('Triggering Send button with createConversation barrier held...');
    await clickSend();

    // Wait until create has begun
    await page.waitForFunction(() => {
      return document.querySelector('button[aria-label="Send message"]')?.disabled === true;
    }, { timeout: 5000 });

    if (startRunCallCount !== countBeforeFirstSendRun) {
      throw new Error('TEST FAILED: startRun was dispatched before createConversation completed!');
    }

    console.log('Releasing create barrier...');
    createBarrierPromise = null;
    resolveBarrier();

    // Wait until run completes
    await page.waitForFunction(() => {
      const stopBtn = document.querySelector('[data-testid="stop-btn"] button, button[aria-label="Stop generation"]');
      const assistantMsg = document.querySelector('[data-message-role="assistant"], .message-row.assistant');
      return Boolean(!stopBtn && assistantMsg && assistantMsg.textContent.includes('受控 Mock 的流式回覆'));
    }, { timeout: 10000 });

    if (createConversationCallCount !== countBeforeFirstSendCreate + 1) {
      throw new Error(`TEST FAILED: Exactly 1 createConversation expected, got ${createConversationCallCount - countBeforeFirstSendCreate}`);
    }
    if (startRunCallCount !== countBeforeFirstSendRun + 1) {
      throw new Error(`TEST FAILED: Exactly 1 startRun expected, got ${startRunCallCount - countBeforeFirstSendRun}`);
    }

    const runEvent = dispatchedEvents.find((e) => e.type === 'startRun_dispatched');
    if (!runEvent || !runEvent.createWasCompleted) {
      throw new Error('TEST FAILED: startRun was dispatched without createCompleted confirmed!');
    }
    console.log('PASS: Strict sequence verified (createConversation completed before startRun dispatched).');
    await page.screenshot({ path: SCREENSHOTS.firstSendSequence });

    // ---------------------------------------------------------------------------------
    // TEST CASE 6: Rapid Double Submit during in-flight create is strictly guarded
    // ---------------------------------------------------------------------------------
    console.log('\n--- Test Case 6: Rapid double submit during in-flight create ---');
    await page.click(newChatBtnSelector);
    await setNativeTextareaValue('');

    const DOUBLE_SUBMIT_MSG = '驗證連續雙擊防雙送訊息';
    await page.focus(textareaSelector);
    await page.type(textareaSelector, DOUBLE_SUBMIT_MSG);

    // Hold create barrier for 600ms
    createBarrierPromise = new Promise((resolve) => setTimeout(resolve, 600));
    const createCountBeforeDouble = createConversationCallCount;

    console.log('Rapidly triggering submit twice (Click + Enter in immediate succession)...');
    await clickSend();
    await page.keyboard.press('Enter');
    await page.click(sendBtnSelector).catch(() => {});

    // Wait for run completion
    await page.waitForFunction(() => {
      const stopBtn = document.querySelector('[data-testid="stop-btn"] button, button[aria-label="Stop generation"]');
      const assistantMsg = document.querySelector('[data-message-role="assistant"], .message-row.assistant');
      return Boolean(!stopBtn && assistantMsg && assistantMsg.textContent.includes('受控 Mock 的流式回覆'));
    }, { timeout: 10000 });

    createBarrierPromise = null;
    const doubleSubmitCreatedCount = createConversationCallCount - createCountBeforeDouble;
    if (doubleSubmitCreatedCount !== 1) {
      throw new Error(`TEST FAILED: Double submit caused duplicate createConversation! Created ${doubleSubmitCreatedCount} times`);
    }
    console.log('PASS: Double submit safely guarded (exactly 1 createConversation executed).');
    await page.screenshot({ path: SCREENSHOTS.doubleSubmitGuarded });

    // ---------------------------------------------------------------------------------
    // TEST CASE 7: createConversation failure via HTTP 500 preserves exact original draft
    // ---------------------------------------------------------------------------------
    console.log('\n--- Test Case 7: createConversation HTTP 500 preserves exact draft ---');
    await page.click(newChatBtnSelector);
    await setNativeTextareaValue('');

    const DRAFT_500 = 'HTTP 500 失敗測試：必須嚴格保留的完整草稿！\n第二行格式與換行符號 123456';
    await setNativeTextareaValue(DRAFT_500);

    createFailureMode = 'http500';
    console.log('Triggering submit with mock createConversation returning 500...');
    await clickSend();

    // Wait for HTTP 500 error banner and draft restoration
    await page.waitForFunction((expectedDraft) => {
      const banner = document.querySelector('[data-testid="stream-error-banner"], .stream-error-banner');
      const textarea = document.querySelector('copilot-chat-input textarea, textarea[data-testid="chat-input"], .composer-textarea, textarea');
      const sendBtn = document.querySelector('copilot-chat-send-button button, button[aria-label="Send message"]');
      const hasHttp500 = banner && banner.textContent.includes('500');
      const hasDraft = textarea && textarea.value === expectedDraft;
      const notSubmitting = sendBtn && !sendBtn.disabled;
      return Boolean(hasHttp500 && hasDraft && notSubmitting);
    }, { timeout: 8000 }, DRAFT_500);

    // Strict === check
    const valAfter500 = await page.$eval(textareaSelector, (el) => el.value);
    if (valAfter500 !== DRAFT_500) {
      throw new Error(`TEST FAILED: Textarea draft was corrupted on HTTP 500!\nExpected:\n${DRAFT_500}\nGot:\n${valAfter500}`);
    }
    console.log('PASS: Draft strictly preserved (===) after HTTP 500 create failure.');
    await page.screenshot({ path: SCREENSHOTS.create500Restored });

    // ---------------------------------------------------------------------------------
    // TEST CASE 8: createConversation failure via GraphQL 200 errors preserves exact draft
    // ---------------------------------------------------------------------------------
    console.log('\n--- Test Case 8: createConversation GraphQL 200 errors preserves exact draft ---');
    const DRAFT_GQL_ERR = 'GraphQL Errors 200 測試：業務例外時完整保留字串！\n第二行英數 123456';
    await setNativeTextareaValue(DRAFT_GQL_ERR);

    createFailureMode = 'gqlError';
    console.log('Triggering submit with mock createConversation returning GraphQL errors...');
    await clickSend();

    // Wait for new error banner with GraphQL error message and draft restoration
    await page.waitForFunction((expectedDraft) => {
      const banner = document.querySelector('[data-testid="stream-error-banner"], .stream-error-banner');
      const textarea = document.querySelector('copilot-chat-input textarea, textarea[data-testid="chat-input"], .composer-textarea, textarea');
      const sendBtn = document.querySelector('copilot-chat-send-button button, button[aria-label="Send message"]');
      const hasGqlError = banner && banner.textContent.includes('Bot unavailable');
      const hasDraft = textarea && textarea.value === expectedDraft;
      const notSubmitting = sendBtn && !sendBtn.disabled;
      return Boolean(hasGqlError && hasDraft && notSubmitting);
    }, { timeout: 8000 }, DRAFT_GQL_ERR);

    // Strict === check
    const valAfterGqlErr = await page.$eval(textareaSelector, (el) => el.value);
    if (valAfterGqlErr !== DRAFT_GQL_ERR) {
      throw new Error(`TEST FAILED: Textarea draft was corrupted on GraphQL Error!\nExpected:\n${DRAFT_GQL_ERR}\nGot:\n${valAfterGqlErr}`);
    }
    console.log('PASS: Draft strictly preserved (===) after GraphQL errors create failure.');
    await page.screenshot({ path: SCREENSHOTS.createGqlErrorRestored });

    // ---------------------------------------------------------------------------------
    // TEST CASE 9: run failure retry reuses created conversation & clientRequestId without duplicate create
    // ---------------------------------------------------------------------------------
    console.log('\n--- Test Case 9: run failure retry reuses conversation ID & clientRequestId ---');
    createFailureMode = 'none';
    shouldFailRun = true;
    returnEmptyRunsOnRecovery = true; // Signals GetRecovery to return runs=[]

    const DRAFT_FOR_RETRY = '首送 run 失敗後測試手動重試之發言內容';
    await setNativeTextareaValue(DRAFT_FOR_RETRY);

    dispatchedEvents.length = 0;
    const createCountBeforeRetryTest = createConversationCallCount;

    console.log('Submitting draft (create succeeds, startRun returns 500)...');
    await clickSend();

    // Wait for retry button
    const retryBtnSelector = '[data-testid="retry-btn"] button, button[aria-label="重試"], [data-testid="retry-btn"]';
    await page.waitForSelector(retryBtnSelector, { timeout: 10000 });
    console.log('Retry button rendered as expected.');

    const initialRunEvent = dispatchedEvents.find((e) => e.type === 'startRun_dispatched');
    if (!initialRunEvent) {
      throw new Error('Expected initial startRun event before retry');
    }
    const targetConvUrl = initialRunEvent.url;
    const initialReqId = initialRunEvent.clientRequestId;
    console.log(`Initial run URL: ${targetConvUrl}, clientRequestId: ${initialReqId}`);

    // Now lift run failure and restore authoritative recovery
    shouldFailRun = false;
    returnEmptyRunsOnRecovery = false;
    dispatchedEvents.length = 0;

    console.log('Clicking Retry button...');
    await page.click(retryBtnSelector);

    // Wait for completion
    await page.waitForFunction(() => {
      const stopBtn = document.querySelector('[data-testid="stop-btn"] button, button[aria-label="Stop generation"]');
      const assistantMsg = document.querySelector('[data-message-role="assistant"], .message-row.assistant');
      return Boolean(!stopBtn && assistantMsg && assistantMsg.textContent.includes('受控 Mock 的流式回覆'));
    }, { timeout: 10000 });

    // Assert that createConversation was NOT called again!
    if (createConversationCallCount !== createCountBeforeRetryTest + 1) {
      throw new Error(`TEST FAILED: Retry must NOT trigger createConversation again! Calls: ${createConversationCallCount - createCountBeforeRetryTest}`);
    }

    const retryRunEvent = dispatchedEvents.find((e) => e.type === 'startRun_dispatched');
    if (!retryRunEvent || retryRunEvent.url !== targetConvUrl) {
      throw new Error(`TEST FAILED: Retry did not reuse established conversation URL!\nExpected: ${targetConvUrl}\nGot: ${retryRunEvent?.url}`);
    }
    if (retryRunEvent.clientRequestId !== initialReqId) {
      throw new Error(`TEST FAILED: Retry did not reuse original clientRequestId!\nExpected: ${initialReqId}\nGot: ${retryRunEvent?.clientRequestId}`);
    }
    console.log('PASS: Retry cleanly reuses established conversation ID and clientRequestId.');
    await page.screenshot({ path: SCREENSHOTS.runFailedRetry });

    // ---------------------------------------------------------------------------------
    // TEST CASE 10: Post-retry "+ New Chat" click resets textarea to empty & zero createConversation
    // ---------------------------------------------------------------------------------
    console.log('\n--- Test Case 10: Post-retry "+ New Chat" click clears draft and does not create conversation ---');
    const createCountBeforePostRetryNewChat = createConversationCallCount;

    console.log('Clicking "+ New Chat" after successful retry...');
    await page.click(newChatBtnSelector);

    // Wait and verify textarea is strictly empty (submitted draft was fully consumed)
    await page.waitForFunction(
      (sel) => {
        const el = document.querySelector(sel);
        return el && el.value === '';
      },
      { timeout: 5000 },
      textareaSelector
    );

    const postRetryNewChatText = await page.$eval(textareaSelector, (el) => el.value);
    if (postRetryNewChatText !== '') {
      throw new Error(`TEST FAILED: Textarea should be empty on New Chat after retry completion! Got: "${postRetryNewChatText}"`);
    }

    if (createConversationCallCount !== createCountBeforePostRetryNewChat) {
      throw new Error(`TEST FAILED: Clicking "+ New Chat" after retry must NOT trigger createConversation! Expected ${createCountBeforePostRetryNewChat}, got ${createConversationCallCount}`);
    }
    console.log('PASS: Post-retry New Chat click correctly resets textarea to empty and triggers zero createConversation mutations.');
    await page.screenshot({ path: SCREENSHOTS.postRetryNewChatCleared });

    // ---------------------------------------------------------------------------------
    // TEST CASE 11: In-flight submission locks selection & transitions safely to established conversation
    // ---------------------------------------------------------------------------------
    console.log('\n--- Test Case 11: Locked selection during in-flight create & safe transition ---');
    await page.click(newChatBtnSelector);
    await setNativeTextareaValue('');

    const IN_FLIGHT_MSG = '驗證送出鎖定期間點選側欄被安全鎖定，回應後正常建立新對話';
    await page.focus(textareaSelector);
    await page.type(textareaSelector, IN_FLIGHT_MSG);

    // Hold create barrier for 600ms
    createBarrierPromise = new Promise((resolve) => setTimeout(resolve, 600));

    console.log('Submitting message with 600ms in-flight creation...');
    await clickSend();

    // Verify submit lock is engaged
    await page.waitForFunction(() => {
      const sendBtn = document.querySelector('button[aria-label="Send message"]');
      return Boolean(sendBtn && sendBtn.disabled);
    }, { timeout: 3000 });

    // Attempt to click existing conversation during in-flight submission
    console.log('User attempts to click sidebar conversation during in-flight submission (should be locked)...');
    await page.click(exactExistingConvSelector).catch(() => {});

    // Wait for create barrier and run completion
    createBarrierPromise = null;
    await page.waitForFunction(() => {
      const stopBtn = document.querySelector('[data-testid="stop-btn"] button, button[aria-label="Stop generation"]');
      const assistantMsg = document.querySelector('[data-message-role="assistant"], .message-row.assistant');
      return Boolean(!stopBtn && assistantMsg && assistantMsg.textContent.includes('受控 Mock 的流式回覆'));
    }, { timeout: 12000 });

    // Assert that after run completes, the newly created conversation is properly established
    const activeConvText = await page.$eval('.conversation-item.active, [data-id].active', (el) => el.textContent?.trim());
    console.log('Active conversation after run completed:', activeConvText);
    if (!activeConvText?.includes('New Conversation')) {
      throw new Error(`TEST FAILED: Newly created conversation was not properly established! Active: ${activeConvText}`);
    }
    console.log('PASS: Selection locked during in-flight create, successfully established new conversation on completion.');
    await page.screenshot({ path: SCREENSHOTS.lateCreateGuarded });

    console.log('\n======================================================');
    console.log('ALL 11 DRAFT & DELAYED CONVERSATION TESTS PASSED (100%)');
    console.log('======================================================');
  } finally {
    await browser.close();
  }
}

// Export for test harness execution
export { run };

// Execute if run directly from CLI
if (process.argv[1] && process.argv[1].endsWith('verify-draft.mjs')) {
  run().catch((err) => {
    console.error('\n❌ VERIFICATION SCRIPT FAILED:', err);
    process.exit(1);
  });
}
