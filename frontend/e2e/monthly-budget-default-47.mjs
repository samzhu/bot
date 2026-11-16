// #47: run only against a fresh, isolated SAFE_MOCK database; this saves one budget.
import assert from 'node:assert/strict';
import { join } from 'node:path';
import { browserUrl, startVerification } from './threadplane-support.mjs';
const {page, guard, check, finish, artifactsDir} = await startVerification('issue47-default-budget');
let budgetWrites = 0;
const prohibited = [];
guard.addRequestHandler(async request => {
  const url = new URL(request.url());
  if (url.pathname === '/api/ag-ui') { prohibited.push('generation'); await request.abort(); return; }
  if (url.pathname !== '/graphql' || request.method() !== 'POST') return;
  const {query = ''} = JSON.parse(request.postData());
  if (/^\s*mutation/.test(query)) {
    if (query.includes('mutation ConfigureMonthlyBudget(')) { budgetWrites++; return; }
    prohibited.push('mutation'); await request.abort();
  }
});
try {
  await check('real budget defaults to 10 and saves an exact disabled amount across refresh', async () => {
    await page.setViewport({width:1440,height:900});
    await page.goto(browserUrl(), {waitUntil:'networkidle2'});
    await page.waitForFunction(() => [...document.querySelectorAll('button')].some(b => b.textContent.trim() === '個人用量' && !b.disabled));
    await page.evaluate(() => [...document.querySelectorAll('button')].find(b => b.textContent.trim() === '個人用量').click());
    await page.evaluate(() => [...document.querySelectorAll('app-usage button')].find(b => b.textContent.trim() === '工作區月預算').click());
    await page.waitForSelector('#budget-cap');
    const value = await page.$eval('#budget-cap', el => el.value);
    assert.equal(value, '10.000000000000');
    assert.equal(await page.$eval('input[name=enabled]', el => el.checked), false);
    await page.$eval('#budget-cap', el => {el.value='123.123456789012'; el.dispatchEvent(new Event('input',{bubbles:true}));});
    await page.click('app-monthly-budget button[type=submit]');
    await page.waitForFunction(() => document.querySelector('app-monthly-budget [role=status]')?.textContent.includes('已儲存'));
    assert.equal(budgetWrites, 1);
    for (const width of [1440,375]) {
      await page.setViewport({width,height:900});
      await page.reload({waitUntil:'networkidle2'});
      await page.waitForFunction(() => [...document.querySelectorAll('button')].some(b => b.textContent.trim() === '個人用量' && !b.disabled));
      await page.evaluate(() => [...document.querySelectorAll('button')].find(b => b.textContent.trim() === '個人用量').click());
      await page.evaluate(() => [...document.querySelectorAll('app-usage button')].find(b => b.textContent.trim() === '工作區月預算').click());
      await page.waitForSelector('#budget-cap');
      assert.equal(await page.$eval('#budget-cap', el => el.value), '123.123456789012');
      assert.equal(await page.$eval('input[name=enabled]', el => el.checked), false);
      await page.screenshot({path:join(artifactsDir,`budget-${width}.png`)});
    }
  });
  await check('no unexpected mutations or model requests', async () => assert.deepEqual(prohibited, []));
} finally { await finish(); console.log(`artifacts: ${artifactsDir}`); }
