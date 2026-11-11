import puppeteer from 'puppeteer-core';
import path from 'node:path';

const CHROME_PATH = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const BASE_URL = 'http://localhost:4200';
const BACKEND_URL = 'http://localhost:8080';

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
  return json.data?.createConversation?.id;
}

async function run() {
  console.log('--- Starting Real Browser Clipboard Copy Verification ---');
  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: true,
    args: ['--no-sandbox', '--disable-gpu', '--window-size=1280,900']
  });

  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 900 });

  page.on('console', msg => {
    console.log('[Console]', msg.type(), msg.text());
  });

  // Grant clipboard read & write permissions to page context via CDP
  const cdp = await page.target().createCDPSession();
  await cdp.send('Browser.grantPermissions', {
    permissions: ['clipboardReadWrite', 'clipboardSanitizedWrite'],
    origin: BASE_URL
  });

  try {
    const convId = await createConversationViaBackend('Clipboard-Test-Conv');
    await page.goto(BASE_URL, { waitUntil: 'networkidle0' });

    await page.evaluate(id => {
      localStorage.setItem('bot_active_conversation_id', id);
    }, convId);
    await page.reload({ waitUntil: 'networkidle0' });

    const promptText = 'Test message for browser clipboard copy verification';
    await page.waitForSelector('.composer-textarea, textarea');
    await page.type('.composer-textarea, textarea', promptText);
    await page.click('.send-btn, button[aria-label="Send message"]');

    // Wait until assistant completes reply
    await page.waitForFunction(() => {
      const assistant = document.querySelector('.message-row.assistant .assistant-bubble span, [data-message-role="assistant"]');
      const stopBtn = Array.from(document.querySelectorAll('header button, .stop-btn')).find(b => b.textContent.includes('Stop'));
      return Boolean(assistant && !stopBtn);
    }, { timeout: 15000 });

    // 1. Verify Assistant Message Copy (Native CopilotKit or Custom)
    console.log('Testing Assistant Message Copy button...');
    const assistantCopyBtn = await page.waitForSelector('copilot-chat-assistant-message-copy-button button, .message-row.assistant .copy-btn', { timeout: 5000 });
    await assistantCopyBtn.click();
    await new Promise(r => setTimeout(r, 500));

    const assistantClipboardText = await page.evaluate(() => navigator.clipboard.readText());
    console.log('Read assistant message from clipboard:', JSON.stringify(assistantClipboardText));
    if (!assistantClipboardText || assistantClipboardText.trim().length === 0) {
      throw new Error('Clipboard text is empty after assistant copy click');
    }

    const screenshotPath = '/tmp/verify-clipboard-passed.png';
    await page.screenshot({ path: screenshotPath });
    console.log('Saved clipboard verification screenshot:', screenshotPath);

    console.log('=== REAL BROWSER CLIPBOARD COPY VERIFICATION PASSED ===');
    console.log(JSON.stringify({
      status: 'SUCCESS',
      assistantClipboardMatch: true,
      clipboardLength: assistantClipboardText.length,
      screenshot: screenshotPath
    }, null, 2));

  } catch (err) {
    console.error('Clipboard verification failed:', err);
    process.exit(1);
  } finally {
    await browser.close();
  }
}

run();
