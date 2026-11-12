import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import puppeteer from 'puppeteer-core';
import { assertSafeBackendOrigin, installBrowserGuard } from '../test/verification-harness.mjs';

export function browserUrl() {
  return process.env.BROWSER_URL || 'http://127.0.0.1:4300';
}

export function backendUrl() {
  return process.env.BACKEND_URL || 'http://127.0.0.1:8181';
}

/** Create one guarded browser session. The caller owns navigation and viewport setup. */
export async function startVerification(suite) {
  if (typeof suite !== 'string' || !suite.trim()) {
    throw new TypeError('A nonempty verification suite name is required');
  }
  // Check both paths: the browser proxy could point at a different backend.
  await assertSafeBackendOrigin(backendUrl());
  await assertSafeBackendOrigin(browserUrl());

  const safeSuite = suite.trim().replace(/[^a-zA-Z0-9_-]+/g, '-').slice(0, 80) || 'suite';
  const artifactsDir = await mkdtemp(join(tmpdir(), `threadplane-${safeSuite}-`));
  const browser = await puppeteer.launch({
    executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless: true,
  });

  let page;
  let guard;
  try {
    page = await browser.newPage();
    guard = await installBrowserGuard(page, { mode: 'verified-backend' });
  } catch (error) {
    await browser.close();
    throw error;
  }

  const results = [];
  const consoleMessages = [];
  const network = [];
  const cleanUrl = value => {
    try {
      const url = new URL(value);
      url.search = '';
      url.hash = '';
      return url.toString();
    } catch {
      return value;
    }
  };
  page.on('console', message => consoleMessages.push({ type: message.type(), text: message.text() }));
  page.on('pageerror', error => consoleMessages.push({ type: 'pageerror', text: String(error.stack || error) }));
  page.on('response', response => network.push({
    method: response.request().method(), url: cleanUrl(response.url()), status: response.status(),
  }));
  page.on('requestfailed', request => network.push({
    method: request.method(), url: cleanUrl(request.url()), failure: request.failure()?.errorText || 'request failed',
  }));

  async function graphql(query, variables = {}) {
    const response = await fetch(new URL('/graphql', backendUrl()), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ query, variables }),
    });
    if (!response.ok) throw new Error(`GraphQL HTTP ${response.status}`);
    const result = await response.json();
    if (result.errors?.length) throw new Error(`GraphQL errors: ${JSON.stringify(result.errors)}`);
    return result.data;
  }

  async function check(name, asyncFn) {
    try {
      const value = await asyncFn();
      results.push({ name, status: 'passed' });
      return value;
    } catch (error) {
      const screenshot = `failure-${String(results.length + 1).padStart(3, '0')}.png`;
      let screenshotError;
      try {
        await page.screenshot({ path: join(artifactsDir, screenshot), fullPage: true });
      } catch (captureError) {
        screenshotError = String(captureError);
      }
      results.push({ name, status: 'failed', error: String(error?.stack || error),
        ...(screenshotError ? { screenshotError } : { screenshot }) });
      return undefined;
    }
  }

  let finished;
  function finish() {
    if (!finished) finished = (async () => {
      let closeError;
      try { await browser.close(); } catch (error) { closeError = error; }
      await Promise.all([
        writeFile(join(artifactsDir, 'results.json'), JSON.stringify({ suite, results }, null, 2) + '\n'),
        writeFile(join(artifactsDir, 'console.json'), JSON.stringify(consoleMessages, null, 2) + '\n'),
        writeFile(join(artifactsDir, 'network.json'), JSON.stringify(network, null, 2) + '\n'),
      ]);
      const failures = results.filter(result => result.status === 'failed');
      if (results.length === 0) throw new Error(`No verification checks executed; artifacts: ${artifactsDir}`);
      if (failures.length) throw new Error(`${failures.length} verification check(s) failed; artifacts: ${artifactsDir}`);
      if (closeError) throw closeError;
    })();
    return finished;
  }

  return { browser, page, guard, graphql, check, finish, artifactsDir };
}
