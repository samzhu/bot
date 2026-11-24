import { test } from 'node:test';
import assert from 'node:assert/strict';
import { rejectExternalConfiguration } from './owned-browser.mjs';

test('rejects external origins and unsafe provider or proxy configuration', () => {
  for (const name of ['BROWSER_URL', 'BACKEND_URL', 'BOT75_FIXTURE_NONCE', 'VERIFICATION_BACKEND_URL', 'SPRING_PROFILES_ACTIVE', 'SPRING_AI_GOOGLE_GENAI_API_KEY', 'GOOGLE_API_KEY', 'HTTPS_PROXY', 'JAVA_TOOL_OPTIONS']) {
    assert.throws(() => rejectExternalConfiguration({ [name]: 'synthetic' }), /External fixture configuration/);
  }
  assert.doesNotThrow(() => rejectExternalConfiguration({ PATH: '/bin', JAVA_HOME: '/jdk' }));
});

test('proxy blocks redirects and refuses requests after its owned child exits', async () => {
  const { createServer } = await import('node:http');
  const { once } = await import('node:events');
  const { proxyRequest } = await import('./owned-browser.mjs');
  let redirected = 0, upstreamCalls = 0;
  const target = createServer((_req, res) => { redirected++; res.end(); });
  target.listen(0, '127.0.0.1'); await once(target, 'listening');
  const upstream = createServer((_req, res) => { upstreamCalls++; res.writeHead(307, { Location: `http://127.0.0.1:${target.address().port}/api/ag-ui` }).end(); });
  upstream.listen(0, '127.0.0.1'); await once(upstream, 'listening');
  const child = { exitCode: null, signalCode: null };
  const proxy = createServer((req, res) => proxyRequest(req, res, upstream.address().port, child));
  proxy.listen(0, '127.0.0.1'); await once(proxy, 'listening');
  try {
    const url = `http://127.0.0.1:${proxy.address().port}/api/ag-ui`;
    assert.equal((await fetch(url, { method: 'POST', body: '{}' })).status, 502);
    assert.equal(upstreamCalls, 1);
    assert.equal(redirected, 0);
    child.exitCode = 1;
    assert.equal((await fetch(url, { method: 'POST', body: '{}' })).status, 503);
    assert.equal(upstreamCalls, 1);
  } finally {
    for (const server of [proxy, upstream, target]) { server.closeAllConnections(); await new Promise(done => server.close(done)); }
  }
});

test('external backend origin fails before a child process can be started', async () => {
  const { spawnSync } = await import('node:child_process');
  const result = spawnSync(process.execPath, ['--input-type=module', '-e', "import { startOwnedFixture } from './test/owned-browser.mjs'; await startOwnedFixture();"], {
    cwd: process.cwd(), env: { PATH: '/usr/bin:/bin', BACKEND_URL: 'http://127.0.0.1:49123' }, encoding: 'utf8'
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /External fixture configuration/);
});

test('startup failure before private readiness fails closed', async () => {
  const { EventEmitter } = await import('node:events');
  const { PassThrough } = await import('node:stream');
  const { waitReady } = await import('./owned-browser.mjs');
  const child = new EventEmitter(); child.stdout = new PassThrough();
  const readiness = waitReady(child, 500);
  child.stdout.write('failed to configure\n');
  child.emit('exit', 1);
  await assert.rejects(readiness, /exited before readiness/);
});

test('child environment does not inherit cloud or proxy credentials', async () => {
  const { fixtureEnvironment } = await import('./owned-browser.mjs');
  const env = fixtureEnvironment({ PATH: '/bin', JAVA_HOME: '/jdk', AWS_SECRET_ACCESS_KEY: 'synthetic', HTTPS_PROXY: 'http://proxy.invalid', GOOGLE_API_KEY: 'synthetic' }, '/tmp/fixture-home');
  assert.deepEqual(Object.keys(env).sort(), ['GRADLE_USER_HOME', 'HOME', 'JAVA_HOME', 'PATH', 'TMPDIR']);
});

test('browser guard rejects wrong origins, redirects and a dead fixture before generation', async () => {
  const { installOwnedBrowserGuard } = await import('./owned-browser.mjs');
  let handler, sent = 0, blocked = 0, generations = 0;
  const page = { async setBypassServiceWorker() {}, async setCacheEnabled() {}, async setRequestInterception() {}, on(_event, fn) { handler = fn; } };
  const fixture = { origin: 'http://127.0.0.1:12345', child: { exitCode: null, signalCode: null } };
  const guard = await installOwnedBrowserGuard(page, fixture, () => generations++);
  function attempt(url, redirects = []) {
    handler({ url: () => url, method: () => 'POST', redirectChain: () => redirects,
      isInterceptResolutionHandled: () => false, abort: () => { blocked++; }, continue: () => { sent++; } });
  }
  attempt('http://127.0.0.1:54321/api/ag-ui');
  attempt(`${fixture.origin}/api/ag-ui`, [{}]);
  fixture.child.exitCode = 1;
  attempt(`${fixture.origin}/api/ag-ui`);
  assert.equal(blocked, 3); assert.equal(guard.blocked, 3);
  assert.equal(sent, 0); assert.equal(generations, 0);
});

test('cleanup refuses to signal when owned daemon identity is unavailable', async () => {
  const { stopOwnedFixture } = await import('./owned-browser.mjs');
  let signal;
  await assert.rejects(stopOwnedFixture({ pid: 123, exitCode: null, signalCode: null, kill(value) { signal = value; } }, {}), /identity unavailable/);
  assert.equal(signal, 'SIGTERM');
});

test('cleanup checks owned ancestry, stops fixture and awaits its daemon and wrapper', async () => {
  const { spawn } = await import('node:child_process');
  const { mkdtemp, writeFile, rm } = await import('node:fs/promises');
  const { tmpdir } = await import('node:os');
  const { join } = await import('node:path');
  const { stopOwnedFixture } = await import('./owned-browser.mjs');
  const dir = await mkdtemp(join(tmpdir(), 'bot72-process-test-'));
  const script = join(dir, 'child.mjs');
  await writeFile(script, `import { spawn } from 'node:child_process';
const role = process.argv[2];
if (role === 'fixture') { console.log('READY ' + process.pid); setInterval(() => {}, 1000); }
else {
  const child = spawn(process.execPath, [process.argv[1], role === 'wrapper' ? 'daemon' : 'fixture'], { detached: role === 'wrapper', stdio: ['ignore', 'pipe', 'inherit'] });
  if (role === 'wrapper') console.log('DAEMON ' + child.pid);
  child.stdout.pipe(process.stdout);
  child.on('exit', () => process.exit(0));
}
`);
  const wrapper = spawn(process.execPath, [script, 'wrapper'], { detached: true, stdio: ['ignore', 'pipe', 'inherit'] });
  try {
    let output = '';
    for await (const chunk of wrapper.stdout) {
      output += chunk;
      if (/READY \d+/.test(output)) break;
    }
    const daemon = Number(/DAEMON (\d+)/.exec(output)?.[1]);
    const fixture = Number(/READY (\d+)/.exec(output)?.[1]);
    assert.ok(daemon && fixture);
    await stopOwnedFixture(wrapper, { daemon, fixture });
    assert.throws(() => process.kill(fixture, 0), error => error.code === 'ESRCH');
    assert.throws(() => process.kill(daemon, 0), error => error.code === 'ESRCH');
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('a proxy target different from the owned port receives zero generation requests', async () => {
  const { createServer } = await import('node:http');
  const { once } = await import('node:events');
  const { proxyRequest } = await import('./owned-browser.mjs');
  let spyCalls = 0;
  const spy = createServer((_req, res) => { spyCalls++; res.end(); });
  spy.listen(0, '127.0.0.1'); await once(spy, 'listening');
  const ownedPort = spy.address().port === 1 ? 2 : 1;
  const proxy = createServer((req, res) => proxyRequest(req, res, ownedPort, { exitCode: null, signalCode: null }, `http://127.0.0.1:${spy.address().port}`));
  proxy.listen(0, '127.0.0.1'); await once(proxy, 'listening');
  try {
    const response = await fetch(`http://127.0.0.1:${proxy.address().port}/api/ag-ui`, { method: 'POST', body: '{}' });
    assert.equal(response.status, 403);
    assert.equal(spyCalls, 0);
  } finally { for (const server of [proxy, spy]) { server.closeAllConnections(); await new Promise(done => server.close(done)); } }
});

test('records fixture identity before readiness so failed startup can be stopped', async () => {
  const { EventEmitter } = await import('node:events');
  const { PassThrough } = await import('node:stream');
  const { waitReady } = await import('./owned-browser.mjs');
  const child = new EventEmitter(); child.stdout = new PassThrough();
  const identity = {};
  const result = waitReady(child, 50, identity);
  child.stdout.write('BROWSER_FIXTURE_DAEMON 123\nBROWSER_FIXTURE_PROCESS 456\n');
  await assert.rejects(result, /timed out/);
  assert.equal(identity.fixture, 456);
});

test('owned guard allows controlled fault responses but never redirects a request', async () => {
  const { installOwnedBrowserGuard } = await import('./owned-browser.mjs');
  let handler, redirected = 0, aborted = 0, responded = 0;
  const page = { async setBypassServiceWorker() {}, async setCacheEnabled() {}, async setRequestInterception() {}, on(_event, fn) { handler = fn; } };
  const fixture = { origin: 'http://127.0.0.1:12345', child: { exitCode: null, signalCode: null } };
  const guard = await installOwnedBrowserGuard(page, fixture);
  guard.addRequestHandler(async request => {
    if (request.url().endsWith('/graphql')) return request.respond({ status: 503 });
    return request.continue({ url: 'http://127.0.0.1:54321/api/ag-ui' });
  });
  function request(path) {
    let handled = false;
    return { url: () => fixture.origin + path, method: () => 'POST', redirectChain: () => [],
      isInterceptResolutionHandled: () => handled,
      abort: () => { handled = true; aborted++; }, respond: () => { handled = true; responded++; },
      continue: options => { handled = true; if (options?.url) redirected++; } };
  }
  await handler(request('/graphql'));
  await handler(request('/api/ag-ui'));
  assert.equal(responded, 1);
  assert.equal(redirected, 0);
  assert.equal(aborted, 1);
});

test('PostgreSQL access requires a single session marker before readiness', async () => {
  const { EventEmitter } = await import('node:events');
  const { PassThrough } = await import('node:stream');
  const { waitReady } = await import('./owned-browser.mjs');
  const nonce = 'b'.repeat(32), id = 'a'.repeat(64);
  for (const marker of ['', `BROWSER_FIXTURE_POSTGRES ${id} test test 456 ${nonce}\n`.repeat(2), `BROWSER_FIXTURE_POSTGRES ${id} test test 999 ${nonce}\n`]) {
    const child = new EventEmitter(); child.stdout = new PassThrough();
    const ready = waitReady(child, 1000, {}, nonce);
    child.stdout.write(`BROWSER_FIXTURE_DAEMON 123\nBROWSER_FIXTURE_PROCESS 456\n${marker}BROWSER_FIXTURE_READY 1234 456\n`);
    await assert.rejects(ready);
  }
  const child = new EventEmitter(); child.stdout = new PassThrough();
  const identity = {};
  const ready = waitReady(child, 1000, identity, nonce);
  child.stdout.write(`BROWSER_FIXTURE_DAEMON 123\nBROWSER_FIXTURE_PROCESS 456\nBROWSER_FIXTURE_POSTGRES ${id} test test 456 ${nonce}\nBROWSER_FIXTURE_READY 1234 456\n`);
  assert.equal(await ready, 1234);
  assert.equal(identity.postgres.containerId, id);
});
