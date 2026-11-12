// Historical layout verification retained for coverage traceability; see README.
throw new Error('Retired legacy browser verification; use the current ThreadPlane browser matrix.');

import puppeteer from 'puppeteer-core';
import { installBrowserGuard, getVerificationOrigins } from './test/verification-harness.mjs';

const { frontendUrl: BASE_URL } = getVerificationOrigins();
const CHROME_PATH = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

const SEED_IDS = [
  'seed-conv-completed-01',
  'seed-conv-failed-01',
  'seed-conv-cancelled-01'
];

async function run() {
  const browser = await puppeteer.launch({
    headless: 'new',
    executablePath: CHROME_PATH,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--window-size=1280,800']
  });

  try {
    const page = await browser.newPage();
    await page.setViewport({ width: 1280, height: 800 });
    await installBrowserGuard(page, { mode: 'fail-closed' });

    page.on('console', msg => {
      if (msg.type() === 'error') {
        console.error('[Browser Console Error]', msg.text());
      }
    });

    console.log('1. Navigating to', BASE_URL, '...');
    await page.goto(BASE_URL, { waitUntil: 'networkidle0', timeout: 15000 });

    await page.waitForSelector('.conversation-item', { timeout: 10000 });

    // Helper to ensure target seed exists in sidebar, loading more pages if needed
    async function ensureSeedInSidebar(seedId) {
      let attempts = 0;
      while (attempts < 10) {
        const exists = await page.$(`[data-id="${seedId}"]`);
        if (exists) return true;
        const loadMoreBtn = await page.$('[data-testid="load-more-conv-btn"] button, button[aria-label="Load more conversations"], .load-more-conv-btn');
        if (!loadMoreBtn) break;
        const disabled = await page.evaluate(el => el.disabled || Boolean(el.closest('[disabled]')), loadMoreBtn);
        if (disabled) break;
        const prevCount = await page.$$eval('.conversation-item', els => els.length);
        await loadMoreBtn.click();
        await page.waitForFunction(
          prev => document.querySelectorAll('.conversation-item').length > prev || !document.querySelector('[data-testid="load-more-conv-btn"], .load-more-conv-btn'),
          { timeout: 5000 },
          prevCount
        );
        attempts++;
      }
      return Boolean(await page.$(`[data-id="${seedId}"]`));
    }

    // Verify all 3 seed conversations can be found (with pagination if necessary)
    for (const id of SEED_IDS) {
      const found = await ensureSeedInSidebar(id);
      if (!found) {
        throw new Error(`Required seed conversation ${id} could not be found in sidebar (even after pagination)!`);
      }
    }

    const baselineItems = await page.$$eval('.conversation-item', els => els.map(e => ({
      id: e.getAttribute('data-id'),
      title: e.textContent.trim()
    })));
    const baselineCount = baselineItems.length;
    console.log(`Sidebar loaded baseline count: ${baselineCount}`);
    for (const it of baselineItems) {
      if (SEED_IDS.includes(it.id)) {
        console.log(` - [${it.id}] ${it.title}`);
      }
    }
    console.log('✓ All 3 seed conversations verified present in sidebar.');

    // 2. Click COMPLETED seed conversation and verify
    console.log('\n2. Testing COMPLETED conversation view...');
    await page.click('[data-id="seed-conv-completed-01"]');
    await page.waitForFunction(() => {
      const title = document.querySelector('.header-title')?.textContent?.trim();
      const user = document.querySelector('[data-message-role="user"]')?.textContent?.trim() || '';
      const assistant = document.querySelector('[data-message-role="assistant"]');
      return title === '示範：成功問答 (COMPLETED)' && user.includes('Spring AI') && assistant;
    }, { timeout: 10000 });

    const completedData = await page.evaluate(() => {
      const user = document.querySelector('[data-message-role="user"]')?.textContent?.trim() || '';
      const assistant = document.querySelector('[data-message-role="assistant"]');
      return {
        userText: user,
        assistantText: assistant?.textContent?.trim() || '',
        hasStrong: Boolean(assistant?.querySelector('strong')),
        hasLi: Boolean(assistant?.querySelector('li')),
        hasCode: Boolean(assistant?.querySelector('pre, code')),
        hasErrorBanner: Boolean(document.querySelector('.stream-error-banner')),
        hasRecoveringBanner: Boolean(document.querySelector('.recovering-banner'))
      };
    });

    console.log('COMPLETED userText:', completedData.userText);
    console.log(`COMPLETED markdown: strong=${completedData.hasStrong}, li=${completedData.hasLi}, code=${completedData.hasCode}`);
    if (!completedData.userText.includes('Spring AI')) {
      throw new Error(`Expected completed user prompt, got: ${completedData.userText}`);
    }
    if (!completedData.hasStrong || !completedData.hasLi || !completedData.hasCode) {
      throw new Error('Completed seed conversation failed to render native markdown elements (strong/li/code)!');
    }
    if (completedData.hasErrorBanner || completedData.hasRecoveringBanner) {
      throw new Error('Completed conversation must not show error or recovering banner!');
    }
    await page.screenshot({ path: '/tmp/browser-seed-completed.png' });
    console.log('✓ COMPLETED view assertions passed. Screenshot saved.');

    // 3. Click FAILED seed conversation and verify
    console.log('\n3. Testing FAILED conversation view...');
    await page.click('[data-id="seed-conv-failed-01"]');
    await page.waitForFunction(() => {
      const title = document.querySelector('.header-title')?.textContent?.trim();
      const user = document.querySelector('[data-message-role="user"]')?.textContent?.trim() || '';
      const assistant = document.querySelector('[data-message-role="assistant"]');
      const banner = document.querySelector('.stream-error-banner');
      return title === '示範：模型錯誤 (FAILED)' && user.includes('觸發超時與配額不足示範') && assistant && banner;
    }, { timeout: 10000 });

    const failedData = await page.evaluate(() => {
      const user = document.querySelector('[data-message-role="user"]')?.textContent?.trim() || '';
      const assistant = document.querySelector('[data-message-role="assistant"]')?.textContent?.trim() || '';
      const banner = document.querySelector('.stream-error-banner')?.textContent?.trim() || '';
      const msgCount = document.querySelectorAll('[data-message-role]').length;
      return { user, assistant, banner, msgCount };
    });

    console.log('FAILED userText:', failedData.user);
    console.log('FAILED assistantText:', failedData.assistant);
    console.log('FAILED bannerText:', failedData.banner);
    console.log('FAILED messageCount:', failedData.msgCount);

    if (!failedData.user.includes('觸發超時與配額不足示範')) {
      throw new Error(`Unexpected user prompt in failed scenario: ${failedData.user}`);
    }
    if (!failedData.assistant.includes('處理請求時發生異常')) {
      throw new Error(`Expected partial assistant message in failed scenario, got: ${failedData.assistant}`);
    }
    if (!failedData.banner.includes('前次回應失敗')) {
      throw new Error(`Expected banner to contain '前次回應失敗', got: ${failedData.banner}`);
    }
    if (failedData.msgCount !== 2) {
      throw new Error(`Expected exactly 2 messages in failed scenario, got: ${failedData.msgCount}`);
    }
    await page.screenshot({ path: '/tmp/browser-seed-failed.png' });
    console.log('✓ FAILED view assertions passed. Screenshot saved.');

    // 4. Click CANCELLED seed conversation and verify
    console.log('\n4. Testing CANCELLED conversation view...');
    await page.click('[data-id="seed-conv-cancelled-01"]');
    await page.waitForFunction(() => {
      const title = document.querySelector('.header-title')?.textContent?.trim();
      const user = document.querySelector('[data-message-role="user"]')?.textContent?.trim() || '';
      const assistant = document.querySelector('[data-message-role="assistant"]');
      const banner = document.querySelector('.recovering-banner');
      return title === '示範：中途取消 (CANCELLED)' && user.includes('五千字的量子力學詳解') && assistant && banner;
    }, { timeout: 10000 });

    const cancelledData = await page.evaluate(() => {
      const user = document.querySelector('[data-message-role="user"]')?.textContent?.trim() || '';
      const assistant = document.querySelector('[data-message-role="assistant"]')?.textContent?.trim() || '';
      const banner = document.querySelector('.recovering-banner')?.textContent?.trim() || '';
      const msgCount = document.querySelectorAll('[data-message-role]').length;
      return { user, assistant, banner, msgCount };
    });

    console.log('CANCELLED userText:', cancelledData.user);
    console.log('CANCELLED assistantText:', cancelledData.assistant);
    console.log('CANCELLED bannerText:', cancelledData.banner);
    console.log('CANCELLED messageCount:', cancelledData.msgCount);

    if (!cancelledData.user.includes('五千字的量子力學詳解')) {
      throw new Error(`Unexpected user prompt in cancelled scenario: ${cancelledData.user}`);
    }
    if (!cancelledData.assistant.includes('量子力學是描述微觀粒子')) {
      throw new Error(`Expected partial assistant message in cancelled scenario, got: ${cancelledData.assistant}`);
    }
    if (cancelledData.assistant.includes('[已由使用者停止生成]')) {
      throw new Error('Cancelled partial reply must NOT contain synthetic "[已由使用者停止生成]" marker!');
    }
    if (!cancelledData.banner.includes('前次回應已中斷')) {
      throw new Error(`Expected banner to contain '前次回應已中斷', got: ${cancelledData.banner}`);
    }
    if (cancelledData.msgCount !== 2) {
      throw new Error(`Expected exactly 2 messages in cancelled scenario, got: ${cancelledData.msgCount}`);
    }
    await page.screenshot({ path: '/tmp/browser-seed-cancelled.png' });
    console.log('✓ CANCELLED view assertions passed. Screenshot saved.');

    // 5. Test New Chat button contract (deferred creation: switches to draft without adding sidebar item)
    console.log('\n5. Testing + New Chat button contract (deferred creation: baseline preserved)...');
    await page.click('[data-testid="new-chat-btn"] button, button[aria-label="+ New Chat"], .new-chat-btn');
    await page.waitForFunction((expectedCount) => {
      const title = document.querySelector('.header-title')?.textContent?.trim();
      const rows = document.querySelectorAll('[data-message-role]');
      const textarea = document.querySelector('textarea');
      const sidebarItems = document.querySelectorAll('.conversation-item');
      return title === 'New Conversation' && rows.length === 0 && textarea && !textarea.disabled && sidebarItems.length === expectedCount;
    }, { timeout: 10000 }, baselineCount);

    const newChatState = await page.evaluate(() => {
      return {
        title: document.querySelector('.header-title')?.textContent?.trim(),
        messageCount: document.querySelectorAll('[data-message-role]').length,
        sidebarCount: document.querySelectorAll('.conversation-item').length,
        textareaDisabled: document.querySelector('textarea')?.disabled
      };
    });
    console.log('New Chat State:', newChatState);

    if (newChatState.title !== 'New Conversation') {
      throw new Error(`Expected title 'New Conversation', got '${newChatState.title}'`);
    }
    if (newChatState.messageCount !== 0) {
      throw new Error(`Expected 0 messages in new conversation view, got ${newChatState.messageCount}`);
    }
    if (newChatState.sidebarCount !== baselineCount) {
      throw new Error(`Sidebar conversation count must remain ${baselineCount} in draft mode, got ${newChatState.sidebarCount}`);
    }
    if (newChatState.textareaDisabled) {
      throw new Error('Textarea must be enabled in new conversation view');
    }
    await page.screenshot({ path: '/tmp/browser-new-chat-empty.png' });
    console.log('✓ New Chat deferred creation draft contract passed.');

    // 6. Reload page and verify baseline conversations and seed IDs remain intact
    console.log('\n6. Reloading page to verify persistence of baseline conversations...');
    await page.reload({ waitUntil: 'networkidle0' });
    await page.waitForSelector('.conversation-item', { timeout: 10000 });

    // Re-verify seeds still present
    for (const id of SEED_IDS) {
      const found = await ensureSeedInSidebar(id);
      if (!found) {
        throw new Error(`Required seed conversation ${id} missing after page reload!`);
      }
    }

    const reloadedCount = await page.$$eval('.conversation-item', els => els.length);
    console.log(`Sidebar conversation count after reload: ${reloadedCount} (expected ${baselineCount})`);
    if (reloadedCount !== baselineCount) {
      throw new Error(`Expected sidebar count ${baselineCount} after reload, got ${reloadedCount}`);
    }

    console.log('\n=== ALL BROWSER SEED AND CONTRACT VERIFICATIONS STRICTLY PASSED! ===');
  } finally {
    await browser.close();
  }
}

run().catch(err => {
  console.error('VERIFICATION ERROR:', err);
  process.exit(1);
});
