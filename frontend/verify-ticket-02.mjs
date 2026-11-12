// Historical layout verification retained for coverage traceability; see README.
throw new Error('Retired legacy browser verification; use the current ThreadPlane browser matrix.');

import puppeteer from 'puppeteer-core';
import path from 'node:path';
import { installNodeFetchGuard, installBrowserGuard, getVerificationOrigins, assertSafeExecutionEnvironment } from './test/verification-harness.mjs';

installNodeFetchGuard({ mode: 'verified-backend' });

const CHROME_PATH = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const { frontendUrl: BASE_URL, backendUrl: BACKEND_URL } = getVerificationOrigins();

const SCREENSHOT_DIR = '/tmp';
const SCREENSHOTS = {
  turn1: path.join(SCREENSHOT_DIR, 'bot-e2e-t2-01-turn1-completed.png'),
  turn2Interrupted: path.join(SCREENSHOT_DIR, 'bot-e2e-t2-02-turn2-interrupted.png'),
  reloaded: path.join(SCREENSHOT_DIR, 'bot-e2e-t2-03-reloaded.png'),
  turn3: path.join(SCREENSHOT_DIR, 'bot-e2e-t2-04-turn3-completed.png'),
  pagination: path.join(SCREENSHOT_DIR, 'bot-e2e-t2-05-pagination-reconciled.png'),
  deepConvRestored: path.join(SCREENSHOT_DIR, 'bot-e2e-t2-06-deep-conv-restored.png')
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

async function queryConversationGraphQL(convId) {
  const res = await fetch(`${BACKEND_URL}/graphql`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      query: `query CheckConv($id: ID!) {
        conversation(id: $id) {
          id
          title
          messages(limit: 50) {
            id
            role
            content
            interrupted
            createdAt
          }
        }
      }`,
      variables: { id: convId }
    })
  });
  const json = await res.json();
  return json.data?.conversation;
}

async function fetchFirst20Conversations() {
  const res = await fetch(`${BACKEND_URL}/graphql`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      query: `query { conversations(limit: 20, offset: 0) { id title } }`
    })
  });
  const json = await res.json();
  return json.data?.conversations || [];
}

async function sendRunViaBackend(convId, text) {
  const res = await fetch(`${BACKEND_URL}/api/conversations/${convId}/runs`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
    body: JSON.stringify({
      clientRequestId: 'seed-' + Math.random().toString(36).substring(2),
      message: text
    })
  });
  if (!res.ok || !res.body) {
    throw new Error(`Failed to send seed run: ${res.status}`);
  }
  const reader = res.body.getReader();
  while (true) {
    const { done } = await reader.read();
    if (done) break;
  }
}

async function run() {
  console.log('--- Starting Ticket 02 Deterministic Browser Automation Verification ---');
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
      console.error('[Browser Error]', msg.text());
    }
  });
  page.on('pageerror', err => {
    console.error('[Browser PageError]', err.message);
  });

  try {
    // -------------------------------------------------------------
    // PART 1: IME Composition Guard and Real Keyboard Shift+Enter
    // -------------------------------------------------------------
    console.log('\n=== Testing IME Composition Guard & Real Keyboard Shift+Enter ===');
    await page.goto(BASE_URL, { waitUntil: 'networkidle0' });
    await page.waitForSelector('textarea');

    // Establish fresh conversation
    await page.waitForFunction(() => {
      const btn = document.querySelector('[data-testid="new-chat-btn"] button, button[aria-label="+ New Chat"], .new-chat-btn');
      return btn && !btn.disabled && !btn.closest('[disabled]');
    });
    const oldId = await page.evaluate(() => localStorage.getItem('bot_active_conversation_id'));
    await page.click('[data-testid="new-chat-btn"] button, button[aria-label="+ New Chat"], .new-chat-btn');
    await page.waitForFunction(
      (prev) => {
        const cur = localStorage.getItem('bot_active_conversation_id');
        const rows = document.querySelectorAll('[data-message-role]');
        return Boolean(cur !== prev && rows.length === 0);
      },
      { timeout: 10000 },
      oldId
    );

    // 1. Dispatch IME compositionstart and keydown Enter with isComposing: true
    console.log('1. Testing IME Composition Guard...');
    await page.focus('textarea');
    await page.evaluate(() => {
      const textarea = document.querySelector('textarea');
      textarea.value = '測試輸入法候選詞';
      textarea.dispatchEvent(new Event('input', { bubbles: true }));
      textarea.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }));
      const enterEvt = new KeyboardEvent('keydown', {
        key: 'Enter',
        code: 'Enter',
        keyCode: 229,
        which: 229,
        isComposing: true,
        bubbles: true,
        cancelable: true
      });
      textarea.dispatchEvent(enterEvt);
    });

    const imeSubmitted = await page.evaluate(() => document.querySelectorAll('[data-message-role]').length);
    console.log('Message count after IME Enter (must be 0):', imeSubmitted);
    if (imeSubmitted !== 0) {
      throw new Error(`IME Guard failed: message submitted during active composition (rows=${imeSubmitted})`);
    }

    // End IME composition
    await page.evaluate(() => {
      const textarea = document.querySelector('textarea');
      textarea.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true }));
      textarea.value = '';
      textarea.dispatchEvent(new Event('input', { bubbles: true }));
    });

    // 2. Real keyboard Shift+Enter: page.keyboard.down('Shift') / press('Enter') / up('Shift')
    console.log('2. Testing Real Keyboard Shift+Enter (must insert actual newline without submitting)...');
    await page.focus('textarea');
    await page.keyboard.type('Line 1 of multiline text');
    await page.keyboard.down('Shift');
    await page.keyboard.press('Enter');
    await page.keyboard.up('Shift');
    await page.keyboard.type('Line 2 of multiline text');

    const textareaValue = await page.$eval('textarea', el => el.value);
    console.log('Textarea value after Shift+Enter:', JSON.stringify(textareaValue));
    if (!textareaValue.includes('\n') || textareaValue !== 'Line 1 of multiline text\nLine 2 of multiline text') {
      throw new Error(`Shift+Enter failed to insert real newline: got ${JSON.stringify(textareaValue)}`);
    }

    const shiftSubmitted = await page.evaluate(() => document.querySelectorAll('[data-message-role]').length);
    console.log('Message count after Shift+Enter (must be 0):', shiftSubmitted);
    if (shiftSubmitted !== 0) {
      throw new Error(`Shift+Enter submitted a message unexpectedly (rows=${shiftSubmitted})`);
    }

    // Clear textarea for subsequent steps
    await page.evaluate(() => {
      const textarea = document.querySelector('textarea');
      textarea.value = '';
      textarea.dispatchEvent(new Event('input', { bubbles: true }));
    });

    // -------------------------------------------------------------
    // PART 2: Turn 1 (Normal Completed Streaming Turn)
    // -------------------------------------------------------------
    console.log('\n=== Testing Turn 1: Normal Completed Stream ===');
    const TURN1_TEXT = 'Hello Luna 1: Normal Completion Test';
    await page.type('textarea', TURN1_TEXT);
    await page.click('button[aria-label="Send message"]');

    await page.waitForFunction(() => {
      const sendBtn = document.querySelector('button[aria-label="Send message"]');
      const stopBtn = document.querySelector('[data-testid="stop-btn"] button, button[aria-label="Stop generation"], .stop-btn');
      const assistant = document.querySelector('[data-message-role="assistant"]');
      return Boolean(sendBtn && !stopBtn && assistant && assistant.textContent.includes('mock streaming response'));
    }, { timeout: 15000 });

    const turn1Count = await page.evaluate(() => ({
      user: document.querySelectorAll('[data-message-role="user"]').length,
      assistant: document.querySelectorAll('[data-message-role="assistant"]').length
    }));
    console.log('Turn 1 Counts:', turn1Count);
    if (turn1Count.user !== 1 || turn1Count.assistant !== 1) {
      throw new Error(`Turn 1 assertion failed: expected 1 user, 1 assistant; got ${JSON.stringify(turn1Count)}`);
    }

    await page.screenshot({ path: SCREENSHOTS.turn1 });
    console.log('Saved Turn 1 screenshot:', SCREENSHOTS.turn1);

    // -------------------------------------------------------------
    // PART 3: Turn 2 (Stop Button -> Cooperative Interruption)
    // -------------------------------------------------------------
    console.log('\n=== Testing Turn 2: Stop Button Cooperative Interruption ===');
    const TURN2_TEXT = 'Hello Luna 2: Stop Generation Interruption Test [DELAY]';
    await page.type('textarea', TURN2_TEXT);
    await page.click('button[aria-label="Send message"]');

    // Wait for the second assistant message bubble to appear and capture first non-empty delta
    await page.waitForFunction(() => {
      const assistants = document.querySelectorAll('[data-message-role="assistant"]');
      if (assistants.length < 2) return false;
      const text = assistants[1]?.textContent?.trim() || '';
      return text.length > 0;
    }, { timeout: 10000 });

    const hasStopBtn = await page.evaluate(() => Boolean(document.querySelector('[data-testid="stop-btn"] button, button[aria-label="Stop generation"], .stop-btn')));
    if (!hasStopBtn) {
      throw new Error('Stop button must be visible while streaming Turn 2');
    }

    console.log('Clicking Stop button...');
    await page.click('[data-testid="stop-btn"] button, button[aria-label="Stop generation"], .stop-btn');

    // Stop button idempotency: clicking stop again must not fail or throw
    await page.evaluate(() => {
      const stopBtn = document.querySelector('[data-testid="stop-btn"] button, button[aria-label="Stop generation"], .stop-btn');
      if (stopBtn) stopBtn.click();
    });

    // Wait until stream finishes, Send button is restored, and interrupted banner appears
    await page.waitForFunction(() => {
      const sendBtn = document.querySelector('button[aria-label="Send message"]');
      const stopBtn = document.querySelector('[data-testid="stop-btn"] button, button[aria-label="Stop generation"], .stop-btn');
      const banner = document.querySelector('[data-testid="last-interrupted-banner"], .recovering-banner');
      return Boolean(sendBtn && !stopBtn && banner);
    }, { timeout: 15000 });

    const turn2State = await page.evaluate(() => {
      const rows = document.querySelectorAll('[data-message-role]');
      const assistants = document.querySelectorAll('[data-message-role="assistant"]');
      const turn2Assistant = assistants[1];
      const banner = document.querySelector('[data-testid="last-interrupted-banner"], .recovering-banner');
      return {
        totalRows: rows.length,
        assistantText: turn2Assistant?.textContent?.trim() || '',
        hasBadge: Boolean(banner),
        badgeText: banner?.textContent?.trim()
      };
    });
    console.log('Turn 2 Interrupted State:', turn2State);

    if (!turn2State.hasBadge || !turn2State.badgeText.includes('中斷')) {
      throw new Error(`Turn 2 failed to show Interrupted banner: ${JSON.stringify(turn2State)}`);
    }
    if (!turn2State.assistantText || turn2State.assistantText.length < 5) {
      throw new Error(`Turn 2 partial content missing: '${turn2State.assistantText}'`);
    }

    await page.screenshot({ path: SCREENSHOTS.turn2Interrupted });
    console.log('Saved Turn 2 Interrupted screenshot:', SCREENSHOTS.turn2Interrupted);

    // -------------------------------------------------------------
    // PART 4: Reload Persistence Verification
    // -------------------------------------------------------------
    console.log('\n=== Testing Page Reload Persistence ===');
    await page.reload({ waitUntil: 'networkidle0' });
    await page.waitForSelector('[data-message-role="assistant"]');

    const reloadedState = await page.evaluate(() => {
      const userRows = Array.from(document.querySelectorAll('[data-message-role="user"]')).map(el => el.textContent?.trim());
      const assistantRows = Array.from(document.querySelectorAll('[data-message-role="assistant"]')).map(el => el.textContent?.trim());
      const banner = document.querySelector('[data-testid="last-interrupted-banner"], .recovering-banner');
      return { userRows, assistantRows, bannerText: banner?.textContent?.trim() };
    });
    console.log('Reloaded State:', reloadedState);

    if (reloadedState.userRows.length !== 2 || reloadedState.assistantRows.length !== 2) {
      throw new Error(`Reloaded message count mismatch: expected 2 users & 2 assistants, got ${JSON.stringify(reloadedState)}`);
    }
    if (reloadedState.userRows[0] !== TURN1_TEXT || reloadedState.userRows[1] !== TURN2_TEXT) {
      throw new Error(`Reloaded user text mismatch: ${JSON.stringify(reloadedState.userRows)}`);
    }
    if (!reloadedState.bannerText || !reloadedState.bannerText.includes('中斷')) {
      throw new Error(`Reloaded interrupted banner mismatch: ${reloadedState.bannerText}`);
    }

    // Verify GraphQL authoritative interrupted flag
    const currentConvId = await page.evaluate(() => localStorage.getItem('bot_active_conversation_id'));
    const convData = await queryConversationGraphQL(currentConvId);
    const interruptedMsg = convData?.messages?.find(m => m.role.toUpperCase() === 'ASSISTANT' && m.interrupted);
    if (!interruptedMsg) {
      throw new Error('Authoritative GraphQL message must have interrupted: true');
    }
    console.log('✓ Verified GraphQL authoritative message.interrupted === true');

    await page.screenshot({ path: SCREENSHOTS.reloaded });
    console.log('Saved Reloaded screenshot:', SCREENSHOTS.reloaded);

    // -------------------------------------------------------------
    // PART 5: Turn 3 Continuation after Interruption
    // -------------------------------------------------------------
    console.log('\n=== Testing Turn 3 Continuation ===');
    const TURN3_TEXT = 'Hello Luna 3: Multi-turn Post-Interruption Test';
    await page.type('textarea', TURN3_TEXT);
    await page.click('button[aria-label="Send message"]');

    await page.waitForFunction(() => {
      const assistants = document.querySelectorAll('[data-message-role="assistant"]');
      if (assistants.length < 3) return false;
      const thirdAssistant = assistants[2]?.textContent || '';
      const sendBtn = document.querySelector('button[aria-label="Send message"]');
      const stopBtn = document.querySelector('[data-testid="stop-btn"] button, button[aria-label="Stop generation"], .stop-btn');
      const textarea = document.querySelector('textarea');
      return Boolean(sendBtn && !stopBtn && textarea && !textarea.disabled && thirdAssistant.includes('mock streaming response'));
    }, { timeout: 15000 });

    const turn3Counts = await page.evaluate(() => ({
      userCount: document.querySelectorAll('[data-message-role="user"]').length,
      assistantCount: document.querySelectorAll('[data-message-role="assistant"]').length
    }));
    console.log('Turn 3 Message Counts:', turn3Counts);
    if (turn3Counts.userCount !== 3 || turn3Counts.assistantCount !== 3) {
      throw new Error(`Turn 3 message counts mismatch: ${JSON.stringify(turn3Counts)}`);
    }

    await page.screenshot({ path: SCREENSHOTS.turn3 });
    console.log('Saved Turn 3 screenshot:', SCREENSHOTS.turn3);

    // -------------------------------------------------------------
    // PART 6: Non-First-Page Saved Conversation ID Restoration &
    // Sidebar "Load more conversations" Bounded Pagination
    // -------------------------------------------------------------
    console.log('\n=== Testing Non-First-Page Saved Conversation ID Restoration ===');
    const targetConvTitle = 't2-target-deep-conv-' + Date.now();
    const targetConvId = await createConversationViaBackend(targetConvTitle);
    console.log('Created Deep Target Conversation ID:', targetConvId, 'Title:', targetConvTitle);
    await sendRunViaBackend(targetConvId, 'Initial deep message for restoration');

    // Create 21 newer conversations with unique prefix so targetConvId is pushed beyond first page (offset 20)
    console.log('Creating 21 newer conversations to push targetConv beyond first page (limit 20)...');
    for (let i = 1; i <= 21; i++) {
      await createConversationViaBackend(`t2-padding-${Date.now()}-${i.toString().padStart(2, '0')}`);
    }

    // Verify via GraphQL that targetConvId is NOT in the first 20 conversations
    const first20 = await fetchFirst20Conversations();
    const inFirst20 = first20.some(c => c.id === targetConvId);
    console.log(`Target conversation is in API first 20: ${inFirst20} (must be false)`);
    if (inFirst20) {
      throw new Error(`Setup failed: targetConvId was unexpectedly found in first 20 conversations`);
    }

    // Store targetConvId in localStorage and reload
    console.log(`Setting localStorage bot_active_conversation_id to ${targetConvId} and reloading...`);
    await page.evaluate((id) => {
      localStorage.setItem('bot_active_conversation_id', id);
    }, targetConvId);

    await page.reload({ waitUntil: 'networkidle0' });

    // Assert: header title, restored messages, and active conversation match targetConvId
    await page.waitForFunction((expectedTitle) => {
      const headerTitle = document.querySelector('.header-title')?.textContent?.trim();
      const message = document.querySelector('[data-message-role="user"]')?.textContent?.trim();
      return headerTitle === expectedTitle && message === 'Initial deep message for restoration';
    }, { timeout: 15000 }, targetConvTitle);

    const deepRestoredState = await page.evaluate(() => {
      const title = document.querySelector('.header-title')?.textContent?.trim();
      const userMsg = document.querySelector('[data-message-role="user"]')?.textContent?.trim();
      const activeSidebarItem = document.querySelector('.conversation-item.active')?.textContent?.trim();
      const hasLoadMoreConvBtn = Boolean(document.querySelector('[data-testid="load-more-conv-btn"], .load-more-conv-btn'));
      return { title, userMsg, activeSidebarItem, hasLoadMoreConvBtn };
    });
    console.log('Deep Conversation Restored State:', deepRestoredState);
    if (deepRestoredState.title !== targetConvTitle) {
      throw new Error(`Expected header title '${targetConvTitle}', got '${deepRestoredState.title}'`);
    }
    if (!deepRestoredState.hasLoadMoreConvBtn) {
      throw new Error(`Expected 'Load more conversations' button in sidebar since > 20 conversations exist`);
    }

    // Click "Load more conversations" in sidebar and verify list expands without duplicate IDs
    console.log('Clicking "Load more conversations" in sidebar...');
    const prevConvItemCount = await page.$$eval('.conversation-item', els => els.length);
    await page.click('[data-testid="load-more-conv-btn"] button, button[aria-label="Load more conversations"], .load-more-conv-btn');

    await page.waitForFunction((prevCount) => {
      const curCount = document.querySelectorAll('.conversation-item').length;
      return curCount > prevCount;
    }, { timeout: 15000 }, prevConvItemCount);

    const sidebarIds = await page.$$eval('.conversation-item', els => els.map(el => el.getAttribute('data-id')));
    console.log(`Sidebar conversation count expanded from ${prevConvItemCount} to ${sidebarIds.length}`);
    const uniqueIds = new Set(sidebarIds);
    if (uniqueIds.size !== sidebarIds.length) {
      throw new Error(`Duplicate conversation IDs detected in sidebar pagination: unique=${uniqueIds.size}, total=${sidebarIds.length}`);
    }

    await page.screenshot({ path: SCREENSHOTS.deepConvRestored });
    console.log('Saved Deep Conversation Restored screenshot:', SCREENSHOTS.deepConvRestored);

    // -------------------------------------------------------------
    // PART 7: Bounded Pagination Deep Regression:
    // Initial 22 messages -> UI submits 11 new turns -> Load earlier messages
    // Assert all 22 expected user texts in complete order and each once
    // -------------------------------------------------------------
    console.log('\n=== Testing Bounded Pagination Deep Regression (11 UI Turns Over History) ===');
    const pagingConvId = await createConversationViaBackend('t2-paging-deep-conv-' + Date.now());
    console.log('Created Seeding Conversation ID:', pagingConvId);

    // Seed 11 complete turns (22 messages: Seed Turn 01 to Seed Turn 11)
    console.log('Seeding 11 turns (22 messages) into database...');
    for (let i = 1; i <= 11; i++) {
      await sendRunViaBackend(pagingConvId, `Seed Turn ${i.toString().padStart(2, '0')}`);
    }
    console.log('Seeding complete: 22 messages created in DB.');

    // Open conversation in browser
    console.log(`Setting active conversation in localStorage to ${pagingConvId} and reloading...`);
    await page.evaluate((id) => {
      localStorage.setItem('bot_active_conversation_id', id);
    }, pagingConvId);

    await page.reload({ waitUntil: 'networkidle0' });

    // Initial page loads 20 messages (Seed Turn 02 through Seed Turn 11 = 20 messages; Seed Turn 01 is older)
    await page.waitForFunction(() => {
      const rows = document.querySelectorAll('[data-message-role]');
      const loadMoreBtn = document.querySelector('[data-testid="load-more-btn"], .load-more-btn');
      return rows.length === 20 && Boolean(loadMoreBtn);
    }, { timeout: 15000 });
    console.log('Initial page of 20 messages mounted with "Load earlier messages" button visible.');

    // Sequentially submit 11 new turns in the UI (UI Turn 12 through UI Turn 22)
    console.log('Sequentially submitting 11 new turns in UI (UI Turn 12 to 22)...');
    for (let i = 12; i <= 22; i++) {
      const uiTurnText = `UI Turn ${i.toString().padStart(2, '0')}`;
      await page.type('textarea', uiTurnText);
      await page.click('button[aria-label="Send message"]');

      // Wait for this turn to complete
      await page.waitForFunction((expectedText) => {
        const userBubbles = Array.from(document.querySelectorAll('[data-message-role="user"]'));
        const lastUser = userBubbles[userBubbles.length - 1]?.textContent?.trim();
        const sendBtn = document.querySelector('button[aria-label="Send message"]');
        const stopBtn = document.querySelector('[data-testid="stop-btn"] button, button[aria-label="Stop generation"], .stop-btn');
        return Boolean(sendBtn && !stopBtn && lastUser === expectedText);
      }, { timeout: 15000 }, uiTurnText);
    }
    console.log('Completed submitting 11 new UI turns.');

    // Currently rendered: 20 initial + 22 new (11 turns * 2) = 42 messages
    const postUiCount = await page.evaluate(() => document.querySelectorAll('[data-message-role]').length);
    console.log(`Rendered message count after 11 UI turns: ${postUiCount} (expected 42)`);
    if (postUiCount !== 42) {
      throw new Error(`Expected 42 messages after 11 UI turns, got ${postUiCount}`);
    }

    // "Load earlier messages" must still be present
    const hasLoadEarlier = await page.evaluate(() => Boolean(document.querySelector('[data-testid="load-more-btn"], .load-more-btn')));
    if (!hasLoadEarlier) {
      throw new Error(`Expected 'Load earlier messages' button to still be present`);
    }

    // Click "Load earlier messages" to load the remaining 2 messages (Seed Turn 01)
    console.log('Clicking "Load earlier messages" button to load earlier history...');
    await page.click('[data-testid="load-more-btn"] button, button[aria-label="Load earlier messages"], .load-more-btn');

    // Wait until all 44 messages are mounted and "Load earlier messages" button disappears
    await page.waitForFunction(() => {
      const rows = document.querySelectorAll('[data-message-role]');
      const loadMoreBtn = document.querySelector('[data-testid="load-more-btn"], .load-more-btn');
      return rows.length === 44 && !loadMoreBtn;
    }, { timeout: 15000 });

    // Assert ALL expected 22 user texts in exact chronological order, with each appearing EXACTLY ONCE
    const expectedUserTexts = [
      'Seed Turn 01', 'Seed Turn 02', 'Seed Turn 03', 'Seed Turn 04', 'Seed Turn 05',
      'Seed Turn 06', 'Seed Turn 07', 'Seed Turn 08', 'Seed Turn 09', 'Seed Turn 10',
      'Seed Turn 11', 'UI Turn 12',   'UI Turn 13',   'UI Turn 14',   'UI Turn 15',
      'UI Turn 16',   'UI Turn 17',   'UI Turn 18',   'UI Turn 19',   'UI Turn 20',
      'UI Turn 21',   'UI Turn 22'
    ];

    const actualUserTexts = await page.$$eval('[data-message-role="user"]', els =>
      els.map(el => el.textContent?.trim())
    );

    console.log(`Total user bubbles loaded: ${actualUserTexts.length} (expected 22)`);
    if (actualUserTexts.length !== 22) {
      throw new Error(`Expected exactly 22 user bubbles, got ${actualUserTexts.length}`);
    }

    for (let idx = 0; idx < expectedUserTexts.length; idx++) {
      const expected = expectedUserTexts[idx];
      const actual = actualUserTexts[idx];
      if (actual !== expected) {
        throw new Error(`Sequence mismatch at index ${idx}: expected '${expected}', got '${actual}'`);
      }
    }

    // Verify frequency count: every text must appear exactly once
    const textCounts = {};
    for (const text of actualUserTexts) {
      textCounts[text] = (textCounts[text] || 0) + 1;
      if (textCounts[text] > 1) {
        throw new Error(`Duplicate message detected for '${text}' (appeared ${textCounts[text]} times)`);
      }
    }

    await page.screenshot({ path: SCREENSHOTS.pagination });
    console.log('Saved Pagination Reconciled screenshot:', SCREENSHOTS.pagination);

    console.log('\n=== ALL TICKET 02 BROWSER AUTOMATION ASSERTIONS PASSED ===');
    console.log(JSON.stringify({
      status: 'SUCCESS',
      turn1: TURN1_TEXT,
      turn2: TURN2_TEXT,
      turn3: TURN3_TEXT,
      deepTargetConvId: targetConvId,
      paginationConvId: pagingConvId,
      paginationTotalMessages: 44,
      totalUserTurnsVerified: 22,
      screenshots: SCREENSHOTS
    }, null, 2));

  } catch (err) {
    console.error('\nDeterministic browser automation failed:', err);
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
}

run();
