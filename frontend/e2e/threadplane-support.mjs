import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import puppeteer from 'puppeteer-core';
import { startOwnedFixture, installOwnedBrowserGuard, rejectExternalConfiguration } from '../test/owned-browser.mjs';

let activeFixture;

export function browserUrl() {
  if (!activeFixture) throw new Error('No owned verification session');
  return activeFixture.origin;
}

export function backendUrl() { return browserUrl(); }

/** Create one guarded browser session. The caller owns navigation and viewport setup. */
export async function startVerification(suite, options = {}) {
  rejectExternalConfiguration(process.env);
  if (typeof suite !== 'string' || !suite.trim()) {
    throw new TypeError('A nonempty verification suite name is required');
  }
  if (activeFixture) throw new Error('A verification session is already active');
  const safeSuite = suite.trim().replace(/[^a-zA-Z0-9_-]+/g, '-').slice(0, 80) || 'suite';
  const artifactsDir = await mkdtemp(join(tmpdir(), `threadplane-${safeSuite}-`));
  const fixture = await startOwnedFixture(options);
  activeFixture = fixture;
  let browser;
  let page;
  let guard;
  try {
    browser = await puppeteer.launch({
      executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
      headless: true, env: fixture.browserEnv, args: ['--no-proxy-server', '--disable-background-networking'],
    });
    page = await browser.newPage();
    guard = await installOwnedBrowserGuard(page, fixture);
  } catch (error) {
    try { if (browser) await browser.close(); } catch (closeError) { console.error('Browser cleanup also failed:', closeError); }
    try { await fixture.close(); } catch (closeError) { console.error('Fixture cleanup also failed:', closeError); } finally { activeFixture = undefined; }
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
  function finish(primaryError) {
    if (!finished && primaryError) results.push({ name: 'suite setup or execution', status: 'failed', error: String(primaryError?.stack || primaryError) });
    if (!finished) finished = (async () => {
      let closeError;
      try { await browser.close(); } catch (error) { closeError = error; }
      try { await fixture.close(); } catch (error) { closeError ||= error; } finally { activeFixture = undefined; }
      try { await Promise.all([
        writeFile(join(artifactsDir, 'results.json'), JSON.stringify({ suite, results }, null, 2) + '\n'),
        writeFile(join(artifactsDir, 'console.json'), JSON.stringify(consoleMessages, null, 2) + '\n'),
        writeFile(join(artifactsDir, 'network.json'), JSON.stringify(network, null, 2) + '\n'),
      ]); } catch (error) {
        if (primaryError) console.error('Writing verification evidence also failed:', error);
        else closeError ||= error;
      }
      const failures = results.filter(result => result.status === 'failed');
      if (primaryError) {
        if (closeError) console.error('Fixture cleanup also failed:', closeError);
        throw primaryError;
      }
      if (closeError && (results.length === 0 || failures.length || guard.blocked)) console.error('Verification cleanup or evidence also failed:', closeError);
      if (results.length === 0) throw new Error(`No verification checks executed; artifacts: ${artifactsDir}`);
      if (failures.length) throw new Error(`${failures.length} verification check(s) failed; artifacts: ${artifactsDir}`);
      if (guard.blocked) throw new Error(`Blocked ${guard.blocked} unsafe request(s); artifacts: ${artifactsDir}`);
      if (closeError) throw closeError;
    })();
    return finished;
  }

  return { browser, page, guard, graphql, check, finish, artifactsDir, fixture };
}
