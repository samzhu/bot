import { spawn, execFileSync } from 'node:child_process';
import { createServer, request } from 'node:http';
import { mkdtemp, readFile, rm, mkdir, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { resolve, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const forbidden = /^(?:BROWSER_URL|BACKEND_URL|VERIFICATION_(?:BACKEND|FRONTEND)_URL|SPRING_.*|BOT_AI_PROVIDER|BOT_GOOGLE_API_KEY|GOOGLE_.*|GEMINI_.*|HTTP_PROXY|HTTPS_PROXY|ALL_PROXY|NO_PROXY|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|GRADLE_OPTS)$/i;

export function rejectExternalConfiguration(env) {
  const found = Object.keys(env).filter(key => forbidden.test(key));
  if (found.length) throw new Error(`External fixture configuration is forbidden: ${found.join(', ')}`);
}

export function waitReady(child, timeout = 180000, identity = {}) {
  return new Promise((resolveReady, reject) => {
    let pending = '';
    const timer = setTimeout(() => finish(new Error('Fixture startup timed out')), timeout);
    function finish(error, port) {
      clearTimeout(timer); child.stdout.off('data', onData); child.off('exit', onExit); child.off('error', onError);
      error ? reject(error) : resolveReady(port);
    }
    function onExit() { finish(new Error('Fixture exited before readiness')); }
    function onError(error) { finish(error); }
    function onData(chunk) {
      pending += chunk.toString();
      if (pending.length > 65536) pending = pending.slice(-65536);
      const lines = pending.split(/\r?\n/); pending = lines.pop();
      for (const line of lines) {
        const daemon = /^BROWSER_FIXTURE_DAEMON ([1-9][0-9]*)$/.exec(line);
        if (daemon) identity.daemon = Number(daemon[1]);
        const fixture = /^BROWSER_FIXTURE_PROCESS ([1-9][0-9]*)$/.exec(line);
        if (fixture) identity.fixture = Number(fixture[1]);
        const match = /^BROWSER_FIXTURE_READY ([1-9][0-9]{0,4}) ([1-9][0-9]*)$/.exec(line);
        if (match && Number(match[1]) <= 65535 && identity.daemon && identity.fixture === Number(match[2])) {
          return finish(null, Number(match[1]));
        }
      }
    }
    child.stdout.on('data', onData); child.once('exit', onExit); child.once('error', onError);
  });
}

export function proxyRequest(req, res, port, child, target = `http://127.0.0.1:${port}`) {
  if (target !== `http://127.0.0.1:${port}`) { res.writeHead(403).end(); return; }
  if (child.exitCode !== null || child.signalCode !== null) { res.writeHead(503).end(); return; }
  const upstream = request({ hostname: '127.0.0.1', port, path: req.url, method: req.method,
    headers: { ...req.headers, host: `127.0.0.1:${port}` }, agent: false }, response => {
    if (response.statusCode >= 300 && response.statusCode < 400) {
      response.resume(); res.writeHead(502).end('Redirect blocked'); return;
    }
    res.writeHead(response.statusCode, response.headers); response.pipe(res);
  });
  upstream.on('error', () => { if (!res.headersSent) res.writeHead(502); res.end(); });
  req.pipe(upstream);
}

export function fixtureEnvironment(source, temp) {
  return { PATH: source.PATH || '/usr/bin:/bin', HOME: temp, TMPDIR: temp,
    GRADLE_USER_HOME: resolve(temp, '.gradle'), ...(source.JAVA_HOME ? { JAVA_HOME: source.JAVA_HOME } : {}) };
}

export async function installOwnedBrowserGuard(page, fixture, onGeneration = () => {}) {
  await page.setBypassServiceWorker(true);
  await page.setCacheEnabled(false);
  await page.setRequestInterception(true);
  const state = { blocked: 0 };
  page.on('request', req => {
    let safe = false;
    try {
      safe = new URL(req.url()).origin === fixture.origin && req.redirectChain().length === 0
        && fixture.child.exitCode === null && fixture.child.signalCode === null;
    } catch { /* Invalid URLs fail closed. */ }
    if (req.isInterceptResolutionHandled()) return;
    if (!safe) { state.blocked++; void req.abort(); return; }
    try {
      if (req.method() === 'POST' && new URL(req.url()).pathname === '/api/ag-ui') onGeneration(req);
      void req.continue();
    } catch { state.blocked++; void req.abort(); }
  });
  return state;
}

function hasChild(parent, child) {
  try {
    const output = execFileSync('/usr/bin/pgrep', ['-P', String(parent)], { encoding: 'utf8', env: { PATH: '/usr/bin:/bin' } });
    return output.trim().split(/\s+/).some(value => Number(value) === child);
  } catch (error) {
    if (error.status === 1) return false;
    throw error;
  }
}

function exists(pid) {
  try { process.kill(pid, 0); return true; }
  catch (error) { if (error.code === 'ESRCH') return false; throw error; }
}

export async function stopOwnedFixture(child, identity) {
  if (!child.pid) return;
  if (!identity.daemon) {
    if (child.exitCode === null && child.signalCode === null) child.kill('SIGTERM');
    throw new Error('Fixture daemon identity unavailable; cleanup cannot be verified');
  }
  if (identity.fixture && exists(identity.fixture)) {
    if (!hasChild(identity.daemon, identity.fixture) || !hasChild(child.pid, identity.daemon)) {
      throw new Error('Fixture process ancestry changed; refusing to signal');
    }
    process.kill(identity.fixture, 'SIGTERM');
  }
  // The single-use daemon and wrapper exit naturally after the fixture's shutdown hook.
  for (let i = 0; i < 200; i++) {
    if ([identity.fixture, identity.daemon, child.pid].filter(Boolean).every(pid => !exists(pid))) return;
    await delay(100);
  }
  throw new Error('Owned fixture processes did not exit; temporary home retained');
}

export async function startOwnedFixture() {
  rejectExternalConfiguration(process.env);
  const temp = await mkdtemp(resolve(tmpdir(), 'bot72-'));
  const env = fixtureEnvironment(process.env, temp);
  if (process.env.BOT72_GRADLE_CACHE) {
    // Reuse only downloaded artifacts; never inherit Gradle init scripts or properties.
    try {
      await mkdir(env.GRADLE_USER_HOME);
      for (const name of ['caches', 'wrapper']) {
        await symlink(resolve(process.env.BOT72_GRADLE_CACHE, name), resolve(env.GRADLE_USER_HOME, name), 'dir');
      }
    } catch (error) { await rm(temp, { recursive: true, force: true }); throw error; }
  }
  const child = spawn(resolve(frontend, '../backend/gradlew'), ['bootTestRun', '--no-daemon', '--console=plain'], {
    cwd: resolve(frontend, '../backend'), env, detached: true, stdio: ['ignore', 'pipe', 'pipe']
  });
  child.stderr.resume();
  let server;
  const identity = {};
  const close = async () => {
    if (server?.listening) await new Promise(done => server.close(done));
    await stopOwnedFixture(child, identity);
    // The Gradle daemon may briefly finish writing after its launcher exits.
    await rm(temp, { recursive: true, force: true, maxRetries: 20, retryDelay: 200 });
  };
  try {
    const port = await waitReady(child, 180000, identity);
    child.stdout.resume();
    if (child.exitCode !== null || child.signalCode !== null) throw new Error('Fixture exited after readiness');
    const root = resolve(frontend, 'dist/bot-frontend/browser');
    await readFile(resolve(root, 'index.html'));
    server = createServer(async (req, res) => {
      if (child.exitCode !== null || child.signalCode !== null) { res.writeHead(503).end(); return; }
      const url = new URL(req.url, 'http://127.0.0.1');
      if (url.pathname === '/graphql' || url.pathname.startsWith('/api/')) return proxyRequest(req, res, port, child);
      if (req.method !== 'GET') { res.writeHead(405).end(); return; }
      let path;
      try { path = resolve(root, '.' + decodeURIComponent(url.pathname)); }
      catch { res.writeHead(400).end(); return; }
      if (path !== root && !path.startsWith(root + '/')) { res.writeHead(403).end(); return; }
      try {
        const filename = path === root ? resolve(root, 'index.html') : path;
        const file = await readFile(filename);
        const type = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml' }[extname(filename)] || 'application/octet-stream';
        res.writeHead(200, { 'Content-Type': type }); res.end(file);
      } catch { res.writeHead(404).end(); }
    });
    await new Promise((done, fail) => { server.once('error', fail); server.listen(0, '127.0.0.1', done); });
    const origin = `http://127.0.0.1:${server.address().port}`;
    child.once('exit', () => { server.close(); });
    return { origin, browserEnv: env, backendOrigin: `http://127.0.0.1:${port}`, child, close };
  } catch (error) { await close(); throw error; }
}
