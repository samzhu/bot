import { describe, it, before, after, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import puppeteer from 'puppeteer-core';
import {
  installBrowserGuard,
  createSyntheticStore,
  getGuardStats,
  resetGuardStats
} from './verification-harness.mjs';

const CHROME_PATH = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

describe('Browser Request Interception Guard (Puppeteer E2E)', () => {
  let trapServer;
  let trapPort;
  let trapUrl;
  let trapReceivedRequests = [];
  let browser;

  before(async () => {
    // 1. HTTP Trap Server
    trapServer = http.createServer((req, res) => {
      let body = '';
      req.on('data', chunk => { body += chunk; });
      req.on('end', () => {
        trapReceivedRequests.push({
          method: req.method,
          url: req.url,
          body
        });

        if (req.url === '/') {
          res.writeHead(200, { 'Content-Type': 'text/html' });
          res.end(`
            <!DOCTYPE html>
            <html>
            <body>
              <button id="send-btn">Send Generation</button>
              <div id="result"></div>
              <script>
                document.getElementById('send-btn').addEventListener('click', async () => {
                  try {
                    const res = await fetch('/api/conversations/test-conv/runs', {
                      method: 'POST',
                      headers: { 'Content-Type': 'application/json' },
                      body: JSON.stringify({ message: 'Browser generation request' })
                    });
                    const text = await res.text();
                    document.getElementById('result').textContent = 'RES:' + res.status + ':' + text;
                  } catch (err) {
                    document.getElementById('result').textContent = 'ERR:' + err.message;
                  }
                });
              </script>
            </body>
            </html>
          `);
        } else {
          res.writeHead(404);
          res.end();
        }
      });
    });

    await new Promise(resolve => trapServer.listen(0, '127.0.0.1', resolve));
    trapPort = trapServer.address().port;
    trapUrl = `http://127.0.0.1:${trapPort}`;

    // 2. Launch headless Chrome
    browser = await puppeteer.launch({
      executablePath: CHROME_PATH,
      headless: true,
      args: ['--no-sandbox', '--disable-gpu']
    });
  });

  after(async () => {
    if (browser) {
      await browser.close();
    }
    if (trapServer) {
      await new Promise(resolve => trapServer.close(resolve));
    }
  });

  beforeEach(() => {
    trapReceivedRequests = [];
    resetGuardStats();
  });

  it('GREEN STEP: installBrowserGuard intercepts in-page generation click with synthetic mock (0 trap calls)', async () => {
    const page = await browser.newPage();
    await installBrowserGuard(page, { mode: 'mock', deltaText: 'Synthetic browser token' });

    await page.goto(trapUrl, { waitUntil: 'domcontentloaded' });

    // Assert trap received the HTML document request
    assert.equal(trapReceivedRequests.length, 1);
    assert.equal(trapReceivedRequests[0].url, '/');

    // Click the send button in page
    await page.click('#send-btn');
    await page.waitForFunction(() => document.getElementById('result').textContent.length > 0);

    const result = await page.$eval('#result', el => el.textContent);
    assert.match(result, /RES:200/);
    assert.match(result, /Synthetic browser token/);

    // Trap server must NOT receive the POST /api/conversations/test-conv/runs request!
    const runsRequests = trapReceivedRequests.filter(r => r.url.includes('/runs'));
    assert.equal(runsRequests.length, 0, 'Trap server must receive ZERO generation requests from browser');

    const stats = getGuardStats();
    assert.equal(stats.mockedCalls, 1);

    await page.close();
  });

  it('GREEN STEP: installBrowserGuard in fail-closed mode blocks in-page generation click (0 trap calls)', async () => {
    const page = await browser.newPage();
    await installBrowserGuard(page, { mode: 'fail-closed' });

    await page.goto(trapUrl, { waitUntil: 'domcontentloaded' });
    await page.click('#send-btn');
    await page.waitForFunction(() => document.getElementById('result').textContent.length > 0);

    const result = await page.$eval('#result', el => el.textContent);
    assert.match(result, /PAID_MODEL_BLOCKED/);

    const runsRequests = trapReceivedRequests.filter(r => r.url.includes('/runs'));
    assert.equal(runsRequests.length, 0, 'Trap server must receive ZERO generation requests in fail-closed mode');

    const stats = getGuardStats();
    assert.equal(stats.blockedCalls, 1);

    await page.close();
  });

  it('GREEN STEP: installBrowserGuard with unknown mode fails closed (0 trap calls)', async () => {
    const page = await browser.newPage();
    await installBrowserGuard(page, { mode: 'unknown-browser-mode' });

    await page.goto(trapUrl, { waitUntil: 'domcontentloaded' });
    await page.click('#send-btn');
    await page.waitForFunction(() => document.getElementById('result').textContent.length > 0);

    const result = await page.$eval('#result', el => el.textContent);
    assert.match(result, /PAID_MODEL_BLOCKED/);

    const runsRequests = trapReceivedRequests.filter(r => r.url.includes('/runs'));
    assert.equal(runsRequests.length, 0, 'Trap server must receive ZERO generation requests for unknown mode');

    await page.close();
  });

  it('GREEN STEP: installBrowserGuard with syntheticStore maintains identical IDs and metadata between store and SSE', async () => {
    const page = await browser.newPage();
    const store = createSyntheticStore();
    await installBrowserGuard(page, { mode: 'mock', syntheticStore: store, deltaText: 'Consistent store reply' });

    await page.goto(trapUrl, { waitUntil: 'domcontentloaded' });
    await page.click('#send-btn');
    await page.waitForFunction(() => document.getElementById('result').textContent.length > 0);

    const result = await page.$eval('#result', el => el.textContent);
    assert.match(result, /RES:200/);

    const lines = result.split('\n').filter(l => l.startsWith('data: ')).map(l => l.substring(6));
    const startFrame = JSON.parse(lines[0]);
    const completedFrame = JSON.parse(lines[lines.length - 1]);

    const conv = store.getConversation('test-conv');
    assert.ok(conv, 'Conversation must exist in store');
    assert.equal(conv.messages.length, 2, 'Store must contain exactly 1 USER and 1 ASSISTANT message');

    const userMsg = conv.messages.find(m => m.role === 'USER');
    const assistantMsg = conv.messages.find(m => m.role === 'ASSISTANT');

    assert.equal(userMsg.id, startFrame.userMessageId, 'userMessageId in startFrame must match store user message ID');
    assert.equal(assistantMsg.id, completedFrame.messageId, 'messageId in completedFrame must match store assistant message ID');
    assert.equal(conv.runs[0].id, startFrame.runId, 'runId in store must match startFrame runId');
    assert.equal(conv.runs[0].status, 'COMPLETED');

    await page.close();
  });

  it('GREEN STEP: Competing rogue listener attempting req.continue cannot bypass fail-closed guard (0 trap calls)', async () => {
    const page = await browser.newPage();
    // 1. Install primary security guard in fail-closed mode
    await installBrowserGuard(page, { mode: 'fail-closed' });

    // 2. Attach a competing / rogue listener that attempts to blindly continue all requests
    page.on('request', async req => {
      if (req.url().includes('/runs')) {
        try {
          if (!req.isInterceptResolutionHandled()) {
            await req.continue();
          }
        } catch (_) {}
      }
    });

    await page.goto(trapUrl, { waitUntil: 'domcontentloaded' });
    await page.click('#send-btn');
    await page.waitForFunction(() => document.getElementById('result').textContent.length > 0);

    const result = await page.$eval('#result', el => el.textContent);
    assert.match(result, /PAID_MODEL_BLOCKED/);

    // Trap server must receive ZERO generation requests, proving competing listener did not bypass guard!
    const runsRequests = trapReceivedRequests.filter(r => r.url.includes('/runs'));
    assert.equal(runsRequests.length, 0, 'Competing listener must NEVER cause generation request to reach trap server');

    await page.close();
  });

  it('GREEN STEP: Cooperative listeners checking isInterceptResolutionHandled() execute without double-handle error', async () => {
    const page = await browser.newPage();
    const dispatcher = await installBrowserGuard(page, { mode: 'mock', deltaText: 'Cooperative test reply' });

    let cooperativeObserverExecuted = false;
    // Test cooperative listener registered on page or dispatcher
    page.on('request', req => {
      if (req.url().includes('/runs')) {
        cooperativeObserverExecuted = true;
      }
      // Must check isInterceptResolutionHandled() before resolution
      if (!req.isInterceptResolutionHandled() && req.url().includes('/unhandled-path')) {
        req.continue();
      }
    });

    await page.goto(trapUrl, { waitUntil: 'domcontentloaded' });
    await page.click('#send-btn');
    await page.waitForFunction(() => document.getElementById('result').textContent.length > 0);

    const result = await page.$eval('#result', el => el.textContent);
    assert.match(result, /RES:200/);
    assert.match(result, /Cooperative test reply/);
    assert.equal(cooperativeObserverExecuted, true, 'Cooperative observer must have executed');

    const runsRequests = trapReceivedRequests.filter(r => r.url.includes('/runs'));
    assert.equal(runsRequests.length, 0, 'Trap server must receive ZERO generation requests');

    await page.close();
  });

  it('GREEN STEP: verified-backend mode blocks competing continue during async check on unsafe backend (0 trap calls)', async () => {
    const page = await browser.newPage();
    // 1. Install primary security guard in verified-backend mode
    await installBrowserGuard(page, { mode: 'verified-backend' });

    // 2. Attach a competing listener that tries to continue requests during async check
    page.on('request', async req => {
      if (req.url().includes('/runs')) {
        try {
          if (!req.isInterceptResolutionHandled()) {
            await req.continue();
          }
        } catch (_) {}
      }
    });

    await page.goto(trapUrl, { waitUntil: 'domcontentloaded' });
    await page.click('#send-btn');
    await page.waitForFunction(() => document.getElementById('result').textContent.length > 0);

    const result = await page.$eval('#result', el => el.textContent);
    // Because trapUrl returns 404 on /api/internal/verification/status (default trapUrl behavior without handler),
    // it fails closed with PAID_MODEL_BLOCKED (HTTP 403)
    assert.match(result, /PAID_MODEL_BLOCKED/);

    // Trap server must receive ZERO generation requests!
    const runsRequests = trapReceivedRequests.filter(r => r.url.includes('/runs'));
    assert.equal(runsRequests.length, 0, 'Trap server must receive ZERO generation requests on unsafe backend');

    await page.close();
  });
});
