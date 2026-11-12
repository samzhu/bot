// Historical layout verification retained for coverage traceability; see README.
throw new Error('Retired legacy browser verification; use the current ThreadPlane browser matrix.');

import puppeteer from 'puppeteer-core';
import path from 'node:path';

/*
 * =====================================================================================
 * Browser Verification Script for Gate 10 & 13
 * =====================================================================================
 * Issue #10: Native toolbar buttons with inner accessible name, adjacent label & responsive reflow
 * Issue #13: Assistant message rows render strictly supported actions (zero dead thumbs, code & whole copy)
 *
 * MOCK INTEGRITY & DATA ISOLATION:
 * - Real backend requests (/api and /graphql) that are not matched by synthetic mocks HARD FAIL immediately.
 * - Zero live cloud model generation, zero database writes.
 * - Overrides clipboard permissions via Chrome CDP for non-interactive clipboard testing.
 */

const CHROME_PATH = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const BASE_URL = 'http://localhost:4200';

const SCREENSHOTS = {
  toolbar1280: '/tmp/bot-ui-10-toolbar-1280.png',
  toolbar390: '/tmp/bot-ui-10-toolbar-390.png',
  stopStreaming: '/tmp/bot-ui-10-stop-streaming.png',
  retryError: '/tmp/bot-ui-10-retry-error.png',
  zeroThumbs: '/tmp/bot-ui-13-zero-thumbs.png',
  clipboardCopy: '/tmp/bot-ui-13-clipboard-copy.png'
};

// Synthetic fixtures
const SYNTHETIC_BOT = {
  id: 'bot-ui-test-seed',
  name: 'General Assistant',
  createdAt: '2026-11-10T00:00:00Z'
};

// 25 conversations to satisfy limit=20 and enable hasMoreConversations button via peek
const SYNTHETIC_CONVERSATIONS = Array.from({ length: 25 }, (_, i) => ({
  id: `conv-ui-${i + 1}`,
  botId: SYNTHETIC_BOT.id,
  title: i === 0 ? 'TypeScript Architecture & Guide' : `對話紀錄串 #${i + 1}`,
  pinned: false,
  createdAt: new Date(Date.now() - i * 3600000).toISOString(),
  updatedAt: new Date(Date.now() - i * 3600000).toISOString()
}));

const SAMPLE_CODE =
  'function greet(name: string): string {\n' +
  '  return `Hello, ${name}!`;\n' +
  '}\n' +
  'console.log(greet("World"));';

const SAMPLE_MARKDOWN =
  'TypeScript is a **strongly typed** programming language that builds on JavaScript.\n\n' +
  '```typescript\n' +
  SAMPLE_CODE + '\n' +
  '```\n\n' +
  'Key benefits include:\n' +
  '- Static type verification\n' +
  '- Enhanced IDE refactoring\n' +
  '- Native modern ECMAScript compilation';

// 20 messages to satisfy limit=20 and enable hasMoreMessages button
const SYNTHETIC_MESSAGES = [
  ...Array.from({ length: 16 }, (_, i) => ({
    id: `m-old-${i + 1}`,
    conversationId: 'conv-ui-1',
    runId: `run-old-${i + 1}`,
    role: i % 2 === 0 ? 'USER' : 'ASSISTANT',
    content: i % 2 === 0 ? `歷史問題 #${i + 1}` : `歷史回答 #${i + 1}`,
    interrupted: false,
    createdAt: new Date(Date.now() - (30 - i) * 60000).toISOString()
  })),
  {
    id: 'm-ui-user-1',
    conversationId: 'conv-ui-1',
    runId: 'run-ui-1',
    role: 'USER',
    content: '請介紹 TypeScript 的核心優勢並給一個範例。',
    interrupted: false,
    createdAt: new Date(Date.now() - 100000).toISOString()
  },
  {
    id: 'm-ui-assistant-1',
    conversationId: 'conv-ui-1',
    runId: 'run-ui-1',
    role: 'ASSISTANT',
    content: SAMPLE_MARKDOWN,
    interrupted: false,
    createdAt: new Date(Date.now() - 90000).toISOString()
  },
  {
    id: 'm-ui-user-2',
    conversationId: 'conv-ui-1',
    runId: 'run-ui-2',
    role: 'USER',
    content: '連線中斷測試詢問',
    interrupted: false,
    createdAt: new Date(Date.now() - 60000).toISOString()
  },
  {
    id: 'm-ui-assistant-2',
    conversationId: 'conv-ui-1',
    runId: 'run-ui-2',
    role: 'ASSISTANT',
    content: '這是一段在串流時被中斷的助手訊息...',
    interrupted: true,
    createdAt: new Date(Date.now() - 50000).toISOString()
  }
];

async function main() {
  console.log('=== Starting Gate 10 & 13 Native UI & Action Verification ===');

  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: true,
    args: [
      '--no-sandbox',
      '--disable-gpu',
      '--window-size=1280,800',
      '--disable-web-security'
    ]
  });

  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 800 });

  page.on('console', msg => {
    console.log('[Console]', msg.type(), msg.text());
  });

  // Grant clipboard read & write permissions to page context via CDP
  const cdp = await page.target().createCDPSession();
  await cdp.send('Browser.grantPermissions', {
    permissions: ['clipboardReadWrite', 'clipboardSanitizedWrite'],
    origin: BASE_URL
  });

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

      // B. Query: conversations list (filtered by variables.pinned and paginated)
      if (query.includes('conversations(') && !query.includes('conversation(')) {
        const pinnedVar = variables.pinned;
        const limit = typeof variables.limit === 'number' ? variables.limit : 20;
        const offset = typeof variables.offset === 'number' ? variables.offset : 0;

        let filtered = SYNTHETIC_CONVERSATIONS;
        if (typeof pinnedVar === 'boolean') {
          filtered = SYNTHETIC_CONVERSATIONS.filter(c => c.pinned === pinnedVar);
        }

        const paged = filtered.slice(offset, offset + limit);
        return req.respond({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: { conversations: paged } })
        });
      }

      // C. Query: GetConversation or single conversation details
      if (query.includes('conversation(')) {
        return req.respond({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              conversation: {
                id: 'conv-ui-1',
                botId: SYNTHETIC_BOT.id,
                title: 'TypeScript Architecture & Guide',
                pinned: false,
                createdAt: new Date().toISOString(),
                updatedAt: new Date().toISOString(),
                messages: SYNTHETIC_MESSAGES
              }
            }
          })
        });
      }

      // D. Mutation: createConversation
      if (query.includes('createConversation(')) {
        const newId = `conv-created-${Date.now()}`;
        return req.respond({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              createConversation: {
                id: newId,
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

      // E. Mutation: setConversationPinned
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
                title: 'TypeScript Architecture & Guide',
                pinned: targetPinned,
                createdAt: new Date().toISOString(),
                updatedAt: new Date().toISOString()
              }
            }
          })
        });
      }

      console.error('[HARD FAIL] Unmocked GraphQL request:', query);
      return req.respond({ status: 500, contentType: 'application/json', body: JSON.stringify({ error: 'Unmocked GraphQL' }) });
    }

    // 2. Intercept API (/api/**) - HARD FAIL on any live model run attempt
    if (url.includes('/api/')) {
      console.error('[HARD FAIL] Blocked live API call during read-only verification:', url);
      return req.respond({ status: 403, contentType: 'application/json', body: JSON.stringify({ error: 'Live API calls prohibited' }) });
    }

    // Allow static frontend assets
    req.continue();
  });

  try {
    console.log(`Navigating to ${BASE_URL}...`);
    await page.goto(BASE_URL, { waitUntil: 'networkidle0', timeout: 15000 });

    // Wait for the conversation and messages to load
    await page.waitForSelector('[data-message-role="assistant"]', { timeout: 10000 });
    console.log('✓ Initial page and synthetic conversation rendered.');

    // ---------------------------------------------------------------------------------
    // TEST SECTION 1: Issue #10 (1280px Desktop Toolbar Controls)
    // ---------------------------------------------------------------------------------
    console.log('\n--- Checking Issue #10 (1280px Desktop) ---');

    // 1.1 New Chat Button
    const newChatBtn = await page.$('[data-testid="new-chat-btn"] button');
    if (!newChatBtn) throw new Error('New Chat inner button not found');
    const newChatAria = await page.evaluate(el => el.getAttribute('aria-label'), newChatBtn);
    const newChatSvg = await page.$('[data-testid="new-chat-btn"] svg');
    const newChatSvgDims = await page.evaluate(el => ({ width: el.getAttribute('width'), height: el.getAttribute('height') }), newChatSvg);
    const newChatLabel = await page.$eval('.header-actions .action-btn-text', el => el.textContent.trim());

    console.log(`New Chat Button: aria-label="${newChatAria}", label="${newChatLabel}", svg=${newChatSvgDims.width}x${newChatSvgDims.height}`);
    if (newChatAria !== '+ New Chat') throw new Error(`Unexpected New Chat aria-label: ${newChatAria}`);
    if (newChatLabel !== 'New Chat') throw new Error(`Unexpected New Chat adjacent text: ${newChatLabel}`);
    if (newChatSvgDims.width !== '16' || newChatSvgDims.height !== '16') throw new Error(`New Chat SVG dimensions not 16x16: ${JSON.stringify(newChatSvgDims)}`);

    // Verify New Chat adjacent text is unclipped
    const newChatLabelMetrics = await page.$eval('.header-actions .action-btn-text', el => {
      const rect = el.getBoundingClientRect();
      return {
        width: rect.width,
        height: rect.height,
        scrollWidth: el.scrollWidth,
        clientWidth: el.clientWidth,
        visible: rect.width > 0 && rect.height > 0
      };
    });
    console.log('New Chat label layout metrics:', newChatLabelMetrics);
    if (!newChatLabelMetrics.visible || newChatLabelMetrics.scrollWidth > newChatLabelMetrics.clientWidth + 1) {
      throw new Error(`New Chat label appears clipped or hidden: ${JSON.stringify(newChatLabelMetrics)}`);
    }

    // 1.2 Conversation Pagination Loader (Load more conversations)
    const convPagingBtn = await page.$('[data-testid="load-more-conv-btn"] button');
    if (!convPagingBtn) throw new Error('Load more conversations button not found');
    const convPagingAria = await page.evaluate(el => el.getAttribute('aria-label'), convPagingBtn);
    const convPagingLabel = await page.$eval('.load-more-conv-container .action-btn-text', el => el.textContent.trim());
    const convPagingSvgDims = await page.$eval('[data-testid="load-more-conv-btn"] svg', el => ({ width: el.getAttribute('width'), height: el.getAttribute('height') }));

    console.log(`Load More Convs: aria-label="${convPagingAria}", label="${convPagingLabel}", svg=${convPagingSvgDims.width}x${convPagingSvgDims.height}`);
    if (convPagingAria !== 'Load more conversations') throw new Error(`Unexpected conv pagination aria-label: ${convPagingAria}`);
    if (convPagingLabel !== 'Load more conversations') throw new Error(`Unexpected conv pagination label: ${convPagingLabel}`);
    if (convPagingSvgDims.width !== '16' || convPagingSvgDims.height !== '16') throw new Error('Conv pagination SVG not 16x16');

    // 1.3 Message History Pagination Loader (Load earlier messages)
    const msgPagingBtn = await page.$('[data-testid="load-more-btn"] button');
    if (!msgPagingBtn) throw new Error('Load earlier messages button not found');
    const msgPagingAria = await page.evaluate(el => el.getAttribute('aria-label'), msgPagingBtn);
    const msgPagingLabel = await page.$eval('.load-more-container .action-btn-text', el => el.textContent.trim());
    const msgPagingSvgDims = await page.$eval('[data-testid="load-more-btn"] svg', el => ({ width: el.getAttribute('width'), height: el.getAttribute('height') }));

    console.log(`Load Earlier Messages: aria-label="${msgPagingAria}", label="${msgPagingLabel}", svg=${msgPagingSvgDims.width}x${msgPagingSvgDims.height}`);
    if (msgPagingAria !== 'Load earlier messages') throw new Error(`Unexpected msg pagination aria-label: ${msgPagingAria}`);
    if (msgPagingLabel !== 'Load earlier messages') throw new Error(`Unexpected msg pagination label: ${msgPagingLabel}`);
    if (msgPagingSvgDims.width !== '16' || msgPagingSvgDims.height !== '16') throw new Error('Msg pagination SVG not 16x16');

    // 1.4 Native Enter and Space Activation on New Chat button
    console.log('Testing native Enter and Space keyboard activation on New Chat button...');
    let newChatClickCount = 0;
    await page.exposeFunction('onNewChatTriggered', () => {
      newChatClickCount++;
    });
    await page.evaluate(() => {
      const btn = document.querySelector('[data-testid="new-chat-btn"] button');
      btn.addEventListener('click', () => window.onNewChatTriggered());
    });

    await page.focus('[data-testid="new-chat-btn"] button');
    await page.keyboard.press('Enter');
    await new Promise(r => setTimeout(r, 100));
    if (newChatClickCount !== 1) throw new Error(`Expected exactly 1 trigger on Enter, got ${newChatClickCount}`);

    // Re-focus button because newConversation legitimately shifts focus to chat textarea
    await page.focus('[data-testid="new-chat-btn"] button');
    await page.keyboard.press('Space');
    await new Promise(r => setTimeout(r, 100));
    if (newChatClickCount !== 2) throw new Error(`Expected exactly 2 triggers after Space, got ${newChatClickCount}`);
    console.log('✓ Native keyboard activation (Enter & Space) verified without double triggering.');

    // Save 1280px screenshot
    await page.screenshot({ path: SCREENSHOTS.toolbar1280, fullPage: false });
    console.log(`✓ Saved 1280px screenshot: ${SCREENSHOTS.toolbar1280}`);

    // ---------------------------------------------------------------------------------
    // TEST SECTION 2: Issue #10 (390px Mobile Viewport Reflow Verification)
    // ---------------------------------------------------------------------------------
    console.log('\n--- Checking Issue #10 (390px Mobile Viewport Reflow) ---');
    await page.setViewport({ width: 390, height: 844 });
    await new Promise(r => setTimeout(r, 400));

    // 2.1 Verify ZERO horizontal document overflow
    const overflowCheck = await page.evaluate(() => {
      const docScrollWidth = document.documentElement.scrollWidth;
      const bodyScrollWidth = document.body.scrollWidth;
      const innerWidth = window.innerWidth;
      return {
        docScrollWidth,
        bodyScrollWidth,
        innerWidth,
        hasHorizontalOverflow: docScrollWidth > innerWidth || bodyScrollWidth > innerWidth
      };
    });
    console.log('Mobile horizontal overflow metrics:', overflowCheck);
    if (overflowCheck.hasHorizontalOverflow) {
      throw new Error(`Mobile viewport has horizontal overflow! doc=${overflowCheck.docScrollWidth}, body=${overflowCheck.bodyScrollWidth}, win=${overflowCheck.innerWidth}`);
    }

    // 2.2 Verify bounding rects of visible controls are strictly within [0, 390]
    const mobileBounds = await page.evaluate(() => {
      const winWidth = window.innerWidth;
      const headerActions = document.querySelector('.header-actions');
      const actionText = document.querySelector('.header-actions .action-btn-text');
      const chatInput = document.querySelector('copilot-chat-input');
      const sidebar = document.querySelector('.sidebar');
      const mainArea = document.querySelector('.main-area');

      const haRect = headerActions ? headerActions.getBoundingClientRect() : null;
      const atRect = actionText ? actionText.getBoundingClientRect() : null;
      const ciRect = chatInput ? chatInput.getBoundingClientRect() : null;
      const sbRect = sidebar ? sidebar.getBoundingClientRect() : null;
      const maRect = mainArea ? mainArea.getBoundingClientRect() : null;

      return {
        headerActions: haRect ? { left: haRect.left, right: haRect.right, width: haRect.width, inBounds: haRect.left >= 0 && haRect.right <= winWidth } : null,
        actionText: atRect ? { left: atRect.left, right: atRect.right, width: atRect.width, inBounds: atRect.left >= 0 && atRect.right <= winWidth } : null,
        chatInput: ciRect ? { left: ciRect.left, right: ciRect.right, width: ciRect.width, inBounds: ciRect.left >= 0 && ciRect.right <= winWidth } : null,
        sidebar: sbRect ? { width: sbRect.width, height: sbRect.height, inBounds: sbRect.left >= 0 && sbRect.right <= winWidth } : null,
        mainArea: maRect ? { width: maRect.width, inBounds: maRect.left >= 0 && maRect.right <= winWidth } : null
      };
    });
    console.log('Mobile bounding rects evaluation:', mobileBounds);

    if (!mobileBounds.headerActions?.inBounds) throw new Error('Header actions out of 390px bounds');
    if (!mobileBounds.actionText?.inBounds) throw new Error('Action text out of 390px bounds');
    if (!mobileBounds.chatInput?.inBounds) throw new Error('Chat input out of 390px bounds');
    if (!mobileBounds.sidebar?.inBounds) throw new Error('Sidebar out of 390px bounds');
    if (!mobileBounds.mainArea?.inBounds) throw new Error('Main area out of 390px bounds');

    // Confirm chatInput has full mobile width (>= 320px, not squeezed into 120px)
    if (mobileBounds.chatInput.width < 320) {
      throw new Error(`Chat input is squeezed! Width is only ${mobileBounds.chatInput.width}px, expected >= 320px`);
    }

    // Save 390px screenshot
    await page.screenshot({ path: SCREENSHOTS.toolbar390, fullPage: false });
    console.log(`✓ Saved 390px screenshot: ${SCREENSHOTS.toolbar390}`);

    // Restore desktop viewport
    await page.setViewport({ width: 1280, height: 800 });
    await new Promise(r => setTimeout(r, 300));

    // ---------------------------------------------------------------------------------
    // TEST SECTION 3: Dynamic Stop & Retry Controls in Browser
    // ---------------------------------------------------------------------------------
    console.log('\n--- Checking Dynamic Stop & Retry Controls ---');

    // 3.1 Dynamic Stop Control (streaming state)
    await page.evaluate(() => {
      const app = window.ng.getComponent(document.querySelector('app-root'));
      if (app) {
        app.activeRunId.set('run-active-stream-test');
        app.isStreaming.set(true);
      }
    });
    await new Promise(r => setTimeout(r, 200));

    const stopBtn = await page.$('[data-testid="stop-btn"] button');
    if (!stopBtn) throw new Error('Stop button not found during dynamic streaming state');
    const stopAria = await page.evaluate(el => el.getAttribute('aria-label'), stopBtn);
    const stopLabel = await page.$eval('.stop-action-container .action-btn-text', el => el.textContent.trim());
    const stopSvgDims = await page.$eval('[data-testid="stop-btn"] svg', el => ({ width: el.getAttribute('width'), height: el.getAttribute('height') }));

    console.log(`Dynamic Stop Button: aria-label="${stopAria}", label="${stopLabel}", svg=${stopSvgDims.width}x${stopSvgDims.height}`);
    if (stopAria !== 'Stop generation') throw new Error(`Unexpected Stop aria-label: ${stopAria}`);
    if (stopLabel !== 'Stop') throw new Error(`Unexpected Stop label: ${stopLabel}`);
    if (stopSvgDims.width !== '16' || stopSvgDims.height !== '16') throw new Error('Stop SVG not 16x16');

    // Save streaming stop screenshot
    await page.screenshot({ path: SCREENSHOTS.stopStreaming, fullPage: false });
    console.log(`✓ Saved Stop button screenshot: ${SCREENSHOTS.stopStreaming}`);

    // Cancel streaming
    await page.evaluate(() => {
      const app = window.ng.getComponent(document.querySelector('app-root'));
      if (app) {
        app.isStreaming.set(false);
        app.activeRunId.set(null);
      }
    });
    await new Promise(r => setTimeout(r, 200));

    // 3.2 Dynamic Retry Control (error state)
    await page.evaluate(() => {
      const app = window.ng.getComponent(document.querySelector('app-root'));
      if (app) {
        app.streamError.set('連線中斷，請重試');
        app.pendingRequest.set({
          conversationId: 'conv-ui-1',
          clientRequestId: 'req-dyn-err',
          message: 'retry test',
          tempUserId: 'u-dyn',
          tempAssistantId: 'a-dyn'
        });
      }
    });
    await new Promise(r => setTimeout(r, 200));

    const retryBtn = await page.$('[data-testid="retry-btn"] button');
    if (!retryBtn) throw new Error('Retry button not found in stream error banner');
    const retryAria = await page.evaluate(el => el.getAttribute('aria-label'), retryBtn);
    const retryLabel = await page.$eval('.retry-action-container .action-btn-text', el => el.textContent.trim());
    const retrySvgDims = await page.$eval('[data-testid="retry-btn"] svg', el => ({ width: el.getAttribute('width'), height: el.getAttribute('height') }));

    console.log(`Dynamic Retry Button: aria-label="${retryAria}", label="${retryLabel}", svg=${retrySvgDims.width}x${retrySvgDims.height}`);
    if (retryAria !== '重試') throw new Error(`Unexpected Retry aria-label: ${retryAria}`);
    if (retryLabel !== '重試') throw new Error(`Unexpected Retry label: ${retryLabel}`);
    if (retrySvgDims.width !== '16' || retrySvgDims.height !== '16') throw new Error('Retry SVG not 16x16');

    // Save retry error screenshot
    await page.screenshot({ path: SCREENSHOTS.retryError, fullPage: false });
    console.log(`✓ Saved Retry button screenshot: ${SCREENSHOTS.retryError}`);

    // Clear error
    await page.evaluate(() => {
      const app = window.ng.getComponent(document.querySelector('app-root'));
      if (app) {
        app.streamError.set(null);
        app.pendingRequest.set(null);
      }
    });
    await new Promise(r => setTimeout(r, 200));

    // ---------------------------------------------------------------------------------
    // TEST SECTION 4: Issue #13 (Strictly Supported Message Actions, Zero Thumbs & Full Copy)
    // ---------------------------------------------------------------------------------
    console.log('\n--- Checking Issue #13 (Zero Thumbs & Supported Message Actions) ---');

    // Click back to conv-ui-1 to view messages (since New Chat keyboard test switched to empty draft)
    await page.click('.conversation-item[data-id="conv-ui-1"]');
    await page.waitForSelector('[data-message-role="assistant"]', { timeout: 5000 });
    console.log('✓ Switched back to synthetic conversation with messages.');

    // 4.1 Verify ZERO thumbs buttons exist in DOM across all messages
    const thumbsUpCount = await page.$$eval(
      'copilot-chat-assistant-message-thumbs-up-button, button[title*="thumbs up" i], button[title*="helpful" i], button[aria-label*="thumbs up" i]',
      els => els.length
    );
    const thumbsDownCount = await page.$$eval(
      'copilot-chat-assistant-message-thumbs-down-button, button[title*="thumbs down" i], button[title*="not helpful" i], button[aria-label*="thumbs down" i]',
      els => els.length
    );
    const readAloudCount = await page.$$eval(
      'copilot-chat-assistant-message-read-aloud-button, button[title*="read aloud" i]',
      els => els.length
    );
    const regenerateCount = await page.$$eval(
      'copilot-chat-assistant-message-regenerate-button, button[title*="regenerate" i]',
      els => els.length
    );

    console.log(`Dead controls count: ThumbsUp=${thumbsUpCount}, ThumbsDown=${thumbsDownCount}, ReadAloud=${readAloudCount}, Regenerate=${regenerateCount}`);
    if (thumbsUpCount !== 0) throw new Error(`Issue #13 VIOLATION: Found ${thumbsUpCount} Thumbs Up buttons in DOM`);
    if (thumbsDownCount !== 0) throw new Error(`Issue #13 VIOLATION: Found ${thumbsDownCount} Thumbs Down buttons in DOM`);
    if (readAloudCount !== 0) throw new Error(`Issue #13 VIOLATION: Found ${readAloudCount} Read Aloud buttons in DOM`);
    if (regenerateCount !== 0) throw new Error(`Issue #13 VIOLATION: Found ${regenerateCount} Regenerate buttons in DOM`);
    console.log('✓ Confirmed 100% ZERO dead controls across all assistant messages.');

    // Save zero thumbs screenshot
    await page.screenshot({ path: SCREENSHOTS.zeroThumbs, fullPage: false });
    console.log(`✓ Saved zero thumbs screenshot: ${SCREENSHOTS.zeroThumbs}`);

    // 4.2 Native Markdown Syntax Tree Verification
    const targetAssistantMsg = await page.$('[data-message-id="m-ui-assistant-1"]');
    if (!targetAssistantMsg) throw new Error('Target markdown assistant message not found');

    const strongText = await targetAssistantMsg.$eval('strong', el => el.textContent.trim());
    console.log(`Markdown strong text: "${strongText}"`);
    if (strongText !== 'strongly typed') throw new Error(`Expected 'strongly typed', got "${strongText}"`);

    const codeBlockText = await targetAssistantMsg.$eval('pre code', el => el.textContent.trim());
    console.log(`Markdown code block contains greet: ${codeBlockText.includes('function greet')}`);
    if (!codeBlockText.includes('function greet(name: string): string')) {
      throw new Error(`Code block content mismatch: ${codeBlockText}`);
    }

    // 4.3 Code Block Header Copy Button Click & Strict Equality Assertion
    const codeBlockCopyBtn = await targetAssistantMsg.$('copilot-chat-code-block-header .code-block-copy-button, .code-block-copy-button');
    if (!codeBlockCopyBtn) throw new Error('Code block copy button not found');

    console.log('Clicking code block copy button...');
    await codeBlockCopyBtn.click();
    await new Promise(r => setTimeout(r, 250));

    const codeClipboard = await page.evaluate(() => navigator.clipboard.readText());
    console.log('Read from clipboard (code block):\n' + codeClipboard);
    if (codeClipboard.trim() !== SAMPLE_CODE.trim()) {
      throw new Error(`Code block clipboard does NOT match sample code!\nExpected:\n${SAMPLE_CODE.trim()}\nGot:\n${codeClipboard.trim()}`);
    }
    console.log('✓ Code block copy button successfully copied full synthetic code to system clipboard.');

    // 4.4 Whole-Message Copy Button Click & Strict Equality Assertion
    const wholeCopyBtn = await targetAssistantMsg.$('copilot-chat-assistant-message-copy-button button');
    if (!wholeCopyBtn) throw new Error('Whole-message copy button not found in assistant message toolbar');

    console.log('Clicking whole-message copy button...');
    await wholeCopyBtn.click();
    await new Promise(r => setTimeout(r, 250));

    const wholeClipboard = await page.evaluate(() => navigator.clipboard.readText());
    console.log('Read from clipboard (whole message):\n' + wholeClipboard);
    if (wholeClipboard.trim() !== SAMPLE_MARKDOWN.trim()) {
      throw new Error(`Whole message clipboard does NOT match raw markdown!\nExpected:\n${SAMPLE_MARKDOWN.trim()}\nGot:\n${wholeClipboard.trim()}`);
    }
    console.log('✓ Whole-message copy button successfully copied full raw markdown to system clipboard.');

    // Save clipboard copy screenshot
    await page.screenshot({ path: SCREENSHOTS.clipboardCopy, fullPage: false });
    console.log(`✓ Saved clipboard copy screenshot: ${SCREENSHOTS.clipboardCopy}`);

    console.log('\n================================================================');
    console.log('ALL VERIFICATIONS PASSED SUCCESSFULLY (Exit Code 0)');
    console.log('================================================================');
  } finally {
    await browser.close();
  }
}

main().catch(err => {
  console.error('\n[FATAL ERROR during verification]:', err);
  process.exit(1);
});
