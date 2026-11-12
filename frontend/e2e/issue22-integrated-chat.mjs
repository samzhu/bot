// PDF p110/114: public API + real browser + PostgreSQL; never use a paid model.
import puppeteer from 'puppeteer-core';
import assert from 'node:assert/strict';
import { assertSafeBackendOrigin } from '../test/verification-harness.mjs';

const backend = process.env.VERIFICATION_BACKEND_URL || 'http://127.0.0.1:8181';
const frontend = process.env.VERIFICATION_FRONTEND_URL || 'http://127.0.0.1:4300';
await assertSafeBackendOrigin(backend);
async function gql(query, variables = {}) {
  const response = await fetch(`${backend}/graphql`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query, variables })
  });
  const result = await response.json();
  assert(response.ok && !result.errors, JSON.stringify(result.errors));
  return result.data;
}
const name = `Review-integrated-${Date.now()}`;
const { createBot: bot } = await gql(
  'mutation($i:CreateBotInput!){createBot(input:$i){id name}}',
  { i: { name, instructions: 'Synthetic verification only' } }
);
const browser = await puppeteer.launch({
  executablePath: process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  headless: true, args: ['--no-sandbox']
});
try {
  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 900 });
  const creations = [], runs = [];
  page.on('request', request => {
    if (request.method() !== 'POST') return;
    if (request.url().endsWith('/graphql') && request.postData()?.includes('mutation CreateConversation')) {
      creations.push(JSON.parse(request.postData()));
    }
    if (/\/api\/conversations\/[^/]+\/runs$/.test(request.url())) runs.push(request.url());
  });
  const t = id => `[data-testid="${id}"]`;
  const input = value => page.$eval('textarea[copilotChatTextarea]', (el, value) => {
    el.value = value;
    el.dispatchEvent(new Event('input', { bubbles: true }));
  }, value);
  async function openBot() {
    await page.click(t('bot-manager-nav-btn'));
    await page.waitForSelector(t('bot-search-input'));
    await page.$eval(t('bot-search-input'), (el, value) => {
      el.value = value;
      el.dispatchEvent(new Event('input', { bubbles: true }));
    }, name);
    await page.waitForSelector(t(`open-draft-btn-${bot.id}`));
    await page.click(t(`open-draft-btn-${bot.id}`));
    await page.waitForSelector(t('header-bot-name'));
  }
  await page.goto(frontend, { waitUntil: 'networkidle0' });
  await page.click(t('new-chat-btn'));
  await page.waitForSelector('textarea[copilotChatTextarea]');
  await input('  General draft\n keep  ');
  await openBot();
  await input('  Bot draft\n keep  ');
  await page.click(t('new-chat-btn'));
  await page.waitForSelector(t('header-bot-meta'), { hidden: true });
  await page.waitForFunction(() => document.querySelector('textarea')?.value === '  General draft\n keep  ');
  await openBot();
  await page.waitForFunction(() => document.querySelector('textarea')?.value === '  Bot draft\n keep  ');
  assert.equal(creations.length, 0);
  assert.equal(runs.length, 0);
  console.log('PASS: general/Bot drafts remain isolated; navigation creates no empty conversations');

  await input(`Fake completion ${name}`);
  await page.click(t('send-message-btn'));
  await page.waitForFunction(() => document.body.innerText.includes('deterministic mock streaming response'), { timeout: 15000 });
  await page.waitForSelector(t('stop-btn'), { hidden: true });
  assert.equal(creations.length, 1);
  assert.equal(runs.length, 1);
  assert.equal(creations[0].variables.botId, bot.id);
  const id = runs[0].split('/').at(-2);
  const { conversation } = await gql(
    'query($id:ID!){conversation(id:$id){botId messages{role content} runs{status executionMode}}}', { id }
  );
  assert.equal(conversation.botId, bot.id);
  assert.equal(conversation.messages.length, 2);
  assert.equal(conversation.runs[0].status, 'COMPLETED');
  assert.equal(conversation.runs[0].executionMode, 'SPRING_AI_SIMULATION');
  await page.screenshot({ path: '/tmp/issue22-integrated-completion.png' });
  console.log('PASS: first send creates one correctly bound conversation/run; fake completion persists');
} finally {
  await browser.close();
}
