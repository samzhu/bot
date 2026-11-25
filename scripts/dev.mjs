import { execFileSync, spawn } from 'node:child_process';
import { access } from 'node:fs/promises';
import { createServer } from 'node:net';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

const host = '127.0.0.1';
const backendPort = 8080;
const frontendPort = 4200;
const children = [];
const owned = new Map();
const stopping = new AbortController();
const stopped = Promise.withResolvers();

function stop(code) {
  if (stopping.signal.aborted) return;
  process.exitCode = code;
  stopping.abort();
  stopped.resolve();
}

for (const [signal, code] of [['SIGINT', 130], ['SIGTERM', 143], ['SIGHUP', 129]]) {
  process.on(signal, () => stop(code));
}

function start(name, command, args, env) {
  const child = spawn(command, args, {
    cwd: fileURLToPath(new URL(`../${name}/`, import.meta.url)),
    detached: true,
    env,
    stdio: ['ignore', 'inherit', 'inherit'],
  });
  children.push(child);
  child.on('error', error => {
    console.error(`[dev] Could not start ${name}: ${error.message}`);
    stop(1);
  });
  child.on('exit', (code, signal) => {
    if (stopping.signal.aborted) return;
    console.error(`[dev] ${name} exited (${signal ?? code}); stopping the development environment.`);
    stop(code || 1);
  });
}

function ownedProcesses() {
  const processes = execFileSync('/bin/ps', ['-ax', '-o', 'pid=,ppid=,pgid=,stat=,lstart='], {
    encoding: 'utf8',
  }).trim().split('\n').map(line => {
    const [pid, parent, group, state, ...started] = line.trim().split(/\s+/);
    return { pid: Number(pid), parent: Number(parent), group: Number(group), state, started: started.join(' ') };
  });
  const live = new Set(processes.filter(({ pid, group, started }) =>
    owned.get(pid) === started || children.some(child => child.pid === group)).map(({ pid }) => pid));

  // A single-use Gradle daemon can run outside the wrapper's process group.
  // Remember observed descendants so shutdown can still find them after reparenting.
  for (let size = -1; size !== live.size;) {
    size = live.size;
    for (const { pid, parent } of processes) {
      if (live.has(parent)) live.add(pid);
    }
  }
  const result = processes.filter(({ pid, state }) => live.has(pid) && !state.startsWith('Z'));
  owned.clear();
  for (const { pid, started } of result) owned.set(pid, started);
  return result;
}

async function shutdown() {
  const signaled = new Set();
  const deadline = Date.now() + 35000;
  while (true) {
    const processes = ownedProcesses();
    if (processes.length === 0) return;
    const signal = Date.now() < deadline ? 'SIGTERM' : 'SIGKILL';
    if (Date.now() >= deadline + 5000) throw new Error('Development processes did not exit');
    // Leave Gradle and npm alive long enough to relay their servers' shutdown output.
    const parents = new Set(processes.map(({ parent }) => parent));
    for (const { pid, started } of processes) {
      if (signal === 'SIGTERM' && parents.has(pid)) continue;
      const key = `${pid} ${started} ${signal}`;
      if (signaled.has(key)) continue;
      try { process.kill(pid, signal); }
      catch (error) { if (error.code !== 'ESRCH') throw error; }
      signaled.add(key);
    }
    await delay(100);
  }
}

async function checkPort(port) {
  const server = createServer();
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, resolve);
  }).catch(error => {
    if (error.code === 'EADDRINUSE') throw new Error(`${host}:${port} is already in use`);
    throw error;
  });
  await new Promise(resolve => server.close(resolve));
}

async function waitUntilReady(url, ready) {
  const deadline = Date.now() + 300000;
  while (Date.now() < deadline) {
    stopping.signal.throwIfAborted();
    try {
      const signal = AbortSignal.any([stopping.signal, AbortSignal.timeout(2000)]);
      const response = await fetch(url, { signal, redirect: 'error' });
      if (response.ok && await ready(response)) return;
      if (!response.bodyUsed) await response.body?.cancel();
    } catch {
      // The server may not be listening yet or may still be initializing.
    }
    await delay(250, undefined, { signal: stopping.signal });
  }
  throw new Error(`Timed out waiting for ${url}`);
}

const tracker = setInterval(() => {
  if (children.length === 0) return;
  try { ownedProcesses(); }
  catch (error) {
    console.error(`[dev] Could not track development processes: ${error.message}`);
    stop(1);
  }
}, 500);

try {
  await Promise.all([
    checkPort(backendPort),
    checkPort(frontendPort),
    access(new URL('../frontend/node_modules/.bin/ng', import.meta.url)).catch(() => {
      throw new Error('Angular CLI is missing. Run npm ci --ignore-scripts in frontend first.');
    }),
  ]);
  stopping.signal.throwIfAborted();
  console.log('[dev] Starting backend...');
  start('backend', './gradlew', [
    'bootRun', '--no-daemon', '--console=plain',
    `--args=--spring.profiles.active=dev --server.address=${host} --server.port=${backendPort} --management.endpoint.health.probes.enabled=true`,
  ], { ...process.env, SPRING_AI_MODEL_CHAT: process.env.SPRING_AI_MODEL_CHAT ?? 'google-genai' });
  await waitUntilReady(`http://${host}:${backendPort}/actuator/health/readiness`,
    async response => (await response.json()).status === 'UP');
  stopping.signal.throwIfAborted();
  ownedProcesses();
  console.log('[dev] Backend ready. Starting frontend...');
  start('frontend', 'npm', ['run', 'start', '--', '--host', host, '--port', String(frontendPort)],
    { ...process.env, PORT: String(frontendPort) });
  const frontendUrl = `http://${host}:${frontendPort}/`;
  await waitUntilReady(frontendUrl, async response => {
    await response.body?.cancel();
    return true;
  });
  console.log(`[dev] Frontend ready at ${frontendUrl} (Ctrl+C to stop)`);
  await stopped.promise;
} catch (error) {
  if (!stopping.signal.aborted) {
    console.error(`[dev] ${error.message}`);
    stop(1);
  }
} finally {
  clearInterval(tracker);
  if (children.length > 0) {
    console.log('[dev] Stopping development processes...');
    try { await shutdown(); }
    catch (error) {
      process.exitCode = 1;
      console.error(`[dev] ${error.message}`);
    }
  }
}
