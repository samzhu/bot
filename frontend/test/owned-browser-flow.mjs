import assert from 'node:assert/strict';
import { startOwnedFixture, installOwnedBrowserGuard } from './owned-browser.mjs';

const fixture = await startOwnedFixture();
let browser;
let closing;
let result;
const close = () => closing ||= (async () => { if (browser) await browser.close(); await fixture.close(); })();
for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => { close().finally(() => process.exit(1)); });
try {
  const { default: puppeteer } = await import('puppeteer-core');
  browser = await puppeteer.launch({ executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true, env: fixture.browserEnv, args: ['--no-proxy-server', '--disable-background-networking'] });
  const page = await browser.newPage();
  const runs = [];
  const guard = await installOwnedBrowserGuard(page, fixture, req => {
    const body = JSON.parse(req.postData()); runs.push({ run: body.runId, conversation: body.threadId });
  });
  page.setDefaultTimeout(15000);
  await page.goto(fixture.origin, { waitUntil: 'networkidle2' });
  await page.waitForSelector('chat-input textarea');
  await page.$eval('chat-input textarea', input => { input.value = 'browser fixture hello'; input.dispatchEvent(new Event('input', { bubbles: true })); });
  await page.waitForFunction(() => { const b = document.querySelector('chat-input button[aria-label="Send message"]'); return b && !b.disabled; });
  await page.click('chat-input button[aria-label="Send message"]');
  await page.waitForFunction(() => document.querySelector('main')?.innerText.includes('browser fixture hello'));
  await page.waitForFunction(() => document.querySelector('main')?.innerText.includes('mock streaming response'));
  assert.equal(runs.length, 1, 'one AG-UI Run should be sent');
  const { run, conversation } = runs[0];
  let payload;
  for (let i = 0; i < 100; i++) {
    const response = await fetch(`${fixture.origin}/graphql`, { method: 'POST', redirect: 'error', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ query: 'query Verify($run: ID!, $conversation: ID!) { run(id: $run) { id status conversationId } conversation(id: $conversation) { id messages(limit: 20) { runId role content } } }', variables: { run, conversation } }) });
    assert.equal(response.status, 200);
    payload = await response.json();
    assert.equal(payload.errors, undefined, JSON.stringify(payload.errors));
    if (payload.data.run?.status === 'COMPLETED') break;
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.equal(payload.data.run.status, 'COMPLETED');
  assert.equal(payload.data.run.conversationId, conversation);
  assert.ok(payload.data.conversation.messages.some(message => message.runId === run && message.role === 'USER' && message.content === 'browser fixture hello'));
  assert.ok(payload.data.conversation.messages.some(message => message.runId === run && message.role === 'ASSISTANT'));
  const evidenceResponse = await fetch(`${fixture.origin}/api/__fixture/evidence?${new URLSearchParams({ conversation, run })}`, { redirect: 'error' });
  assert.equal(evidenceResponse.status, 200);
  assert.deepEqual(await evidenceResponse.json(), { conversation: 1, run: 1, messages: 2 });
  assert.equal(guard.blocked, 0, 'browser attempted an external or redirected request');
  result = { result: 'passed', conversation, run, status: payload.data.run.status };
} finally { await close(); }
console.log(JSON.stringify(result));
