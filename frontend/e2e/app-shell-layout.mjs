import assert from 'node:assert/strict';
import { readdir, readFile } from 'node:fs/promises';
import { extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer-core';

const origin = 'http://app-shell.test';
const artifact = process.argv[2] ? resolve(process.argv[2])
  : fileURLToPath(new URL('../dist/bot-frontend/browser/', import.meta.url));
const mediaTypes = new Map([
  ['.html', 'text/html'], ['.js', 'text/javascript'],
  ['.css', 'text/css'], ['.ico', 'image/x-icon'],
]);
const files = await readdir(artifact, { withFileTypes: true }).catch(error => {
  throw new Error(`Cannot read ${artifact}. Run npm run build first.`, { cause: error });
});
const assets = new Map(files.filter(file => file.isFile() && mediaTypes.has(extname(file.name)))
  .map(file => [`/${file.name}`, file.name]));
assert.ok(assets.has('/index.html'), `Missing index.html in ${artifact}. Run npm run build first.`);

const defaultLocalBot = {
  id: 'fixture-bot', name: 'Layout Fixture', description: '', instructions: null,
  target: 'LOCAL', connectionStatus: 'NOT_APPLICABLE', executionMode: 'LOCAL', statusReason: null,
  canChat: true, createdAt: '2026-11-20T00:00:00Z', updatedAt: '2026-11-20T00:00:00Z',
};
const reads = new Map([
  ['Workspaces', { workspaces: [{ id: 'fixture-workspace', name: 'Layout Fixture', role: 'MANAGER', personal: true }] }],
  ['GetDefaultLocalBot', { defaultLocalBot }],
  ['GetBots', { defaultLocalBot, bots: [] }],
  ['GetConversations', { conversations: [] }],
]);

const browser = await puppeteer.launch({
  headless: true,
  ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : { channel: 'chrome' }),
}).catch(error => {
  throw new Error('Cannot launch Chrome. Install Chrome or set CHROME_PATH to its executable.', { cause: error });
});

try {
  const page = await browser.newPage();
  const denied = [];
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  await page.setRequestInterception(true);
  // Respond in Chrome from the bundle and read-only fixtures, with no server or backend to forward to.
  page.on('request', async request => {
    const { origin: requestOrigin, pathname } = new URL(request.url());
    const method = request.method();
    try {
      if (requestOrigin === origin && method === 'GET') {
        const name = assets.get(pathname === '/' ? '/index.html' : pathname);
        if (name) return await request.respond({ contentType: mediaTypes.get(extname(name)), body: await readFile(join(artifact, name)) });
      }
      if (requestOrigin === origin && method === 'POST' && pathname === '/graphql') {
        const { query } = JSON.parse(request.postData() ?? '{}');
        const operation = /^\s*query\s+(\w+)\s*[(\{]/.exec(query)?.[1];
        const data = reads.get(operation);
        if (data) return await request.respond({ contentType: 'application/json', body: JSON.stringify({ data }) });
      }
      denied.push(`${method} ${requestOrigin}${pathname}`);
    } catch (error) {
      denied.push(`${method} ${pathname}: ${error.message}`);
    }
    await request.abort();
  });

  const rail = 'nav[aria-label="主要功能"]';
  const sidebar = '#navigation-context';
  const main = 'main.main-area';
  const measure = () => page.evaluate(({ rail, sidebar, main }) => {
    const bounds = element => element.getBoundingClientRect().toJSON();
    const panel = document.querySelector(sidebar);
    const header = panel.querySelector('.navigation-title');
    const control = header.querySelector('button');
    const headerBounds = bounds(header);
    const headerStyle = getComputedStyle(header);
    const controlBounds = bounds(control);
    const target = document.elementFromPoint(controlBounds.x + controlBounds.width / 2, controlBounds.y + controlBounds.height / 2);
    return {
      rail: bounds(document.querySelector(rail)), sidebar: bounds(panel), main: bounds(document.querySelector(main)),
      title: bounds(panel.querySelector('h2')), control: controlBounds, controlHit: control.contains(target),
      headerStart: headerBounds.left + parseFloat(headerStyle.paddingInlineStart),
      headerEnd: headerBounds.right - parseFloat(headerStyle.paddingInlineEnd),
      viewport: document.documentElement.clientWidth, scrollWidth: document.documentElement.scrollWidth,
    };
  }, { rail, sidebar, main });

  const closeTo = (actual, expected, label) => assert.ok(Math.abs(actual - expected) <= 1, `${label}: ${actual} vs ${expected}`);
  function checkFrame(boxes, mode) {
    closeTo(boxes.rail.left, 0, `${mode} rail starts at viewport edge`);
    assert.ok(boxes.rail.width > 0 && boxes.main.width > 0, `${mode} rail and main are visible`);
    closeTo(boxes.main.right, boxes.viewport, `${mode} main fills remaining width`);
    assert.ok(boxes.scrollWidth <= boxes.viewport, `${mode} page does not overflow horizontally`);
  }
  function checkSidebar(boxes, mode) {
    closeTo(boxes.sidebar.left, boxes.rail.right, `${mode} sidebar starts at rail edge`);
    assert.ok(boxes.sidebar.width > 0 && boxes.sidebar.right <= boxes.viewport, `${mode} sidebar fits within viewport`);
    closeTo(boxes.title.left, boxes.headerStart, `${mode} heading aligns with header start`);
    closeTo(boxes.control.right, boxes.headerEnd, `${mode} sidebar control aligns with header end`);
    assert.ok(boxes.title.right <= boxes.control.left, `${mode} heading precedes sidebar control`);
    assert.ok(boxes.control.right <= boxes.sidebar.right && boxes.control.width >= 44 && boxes.control.height >= 44 && boxes.controlHit,
      `${mode} header control fits and is hittable with a 44 × 44px target`);
  }

  await page.setDefaultTimeout(8000);
  await page.emulateMediaFeatures([{ name: 'prefers-reduced-motion', value: 'reduce' }]);
  await page.setViewport({ width: 1280, height: 800 });
  await page.goto(origin);
  await page.waitForSelector(`${main} .workspace-name`);

  const docked = await measure();
  checkFrame(docked, 'docked');
  checkSidebar(docked, 'docked');
  closeTo(docked.main.left, docked.sidebar.right, 'docked main starts at sidebar edge');

  await page.click(`${sidebar} button[aria-label="收合側欄"]`);
  await page.waitForSelector(sidebar, { hidden: true });
  const collapsed = await measure();
  checkFrame(collapsed, 'collapsed');
  closeTo(collapsed.main.left, collapsed.rail.right, 'collapsed main starts at rail edge');
  assert.equal(collapsed.sidebar.width, 0);

  await page.click(`${rail} button[aria-label="對話"]`);
  await page.waitForSelector(`${sidebar} button[aria-label="固定側欄"]`, { visible: true });
  const peek = await measure();
  checkFrame(peek, 'peek');
  checkSidebar(peek, 'peek');
  closeTo(peek.main.left, collapsed.main.left, 'peek does not shift main');
  closeTo(peek.main.width, collapsed.main.width, 'peek does not resize main');

  await page.setViewport({ width: 375, height: 667 });
  await page.waitForSelector(`${main}[data-navigation-mode="drawer"]`);
  await page.waitForSelector(sidebar, { hidden: true });
  const mobileClosed = await measure();
  checkFrame(mobileClosed, 'mobile closed');
  closeTo(mobileClosed.main.left, mobileClosed.rail.right, 'mobile main starts at rail edge');

  await page.click(`${rail} button[aria-label="對話"]`);
  await page.waitForSelector('[role="dialog"][aria-label="側欄導覽"]', { visible: true });
  const drawer = await measure();
  checkFrame(drawer, 'drawer');
  checkSidebar(drawer, 'drawer');
  closeTo(drawer.main.left, mobileClosed.main.left, 'drawer does not shift main');
  closeTo(drawer.main.width, mobileClosed.main.width, 'drawer does not resize main');

  assert.deepEqual(pageErrors, [], 'browser runtime errors');
  assert.deepEqual(denied, [], 'unexpected requests blocked by the fixture');
  console.log(`App shell geometry passed in ${await browser.version()} at 1280×800 and 375×667 CSS px.`);
} finally {
  await browser.close();
}
