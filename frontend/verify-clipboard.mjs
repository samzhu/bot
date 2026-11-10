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
    await page.waitForSelector('.composer-textarea');
    await page.type('.composer-textarea', promptText);
    await page.click('.send-btn');

    // Wait until assistant completes reply
    await page.waitForFunction(() => {
      const assistant = document.querySelector('.message-row.assistant .assistant-bubble span');
      const stopBtn = document.querySelector('.stop-btn');
      return Boolean(assistant && !stopBtn);
    }, { timeout: 10000 });

    // 1. Verify User Message Copy
    console.log('Testing User Message Copy button...');
    const userCopyBtn = await page.waitForSelector('.message-row.user .copy-btn');
    const initialUserBtnText = await page.$eval('.message-row.user .copy-btn', el => el.textContent?.trim());
    console.log('Initial copy button text:', initialUserBtnText);

    await userCopyBtn.click();

    // Verify button text immediately switches to "Copied!"
    await page.waitForFunction(() => {
      const btn = document.querySelector('.message-row.user .copy-btn');
      return btn && btn.textContent.trim() === 'Copied!';
    }, { timeout: 2000 });

    // Read clipboard content directly from browser clipboard API
    const userClipboardText = await page.evaluate(() => navigator.clipboard.readText());
    console.log('Read user message from clipboard:', JSON.stringify(userClipboardText));
    if (userClipboardText !== promptText) {
      throw new Error(`Clipboard text '${userClipboardText}' did not match user prompt '${promptText}'`);
    }

    // 2. Verify Assistant Message Copy
    console.log('Testing Assistant Message Copy button...');
    const assistantExpectedText = await page.$eval('.message-row.assistant .assistant-bubble span', el => el.textContent);
    const assistantCopyBtn = await page.waitForSelector('.message-row.assistant .copy-btn');
    await assistantCopyBtn.click();

    await page.waitForFunction(() => {
      const btn = document.querySelector('.message-row.assistant .copy-btn');
      return btn && btn.textContent.trim() === 'Copied!';
    }, { timeout: 2000 });

    const assistantClipboardText = await page.evaluate(() => navigator.clipboard.readText());
    console.log('Read assistant message from clipboard:', JSON.stringify(assistantClipboardText));
    if (assistantClipboardText !== assistantExpectedText) {
      throw new Error(`Clipboard text did not match assistant bubble content`);
    }

    // 3. Wait for timeout and verify button text reverts to "Copy"
    console.log('Waiting for "Copied!" text to revert to "Copy"...');
    await page.waitForFunction(() => {
      const btn = document.querySelector('.message-row.assistant .copy-btn');
      return btn && btn.textContent.trim() === 'Copy';
    }, { timeout: 4000 });

    const screenshotPath = '/tmp/verify-clipboard-passed.png';
    await page.screenshot({ path: screenshotPath });
    console.log('Saved clipboard verification screenshot:', screenshotPath);

    console.log('=== REAL BROWSER CLIPBOARD COPY VERIFICATION PASSED ===');
    console.log(JSON.stringify({
      status: 'SUCCESS',
      userClipboardMatch: true,
      assistantClipboardMatch: true,
      revertedToCopyState: true,
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
