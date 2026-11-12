import puppeteer from 'puppeteer-core';
import http from 'node:http';
import fs from 'node:fs';

const CHROME_PATH = process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const FRONTEND_URL = process.env.VERIFICATION_FRONTEND_URL || 'http://localhost:4300';
const BACKEND_URL = process.env.VERIFICATION_BACKEND_URL || 'http://localhost:8181';

const SCREENSHOTS = {
  managerView: '/tmp/issue17-01-manager-view.png',
  localReadOnly: '/tmp/issue17-02-local-read-only.png',
  editDialogOpened: '/tmp/issue17-03-edit-dialog-opened.png',
  dialogCancelled: '/tmp/issue17-04-dialog-cancelled.png',
  validationBlocked: '/tmp/issue17-05-validation-blocked.png',
  failurePreserved: '/tmp/issue17-06-failure-preserved.png',
  botUpdated: '/tmp/issue17-07-bot-updated.png',
  reloaded: '/tmp/issue17-08-reloaded.png',
  searchExcludesInstructions: '/tmp/issue17-09-search-excludes-instructions.png',
  targetFiltering: '/tmp/issue17-10-target-filtering.png',
  paginationPage1: '/tmp/issue17-11-pagination-page1.png',
  paginationPage2: '/tmp/issue17-12-pagination-page2.png',
  paginationReset: '/tmp/issue17-13-pagination-reset.png',
  refetchErrorHandling: '/tmp/issue17-14-refetch-error-handling.png',
  staleResponseProtection: '/tmp/issue17-15-stale-response-protection.png'
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
      const proto = el instanceof HTMLTextAreaElement ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
      const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
      if (setter) {
        setter.call(el, val);
      } else {
        el.value = val;
      }
      el.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
      el.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
      el.dispatchEvent(new Event('blur', { bubbles: true, cancelable: true }));
    }
  }, selector, value);
  await new Promise(r => setTimeout(r, 100));
}

async function checkUrlReady(url, maxTimeoutMs = 30000, acceptableStatuses = [200, 204, 301, 302, 405]) {
  const startTime = Date.now();
  while (Date.now() - startTime < maxTimeoutMs) {
    try {
      const isReady = await new Promise((resolve) => {
        const req = http.get(url, (res) => {
          resolve(acceptableStatuses.includes(res.statusCode) || (res.statusCode >= 200 && res.statusCode < 400));
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

async function sendGraphQL(query, variables = {}) {
  const res = await fetch(`${BACKEND_URL}/graphql`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query, variables })
  });
  return await res.json();
}

async function ensureSeedBotsForPagination(targetCount = 23) {
  const listData = await sendGraphQL('query { bots(limit: 50) { id name target } }');
  const existingBots = listData?.data?.bots || [];
  const currentCount = existingBots.length;
  console.log(`[SeedCheck] Current bots in DB: ${currentCount}. Target for 20+ pagination: ${targetCount}`);

  if (currentCount < targetCount) {
    const toCreate = targetCount - currentCount;
    console.log(`[SeedCheck] Creating ${toCreate} synthetic GKE bots to enable 20+ pagination testing...`);
    for (let i = 1; i <= toCreate; i++) {
      const seedName = `Pagination Bot ${Date.now()}-${i}`;
      const seedDesc = `Synthetic bot for pagination validation #${i}`;
      const seedInstr = `Instructions for pagination seed ${i}`;
      await sendGraphQL(
        `mutation CreateBot($input: CreateBotInput!) {
          createBot(input: $input) { id name }
        }`,
        { input: { name: seedName, description: seedDesc, instructions: seedInstr } }
      );
    }
    console.log(`[SeedCheck] Seed creation completed.`);
  }
}

export async function runVerification() {
  console.log('=== Issue #17 Bot Edit, Search, Target Filtering & Pagination Independent Verification ===');
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

  // Ensure database has >= 21 bots to test 20+ pagination properly
  await ensureSeedBotsForPagination(23);

  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--window-size=1280,800']
  });

  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 800 });

  let capturedMutations = [];
  let shouldFailNextUpdateBot = false;
  let shouldFailNextBotsQuery = false;

  await page.setRequestInterception(true);
  page.on('request', async (req) => {
    if (req.url().includes('/graphql') && req.method() === 'POST') {
      try {
        const postData = JSON.parse(req.postData() || '{}');
        const query = postData.query || '';

        if (query.includes('updateBot') || query.includes('createBot')) {
          capturedMutations.push(postData);
          if (shouldFailNextUpdateBot && query.includes('updateBot')) {
            shouldFailNextUpdateBot = false;
            req.respond({
              status: 500,
              contentType: 'application/json',
              body: JSON.stringify({ errors: [{ message: 'Simulated 500 updateBot failure for retry verification' }] })
            });
            return;
          }
        }

        if (query.includes('query GetBots') || query.includes('bots(') || query.includes('query { bots')) {
          if (shouldFailNextBotsQuery) {
            shouldFailNextBotsQuery = false;
            req.respond({
              status: 500,
              contentType: 'application/json',
              body: JSON.stringify({ errors: [{ message: 'Simulated 500 bots list query error after mutation' }] })
            });
            return;
          }

          if (postData.variables?.search === 'SLOWNESS') {
            await new Promise((r) => setTimeout(r, 1200));
            req.respond({
              status: 200,
              contentType: 'application/json',
              body: JSON.stringify({
                data: {
                  defaultLocalBot: null,
                  bots: [{
                    id: 'bot-stale-slow',
                    name: 'STALE_OVERWRITE_ATTEMPT',
                    description: 'This must never appear',
                    instructions: null,
                    target: 'GKE_REMOTE',
                    connectionStatus: 'NOT_CONNECTED',
                    executionMode: 'SPRING_AI_SIMULATION',
                    statusReason: 'Simulated',
                    canChat: false,
                    createdAt: new Date().toISOString(),
                    updatedAt: new Date().toISOString()
                  }]
                }
              })
            });
            return;
          }

          if (postData.variables?.search === 'FAST') {
            req.respond({
              status: 200,
              contentType: 'application/json',
              body: JSON.stringify({
                data: {
                  defaultLocalBot: null,
                  bots: [{
                    id: 'bot-fresh-fast',
                    name: 'FRESH_FAST_RESULT',
                    description: 'This must remain visible',
                    instructions: null,
                    target: 'GKE_REMOTE',
                    connectionStatus: 'NOT_CONNECTED',
                    executionMode: 'SPRING_AI_SIMULATION',
                    statusReason: 'Simulated',
                    canChat: false,
                    createdAt: new Date().toISOString(),
                    updatedAt: new Date().toISOString()
                  }]
                }
              })
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
    recordResult('Navigate to Bot Manager', true, 'Switched to bot manager view');

    // 2. Filter target=LOCAL & Verify Read-Only Protection on bot-general
    const targetFilterSelector = '[data-testid="target-filter-select"]';
    await page.waitForSelector(targetFilterSelector);
    await page.select(targetFilterSelector, 'LOCAL');
    await new Promise((r) => setTimeout(r, 600));
    await page.waitForNetworkIdle({ idleTime: 300, timeout: 5000 }).catch(() => {});

    const localBotCardSelector = '[data-testid="bot-card-bot-general"]';
    await page.waitForSelector(localBotCardSelector, { timeout: 5000 });

    const localCardDetails = await page.evaluate(() => {
      const card = document.querySelector('[data-testid="bot-card-bot-general"]');
      if (!card) return null;
      const targetText = card.querySelector('.target-tag')?.textContent?.trim();
      const hasEditBtn = !!card.querySelector('[data-testid^="edit-bot-btn-"]');
      const hasOpenDraftBtn = !!card.querySelector('[data-testid^="open-draft-btn-"]');
      return { targetText, hasEditBtn, hasOpenDraftBtn };
    });

    const localReadOnlyPassed = localCardDetails?.targetText === 'LOCAL' &&
      localCardDetails?.hasEditBtn === false &&
      localCardDetails?.hasOpenDraftBtn === true;

    await page.screenshot({ path: SCREENSHOTS.localReadOnly });
    recordResult('LOCAL Bot Read-Only Protection', localReadOnlyPassed,
      `target=${localCardDetails?.targetText}, hasEditBtn=${localCardDetails?.hasEditBtn}, hasOpenDraftBtn=${localCardDetails?.hasOpenDraftBtn}`);

    // 3. Switch filter to GKE_REMOTE to select an editable GKE bot
    await page.select(targetFilterSelector, 'GKE_REMOTE');
    await new Promise((r) => setTimeout(r, 600));
    await page.waitForNetworkIdle({ idleTime: 300, timeout: 5000 }).catch(() => {});

    const targetGkeBotInfo = await page.evaluate(() => {
      const gkeCards = Array.from(document.querySelectorAll('.bot-card')).filter(c =>
        c.querySelector('.target-tag')?.textContent?.includes('GKE_REMOTE') &&
        c.querySelector('[data-testid^="edit-bot-btn-"]')
      );
      if (gkeCards.length === 0) return null;
      const card = gkeCards[0];
      const id = card.getAttribute('data-testid')?.replace('bot-card-', '');
      const name = card.querySelector('.card-name')?.textContent?.trim();
      const desc = card.querySelector('.card-description')?.textContent?.trim();
      return { id, name, desc };
    });

    if (!targetGkeBotInfo) {
      throw new Error('No editable GKE bot card found in list under GKE_REMOTE filter!');
    }

    const editBtnSelector = `[data-testid="edit-bot-btn-${targetGkeBotInfo.id}"]`;
    await page.waitForSelector(editBtnSelector);
    await page.focus(editBtnSelector);
    await page.click(editBtnSelector);

    const dialogSelector = 'dialog[data-testid="create-bot-dialog"]';
    await page.waitForSelector(dialogSelector, { visible: true });

    const dialogData = await page.evaluate(() => {
      const dialog = document.querySelector('dialog[data-testid="create-bot-dialog"]');
      const title = document.querySelector('#bot-dialog-title')?.textContent?.trim();
      const mode = dialog?.getAttribute('data-mode');
      const nameVal = document.querySelector('[data-testid="bot-name-input"]')?.value;
      const descVal = document.querySelector('[data-testid="bot-desc-input"]')?.value;
      const instVal = document.querySelector('[data-testid="bot-instructions-input"]')?.value;
      return { open: dialog?.open, title, mode, nameVal, descVal, instVal };
    });

    const isPrefillAccurate = dialogData.open &&
      dialogData.mode === 'edit' &&
      dialogData.title === '編輯 GKE 代理定義' &&
      dialogData.nameVal === targetGkeBotInfo.name;

    await page.screenshot({ path: SCREENSHOTS.editDialogOpened });
    recordResult('Edit Dialog Prefill & Accessibility', isPrefillAccurate,
      `mode=${dialogData.mode}, title="${dialogData.title}", prefilledName="${dialogData.nameVal}"`);

    // 4. Cancel / Escape Behavior and Focus Return
    capturedMutations = [];
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.querySelector('dialog[data-testid="create-bot-dialog"]')?.open, { timeout: 3000 });

    const activeElementTestId = await page.evaluate(() => document.activeElement?.getAttribute('data-testid'));
    const zeroMutationOnCancel = capturedMutations.length === 0;
    const focusReturnedToEditBtn = activeElementTestId === `edit-bot-btn-${targetGkeBotInfo.id}`;

    await page.screenshot({ path: SCREENSHOTS.dialogCancelled });
    recordResult('Cancel/Escape Restores Focus without Mutation',
      zeroMutationOnCancel && focusReturnedToEditBtn,
      `mutations=${capturedMutations.length}, focusedTestId="${activeElementTestId}"`);

    // 5. Validation Rules on Edit Form (Trimmed Name Length 1..100)
    await page.click(editBtnSelector);
    await page.waitForSelector(dialogSelector, { visible: true });

    // 5a. Whitespace-only name blocks submit
    await setInputValue(page, '[data-testid="bot-name-input"]', '     ');
    const isWhitespaceSubmitDisabled = await page.$eval('[data-testid="submit-bot-btn"]', (btn) => btn.disabled);

    // 5b. Over 100 trimmed characters blocks submit
    await setInputValue(page, '[data-testid="bot-name-input"]', 'A'.repeat(105));
    const isOver100SubmitDisabled = await page.$eval('[data-testid="submit-bot-btn"]', (btn) => btn.disabled);

    // 5c. Exactly 100 trimmed characters with leading/trailing spaces enables submit
    const exact100WithSpaces = '   ' + 'B'.repeat(100) + '   ';
    await setInputValue(page, '[data-testid="bot-name-input"]', exact100WithSpaces);
    const isExact100SubmitEnabled = await page.$eval('[data-testid="submit-bot-btn"]', (btn) => !btn.disabled);

    await page.screenshot({ path: SCREENSHOTS.validationBlocked });
    const validationPassed = isWhitespaceSubmitDisabled && isOver100SubmitDisabled && isExact100SubmitEnabled;
    recordResult('SignalForms Custom Name Trim Length Validation', validationPassed,
      `whitespaceDisabled=${isWhitespaceSubmitDisabled}, over100Disabled=${isOver100SubmitDisabled}, exact100Enabled=${isExact100SubmitEnabled}`);

    // 6. Simulated Network 500 Failure: Preserve Inputs and Keep Dialog Open
    const failTestName = 'FailTest ' + Date.now();
    const failTestDesc = 'Description during fail test';
    const failTestInstr = 'Instructions during fail test';

    await setInputValue(page, '[data-testid="bot-name-input"]', failTestName);
    await setInputValue(page, '[data-testid="bot-desc-input"]', failTestDesc);
    await setInputValue(page, '[data-testid="bot-instructions-input"]', failTestInstr);

    shouldFailNextUpdateBot = true;
    capturedMutations = [];

    await page.click('[data-testid="submit-bot-btn"]');
    await page.waitForSelector('[data-testid="form-error-banner"]', { visible: true, timeout: 5000 });

    const preservedAfterError = await page.evaluate(() => ({
      dialogOpen: document.querySelector('dialog[data-testid="create-bot-dialog"]')?.open,
      name: document.querySelector('[data-testid="bot-name-input"]')?.value,
      desc: document.querySelector('[data-testid="bot-desc-input"]')?.value,
      instr: document.querySelector('[data-testid="bot-instructions-input"]')?.value
    }));

    const updateErrorPreserved = preservedAfterError.dialogOpen &&
      preservedAfterError.name === failTestName &&
      preservedAfterError.desc === failTestDesc &&
      preservedAfterError.instr === failTestInstr;

    await page.screenshot({ path: SCREENSHOTS.failurePreserved });
    recordResult('Mutation Error Keeps Dialog Open & Retains User Input', updateErrorPreserved,
      `dialogOpen=${preservedAfterError.dialogOpen}, namePreserved=${preservedAfterError.name === failTestName}`);

    // 7. Successful Update Mutation & Card Grid Refresh
    shouldFailNextUpdateBot = false;
    const timestamp = Date.now();
    const updatedName = `GKE-Updated-${timestamp}`;
    const rawUpdatedDesc = `  Raw Leading/Trailing Spaces Description ${timestamp}  `;
    const secretInstructionToken = `SECRET_TOKEN_${timestamp}_DO_NOT_SEARCH`;
    const rawUpdatedInstr = `  Line 1\n  Line 2 with ${secretInstructionToken}\n  Line 3  `;

    await setInputValue(page, '[data-testid="bot-name-input"]', updatedName);
    await setInputValue(page, '[data-testid="bot-desc-input"]', rawUpdatedDesc);
    await setInputValue(page, '[data-testid="bot-instructions-input"]', rawUpdatedInstr);

    capturedMutations = [];
    await page.click('[data-testid="submit-bot-btn"]');
    await page.waitForFunction(() => !document.querySelector('dialog[data-testid="create-bot-dialog"]')?.open, { timeout: 8000 });

    // Verify card displays updated name
    await page.waitForFunction(
      (name) => Array.from(document.querySelectorAll('.card-name')).some(el => el.textContent?.includes(name)),
      { timeout: 5000 },
      updatedName
    );

    const cardUpdated = await page.evaluate((botId, expectedName) => {
      const card = document.querySelector(`[data-testid="bot-card-${botId}"]`);
      if (!card) return null;
      const name = card.querySelector('.card-name')?.textContent?.trim();
      const desc = card.querySelector('.card-description')?.textContent;
      const instr = card.querySelector('.instructions-content')?.textContent;
      return { name, desc, instr };
    }, targetGkeBotInfo.id, updatedName);

    const updateMatches = cardUpdated?.name === updatedName &&
      cardUpdated?.desc === rawUpdatedDesc &&
      cardUpdated?.instr === rawUpdatedInstr;

    await page.screenshot({ path: SCREENSHOTS.botUpdated });
    recordResult('Bot Update Reflects in List & Preserves Raw Formatting', updateMatches,
      `updatedName="${cardUpdated?.name}", rawDescPreserved=${cardUpdated?.desc === rawUpdatedDesc}, rawInstrPreserved=${cardUpdated?.instr === rawUpdatedInstr}`);

    // 8. Page Reload & Database Persistence Check
    await page.reload({ waitUntil: 'networkidle0' });
    await page.waitForFunction(() => !document.querySelector('[data-testid="initial-loading-hint"]'));
    await page.waitForSelector('[data-testid="bot-manager-nav-btn"]', { timeout: 10000 });
    await page.click('[data-testid="bot-manager-nav-btn"]');
    await page.waitForSelector('.bot-manager-container', { timeout: 5000 });
    await page.waitForSelector('[data-testid="bot-search-input"]', { timeout: 5000 });

    // Make sure we select GKE_REMOTE where this updated bot appears on page 1
    await page.select(targetFilterSelector, 'GKE_REMOTE');
    await new Promise((r) => setTimeout(r, 600));
    await page.waitForNetworkIdle({ idleTime: 300, timeout: 5000 }).catch(() => {});

    const persistsAfterReload = await page.evaluate((botId, expectedName) => {
      const card = document.querySelector(`[data-testid="bot-card-${botId}"]`);
      const name = card?.querySelector('.card-name')?.textContent?.trim();
      return name === expectedName;
    }, targetGkeBotInfo.id, updatedName);

    await page.screenshot({ path: SCREENSHOTS.reloaded });
    recordResult('Database Persistence Across Page Reload', persistsAfterReload,
      `Bot "${targetGkeBotInfo.id}" persists with name "${updatedName}" in grid after reload`);

    // 9. Search Excludes Instructions: Strict ID-Based Affirmative & Negative Assertion
    const searchInputSelector = '[data-testid="bot-search-input"]';
    await page.waitForSelector(searchInputSelector);

    // 9a. Negative check: Search for secretInstructionToken (present only in instructions)
    // Must NOT return targetGkeBotInfo.id (ideal: 0 matching cards)
    await setInputValue(page, searchInputSelector, secretInstructionToken);
    await new Promise((r) => setTimeout(r, 600)); // wait for debounce
    await page.waitForNetworkIdle({ idleTime: 300, timeout: 5000 }).catch(() => {});

    const targetBotVisibleUnderTokenSearch = await page.evaluate((botId) => {
      return !!document.querySelector(`[data-testid="bot-card-${botId}"]`);
    }, targetGkeBotInfo.id);

    const instructionSearchStrictlyExcluded = !targetBotVisibleUnderTokenSearch;

    // 9b. Affirmative check: Search for updatedName (present in name)
    // MUST return targetGkeBotInfo.id
    await setInputValue(page, searchInputSelector, updatedName);
    await new Promise((r) => setTimeout(r, 600));
    await page.waitForNetworkIdle({ idleTime: 300, timeout: 5000 }).catch(() => {});

    const targetBotVisibleUnderNameSearch = await page.evaluate((botId) => {
      return !!document.querySelector(`[data-testid="bot-card-${botId}"]`);
    }, targetGkeBotInfo.id);

    const nameSearchMatchesTargetBot = targetBotVisibleUnderNameSearch;

    // 9c. Clear search input
    await setInputValue(page, searchInputSelector, '');
    await new Promise((r) => setTimeout(r, 600));
    await page.waitForNetworkIdle({ idleTime: 300, timeout: 5000 }).catch(() => {});

    await page.screenshot({ path: SCREENSHOTS.searchExcludesInstructions });
    recordResult('Backend Search Matches Name/Description & Strictly Excludes Instructions',
      instructionSearchStrictlyExcluded && nameSearchMatchesTargetBot,
      `excludedOnToken=${instructionSearchStrictlyExcluded}, matchedOnName=${nameSearchMatchesTargetBot}`);

    // 10. Target Filtering (ALL, LOCAL, GKE_REMOTE)
    // 10a. Filter LOCAL
    await page.select(targetFilterSelector, 'LOCAL');
    await new Promise((r) => setTimeout(r, 600));
    await page.waitForNetworkIdle({ idleTime: 300, timeout: 5000 }).catch(() => {});

    const localFilterResults = await page.evaluate(() => {
      const tags = Array.from(document.querySelectorAll('.target-tag')).map(el => el.textContent?.trim());
      return {
        allLocal: tags.length > 0 && tags.every(t => t === 'LOCAL'),
        count: tags.length
      };
    });

    // 10b. Filter GKE_REMOTE
    await page.select(targetFilterSelector, 'GKE_REMOTE');
    await new Promise((r) => setTimeout(r, 600));
    await page.waitForNetworkIdle({ idleTime: 300, timeout: 5000 }).catch(() => {});

    const gkeFilterResults = await page.evaluate(() => {
      const tags = Array.from(document.querySelectorAll('.target-tag')).map(el => el.textContent?.trim());
      return {
        allGke: tags.length > 0 && tags.every(t => t === 'GKE_REMOTE'),
        count: tags.length
      };
    });

    // 10c. Reset to ALL
    await page.select(targetFilterSelector, 'ALL');
    await new Promise((r) => setTimeout(r, 600));
    await page.waitForNetworkIdle({ idleTime: 300, timeout: 5000 }).catch(() => {});

    const allFilterResults = await page.evaluate(() => {
      const tags = Array.from(document.querySelectorAll('.target-tag')).map(el => el.textContent?.trim());
      return {
        count: tags.length,
        hasGke: tags.some(t => t === 'GKE_REMOTE')
      };
    });

    const targetFilteringPassed = localFilterResults.allLocal &&
      gkeFilterResults.allGke &&
      allFilterResults.count === 20 &&
      allFilterResults.hasGke;

    await page.screenshot({ path: SCREENSHOTS.targetFiltering });
    recordResult('Target Filtering (ALL / LOCAL / GKE_REMOTE)', targetFilteringPassed,
      `localCount=${localFilterResults.count}, gkeCount=${gkeFilterResults.count}, allCount=${allFilterResults.count}`);

    // 11. 20+ Pagination and Filter/Search Offset Reset
    // With >= 23 bots in DB and ALL filter, page 1 displays 20 items, next-page-btn is enabled
    const paginationSelector = '[data-testid="bot-pagination"]';
    await page.waitForSelector(paginationSelector);

    const page1State = await page.evaluate(() => ({
      indicator: document.querySelector('[data-testid="current-page-indicator"]')?.textContent?.trim(),
      prevDisabled: document.querySelector('[data-testid="prev-page-btn"]')?.disabled,
      nextDisabled: document.querySelector('[data-testid="next-page-btn"]')?.disabled,
      cardCount: document.querySelectorAll('.bot-card').length
    }));

    await page.screenshot({ path: SCREENSHOTS.paginationPage1 });

    const page1Passed = page1State.indicator === '第 1 頁' &&
      page1State.prevDisabled === true &&
      page1State.nextDisabled === false &&
      page1State.cardCount === 20;

    recordResult('Pagination Page 1 Controls & Limit Bounds (20 per page)', page1Passed,
      `indicator="${page1State.indicator}", prevDisabled=${page1State.prevDisabled}, nextDisabled=${page1State.nextDisabled}, cards=${page1State.cardCount}`);

    // Navigate to Page 2
    await page.click('[data-testid="next-page-btn"]');
    await new Promise((r) => setTimeout(r, 600));
    await page.waitForNetworkIdle({ idleTime: 300, timeout: 5000 }).catch(() => {});

    const page2State = await page.evaluate(() => ({
      indicator: document.querySelector('[data-testid="current-page-indicator"]')?.textContent?.trim(),
      prevDisabled: document.querySelector('[data-testid="prev-page-btn"]')?.disabled,
      cardCount: document.querySelectorAll('.bot-card').length,
      hasLocalBot: !!document.querySelector('[data-testid="bot-card-bot-general"]')
    }));

    await page.screenshot({ path: SCREENSHOTS.paginationPage2 });

    const page2Passed = page2State.indicator === '第 2 頁' &&
      page2State.prevDisabled === false &&
      page2State.cardCount > 0;

    recordResult('Pagination Page 2 Navigation (offset: 20)', page2Passed,
      `indicator="${page2State.indicator}", prevDisabled=${page2State.prevDisabled}, cards=${page2State.cardCount}, hasLocalOnPage2=${page2State.hasLocalBot}`);

    // Offset Reset on Target Filter Change while on Page 2
    await page.select(targetFilterSelector, 'GKE_REMOTE');
    await new Promise((r) => setTimeout(r, 600));
    await page.waitForNetworkIdle({ idleTime: 300, timeout: 5000 }).catch(() => {});

    const resetIndicator = await page.$eval('[data-testid="current-page-indicator"]', el => el.textContent?.trim());
    const resetPassed = resetIndicator === '第 1 頁';

    // Reset back to ALL
    await page.select(targetFilterSelector, 'ALL');
    await new Promise((r) => setTimeout(r, 600));
    await page.waitForNetworkIdle({ idleTime: 300, timeout: 5000 }).catch(() => {});

    await page.screenshot({ path: SCREENSHOTS.paginationReset });
    recordResult('Offset Resets to Page 1 on Filter/Search Change', resetPassed,
      `pageIndicatorAfterFilter="${resetIndicator}" (expected: "第 1 頁")`);

    // 12. Separation of Mutation Execution from List Refetch
    // When mutation succeeds but subsequent list refetch fails with 500:
    // Modal closes, form clears, store error banner appears with retry button.
    // Clicking retry button ONLY refetches list without repeating mutation.
    const createBtnSelector = '[data-testid="create-bot-modal-btn"]';
    await page.waitForSelector(createBtnSelector);
    await page.click(createBtnSelector);
    await page.waitForSelector(dialogSelector, { visible: true });

    const newBotName = `Separate-Test-${Date.now()}`;
    await setInputValue(page, '[data-testid="bot-name-input"]', newBotName);

    // Arm 500 for list query only (mutation will pass through)
    shouldFailNextBotsQuery = true;
    capturedMutations = [];

    await page.click('[data-testid="submit-bot-btn"]');
    // Dialog must close because mutation succeeded
    await page.waitForFunction(() => !document.querySelector('dialog[data-testid="create-bot-dialog"]')?.open, { timeout: 8000 });

    // Store error banner should display refetch error
    await page.waitForSelector('[data-testid="bot-store-error"]', { visible: true, timeout: 5000 });
    const storeErrorText = await page.$eval('[data-testid="bot-store-error"]', el => el.textContent?.trim());

    // Clicking retry button in manager error banner
    capturedMutations = [];
    shouldFailNextBotsQuery = false;
    await page.click('[data-testid="bot-store-error"] .retry-link-btn');
    await page.waitForNetworkIdle({ idleTime: 300, timeout: 5000 }).catch(() => {});

    // Ensure NO mutation was dispatched upon retry
    const zeroMutationOnListRetry = capturedMutations.length === 0;

    await page.screenshot({ path: SCREENSHOTS.refetchErrorHandling });

    const isDialogOpen = await page.evaluate(() => !!document.querySelector('dialog[data-testid="create-bot-dialog"]')?.open);
    const separationPassed = !isDialogOpen &&
      storeErrorText.includes('500') &&
      zeroMutationOnListRetry;

    recordResult('Separation of Mutation Success from List Refetch', separationPassed,
      `dialogClosed=${!isDialogOpen}, storeErrorVisible=true, zeroMutationOnRetry=${zeroMutationOnListRetry}`);

    // 13. Request Generation: Stale Delayed Response Discarded
    // Search "SLOWNESS", then immediately search "FAST"
    // "SLOWNESS" delayed by 1200ms returning STALE_OVERWRITE_ATTEMPT
    // "FAST" returns immediately returning FRESH_FAST_RESULT
    await setInputValue(page, searchInputSelector, 'SLOWNESS');
    await new Promise((r) => setTimeout(r, 100));
    await setInputValue(page, searchInputSelector, 'FAST');

    // Wait for all delayed requests to settle (1200ms delay + buffer)
    await new Promise((r) => setTimeout(r, 1600));
    await page.waitForNetworkIdle({ idleTime: 300, timeout: 5000 }).catch(() => {});

    // Verify DOM contains FRESH_FAST_RESULT and does NOT contain STALE_OVERWRITE_ATTEMPT
    const renderedCardNames = await page.evaluate(() =>
      Array.from(document.querySelectorAll('.card-name')).map(el => el.textContent?.trim())
    );

    const staleProtectionPassed = renderedCardNames.includes('FRESH_FAST_RESULT') &&
      !renderedCardNames.includes('STALE_OVERWRITE_ATTEMPT');

    // Clear search filter to restore normal view
    await setInputValue(page, searchInputSelector, '');
    await new Promise((r) => setTimeout(r, 600));

    await page.screenshot({ path: SCREENSHOTS.staleResponseProtection });
    recordResult('Request Generation Race Condition Protection', staleProtectionPassed,
      `renderedNames=${JSON.stringify(renderedCardNames)}, stale STALE_OVERWRITE_ATTEMPT cleanly discarded`);

  } catch (err) {
    recordResult('Verification Execution', false, `Unhandled error: ${err.message}\n${err.stack}`);
  } finally {
    await browser.close();
  }

  printSummary();
  return results.every(r => r.passed);
}

function printSummary() {
  console.log('\n=== Issue #17 Verification Execution Summary ===');
  const passedCount = results.filter(r => r.passed).length;
  const failedCount = results.filter(r => !r.passed).length;
  console.log(`Total Steps: ${results.length} | Passed: ${passedCount} | Failed: ${failedCount}`);
  for (const r of results) {
    console.log(`- [${r.passed ? 'PASS' : 'FAIL'}] ${r.stepName}: ${r.details}`);
  }
  console.log(`Screenshots captured in: /tmp/issue17-*.png`);
}

if (process.argv[1]?.endsWith('issue17-bot-edit-search.mjs')) {
  runVerification().then((success) => {
    process.exit(success ? 0 : 1);
  });
}
