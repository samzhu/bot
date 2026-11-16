// Isolated #46 browser verification. Both origins must identify as SAFE_MOCK.
import assert from 'node:assert/strict';
import { join } from 'node:path';
import { browserUrl, startVerification } from './threadplane-support.mjs';

const {page, guard, check, finish, artifactsDir} = await startVerification('issue46-empty');
let failHistory = false;
let historyRequests = 0;
const prohibited = [];
guard.addRequestHandler(async request => {
  const url = new URL(request.url());
  if (url.pathname === '/api/ag-ui') { prohibited.push('generation'); await request.abort(); return; }
  if (url.pathname !== '/graphql' || request.method() !== 'POST') return;
  const {query = ''} = JSON.parse(request.postData());
  if (/^\s*mutation/.test(query)) {
    prohibited.push('mutation'); await request.abort(); return;
  }
  if (query.includes('SessionHistory')) {
    historyRequests++;
    if (failHistory) { failHistory = false; await request.respond({status:503, contentType:'application/json',body:'{}'}); }
  }
});
const stale = '00000000-0000-4000-8000-000000000046';
async function openWithSaved(width, fail = false) {
  await page.setViewport({width, height:900});
  await page.goto(browserUrl(), {waitUntil:'networkidle2'});
  await page.evaluate(id => { localStorage.setItem('bot_active_conversation_id', id); localStorage.setItem('issue46_unrelated','preserved'); }, stale);
  failHistory = fail;
  await page.reload({waitUntil:'networkidle2'});
}
try {
  for (const width of [1440,375]) {
    await check(`${width}px saved unavailable selection recovers and survives refresh`, async () => {
      await openWithSaved(width);
      await page.waitForSelector('chat-input textarea');
      await page.waitForFunction(() => localStorage.getItem('bot_active_conversation_id') === null);
      assert.equal(await page.evaluate(() => localStorage.getItem('issue46_unrelated')), 'preserved');
      assert.ok(!(await page.locator('body').map(el => el.innerText).wait()).includes('載入對話歷史失敗'));
      const input = await page.$('chat-input textarea');
      assert.equal(await input.evaluate(el => el.disabled), false);
      await input.focus(); await page.keyboard.type('draft');
      assert.equal(await input.evaluate(el => el.value), 'draft');
      await page.screenshot({path:join(artifactsDir,`empty-${width}.png`)});
      const before = historyRequests;
      await page.reload({waitUntil:'networkidle2'});
      assert.equal(historyRequests, before);
    });
  }
  await check('HTTP history failure remains retryable and retry recovers', async () => {
    await openWithSaved(1440, true);
    await page.waitForSelector('[role="alert"] button');
    assert.match(await page.$eval('[role="alert"]', el => el.textContent), /載入對話歷史失敗/);
    assert.equal(await page.evaluate(() => localStorage.getItem('bot_active_conversation_id')), stale);
    await page.click('[role="alert"] button');
    await page.waitForFunction(() => localStorage.getItem('bot_active_conversation_id') === null);
    assert.equal(await page.$('[role="alert"]'), null);
  });
  await check('no unexpected mutations or model requests', async () => assert.deepEqual(prohibited, []));
} finally { await finish(); console.log(`artifacts: ${artifactsDir}`); }
