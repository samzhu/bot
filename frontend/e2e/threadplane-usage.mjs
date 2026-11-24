// Synthetic usage fixture is explicitly created in this session's owned PostgreSQL.
import { startVerification, browserUrl } from './threadplane-support.mjs';
import { ownedFixturePostgres } from '../test/owned-fixture-postgres.mjs';
import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { join } from 'node:path';
import assert from 'node:assert/strict';
const v = await startVerification('usage-canonical', { postgresAccess: true });
const { page, check, artifactsDir } = v;
let database;
const metrics = [];
const expected = '{"nested": {"text": "中文", "large": 123456789012345678901234567890, "explicitNull": null}, "promptTokenCount": 9007199254740993}';
let otherWorkspace;
async function selectWorkspace(id) {
  const close = await page.$('[role="dialog"][aria-label="側欄導覽"] button[aria-label="關閉導覽"]');
  if (close) await close.click();
  await page.click('button[aria-label="工作區"]');
  await page.waitForSelector('#workspace-select', { visible: true });
  await page.select('#workspace-select', id);
  await page.waitForFunction(() => !document.querySelector('#workspace-select'));
}
const setup = async width => {
  await page.setViewport({ width, height: 900 });
  await page.goto(browserUrl(), { waitUntil: 'networkidle2' });
  await selectWorkspace('32000000-0000-4000-8000-000000000002');
  await page.click('button[aria-label="個人用量"]');
  await page.waitForSelector('nav[aria-label="用量頁面"]');
  await page.evaluate(() => [...document.querySelectorAll('nav[aria-label="用量頁面"] button')].find(b => b.textContent.trim() === '逐次用量').click());
  await page.waitForSelector('app-usage tbody tr button');
};
const visible = async selector => page.evaluate(s => [...document.querySelectorAll(s)].map(e => {
  let r = e.getBoundingClientRect();
  return {
    x: r.x + r.width / 2,
    y: r.y + r.height / 2,
    top: r.top,
    bottom: r.bottom,
    width: r.width
  };
}).find(r => r.top >= 0 && r.bottom <= innerHeight && r.x > 0 && r.x < innerWidth && r.width > 0) || null, selector);
const measure = async label => metrics.push({
  label,
  containers: await page.evaluate(() => [...document.querySelectorAll('html,body,.main-area,app-usage,.usage,.table-scroll')].map(e => ({
    selector: e.tagName + '.' + e.className,
    clientHeight: e.clientHeight,
    scrollHeight: e.scrollHeight,
    scrollTop: e.scrollTop,
    overflowY: getComputedStyle(e).overflowY
  })))
});
try {
  database = ownedFixturePostgres(v.fixture);
  await database.setup();
  otherWorkspace = (await v.graphql('query { personalWorkspace { id } }')).personalWorkspace.id;
  assert.ok(otherWorkspace);
  await check('synthetic fixture GraphQL source and raw precision', async () => {
    const data = await v.graphql(`query($w: ID!, $id: ID!){myUsageAttempt(workspaceId:$w,id:$id){id simulation model snapshots(first:20){nodes{id sourceLayer rawUsage} pageInfo{hasNextPage}}}}`, {
      w: '32000000-0000-4000-8000-000000000002',
      id: 'issue33-browser-a-001'
    });
    const a = data.myUsageAttempt;
    assert.equal(a.id, 'issue33-browser-a-001');
    assert.equal(a.simulation, true);
    assert.equal(a.model, 'issue33-browser-synthetic');
    assert.equal(a.snapshots.nodes.length, 20);
    assert.equal(a.snapshots.pageInfo.hasNextPage, true);
    const sample = a.snapshots.nodes.find(x => x.id === 'issue33-browser-s-025');
    assert.equal(sample.sourceLayer, 'FAKE_RAW');
    assert.equal(sample.rawUsage, expected);
  });
  for (const width of [1280, 375]) {
    await check(`${width} true wheel reaches pagination, raw and download`, async () => {
      await setup(width);
      await measure(`${width} before`);
      await page.mouse.move(width === 375 ? 330 : 1000, 650);
      for (let i = 0; i < 16 && !(await visible('app-usage button:not([disabled])')); i++) await page.mouse.wheel({
        deltaY: 500
      });
      for (let i = 0; i < 16; i++) {
        let found = await page.evaluate(() => [...document.querySelectorAll('app-usage button')].some(e => {
          let r = e.getBoundingClientRect();
          return e.textContent.trim() === '載入更多用量' && r.top >= 0 && r.bottom <= innerHeight;
        }));
        if (found) break;
        await page.mouse.wheel({
          deltaY: 500
        });
        await new Promise(r => setTimeout(r, 20));
      }
      let more = await page.evaluate(() => {
        let b = [...document.querySelectorAll('app-usage button')].find(e => e.textContent.trim() === '載入更多用量');
        let r = b.getBoundingClientRect();
        return {
          x: r.x + r.width / 2,
          y: r.y + r.height / 2,
          top: r.top,
          bottom: r.bottom
        };
      });
      assert.ok(more.top >= 0 && more.bottom <= 900, 'load more not wheel-reachable');
      await measure(`${width} list bottom`);
      await page.screenshot({
        path: join(artifactsDir, `${width}-wheel-list-bottom.png`)
      });
      await page.mouse.click(more.x, more.y);
      await page.waitForFunction(() => document.querySelectorAll('app-usage tbody tr').length >= 25);
      for (let i = 0; i < 16; i++) await page.mouse.wheel({
        deltaY: -500
      });
      await new Promise(r => setTimeout(r, 150));
      await page.mouse.move(width === 375 ? 330 : 1000, 400);
      for (let i = 0; i < 6; i++) await page.mouse.wheel({
        deltaX: 700
      });
      await new Promise(r => setTimeout(r, 150));
      let row = await page.evaluate(() => {
        let b = [...document.querySelectorAll('app-usage tbody tr button')].find(e => e.closest('tr').innerText.includes('issue33-browser-synthetic') && (() => {
          let r = e.getBoundingClientRect();
          return r.top >= 0 && r.bottom <= innerHeight && r.left >= 0 && r.right <= innerWidth;
        })());
        if (!b) return null;
        let r = b.getBoundingClientRect();
        return {
          x: r.x + r.width / 2,
          y: r.y + r.height / 2
        };
      });
      assert.ok(row, 'fixture row not visible after wheel');
      await page.mouse.click(row.x, row.y);
      await page.waitForSelector('app-usage article pre');
      await page.mouse.move(width === 375 ? 330 : 1000, 650);
      let raw;
      for (let i = 0; i < 30; i++) {
        raw = await visible('app-usage article pre');
        if (raw) break;
        await page.mouse.wheel({
          deltaY: 250
        });
        await new Promise(r => setTimeout(r, 20));
      }
      assert.ok(raw, 'raw not wheel-reachable');
      await measure(`${width} raw visible`);
      await page.screenshot({
        path: join(artifactsDir, `${width}-raw-visible.png`)
      });
      let rawtext = await page.evaluate((x, y) => document.elementFromPoint(x, y)?.closest('pre')?.textContent, raw.x, raw.y);
      assert.equal(rawtext, expected);
      let button;
      for (let i = 0; i < 4; i++) {
        button = await visible('app-usage article button');
        if (button) break;
        await page.mouse.wheel({
          deltaY: 100
        });
        await new Promise(r => setTimeout(r, 20));
      }
      assert.ok(button, 'download not wheel-reachable');
      let dl = join(artifactsDir, `downloads-${width}`);
      await mkdir(dl);
      let c = await page.createCDPSession();
      await c.send('Page.setDownloadBehavior', {
        behavior: 'allow',
        downloadPath: dl
      });
      await page.mouse.click(button.x, button.y);
      let file;
      for (let i = 0; i < 50; i++) {
        await new Promise(r => setTimeout(r, 100));
        file = (await readdir(dl)).find(x => x.endsWith('.json'));
        if (file) break;
      }
      assert.ok(file);
      assert.deepEqual(await readFile(join(dl, file)), Buffer.from(expected, 'utf8'));
      let next;
      for (let i = 0; i < 60; i++) {
        next = await page.evaluate(() => {
          let b = [...document.querySelectorAll('app-usage button')].find(e => e.textContent.trim() === '載入更多快照');
          if (!b) return null;
          let r = b.getBoundingClientRect();
          return {
            x: r.x + r.width / 2,
            y: r.y + r.height / 2,
            top: r.top,
            bottom: r.bottom
          };
        });
        if (next && next.top >= 0 && next.bottom <= 900) break;
        await page.mouse.wheel({
          deltaY: 400
        });
        await new Promise(r => setTimeout(r, 20));
      }
      assert.ok(next && next.top >= 0 && next.bottom <= 900, 'snapshot paging not wheel-reachable');
      await page.mouse.click(next.x, next.y);
      await page.waitForFunction(() => document.querySelectorAll('app-usage article').length === 25);
    });
    await check(`${width} keyboard reaches named region pagination and download`, async () => {
      await setup(width);
      let focused;
      for (let i = 0; i < 120; i++) {
        await page.keyboard.press('Tab');
        focused = await page.evaluate(() => ({
          name: document.activeElement?.getAttribute('aria-label'),
          text: document.activeElement?.textContent?.trim(),
          tag: document.activeElement?.tagName,
          cls: document.activeElement?.className
        }));
        if (focused.cls === 'usage') break;
      }
      assert.equal(focused.cls, 'usage', 'named region unreachable by Tab');
      assert.equal(focused.name, '個人用量內容，可垂直捲動');
      let before = await page.$eval('app-usage .usage', e => e.scrollTop);
      await page.keyboard.press('PageDown');
      await new Promise(r => setTimeout(r, 400));
      let after = await page.$eval('app-usage .usage', e => e.scrollTop);
      assert.ok(after > before, 'PageDown did not scroll');
      let hit = false;
      for (let i = 0; i < 120; i++) {
        await page.keyboard.press('Tab');
        let t = await page.evaluate(() => document.activeElement?.textContent?.trim());
        if (t === '載入更多用量') {
          hit = true;
          break;
        }
      }
      assert.ok(hit, 'load more not keyboard reachable');
      await page.keyboard.press('Enter');
      await page.waitForFunction(() => document.querySelectorAll('app-usage tbody tr').length >= 25);
      let match = false;
      for (let i = 0; i < 120; i++) {
        await page.keyboard.down('Shift');
        await page.keyboard.press('Tab');
        await page.keyboard.up('Shift');
        let found = await page.evaluate(() => document.activeElement?.closest('tr')?.innerText.includes('2026-11-12T16:59:59Z') && document.activeElement?.tagName === 'BUTTON');
        if (found) {
          match = true;
          break;
        }
      }
      assert.ok(match, 'fixture row not keyboard reachable');
      await page.keyboard.press('Enter');
      await page.waitForSelector('app-usage article pre');
      let download = false;
      for (let i = 0; i < 120; i++) {
        await page.keyboard.press('Tab');
        if (await page.evaluate(() => document.activeElement?.textContent?.trim() === '下載原生用量 JSON')) {
          download = true;
          break;
        }
      }
      assert.ok(download, 'download not keyboard reachable');
      await page.screenshot({
        path: join(artifactsDir, `${width}-keyboard-download.png`)
      });
    });
    await check(`${width} true revocation clears detail and creates no Blob`, async () => {
      await setup(width);
      await page.evaluate(() => [...document.querySelectorAll('app-usage tbody tr')].find(r => r.innerText.includes('2026-11-12T16:59:59Z')).querySelector('button').click());
      await page.waitForSelector('app-usage article pre');
      await page.evaluate(() => {
        window.__usageBlobCount = 0;
        const original = URL.createObjectURL;
        URL.createObjectURL = function (...args) {
          window.__usageBlobCount++;
          return original.apply(this, args);
        };
      });
      try {
        database.setMembership(false);
        const response = page.waitForResponse(r => r.url().endsWith('/graphql') && r.request().postData()?.includes('AuthorizeUsageDownload'));
        await page.$eval('app-usage article button', e => e.click());
        const body = await (await response).json();
        assert.equal(body.errors?.[0]?.extensions?.code, 'WORKSPACE_ACCESS_DENIED');
        await page.waitForSelector('app-usage [role=alert]');
        assert.equal(await page.evaluate(() => window.__usageBlobCount), 0);
        assert.equal(await page.$$eval('app-usage article', a => a.length), 0);
        assert.equal(await page.$$eval('app-usage tbody tr', a => a.length), 0);
      } finally {
        database.setMembership(true);
      }
    });
    await check(`${width} raw draft survives navigation`, async () => {
      await setup(width);
      await page.evaluate(() => [...document.querySelectorAll('app-usage button')].find(b => b.textContent.trim() === '返回對話').click());
      await page.waitForSelector('chat-input textarea:not([disabled])');
      const raw = '  Usage draft 中文  ';
      await page.type('chat-input textarea', raw);
      await page.click('button[aria-label="個人用量"]');
      await page.waitForSelector('app-usage');
      await page.evaluate(() => [...document.querySelectorAll('app-usage button')].find(b => b.textContent.trim() === '返回對話').click());
      await page.waitForSelector('chat-input textarea');
      assert.equal(await page.$eval('chat-input textarea', e => e.value), raw);
    });
    await check(`${width} injected delay: stale workspace detail ignored`, async () => {
      await setup(width);
      const originalWorkspace = otherWorkspace;
      assert.ok(originalWorkspace, 'A second workspace is required');
      await page.evaluate(() => {
        const original = fetch;
        window.__usageRelease = null;
        window.__usageHold = 'MyUsageAttempt(';
        window.fetch = async (...args) => {
          const response = await original(...args);
          if (typeof args[1]?.body === 'string' && args[1].body.includes(window.__usageHold) && !window.__usageRelease) await new Promise(resolve => window.__usageRelease = resolve);
          return response;
        };
      });
      await page.evaluate(() => [...document.querySelectorAll('app-usage tbody tr')].find(r => r.innerText.includes('2026-11-12T16:59:59Z')).querySelector('button').click());
      await page.waitForFunction(() => !!window.__usageRelease);
      await selectWorkspace(originalWorkspace);
      await page.evaluate(() => window.__usageRelease());
      await new Promise(r => setTimeout(r, 300));
      assert.equal(await page.$$eval('app-usage article', x => x.length), 0);
      assert.ok(!(await page.$eval('app-usage', e => e.innerText)).includes('issue33-browser-synthetic'));
    });
    await check(`${width} injected delay: stale workspace list ignored`, async () => {
      await setup(width);
      const other = otherWorkspace;
      assert.ok(other, 'A second workspace is required');
      await selectWorkspace(other);
      await page.evaluate(() => {
        const original = fetch;
        window.__usageRelease = null;
        window.fetch = async (...args) => {
          const response = await original(...args);
          if (typeof args[1]?.body === 'string' && args[1].body.includes('query MyUsageAttempts') && args[1].body.includes('32000000-0000-4000-8000-000000000002') && !window.__usageRelease) await new Promise(resolve => window.__usageRelease = resolve);
          return response;
        };
      });
      await selectWorkspace('32000000-0000-4000-8000-000000000002');
      await page.waitForFunction(() => !!window.__usageRelease);
      await selectWorkspace(other);
      await page.evaluate(() => window.__usageRelease());
      await new Promise(r => setTimeout(r, 300));
      assert.ok(!(await page.$eval('app-usage', e => e.innerText)).includes('issue33-browser-synthetic'));
    });
    await check(`${width} injected delay: older detail cannot replace newer detail`, async () => {
      await setup(width);
      await page.evaluate(() => {
        const original = fetch;
        window.__usageRelease = null;
        window.fetch = async (...args) => {
          const response = await original(...args);
          if (typeof args[1]?.body === 'string' && args[1].body.includes('query MyUsageAttempt(') && !window.__usageRelease) await new Promise(resolve => window.__usageRelease = resolve);
          return response;
        };
      });
      const buttons = await page.$$('app-usage tbody tr button');
      assert.ok(buttons.length >= 2);
      await buttons[0].evaluate(e => e.click());
      await page.waitForFunction(() => !!window.__usageRelease);
      await buttons[1].evaluate(e => e.click());
      await page.waitForFunction(() => {
        const e = document.querySelector('app-usage section[aria-labelledby]');
        return e && !e.innerText.includes('載入明細中') && e.querySelector('p');
      });
      const before = await page.$eval('app-usage section[aria-labelledby]', e => e.innerText);
      await page.evaluate(() => window.__usageRelease());
      await new Promise(r => setTimeout(r, 300));
      assert.equal(await page.$eval('app-usage section[aria-labelledby]', e => e.innerText), before);
    });
  }
} finally {
  await writeFile(join(artifactsDir, 'scroll-metrics.json'), JSON.stringify(metrics, null, 2));
  console.log('ARTIFACTS ' + artifactsDir);
  await v.finish();
}
