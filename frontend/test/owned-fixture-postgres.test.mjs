import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ownedFixturePostgres } from './owned-fixture-postgres.mjs';
const id = 'a'.repeat(64), nonce = 'b'.repeat(32);
const fixture = () => ({ child: { pid: 1, exitCode: null, signalCode: null }, identity: { daemon: 2, postgres: { containerId: id, database: 'test', username: 'test', fixturePid: 3, nonce } } });
const good = () => ({ Id: id, State: { Running: true }, Config: { Image: 'pgvector/pgvector:pg16', Labels: { 'io.github.samzhu.bot.browser-fixture.nonce': nonce } } });
test('refuses SQL for wrong identity, label, image, stopped container or changed process ancestry', () => {
  for (const mutate of [x => x.Id = 'c'.repeat(64), x => x.State.Running = false, x => x.Config.Image = 'other', x => x.Config.Labels = {}]) {
    const item = good(); mutate(item); let executions = 0;
    const run = (_cmd, args) => { if (args[0] === 'exec') executions++; return JSON.stringify([item]); };
    assert.throws(() => ownedFixturePostgres(fixture(), run, () => true).setMembership(false));
    assert.equal(executions, 0);
  }
  let calls = 0;
  assert.throws(() => ownedFixturePostgres(fixture(), () => { calls++; }, () => false).setMembership(false));
  assert.equal(calls, 0);
});
test('uses only the marked membership after inspection and checks exactly one transition', () => {
  let sql = '';
  const run = (_cmd, args, opts) => { if (args[0] === 'container') return JSON.stringify([good()]); sql = opts.input; return '32000000-0000-4000-8000-000000000002\nUPDATE 1\n'; };
  ownedFixturePostgres(fixture(), run, () => true).setMembership(false);
  assert.match(sql, /ISSUE40 synthetic usage workspace/);
  assert.match(sql, /user_id='dev-user'/);
  assert.match(sql, /m.active=true/);
});

test('navigation fixture inserts only one fixed LOCAL bot after ownership verification', () => {
  let statement = '';
  const run = (_cmd, args, opts) => { if (args[0] === 'container') return JSON.stringify([good()]); statement = opts.input; return 'bot75-navigation-local\nINSERT 0 1\n'; };
  assert.equal(ownedFixturePostgres(fixture(), run, () => true).setupNavigationBot(), 'bot75-navigation-local');
  assert.match(statement, /^INSERT INTO bot\(id,name,target,system_prompt\)/);
  assert.match(statement, /'LOCAL'/);
  assert.doesNotMatch(statement, /UPDATE|DELETE/i);
});
