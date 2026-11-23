import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { mkdtemp, mkdir, rm, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { fixtureEnvironment, stopOwnedFixture, waitReady } from './owned-browser.mjs';

if (!process.env.BOT72_GRADLE_CACHE || !process.env.JAVA_HOME) throw new Error('Isolated Gradle cache and Java 25 are required');
let outbound = 0;
const spy = createServer((_req, res) => { outbound++; res.writeHead(500).end(); });
spy.listen(0, '127.0.0.1'); await once(spy, 'listening');
const endpoint = `http://127.0.0.1:${spy.address().port}`;
try {
  for (const scenario of ['selector', 'credential', 'startup-hang']) {
    const home = await mkdtemp(resolve(tmpdir(), 'bot72-negative-'));
    const env = fixtureEnvironment(process.env, home);
    await mkdir(env.GRADLE_USER_HOME);
    for (const name of ['caches', 'wrapper']) await symlink(resolve(process.env.BOT72_GRADLE_CACHE, name), resolve(env.GRADLE_USER_HOME, name), 'dir');
    if (scenario === 'selector') {
      env.GOOGLE_GENAI_BASE_URL = endpoint;
      env.SPRING_AI_MODEL_CHAT = 'google-genai';
    } else if (scenario === 'credential') {
      env.GOOGLE_GENAI_BASE_URL = endpoint;
      env.GOOGLE_API_KEY = 'synthetic-not-a-real-key';
    } else env.BOT72_TEST_HANG_BEFORE_READY = '1';
    const child = spawn(resolve('../backend/gradlew'), ['bootTestRun', '--no-daemon', '--offline', '--console=plain'], {
      cwd: resolve('../backend'), env, detached: true, stdio: ['ignore', 'pipe', 'pipe']
    });
    let output = '';
    child.stdout.on('data', chunk => { output = (output + chunk).slice(-65536); });
    child.stderr.on('data', chunk => { output = (output + chunk).slice(-65536); });
    const identity = {};
    try {
      const ready = assert.rejects(waitReady(child, 180000, identity), /exited before readiness/);
      if (scenario === 'startup-hang') {
        for (let i = 0; i < 600 && !identity.fixture; i++) await new Promise(resolve => setTimeout(resolve, 100));
        assert.ok(identity.daemon && identity.fixture, 'hung test main must identify itself');
        await stopOwnedFixture(child, identity);
        await ready;
      } else {
        await ready;
        assert.ok(identity.daemon && identity.fixture, 'actual test main must have started');
        assert.match(output, scenario === 'selector' ? /Unsafe fixture selector/ : /Unsafe fixture provider credentials/);
        await stopOwnedFixture(child, identity);
      }
      assert.doesNotMatch(output, /BROWSER_FIXTURE_READY/);
      assert.equal(outbound, 0);
      console.log(JSON.stringify({ scenario, result: scenario === 'startup-hang' ? 'stopped-before-readiness' : 'rejected-before-readiness', providerSpyCalls: outbound, processesExited: true }));
      await rm(home, { recursive: true, force: true, maxRetries: 20, retryDelay: 200 });
    } catch (error) {
      console.error(`Negative fixture failed; temp retained: ${home}`);
      throw error;
    }
  }
} finally { spy.closeAllConnections(); await new Promise(done => spy.close(done)); }
