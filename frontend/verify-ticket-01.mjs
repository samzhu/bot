import puppeteer from 'puppeteer-core';
import path from 'node:path';

const CHROME_PATH = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const BASE_URL = 'http://localhost:4200';
const SENT_MESSAGE = '請用繁體中文回答：這是一次本機最小聊天閉環驗證。';

const SCREENSHOT_DIR = '/tmp';
const SCREENSHOTS = {
  initial: path.join(SCREENSHOT_DIR, 'bot-e2e-01-initial.png'),
  streaming: path.join(SCREENSHOT_DIR, 'bot-e2e-02-streaming.png'),
  completed: path.join(SCREENSHOT_DIR, 'bot-e2e-03-completed.png'),
  reloaded: path.join(SCREENSHOT_DIR, 'bot-e2e-04-reloaded.png')
};

async function run() {
  console.log('--- Starting Ticket 01 Deterministic Browser Automation Verification ---');
  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: true,
    args: ['--no-sandbox', '--disable-gpu', '--window-size=1280,800']
  });

  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 800 });

  try {
    // 1. Navigate to Web UI
    console.log(`Navigating to ${BASE_URL}...`);
    await page.goto(BASE_URL, { waitUntil: 'networkidle0' });

    // 2. Verify Seed Bot and Clean White Layout
    await page.waitForSelector('.sidebar');
    const workspaceTitle = await page.$eval('.workspace-title', el => el.textContent?.trim());
    console.log('Workspace Title:', workspaceTitle);
    if (!workspaceTitle?.includes('Herdr Bot')) {
      throw new Error(`Expected workspace title 'Herdr Bot', got '${workspaceTitle}'`);
    }

    const seedBotName = await page.$eval('.seed-bot-name', el => el.textContent?.trim());
    console.log('Seed Bot Name:', seedBotName);
    if (!seedBotName?.includes('General Assistant')) {
      throw new Error(`Expected Seed Bot 'General Assistant', got '${seedBotName}'`);
    }

    // 3. Trigger "New Conversation" without arbitrary sleeps
    const prevConvId = await page.evaluate(() => localStorage.getItem('bot_active_conversation_id'));
    console.log('Initial Active Conversation ID:', prevConvId);

    console.log('Clicking "+ New Chat"...');
    await page.click('.new-chat-btn');

    // Deterministic wait: wait until active conversation ID changes and message view is empty
    await page.waitForFunction(
      (oldId) => {
        const currentId = localStorage.getItem('bot_active_conversation_id');
        const rows = document.querySelectorAll('.message-row, [data-message-role]');
        return Boolean(currentId && currentId !== oldId && rows.length === 0);
      },
      { timeout: 10000 },
      prevConvId
    );

    const convId = await page.evaluate(() => localStorage.getItem('bot_active_conversation_id'));
    console.log('Newly Established Conversation ID:', convId);
    if (!convId || convId === prevConvId) {
      throw new Error(`Failed to establish a new distinct conversation ID: got ${convId}`);
    }

    await page.screenshot({ path: SCREENSHOTS.initial });
    console.log('Saved initial screenshot:', SCREENSHOTS.initial);

    // 4. Type Traditional Chinese message into composer
    console.log('Typing message into composer...');
    await page.type('.composer-textarea, textarea', SENT_MESSAGE);

    // Verify Send button is enabled
    const sendDisabledBefore = await page.$eval('.send-btn, button[aria-label="Send message"]', el => el.disabled);
    if (sendDisabledBefore) {
      throw new Error('Send button should be enabled when non-empty text is entered');
    }

    // 5. Send message and observe streaming lifecycle
    console.log('Submitting message...');
    await page.click('.send-btn, button[aria-label="Send message"]');

    // Wait for message rows to mount
    await page.waitForSelector('.message-row.user, [data-message-role="user"]');
    await page.waitForSelector('.message-row.assistant, [data-message-role="assistant"]');

    // Strict assertion: verify controls reflect active streaming (Stop available, sending blocked, nav disabled)
    const streamLockState = await page.evaluate(() => {
      const stopBtn = Array.from(document.querySelectorAll('.stop-btn, header button')).find(b => b.textContent.includes('Stop'));
      const sendBtn = document.querySelector('.send-btn');
      const newChatBtn = document.querySelector('.new-chat-btn');
      return {
        hasStopBtn: Boolean(stopBtn),
        stopText: stopBtn?.textContent?.trim(),
        sendBtnExists: Boolean(sendBtn),
        newChatDisabled: newChatBtn?.disabled === true
      };
    });
    console.log('Stream Lock Assertions:', streamLockState);
    if (!streamLockState.hasStopBtn) {
      throw new Error('Assertion failed: Stop button must be visible during streaming');
    }
    if (streamLockState.stopText !== 'Stop') {
      throw new Error(`Assertion failed: Stop button text must be 'Stop', got '${streamLockState.stopText}'`);
    }
    if (streamLockState.sendBtnExists) {
      throw new Error('Assertion failed: Send button must not be active while streaming (replaced by Stop)');
    }
    if (!streamLockState.newChatDisabled) {
      throw new Error('Assertion failed: New Chat button must be disabled during streaming');
    }

    // Deterministic verification of token progression via DOM observation:
    // Capture Delta 1 (non-empty initial token delta)
    const delta1Handle = await page.waitForFunction(() => {
      const text = (document.querySelector('.message-row.assistant .assistant-bubble, [data-message-role="assistant"]')?.textContent || '').trim();
      return text.length > 0 ? text : null;
    }, { timeout: 10000 });
    const delta1Text = await delta1Handle.jsonValue();
    console.log('Captured Assistant Streaming Delta 1:', JSON.stringify(delta1Text));

    // Capture screenshot during active live streaming
    await page.screenshot({ path: SCREENSHOTS.streaming });
    console.log('Saved verified live streaming screenshot:', SCREENSHOTS.streaming);

    // Capture Delta 2 or completion
    const delta2Handle = await page.waitForFunction((prev) => {
      const text = (document.querySelector('.message-row.assistant .assistant-bubble, [data-message-role="assistant"]')?.textContent || '').trim();
      const stopBtn = Array.from(document.querySelectorAll('header button, .stop-btn')).find(b => b.textContent.includes('Stop'));
      return (text.length > prev.length && text.startsWith(prev)) || (!stopBtn && text.length > 0);
    }, { timeout: 10000 }, delta1Text);
    const delta2Text = await delta2Handle.jsonValue();
    console.log('Captured Assistant Streaming Delta 2 / Final:', JSON.stringify(delta2Text));

    // 6. Wait for final generation completion (terminal event received)
    console.log('Waiting for terminal completion...');
    await page.waitForFunction(() => {
      const stopBtn = Array.from(document.querySelectorAll('header button, .stop-btn')).find(b => b.textContent.includes('Stop'));
      const newChatBtn = document.querySelector('.new-chat-btn');
      return !stopBtn && newChatBtn?.disabled === false;
    }, { timeout: 15000 });

    const assistantText = await page.$eval('.message-row.assistant .assistant-bubble, [data-message-role="assistant"]', el => el.textContent?.trim());
    console.log('Final Assistant Reply:', assistantText);

    await page.screenshot({ path: SCREENSHOTS.completed });
    console.log('Saved completed screenshot:', SCREENSHOTS.completed);

    // 7. Verify Message Counts BEFORE reload
    const userCountBefore = await page.$$eval('.message-row.user, [data-message-role="user"]', els => els.length);
    const assistantCountBefore = await page.$$eval('.message-row.assistant, [data-message-role="assistant"]', els => els.length);
    console.log(`Pre-refresh counts - User: ${userCountBefore}, Assistant: ${assistantCountBefore}`);
    if (userCountBefore !== 1 || assistantCountBefore !== 1) {
      throw new Error(`Pre-refresh message count error: user=${userCountBefore}, assistant=${assistantCountBefore}`);
    }

    // 8. Reload page to verify persistence and restoration
    console.log('Reloading browser page to verify restoration...');
    await page.reload({ waitUntil: 'networkidle0' });

    // Wait for restored messages
    await page.waitForSelector('.message-row.user, [data-message-role="user"]', { timeout: 10000 });
    await page.waitForSelector('.message-row.assistant, [data-message-role="assistant"]', { timeout: 10000 });

    // 9. Strict DOM and content assertions post-refresh
    const reloadedConvId = await page.evaluate(() => localStorage.getItem('bot_active_conversation_id'));
    if (reloadedConvId !== convId) {
      throw new Error(`Conversation ID mismatch after reload: expected ${convId}, got ${reloadedConvId}`);
    }

    const userCountAfter = await page.$$eval('.message-row.user, [data-message-role="user"]', els => els.length);
    const assistantCountAfter = await page.$$eval('.message-row.assistant, [data-message-role="assistant"]', els => els.length);
    console.log(`Post-refresh counts - User: ${userCountAfter}, Assistant: ${assistantCountAfter}`);

    if (userCountAfter !== 1) {
      throw new Error(`Expected exactly 1 user message after reload, found ${userCountAfter}`);
    }
    if (assistantCountAfter !== 1) {
      throw new Error(`Expected exactly 1 assistant message after reload, found ${assistantCountAfter}`);
    }

    const reloadedUserText = await page.$eval('.message-row.user .user-bubble, [data-message-role="user"]', el => el.textContent?.trim());
    const reloadedAssistantText = await page.$eval('.message-row.assistant .assistant-bubble, [data-message-role="assistant"]', el => el.textContent?.trim());

    console.log('Reloaded User Text:', reloadedUserText);
    console.log('Reloaded Assistant Text:', reloadedAssistantText);

    if (reloadedUserText !== SENT_MESSAGE) {
      throw new Error(`Reloaded user text does not match: expected '${SENT_MESSAGE}', got '${reloadedUserText}'`);
    }
    if (reloadedAssistantText !== assistantText) {
      throw new Error(`Reloaded assistant text does not match pre-refresh text`);
    }

    await page.screenshot({ path: SCREENSHOTS.reloaded });
    console.log('Saved reloaded screenshot:', SCREENSHOTS.reloaded);

    console.log('\n=== ALL DETERMINISTIC BROWSER E2E ASSERTIONS PASSED ===');
    console.log(JSON.stringify({
      status: 'SUCCESS',
      conversationId: convId,
      delta1: delta1Text,
      delta2: delta2Text,
      userMessage: reloadedUserText,
      assistantMessage: reloadedAssistantText,
      screenshots: SCREENSHOTS
    }, null, 2));

  } finally {
    await browser.close();
  }
}

run().catch(err => {
  console.error('Deterministic browser automation failed:', err);
  process.exit(1);
});
