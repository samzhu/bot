import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

import {
  verifyTargetContainer,
  executeSyntheticSql,
  CHECKED_IN_SQL_FILE
} from './synthetic-demo-guard.mjs';
import { runCli, isMainExecution } from './synthetic-demo.mjs';

const mockContainerId = 'a'.repeat(64);
const mockNonce = 'b'.repeat(32);

function makeValidContainerInfo(overrides = {}) {
  return {
    Id: mockContainerId,
    Name: '/bot76-test-postgres',
    State: { Running: true },
    Config: {
      Image: 'pgvector/pgvector:pg16',
      Labels: {
        'io.github.samzhu.bot.browser-fixture.nonce': mockNonce
      }
    },
    ...overrides
  };
}

test('verifyTargetContainer: accepts valid running isolated test container with positive matching', () => {
  const containerInfo = makeValidContainerInfo();
  const run = (cmd, args) => {
    if (cmd === 'docker' && args[0] === 'container' && args[1] === 'inspect') {
      return JSON.stringify([containerInfo]);
    }
    throw new Error(`Unexpected command: ${cmd} ${args.join(' ')}`);
  };

  const target = verifyTargetContainer(
    {
      container: mockContainerId,
      nonce: mockNonce,
      database: 'test_db',
      user: 'test_user'
    },
    run
  );

  assert.equal(target.containerId, mockContainerId);
  assert.equal(target.image, 'pgvector/pgvector:pg16');
  assert.equal(target.database, 'test_db');
  assert.equal(target.username, 'test_user');
  assert.equal(target.nonce, mockNonce);
});

test('verifyTargetContainer: rejects non-64-hex container IDs', () => {
  let inspectCalls = 0;
  const run = () => { inspectCalls++; return '[]'; };

  for (const badId of ['short123', 'a'.repeat(63), 'a'.repeat(65), 'xyz'.repeat(21) + 'x', '']) {
    assert.throws(
      () => verifyTargetContainer({ container: badId, nonce: mockNonce, database: 'test', user: 'postgres' }, run),
      /Target container must be an explicit 64-hex ID/
    );
  }
  assert.equal(inspectCalls, 0, 'Must fail closed before calling docker inspect');
});

test('verifyTargetContainer: rejects missing or non-32-hex nonce', () => {
  let inspectCalls = 0;
  const run = () => { inspectCalls++; return '[]'; };

  for (const badNonce of [undefined, '', 'short', 'b'.repeat(31), 'b'.repeat(33), 'non-hex-32-characters-long!!!!!!']) {
    assert.throws(
      () => verifyTargetContainer({ container: mockContainerId, nonce: badNonce, database: 'test', user: 'postgres' }, run),
      /Target container requires an explicit 32-hex dedicated nonce/
    );
  }
  assert.equal(inspectCalls, 0, 'Must fail closed before calling docker inspect');
});

test('verifyTargetContainer: rejects missing explicit database and username (no defaults allowed)', () => {
  let inspectCalls = 0;
  const run = () => { inspectCalls++; return '[]'; };

  assert.throws(
    () => verifyTargetContainer({ container: mockContainerId, nonce: mockNonce, database: '', user: 'postgres' }, run),
    /Explicit target database name is required/
  );
  assert.throws(
    () => verifyTargetContainer({ container: mockContainerId, nonce: mockNonce, database: 'test', user: '' }, run),
    /Explicit target username is required/
  );
  assert.equal(inspectCalls, 0);
});

test('verifyTargetContainer: fails closed on stopped container', () => {
  const containerInfo = makeValidContainerInfo({ State: { Running: false } });
  const run = () => JSON.stringify([containerInfo]);

  assert.throws(
    () => verifyTargetContainer({ container: mockContainerId, nonce: mockNonce, database: 'test', user: 'postgres' }, run),
    /is not running/
  );
});

test('verifyTargetContainer: fails closed on generic, prefix, or foreign image', () => {
  for (const badImage of ['pgvector/pgvector:latest', 'pgvector/pgvector:pg15', 'postgres:16', 'postgres:latest', 'custom-pg:16']) {
    const containerInfo = makeValidContainerInfo({
      Config: { Image: badImage, Labels: { 'io.github.samzhu.bot.browser-fixture.nonce': mockNonce } }
    });
    const run = () => JSON.stringify([containerInfo]);

    assert.throws(
      () => verifyTargetContainer({ container: mockContainerId, nonce: mockNonce, database: 'test', user: 'postgres' }, run),
      /not the approved "pgvector\/pgvector:pg16"/
    );
  }
});

test('verifyTargetContainer: fails closed on nonce label mismatch', () => {
  const containerInfo = makeValidContainerInfo();
  const run = () => JSON.stringify([containerInfo]);

  assert.throws(
    () => verifyTargetContainer({ container: mockContainerId, nonce: 'c'.repeat(32), database: 'test', user: 'postgres' }, run),
    /Container label "io\.github\.samzhu\.bot\.browser-fixture\.nonce" mismatch/
  );
});

test('verifyTargetContainer: fails closed on inspected Id mismatch', () => {
  const containerInfo = makeValidContainerInfo({ Id: 'd'.repeat(64) });
  const run = () => JSON.stringify([containerInfo]);

  assert.throws(
    () => verifyTargetContainer({ container: mockContainerId, nonce: mockNonce, database: 'test', user: 'postgres' }, run),
    /Container ID mismatch/
  );
});

test('executeSyntheticSql: executes psql with ON_ERROR_STOP and verified target', () => {
  const verifiedTarget = {
    containerId: mockContainerId,
    nonce: mockNonce,
    database: 'test_db',
    username: 'test_user'
  };

  let executedCmd = null;
  let executedArgs = null;
  let executedOpts = null;

  const run = (cmd, args, opts) => {
    executedCmd = cmd;
    executedArgs = args;
    executedOpts = opts;
    return 'demo-execution-output';
  };

  const output = executeSyntheticSql(verifiedTarget, 'SELECT 1;', run);

  assert.equal(executedCmd, 'docker');
  assert.deepEqual(executedArgs, [
    'exec',
    '-i',
    mockContainerId,
    'psql',
    '-X',
    '-v',
    'ON_ERROR_STOP=1',
    '-At',
    '-U',
    'test_user',
    '-d',
    'test_db'
  ]);
  assert.equal(executedOpts.input, 'SELECT 1;');
  assert.equal(output, 'demo-execution-output');
});

test('SQL fixture: dedicated workspace, payload verification, and zero framework session tampering', async () => {
  const content = await readFile(CHECKED_IN_SQL_FILE, 'utf8');

  // Must begin and end with single transaction
  assert.match(content, /^BEGIN;/m);
  assert.match(content, /COMMIT;\s*$/m);

  // Must check schema existence pre-flight
  assert.match(content, /to_regclass\('conversation'\)/);
  assert.match(content, /to_regclass\('provider_attempt'\)/);

  // Must enforce that default bot preparation is owned by release provisioning
  assert.match(content, /Required default bot "bot-general" missing/);
  assert.doesNotMatch(content, /INSERT INTO bot/i);

  // Dedicated workspace isolation: never attaches to existing non-fixture personal workspace
  assert.match(content, /Foreign personal workspace already exists for dev-user/);
  assert.match(content, /seed-workspace-demo-01/);

  // Payload collision protection: proves exact payload or rejects in one transaction
  assert.match(content, /Synthetic payload collision on conversation/);
  assert.match(content, /Synthetic payload collision on chat_run/);
  assert.match(content, /Synthetic payload collision on provider_attempt/);
  assert.match(content, /Synthetic payload collision on usage_snapshot/);

  // Strictly excludes framework-owned Session / SessionEvent tables
  assert.doesNotMatch(content, /INSERT INTO ai_session/i, 'Must not tamper with framework ai_session');
  assert.doesNotMatch(content, /INSERT INTO ai_session_event/i, 'Must not tamper with framework ai_session_event');
  assert.doesNotMatch(content, /INSERT INTO ai_session_index/i, 'Must not tamper with framework ai_session_index');

  // Simulation proof in usage ledger
  assert.match(content, /'seed-usage-attempt-01'/);
  assert.match(content, /'seed-usage-snapshot-01'/);
  assert.match(content, /'FAKE'/);
  assert.match(content, /'synthetic-demo'/);
  assert.match(content, /TRUE,\s*'VERIFIED'/);

  // Strictly non-destructive
  assert.doesNotMatch(content, /\bDROP\s+TABLE\b/i);
  assert.doesNotMatch(content, /\bTRUNCATE\b/i);
  assert.doesNotMatch(content, /\bDELETE\s+FROM\b/i);
});

test('runCli: --help displays usage and returns exitCode 0', async () => {
  const result = await runCli(['--help']);
  assert.equal(result.exitCode, 0);
  assert.equal(result.help, true);
});

test('runCli: rejects arbitrary --sql-file option', async () => {
  const result = await runCli(['--sql-file', '/tmp/malicious.sql']);
  assert.equal(result.exitCode, 1);
});

test('runCli: fails closed without required positive isolation arguments', async () => {
  const result = await runCli(['--container', mockContainerId]);
  assert.equal(result.exitCode, 1);
  assert.match(result.error, /explicit 32-hex dedicated nonce/);
});

test('runCli: fails closed on wrong nonce with strictly ZERO SQL executions (zero exec guarantee)', async () => {
  const containerInfo = makeValidContainerInfo({
    Config: {
      Image: 'pgvector/pgvector:pg16',
      Labels: {
        'io.github.samzhu.bot.browser-fixture.nonce': mockNonce
      }
    }
  });

  const executedCommands = [];
  let sqlExecutedCount = 0;

  const run = (cmd, args) => {
    executedCommands.push({ cmd, args });
    if (args[0] === 'container' && args[1] === 'inspect') {
      return JSON.stringify([containerInfo]);
    }
    if (args[0] === 'exec') {
      sqlExecutedCount++;
      return 'UNEXPECTED SQL EXECUTION';
    }
    throw new Error(`Unexpected command: ${cmd} ${args.join(' ')}`);
  };

  const wrongNonce = 'f'.repeat(32);
  const result = await runCli([
    '--container', mockContainerId,
    '--nonce', wrongNonce,
    '--database', 'test_db',
    '--user', 'test_user'
  ], { run });

  // 1. Must exit with error
  assert.equal(result.exitCode, 1, 'Must exit with code 1 on wrong nonce');
  assert.match(result.error, /nonce.*mismatch/i, 'Error must indicate nonce mismatch');

  // 2. Must NEVER execute SQL (docker exec)
  assert.equal(sqlExecutedCount, 0, 'Must have strictly ZERO SQL execution calls on wrong nonce');
  const execCommands = executedCommands.filter(c => c.args && c.args[0] === 'exec');
  assert.equal(execCommands.length, 0, 'No docker exec command must be issued');

  // 3. Only container inspection should have occurred
  assert.equal(executedCommands.length, 1, 'Only docker container inspect should be called');
  assert.equal(executedCommands[0].args[0], 'container');
  assert.equal(executedCommands[0].args[1], 'inspect');
});

test('isMainExecution: resolves symlinked paths such as macOS /tmp to /private/tmp', () => {
  // Direct identical path
  assert.equal(isMainExecution('/path/to/script.mjs', '/path/to/script.mjs'), true);
  // Non-matching path
  assert.equal(isMainExecution('/path/to/other.mjs', '/path/to/script.mjs'), false);
  // macOS /tmp vs /private/tmp resolution test
  // If /tmp exists on system, realpathSync('/tmp') resolves to '/private/tmp'
  const tmpScript = '/tmp/synthetic-test-entrypoint.mjs';
  const privateTmpScript = '/private/tmp/synthetic-test-entrypoint.mjs';
  assert.equal(isMainExecution(tmpScript, privateTmpScript), true, '/tmp and /private/tmp must be recognized as same entrypoint');
});

test('runCli: --dry-run verifies positive isolation, skips mutations, and does not log nonce', async () => {
  const containerInfo = makeValidContainerInfo();
  let sqlExecuted = false;
  const logs = [];
  const origLog = console.log;
  console.log = (...args) => logs.push(args.join(' '));

  try {
    const run = (cmd, args) => {
      if (args[0] === 'container') return JSON.stringify([containerInfo]);
      if (args[0] === 'exec') {
        sqlExecuted = true;
        return 'unexpected execution';
      }
      throw new Error('Unexpected command');
    };

    const result = await runCli([
      '--container', mockContainerId,
      '--nonce', mockNonce,
      '--database', 'test_db',
      '--user', 'test_user',
      '--dry-run'
    ], { run });

    assert.equal(result.exitCode, 0);
    assert.equal(result.dryRun, true);
    assert.equal(sqlExecuted, false);

    const logOutput = logs.join('\n');
    assert.ok(!logOutput.includes(mockNonce), 'Log output must NOT reveal the nonce');
  } finally {
    console.log = origLog;
  }
});

test('runCli: deterministic repetition simulation', async () => {
  const containerInfo = makeValidContainerInfo();
  let executionCount = 0;

  const run = (cmd, args) => {
    if (args[0] === 'container') return JSON.stringify([containerInfo]);
    if (args[0] === 'exec') {
      executionCount++;
      return 'INSERT 0 0\n';
    }
    throw new Error('Unexpected command');
  };

  const args = [
    '--container', mockContainerId,
    '--nonce', mockNonce,
    '--database', 'test_db',
    '--user', 'test_user'
  ];

  // Run 1
  const result1 = await runCli(args, { run });
  assert.equal(result1.exitCode, 0);
  assert.equal(result1.success, true);

  // Run 2
  const result2 = await runCli(args, { run });
  assert.equal(result2.exitCode, 0);
  assert.equal(result2.success, true);

  assert.equal(executionCount, 2);
});
