import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { startOwnedFixture, installOwnedBrowserGuard } from '../test/owned-browser.mjs';

const fixture = await startOwnedFixture({ postgresAccess: true, titleFixture: true });
let browser;
const close = async () => {
  if (browser) await browser.close();
  const ownedId = fixture.identity.postgres?.containerId;
  assert.ok(ownedId, 'owned PostgreSQL identity must be recorded');
  await fixture.close();
  const remaining = execFileSync('docker', ['container', 'ls', '-aq', '--no-trunc', '--filter', `id=${ownedId}`], { encoding: 'utf8' }).trim();
  assert.equal(remaining, '', 'owned PostgreSQL container must be removed');
};
try {
  const { default: puppeteer } = await import('puppeteer-core');
  browser = await puppeteer.launch({ executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true, env: fixture.browserEnv, args: ['--no-proxy-server', '--disable-background-networking'] });
  const page = await browser.newPage();
  const runs = [];
  const guard = await installOwnedBrowserGuard(page, fixture, req => { const body = JSON.parse(req.postData()); runs.push({ run: body.runId, conversation: body.threadId }); });
  const control = async (path, method = 'GET') => {
    const response = await fetch(`${fixture.backendOrigin}/api/__fixture/${path}`, { method, redirect: 'error', headers: { 'X-Fixture-Nonce': fixture.titleNonce } });
    assert.equal(response.status, 200); return response.json();
  };
  page.setDefaultTimeout(15000);
  await page.setViewport({ width: 1280, height: 800 });
  await page.goto(fixture.origin, { waitUntil: 'networkidle2' });
  await page.waitForSelector('chat-input textarea');
  await page.$eval('chat-input textarea', input => { input.value = 'browser title fixture'; input.dispatchEvent(new Event('input', { bubbles: true })); });
  await page.waitForFunction(() => { const b = document.querySelector('chat-input button[aria-label="Send message"]'); return b && !b.disabled; });
  await page.click('chat-input button[aria-label="Send message"]');
  await page.waitForFunction(() => document.querySelector('main')?.innerText.includes('mock streaming response'));
  assert.equal(runs.length, 1);
  assert.equal(await page.$eval('[data-testid="chat-header-title"]', e => e.textContent.trim()), 'New Conversation');
  await page.waitForFunction(() => document.querySelector('.navigation')?.innerText.includes('New Conversation'));
  let status;
  for (let i = 0; i < 100; i++) { status = await control('title-status'); if (status.calls === 1) break; await new Promise(resolve => setTimeout(resolve, 50)); }
  assert.equal(status.calls, 1);
  assert.equal((await control('title-release', 'POST')).calls, 1);
  await page.waitForFunction(() => document.querySelector('[data-testid="chat-header-title"]')?.textContent.trim() === 'Synthetic browser title');
  await page.waitForFunction(() => document.querySelector('.navigation')?.innerText.includes('Synthetic browser title'));
  const query = async () => {
    const response = await fetch(`${fixture.origin}/graphql`, { method: 'POST', redirect: 'error', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ query: 'query($id: ID!) { conversation(id: $id) { id title } }', variables: { id: runs[0].conversation } }) });
    const body = await response.json(); assert.equal(body.errors, undefined, JSON.stringify(body)); return body.data.conversation;
  };
  assert.deepEqual(await query(), { id: runs[0].conversation, title: 'Synthetic browser title' });
  await page.reload({ waitUntil: 'networkidle2' });
  await page.waitForFunction(() => document.querySelector('.navigation')?.innerText.includes('Synthetic browser title'));
  await page.evaluate(() => {
    const button = [...document.querySelectorAll('.navigation chat-thread-list button[title]')].find(e => e.title === 'Synthetic browser title');
    if (!button) throw new Error('Reloaded conversation button missing');
    button.click();
  });
  await page.waitForFunction(() => document.querySelector('[data-testid="chat-header-title"]')?.textContent.trim() === 'Synthetic browser title');
  assert.deepEqual(await query(), { id: runs[0].conversation, title: 'Synthetic browser title' });
  assert.equal((await control('title-status')).calls, 1);
  assert.equal(guard.blocked, 0);
  console.log(JSON.stringify({ result: 'passed', conversation: runs[0].conversation, run: runs[0].run, titleCalls: 1 }));
} finally { await close(); }
