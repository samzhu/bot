// Historical layout verification retained for coverage traceability; see README.
throw new Error('Retired legacy browser verification; use the current ThreadPlane browser matrix.');

import puppeteer from 'puppeteer-core';
import path from 'node:path';
import { installBrowserGuard, getGuardStats, getVerificationOrigins } from './test/verification-harness.mjs';

const CHROME_PATH = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const { frontendUrl: BASE_URL } = getVerificationOrigins();
const SEED_CONV_ID = 'seed-conv-completed-01';

async function run() {
  console.log('--- Starting Safe Real Browser Clipboard Copy Verification (Zero Paid Model Calls) ---');
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

  // 1. Install Fail-Closed Browser Interception Guard
  // Guarantees that any accidental prompt dispatch immediately fails closed (0 live model calls)
  await installBrowserGuard(page, { mode: 'fail-closed' });

  // 2. Grant clipboard read & write permissions to page context via CDP
  const cdp = await page.target().createCDPSession();
  await cdp.send('Browser.grantPermissions', {
    permissions: ['clipboardReadWrite', 'clipboardSanitizedWrite'],
    origin: BASE_URL
  });

  try {
    // 3. Navigate to frontend using existing synthetic seed conversation
    console.log(`Navigating to ${BASE_URL}...`);
    await page.goto(BASE_URL, { waitUntil: 'networkidle0' });

    console.log(`Selecting completed seed conversation: ${SEED_CONV_ID}...`);
    await page.evaluate(id => {
      localStorage.setItem('bot_active_conversation_id', id);
    }, SEED_CONV_ID);
    await page.reload({ waitUntil: 'networkidle0' });

    // Wait for the seeded assistant message to mount
    console.log('Waiting for seeded assistant message...');
    await page.waitForSelector('.message-row.assistant, [data-message-role="assistant"]', { timeout: 10000 });

    // 4. Verify Assistant Message Copy Button
    console.log('Testing Assistant Message Copy button on pre-seeded message...');
    const assistantCopyBtn = await page.waitForSelector(
      'copilot-chat-assistant-message-copy-button button, .message-row.assistant .copy-btn',
      { timeout: 5000 }
    );
    await assistantCopyBtn.click();
    await new Promise(r => setTimeout(r, 500));

    // 5. Read back clipboard text
    const assistantClipboardText = await page.evaluate(() => navigator.clipboard.readText());
    console.log('Read assistant message from clipboard:', JSON.stringify(assistantClipboardText));
    if (!assistantClipboardText || assistantClipboardText.trim().length === 0) {
      throw new Error('Clipboard text is empty after assistant copy click');
    }

    // Verify it contains expected content from seed
    if (!assistantClipboardText.includes('Spring AI') && !assistantClipboardText.includes('ChatClient')) {
      throw new Error(`Clipboard text did not match expected seed content: ${assistantClipboardText.substring(0, 50)}...`);
    }

    const screenshotPath = '/tmp/verify-clipboard-passed.png';
    await page.screenshot({ path: screenshotPath });
    console.log('Saved clipboard verification screenshot:', screenshotPath);

    // Assert Guard Statistics: 0 generation calls attempted or dispatched
    const stats = getGuardStats();
    if (stats.blockedCalls > 0) {
      throw new Error(`UNEXPECTED GENERATION: Attempted ${stats.blockedCalls} generation calls during pure clipboard verification!`);
    }

    console.log('=== REAL BROWSER CLIPBOARD COPY VERIFICATION PASSED (0 MODEL CALLS) ===');
    console.log(JSON.stringify({
      status: 'SUCCESS',
      assistantClipboardMatch: true,
      clipboardLength: assistantClipboardText.length,
      screenshot: screenshotPath,
      guardStats: stats
    }, null, 2));

  } catch (err) {
    console.error('Clipboard verification failed:', err);
    process.exit(1);
  } finally {
    await browser.close();
  }
}

run();
