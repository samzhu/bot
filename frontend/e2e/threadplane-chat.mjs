import { writeFile } from 'node:fs/promises';
import { browserUrl, backendUrl, startVerification } from './threadplane-support.mjs';

// Run against the verified local fake backend; generated conversations are retained.
const { page, guard, graphql, check, finish, artifactsDir } = await startVerification('chat');
const runs = [];
let failCreation = false;
let injectUnavailableBot = false;
let holdCreation = false;
let releaseCreation;
let creationObserved = false;
let createCount = 0;
const reconcileResponses = [];
page.on('response', async response => { if (response.url().endsWith('/graphql') && response.request().postData()?.includes('query ReconcileRun')) { try { const request = JSON.parse(response.request().postData()); const body = await response.json(); reconcileResponses.push({ id: request.variables.id, status: body.data?.run?.status ?? null }); } catch {} } });
const checks = [];
page.on('request', request => { if (request.url().endsWith('/graphql') && request.postData()?.includes('query ReconcileRun')) checks.push(JSON.parse(request.postData()).variables.id); });
page.on('request', request => {
  if (request.url().includes('/api/ag-ui') && request.method() === 'POST') {
    const body = JSON.parse(request.postData());
    runs.push({ id: body.runId, conversationId: body.threadId });
  }
  if (request.url().endsWith('/graphql') && request.postData()?.includes('mutation CreateConversation')) createCount++;
});
guard.addRequestHandler(async request => {
  if (injectUnavailableBot && request.url().endsWith('/graphql') && request.postData()?.includes('query GetBots')) {
    const response = await fetch(new URL('/graphql', backendUrl()), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: request.postData() });
    const payload = await response.json();
    payload.data.bots = [{ id: 'canonical-off-fixture', name: 'Canonical OFF fixture', target: 'GKE_REMOTE', connectionStatus: 'NOT_CONNECTED', executionMode: 'UNAVAILABLE', canChat: false, statusReason: 'injected unavailable fixture', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }];
    await request.respond({ status: 200, contentType: 'application/json', body: JSON.stringify(payload) }); return;
  }
  if (holdCreation && request.url().endsWith('/graphql') && request.postData()?.includes('mutation CreateConversation')) {
    creationObserved = true; await new Promise(resolve => { releaseCreation = resolve; });
    await request.respond({ status: 503, contentType: 'application/json', body: '{\"errors\":[{\"message\":\"injected deferred create failure\"}]}' }); return;
  }
  if (failCreation && request.url().endsWith('/graphql') && request.postData()?.includes('mutation CreateConversation')) {
    await request.respond({ status: 503, contentType: 'application/json', body: '{"errors":[{"message":"injected create failure"}]}' });
  }
});
const verify = async (name, fn) => { if (!process.env.CHAT_ONLY || name.includes(process.env.CHAT_ONLY)) await check(name, fn); };
const assert = (condition, message) => { if (!condition) throw new Error(message); };
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const editor = 'chat-input textarea';
const newChat = async () => { await page.click('[aria-label="New chat"]'); await page.waitForSelector(editor); };
const write = async value => { await page.$eval(editor, (element, text) => { element.value = text; element.dispatchEvent(new Event('input', { bubbles: true })); }, value); };
const send = async value => { await write(value); await page.waitForFunction(() => { const button = document.querySelector('chat-input button[aria-label="Send message"]'); return button && !button.disabled; }); await page.click('chat-input button[aria-label="Send message"]'); };
const search = async title => {
  await page.click('button[aria-label="Search conversations"]'); await page.waitForSelector('[role="combobox"]');
  await page.$eval('[role="combobox"]', (element, value) => { element.value = value; element.dispatchEvent(new Event('input', { bubbles: true })); }, title);
  await page.waitForFunction(value => [...document.querySelectorAll('[role="option"]')].some(element => element.textContent.includes(value)), { timeout: 8000 }, title);
  await page.evaluate(value => [...document.querySelectorAll('[role="option"]')].find(element => element.textContent.includes(value)).click(), title);
  await page.waitForFunction(() => !document.querySelector('[role="combobox"]'));
};
const details = async run => {
  const data = await graphql('query Verify($id: ID!, $conversation: ID!) { run(id: $id) { id status } conversation(id: $conversation) { messages(limit: 20, offset: 0) { id runId role content interrupted } } }', { id: run.id, conversation: run.conversationId });
  return { run: data.run, rows: data.conversation.messages.filter(row => row.runId === run.id) };
};
try {
  page.setDefaultTimeout(8000);
  await page.evaluateOnNewDocument(() => {
    const nativeFetch = window.fetch.bind(window);
    window.__fault = { abortGeneration: false, cutAfterDelta: false, drained: false, seenStarted: false, seenDelta: false, runIds: [], clipboardReject: false, clipboardValue: '' };
    window.fetch = async (url, init) => {
      if (window.__fault.abortGeneration && String(url).includes('/api/ag-ui')) { window.__fault.runIds.push(JSON.parse(init.body).runId); throw new Error('injected network failure'); }
      const response = await nativeFetch(url, init);
      if (!window.__fault.cutAfterDelta || !String(url).includes('/api/ag-ui') || !response.ok || !response.body) return response;
      window.__fault.cutAfterDelta = false;
      const reader = response.body.getReader(), decoder = new TextDecoder(); let seen = '', cut = false;
      const stream = new ReadableStream({ start(controller) {
        (async () => { try { for (;;) { const next = await reader.read(); if (next.done) break; seen += decoder.decode(next.value, { stream: true });
          window.__fault.seenStarted ||= seen.includes('RUN_STARTED'); window.__fault.seenDelta ||= seen.includes('TEXT_MESSAGE_CONTENT');
          if (!cut) { controller.enqueue(next.value); if (window.__fault.seenStarted && window.__fault.seenDelta) { cut = true; controller.error(new Error('injected downstream stream interruption')); } }
        } if (!cut) controller.close(); } catch (error) { if (!cut) controller.error(error); } finally { reader.releaseLock(); window.__fault.drained = true; } })();
      }});
      return new Response(stream, { status: response.status, statusText: response.statusText, headers: response.headers });
    };
    window.__systemClipboard = navigator.clipboard;
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async text => { if (window.__fault.clipboardReject) throw new Error('injected clipboard rejection'); window.__fault.clipboardValue = text; } } });
  });
  await page.setViewport({ width: 1280, height: 800 });
  await page.goto(browserUrl(), { waitUntil: 'networkidle2' });
  await verify('native components and deferred creation', async () => {
    for (const selector of ['chat-window', 'chat-input', 'chat-message-list']) assert(await page.$(selector), `missing ${selector}`);
    const before = createCount; await newChat(); await write('  retained\n '); await wait(150);
    assert(createCount === before, 'new draft created a conversation');
    await search('COMPLETED'); await newChat(); await wait(150);
    assert(await page.$eval(editor, e => e.value) === '  retained\n ', 'raw draft changed after selection');
  });
  await verify('create failure restores raw draft (injected HTTP 503)', async () => {
    await newChat(); failCreation = true;
    try {
      await send('  failed source\n ');
      await page.waitForFunction(() => document.querySelector('main')?.innerText.includes('執行失敗'), { timeout: 8000 });
      assert(await page.$eval(editor, e => e.value) === '  failed source\n ', 'raw draft not restored');
    } finally { failCreation = false; }
  });
  await verify('deferred create failure keeps old and newer drafts', async () => {
    await newChat(); holdCreation = true; creationObserved = false;
    try {
      await send('  original deferred\n ');
      for (let i = 0; i < 40 && !creationObserved; i++) await wait(100);
      assert(creationObserved && releaseCreation, 'create request not observed');
      await newChat(); await write('newer independent draft'); releaseCreation(); releaseCreation = undefined; holdCreation = false;
      await page.waitForFunction(() => document.querySelector('main')?.innerText.includes('original deferred'), { timeout: 8000 });
      assert(await page.$eval(editor, e => e.value) === 'newer independent draft', 'newer draft overwritten');
    } finally { holdCreation = false; releaseCreation?.(); releaseCreation = undefined; }
  });
  await verify('IME composition Enter does not submit', async () => {
    await newChat(); await write('ime draft'); const before = createCount;
    await page.$eval(editor, element => { element.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true })); element.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', isComposing: true, bubbles: true, cancelable: true })); element.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true })); });
    await wait(200); assert(createCount === before && await page.$eval(editor, e => e.value) === 'ime draft', 'IME enter submitted or lost draft');
  });
  await verify('OFF bot Enter preserves input (injected GraphQL fixture)', async () => {
    injectUnavailableBot = true;
    try {
      await page.reload({ waitUntil: 'networkidle2' }); await page.click('[data-testid="nav-bots"]');
      await page.waitForSelector('[data-testid="bot-item-canonical-off-fixture"]');
      await page.click('[data-testid="bot-item-canonical-off-fixture"]'); await page.waitForSelector(editor);
      await write('  OFF raw\n '); const before = createCount;
      await page.$eval(editor, element => element.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })));
      await wait(200); assert(createCount === before && await page.$eval(editor, e => e.value) === '  OFF raw\n ', 'OFF input submitted or lost');
    } finally { injectUnavailableBot = false; }
  });
  await verify('parallel A/B runs and authoritative message identities', async () => {
    const start = runs.length; await newChat(); await send('[DELAY] canonical-parallel-A');
    await page.waitForSelector('chat-input button[aria-label="Stop generating"]');
    await newChat(); await send('[DELAY] canonical-parallel-B');
    await page.waitForFunction(() => document.querySelector('main')?.innerText.includes('canonical-parallel-B') && document.querySelector('main')?.innerText.includes('deterministic mock'), { timeout: 10000 });
    const pair = runs.slice(start); assert(pair.length === 2 && pair[0].conversationId !== pair[1].conversationId, 'runs are not independently owned');
    for (const run of pair) { let result; for (let attempt = 0; attempt < 40; attempt++) { result = await details(run); if (result.run?.status === 'COMPLETED') break; await wait(100); } assert(result.run?.status === 'COMPLETED', 'run did not complete'); assert(result.rows.filter(row => row.role === 'USER').length === 1 && result.rows.filter(row => row.role === 'ASSISTANT').length === 1, 'authoritative messages missing or duplicated'); assert(new Set(result.rows.map(row => row.id)).size === result.rows.length, 'duplicate ids'); }
  });
  await verify('cancel partial run and verify authoritative status', async () => {
    const start = runs.length; await newChat(); await send('[DELAY] canonical-cancel');
    await page.waitForSelector('chat-input button[aria-label="Stop generating"]');
    await page.waitForFunction(() => document.querySelector('main')?.innerText.includes('Hello,'), { timeout: 8000 });
    await page.click('chat-input button[aria-label="Stop generating"]');
    await page.waitForFunction(() => document.querySelector('main')?.innerText.includes('回覆已停止'), { timeout: 8000 });
    const run = runs[start]; assert(run, 'missing run id'); const result = await details(run);
    assert(result.run?.status === 'CANCELLED', `authoritative status ${result.run?.status}`);
    assert(result.rows.filter(row => row.role === 'USER').length === 1 && result.rows.filter(row => row.role === 'ASSISTANT' && row.interrupted).length === 1, 'cancelled messages missing');
  });
  await verify('clipboard simulated success and rejection', async () => {
    const button = await page.$('button[aria-label="複製回覆"]'); assert(button, 'copy control missing');
    await page.evaluate(() => { window.__fault.clipboardReject = false; }); await button.click();
    await page.waitForFunction(() => document.querySelector('main')?.innerText.includes('已複製回覆'));
    assert((await page.evaluate(() => window.__fault.clipboardValue)).length > 0, 'clipboard spy received empty content');
    await page.evaluate(() => { window.__fault.clipboardReject = true; }); await button.click();
    await page.waitForFunction(() => document.querySelector('main')?.innerText.includes('無法複製回覆'));
  });
  await verify('system clipboard actual write and read', async () => {
    // Chromium distinguishes sanitized text writes from general clipboard writes.
    await page.browser().defaultBrowserContext().overridePermissions(browserUrl(), ['clipboard-read', 'clipboard-write', 'clipboard-sanitized-write']);
    await page.evaluate(() => Object.defineProperty(navigator, 'clipboard', { configurable: true, value: window.__systemClipboard }));
    try {
      await page.evaluate(() => navigator.clipboard.writeText('threadplane-owned-marker'));
      assert(await page.evaluate(() => navigator.clipboard.readText()) === 'threadplane-owned-marker', 'clipboard permission unavailable');
      await newChat(); await send('canonical-clipboard-own-text');
      await page.waitForFunction(() => document.querySelector('chat-message[role=assistant]')?.innerText.includes('canonical-clipboard-own-text'), { timeout: 8000 });
      await page.click('button[aria-label="複製回覆"]');
      await page.waitForFunction(() => document.querySelector('main')?.innerText.includes('已複製回覆'));
      assert((await page.evaluate(() => navigator.clipboard.readText())).includes('canonical-clipboard-own-text'), 'actual clipboard differs');
    } finally {
      await page.evaluate(() => Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async text => { if (window.__fault.clipboardReject) throw new Error('injected clipboard rejection'); window.__fault.clipboardValue = text; } } }));
    }
  });
  await verify('unknown checks original UUID without resubmission (injected fetch failure)', async () => {
    await newChat(); await page.evaluate(() => { window.__fault.abortGeneration = true; });
    try {
      await send('canonical-unknown'); await page.waitForSelector('chat-error button', { timeout: 8000 });
      const id = await page.evaluate(() => window.__fault.runIds.at(-1)); assert(id, 'fault did not capture run');
      const before = checks.length; await page.click('chat-error button');
      for (let i = 0; i < 40 && checks.length <= before; i++) await wait(100);
      assert(checks.length > before && checks.slice(before).every(value => value === id), 'check used a different id');
      assert((await page.evaluate(() => window.__fault.runIds.length)) === 1, 'new run submitted');
    } finally { await page.evaluate(() => { window.__fault.abortGeneration = false; }); }
  });
  await verify('accepted run downstream interruption reconciles original UUID', async () => {
    await newChat(); const start = runs.length; const checkStart = reconcileResponses.length;
    await page.evaluate(() => { Object.assign(window.__fault, { cutAfterDelta: true, drained: false, seenStarted: false, seenDelta: false }); });
    try {
      await send('[DELAY] canonical-accepted-interruption');
      await page.waitForSelector('chat-error button', { timeout: 8000 });
      const run = runs[start]; assert(run && runs.length === start + 1, 'exactly one accepted run required');
      const fault = await page.evaluate(() => ({ started: window.__fault.seenStarted, delta: window.__fault.seenDelta }));
      assert(fault.started && fault.delta, 'stream cut before started and first delta');
      for (let i = 0; i < 30 && !reconcileResponses.slice(checkStart).some(item => item.id === run.id); i++) await wait(50);
      const first = reconcileResponses.slice(checkStart).find(item => item.id === run.id);
      assert(first && ['PENDING', 'RUNNING'].includes(first.status), `first reconciliation was not running: ${JSON.stringify(first)}`);
      let result; for (let i = 0; i < 80; i++) { result = await details(run); if (result.run?.status === 'COMPLETED') break; await wait(50); }
      assert(result.run?.status === 'COMPLETED', 'original run did not complete');
      await page.waitForFunction(() => window.__fault.drained, { timeout: 8000 });
      const prior = checks.length; await page.click('chat-error button');
      for (let i = 0; i < 40 && checks.length <= prior; i++) await wait(50);
      assert(checks.length > prior && checks.slice(prior).every(id => id === run.id), 'status check changed run identity');
      assert(runs.length === start + 1, 'recovery submitted another run');
      await page.waitForFunction(() => document.querySelector('main')?.innerText.includes('deterministic mock streaming response for: canonical-accepted-interruption'), { timeout: 8000 });
      const user = result.rows.filter(row => row.role === 'USER'), assistant = result.rows.filter(row => row.role === 'ASSISTANT');
      assert(user.length === 1 && assistant.length === 1 && user[0].id !== assistant[0].id, 'authoritative identities missing or duplicate');
      const rendered = await page.$$eval('chat-message', nodes => nodes.map(node => node.innerText));
      assert(rendered.filter(text => text.includes('canonical-accepted-interruption') && !text.includes('deterministic mock')).length === 1 && rendered.filter(text => text.includes('deterministic mock streaming response for: canonical-accepted-interruption')).length === 1, 'reconciled messages duplicated');
      await writeFile(`${artifactsDir}/accepted-interruption.json`, JSON.stringify({ injection: 'downstream stream interrupted after RUN_STARTED and first delta; original reader drained', run, firstCheck: first, finalStatus: result.run.status, userIds: user.map(row => row.id), assistantIds: assistant.map(row => row.id), checkIds: checks.slice(checkStart), renderedUserCount: 1, renderedAssistantCount: 1 }, null, 2));
    } finally { await page.evaluate(() => { window.__fault.cutAfterDelta = false; }); }
  });
  for (const status of ['FAILED', 'CANCELLED']) await verify(`history ${status}`, async () => {
    await search(status); await page.waitForFunction(value => document.querySelector('[data-testid="chat-header-title"]')?.textContent.includes(value), {}, status);
    await page.waitForFunction(value => document.querySelector('main')?.innerText.includes(value), { timeout: 8000 }, status === 'FAILED' ? '回覆失敗' : '回覆已停止');
  });
  await page.screenshot({ path: `${artifactsDir}/chat-final.png` });
} finally { await finish(); }
