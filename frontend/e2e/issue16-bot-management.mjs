// Historical layout verification retained for coverage traceability; see README.
throw new Error('Retired legacy browser verification; use the current ThreadPlane browser matrix.');

import puppeteer from 'puppeteer-core';
import http from 'node:http';
import fs from 'node:fs';

const CHROME_PATH = process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const FRONTEND_URL = process.env.VERIFICATION_FRONTEND_URL || 'http://localhost:4300';
const BACKEND_URL = process.env.VERIFICATION_BACKEND_URL || 'http://localhost:8181';

const SCREENSHOTS = {
  managerView: '/tmp/issue16-01-manager-view.png',
  dialogOpened: '/tmp/issue16-02-dialog-opened.png',
  dialogCancelled: '/tmp/issue16-03-dialog-cancelled.png',
  validationBlocked: '/tmp/issue16-04-validation-blocked.png',
  retryPreserved: '/tmp/issue16-05-retry-preserved.png',
  botCreated: '/tmp/issue16-06-bot-created.png',
  reloaded: '/tmp/issue16-07-reloaded.png',
  chatWorking: '/tmp/issue16-08-chat-working.png',
  manager1280: '/tmp/issue16-manager-1280.png',
  manager375: '/tmp/issue16-manager-375.png',
  dialog1280: '/tmp/issue16-dialog-1280.png',
  dialog375: '/tmp/issue16-dialog-375.png'
};

const results = [];

function recordResult(stepName, passed, details = '') {
  results.push({ stepName, passed, details });
  const statusMark = passed ? '✅ PASS' : '❌ FAIL';
  console.log(`${statusMark} - ${stepName} ${details ? '(' + details + ')' : ''}`);
}

async function setInputValue(page, selector, value) {
  await page.evaluate((sel, val) => {
    const el = document.querySelector(sel);
    if (el) {
      el.focus();
      el.value = val;
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
      el.dispatchEvent(new Event('blur', { bubbles: true }));
    }
  }, selector, value);
}

async function checkNoHorizontalOverflow(page) {
  return await page.evaluate(() => {
    const clientWidth = document.documentElement.clientWidth;
    const scrollWidth = document.documentElement.scrollWidth;
    const bodyScrollWidth = document.body.scrollWidth;

    const cards = Array.from(document.querySelectorAll('.bot-card'));
    const overflowCards = [];
    for (const card of cards) {
      if (card.scrollWidth > card.clientWidth + 1) {
        overflowCards.push({
          id: card.getAttribute('data-testid'),
          cardScrollWidth: card.scrollWidth,
          cardClientWidth: card.clientWidth
        });
      }
      const nameEl = card.querySelector('.card-name');
      if (nameEl && nameEl.scrollWidth > nameEl.clientWidth + 1) {
        overflowCards.push({
          name: nameEl.textContent?.substring(0, 30),
          nameScrollWidth: nameEl.scrollWidth,
          nameClientWidth: nameEl.clientWidth
        });
      }
    }

    const pageOverflow = scrollWidth > clientWidth + 1 || bodyScrollWidth > clientWidth + 1;
    return {
      passed: !pageOverflow && overflowCards.length === 0,
      clientWidth,
      scrollWidth,
      overflowCards
    };
  });
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

export async function runVerification() {
  console.log('=== Issue #16 Bot Management Independent Verification ===');
  console.log(`Checking Frontend (${FRONTEND_URL}) and Backend (${BACKEND_URL}) readiness...`);

  const frontendReady = await checkUrlReady(FRONTEND_URL);
  if (!frontendReady) {
    recordResult('Service Readiness Check', false, `Frontend at ${FRONTEND_URL} not ready within timeout`);
    printSummary();
    return false;
  }

  const backendReady = await checkUrlReady(`${BACKEND_URL}/actuator/health`) || await checkUrlReady(`${BACKEND_URL}/graphql`);
  if (!backendReady) {
    recordResult('Service Readiness Check', false, `Backend at ${BACKEND_URL} not ready within timeout`);
    printSummary();
    return false;
  }
  recordResult('Service Readiness Check', true, 'Frontend 4300 and Backend 8181 are active');

  if (!fs.existsSync(CHROME_PATH)) {
    recordResult('Chrome Executable Check', false, `Chrome binary not found at ${CHROME_PATH}`);
    printSummary();
    return false;
  }

  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--window-size=1280,800']
  });

  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 800 });

  let capturedMutations = [];
  let shouldFailNextCreateBot = false;

  await page.setRequestInterception(true);
  page.on('request', (req) => {
    if (req.url().includes('/graphql') && req.method() === 'POST') {
      try {
        const postData = JSON.parse(req.postData() || '{}');
        const query = postData.query || '';
        if (query.includes('createBot')) {
          capturedMutations.push(postData);
          if (shouldFailNextCreateBot) {
            shouldFailNextCreateBot = false;
            req.respond({
              status: 500,
              contentType: 'application/json',
              body: JSON.stringify({ errors: [{ message: 'Synthetic simulated 500 error for browser retry test' }] })
            });
            return;
          }
        }
      } catch (err) {
        // passthrough
      }
    }
    req.continue();
  });

  try {
    // 1. Initial Load & Navigate to Bot Manager
    await page.goto(FRONTEND_URL, { waitUntil: 'networkidle0' });
    await page.waitForSelector('[data-testid="bot-manager-nav-btn"]', { timeout: 10000 });
    await page.click('[data-testid="bot-manager-nav-btn"]');
    await page.waitForSelector('.bot-manager-container', { timeout: 5000 });
    await page.screenshot({ path: SCREENSHOTS.managerView });
    recordResult('Navigate to Bot Manager', true, 'Switched from chat to bot manager view');

    // 2. Open Create Dialog & Check Accessibility/Focus
    const createBtnSelector = '[data-testid="create-bot-modal-btn"]';
    await page.waitForSelector(createBtnSelector);
    await page.click(createBtnSelector);

    const dialogSelector = 'dialog[data-testid="create-bot-dialog"]';
    await page.waitForSelector(dialogSelector, { visible: true });

    const dialogAttributes = await page.$eval(dialogSelector, (el) => ({
      open: el.open,
      ariaLabelledBy: el.getAttribute('aria-labelledby'),
      ariaModal: el.getAttribute('aria-modal')
    }));

    const isDialogProperlyLabelled = dialogAttributes.open &&
      dialogAttributes.ariaLabelledBy === 'bot-dialog-title' &&
      dialogAttributes.ariaModal === 'true';

    await page.screenshot({ path: SCREENSHOTS.dialogOpened });
    recordResult('Native Dialog Accessibility', isDialogProperlyLabelled,
      `open=${dialogAttributes.open}, labelledby=${dialogAttributes.ariaLabelledBy}`);

    // 3. Cancel / Escape Behavior and Focus Return
    capturedMutations = [];
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.querySelector('dialog[data-testid="create-bot-dialog"]')?.open, { timeout: 3000 });

    const focusedTagAfterCancel = await page.evaluate(() => document.activeElement?.getAttribute('data-testid'));
    const zeroMutationOnCancel = capturedMutations.length === 0;
    const focusReturnedToTrigger = focusedTagAfterCancel === 'create-bot-modal-btn';

    await page.screenshot({ path: SCREENSHOTS.dialogCancelled });
    recordResult('Cancel/Escape Restores Focus without Mutation',
      zeroMutationOnCancel && focusReturnedToTrigger,
      `mutations=${capturedMutations.length}, focused=${focusedTagAfterCancel}`);

    // 4. Client-side Validation (Whitespace & Length Limits)
    await page.click(createBtnSelector);
    await page.waitForSelector(dialogSelector, { visible: true });

    // Test 4a: whitespace-only name
    await setInputValue(page, '[data-testid="bot-name-input"]', '    ');
    let isWhitespaceSubmitDisabled = await page.$eval('[data-testid="submit-bot-btn"]', (btn) => btn.disabled);
    let nameErrorVisible = await page.$eval('[data-testid="bot-name-error"]', (el) => !!el.textContent.trim()).catch(() => false);

    // Test 4b: over-length description (> 2000 characters)
    await setInputValue(page, '[data-testid="bot-name-input"]', 'Valid Name');
    const overlengthDesc = 'B'.repeat(2005);
    await setInputValue(page, '[data-testid="bot-desc-input"]', overlengthDesc);
    let isOverDescSubmitDisabled = await page.$eval('[data-testid="submit-bot-btn"]', (btn) => btn.disabled);

    // Test 4c: over-length name (> 100 characters)
    await setInputValue(page, '[data-testid="bot-desc-input"]', '');
    const overlengthName = 'A'.repeat(105);
    await setInputValue(page, '[data-testid="bot-name-input"]', overlengthName);
    let isOverNameSubmitDisabled = await page.$eval('[data-testid="submit-bot-btn"]', (btn) => btn.disabled);

    capturedMutations = [];
    if (!isOverNameSubmitDisabled) {
      await page.click('[data-testid="submit-bot-btn"]');
    }
    const zeroMutationOnInvalid = capturedMutations.length === 0;

    await page.screenshot({ path: SCREENSHOTS.validationBlocked });
    const validationPassed = isWhitespaceSubmitDisabled && isOverDescSubmitDisabled && zeroMutationOnInvalid;
    recordResult('Form Validation Blocks Invalid Submissions',
      validationPassed,
      `whitespaceDisabled=${isWhitespaceSubmitDisabled}, overDescDisabled=${isOverDescSubmitDisabled}, overNameDisabled=${isOverNameSubmitDisabled}, zeroMutation=${zeroMutationOnInvalid}`);

    // 5. Simulated Network 500 Interception: Preserve Inputs and Allow Retry
    // Note: Use valid exactly 100-character non-whitespace name to strictly test card overflow boundary
    const syntheticName = 'GkeAgent' + 'X'.repeat(92); // exactly 100 characters, no whitespace
    const syntheticDesc = 'Synthetic agent specification for browser verification';
    const syntheticInstructions = 'You are a synthetic test assistant running on simulated mode.';

    await setInputValue(page, '[data-testid="bot-name-input"]', syntheticName);
    await setInputValue(page, '[data-testid="bot-desc-input"]', syntheticDesc);
    await setInputValue(page, '[data-testid="bot-instructions-input"]', syntheticInstructions);

    // Verify submit button is enabled for valid inputs
    const isSubmitReady = await page.$eval('[data-testid="submit-bot-btn"]', (btn) => !btn.disabled);

    // Arm 500 failure for next submit
    shouldFailNextCreateBot = true;
    capturedMutations = [];

    await page.click('[data-testid="submit-bot-btn"]');
    await page.waitForSelector('[data-testid="form-error-banner"]', { visible: true, timeout: 5000 });

    const preservedValues = await page.evaluate(() => ({
      name: document.querySelector('[data-testid="bot-name-input"]')?.value,
      desc: document.querySelector('[data-testid="bot-desc-input"]')?.value,
      inst: document.querySelector('[data-testid="bot-instructions-input"]')?.value,
      dialogOpen: document.querySelector('dialog[data-testid="create-bot-dialog"]')?.open
    }));

    const inputPreserved = preservedValues.dialogOpen &&
      preservedValues.name === syntheticName &&
      preservedValues.desc === syntheticDesc &&
      preservedValues.inst === syntheticInstructions;

    await page.screenshot({ path: SCREENSHOTS.retryPreserved });
    recordResult('Simulated Failure Preserves Input for Retry', inputPreserved,
      `dialogStillOpen=${preservedValues.dialogOpen}, inputsPreserved=${inputPreserved}`);

    // 6. Retry Submit & Verify Card in Grid
    shouldFailNextCreateBot = false;
    await page.click('[data-testid="submit-bot-btn"]');
    await page.waitForFunction(() => !document.querySelector('dialog[data-testid="create-bot-dialog"]')?.open, { timeout: 8000 });

    // Locate the newly created bot card in grid
    const createdBotInfo = await page.evaluate((name) => {
      const cards = Array.from(document.querySelectorAll('.bot-card'));
      for (const card of cards) {
        const title = card.querySelector('.card-name')?.textContent?.trim();
        if (title === name) {
          const id = card.getAttribute('data-testid')?.replace('bot-card-', '');
          const badge = card.querySelector('.status-pill')?.textContent?.trim();
          const conn = card.querySelector('.detail-value')?.textContent?.trim();
          return { id, badge, conn };
        }
      }
      return null;
    }, syntheticName);

    const cardRendered = !!createdBotInfo;
    const isSimulatedBadge = createdBotInfo?.badge?.includes('SPRING_AI_SIMULATION') || createdBotInfo?.badge?.includes('模擬');
    const isNotConnected = createdBotInfo?.conn?.includes('NOT_CONNECTED') || createdBotInfo?.conn?.includes('未連線');

    await page.screenshot({ path: SCREENSHOTS.botCreated });
    recordResult('Bot Creation & Display Verification', cardRendered && isSimulatedBadge,
      `id=${createdBotInfo?.id}, badge=${createdBotInfo?.badge}, conn=${createdBotInfo?.conn}`);

    // 6b. Viewport 1280 & 375 Horizontal Scroll and Overflow Verification (with 100-char long name)
    // 1280 Desktop Check
    await page.setViewport({ width: 1280, height: 800 });
    await page.screenshot({ path: SCREENSHOTS.manager1280 });
    const overflow1280 = await checkNoHorizontalOverflow(page);

    // Open dialog at 1280 to capture updated copy
    await page.click(createBtnSelector);
    await page.waitForSelector(dialogSelector, { visible: true });
    await page.screenshot({ path: SCREENSHOTS.dialog1280 });
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.querySelector('dialog[data-testid="create-bot-dialog"]')?.open, { timeout: 3000 });

    // 375 Mobile Check
    await page.setViewport({ width: 375, height: 667 });
    await page.screenshot({ path: SCREENSHOTS.manager375 });
    const overflow375 = await checkNoHorizontalOverflow(page);

    // Open dialog at 375 to capture mobile layout
    await page.click(createBtnSelector);
    await page.waitForSelector(dialogSelector, { visible: true });
    await page.screenshot({ path: SCREENSHOTS.dialog375 });
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.querySelector('dialog[data-testid="create-bot-dialog"]')?.open, { timeout: 3000 });

    // Restore desktop viewport
    await page.setViewport({ width: 1280, height: 800 });

    const layout1280Passed = overflow1280.passed;
    const layout375Passed = overflow375.passed;
    recordResult('1280px & 375px Horizontal Scroll & Overflow Check', layout1280Passed && layout375Passed,
      `layout1280=${layout1280Passed} (client=${overflow1280.clientWidth}, scroll=${overflow1280.scrollWidth}), layout375=${layout375Passed} (client=${overflow375.clientWidth}, scroll=${overflow375.scrollWidth}), errors=${JSON.stringify(overflow375.overflowCards)}`);

    // 7. Page Reload & Database Persistence Check
    await page.reload({ waitUntil: 'networkidle0' });
    await page.waitForSelector('[data-testid="bot-manager-nav-btn"]', { timeout: 10000 });
    await page.click('[data-testid="bot-manager-nav-btn"]');
    await page.waitForSelector('.cards-grid', { timeout: 5000 });

    const botExistsAfterReload = await page.evaluate((name) => {
      const cards = Array.from(document.querySelectorAll('.bot-card .card-name'));
      return cards.some(el => el.textContent?.trim() === name);
    }, syntheticName);

    await page.screenshot({ path: SCREENSHOTS.reloaded });
    recordResult('PostgreSQL Persistence Across Reload', botExistsAfterReload,
      `syntheticBot="${syntheticName}" persists in cards list`);

    // 8. Default Local Bot Intact & Regular Chat Verification
    await page.click('[data-testid="nav-chat-view-btn"]');
    await page.waitForSelector('.seed-bot-card', { timeout: 5000 });

    const seedBotName = await page.$eval('.seed-bot-name', el => el.textContent?.trim());
    const isSeedBotDefault = seedBotName === 'General Assistant';

    // Click New Chat to start a clean session
    const newChatBtnSelector = '[data-testid="new-chat-btn"] button, [data-testid="new-chat-btn"]';
    await page.waitForSelector(newChatBtnSelector, { timeout: 5000 });
    await page.click(newChatBtnSelector);

    // Generate unique marker and construct prompt
    const chatMarker = `marker_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`;
    const userPrompt = `Hello bot verification test ${chatMarker}`;

    const textareaSelector = 'copilot-chat-input textarea, textarea';
    await page.waitForSelector(textareaSelector, { timeout: 5000 });
    await page.focus(textareaSelector);
    await page.type(textareaSelector, userPrompt);

    // Submit message via Send button
    const sendBtnSelector = '[data-testid="send-message-btn"] button, copilot-chat-send-button button, button[aria-label="Send message"]';
    await page.waitForSelector(sendBtnSelector, { timeout: 5000 });
    await page.click(sendBtnSelector);

    // Wait strictly for new assistant message with the exact marker and mock streaming prefix (no swallowing timeout)
    await page.waitForFunction(
      (expectedMarker) => {
        const asstEls = Array.from(document.querySelectorAll('copilot-chat-assistant-message'));
        return asstEls.some(el => {
          const text = el.textContent || '';
          return text.includes(expectedMarker) && text.includes('mock streaming response');
        });
      },
      { timeout: 15000 },
      chatMarker
    );

    // Wait for streaming and processing to complete (stop button dismounts)
    await page.waitForFunction(
      () => {
        const stopBtn = document.querySelector('[data-testid="stop-btn"] button, [data-testid="stop-btn"]');
        const processingInput = document.querySelector('copilot-chat-input[mode="processing"]');
        return !stopBtn && !processingInput;
      },
      { timeout: 15000 }
    );

    const receivedAssistantText = await page.evaluate((expectedMarker) => {
      const asstEls = Array.from(document.querySelectorAll('copilot-chat-assistant-message'));
      const match = asstEls.find(el => (el.textContent || '').includes(expectedMarker));
      return match ? match.textContent.trim() : null;
    }, chatMarker);

    const chatReplied = !!receivedAssistantText &&
      receivedAssistantText.includes(chatMarker) &&
      receivedAssistantText.includes('mock streaming response');

    const chatVerificationPassed = isSeedBotDefault && chatReplied;

    await page.screenshot({ path: SCREENSHOTS.chatWorking });
    recordResult('Default Local Bot Stability & Chat Flow', chatVerificationPassed,
      `seedBot=${seedBotName}, marker=${chatMarker}, chatReplied=${chatReplied}`);

  } catch (err) {
    recordResult('Verification Execution', false, `Unhandled error: ${err.message}`);
  } finally {
    await browser.close();
  }

  printSummary();
  return results.every(r => r.passed);
}

function printSummary() {
  console.log('\n=== Execution Summary ===');
  const passedCount = results.filter(r => r.passed).length;
  const failedCount = results.filter(r => !r.passed).length;
  console.log(`Total Steps: ${results.length} | Passed: ${passedCount} | Failed: ${failedCount}`);
  for (const r of results) {
    console.log(`- [${r.passed ? 'PASS' : 'FAIL'}] ${r.stepName}: ${r.details}`);
  }
  console.log(`Screenshots captured in: /tmp/issue16-*.png`);
}

if (process.argv[1]?.endsWith('issue16-bot-management.mjs')) {
  runVerification().then((success) => {
    process.exit(success ? 0 : 1);
  });
}
