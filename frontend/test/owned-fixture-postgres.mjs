import { execFileSync } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { hasChild } from './owned-browser.mjs';

const workspace = '32000000-0000-4000-8000-000000000002';
const marker = 'ISSUE40 synthetic usage workspace';
const label = 'io.github.samzhu.bot.browser-fixture.nonce';

// A marker is only a clue: recheck the exact live process and container before every SQL call.
export function ownedFixturePostgres(fixture, run = execFileSync, isChild = hasChild) {
  const identity = fixture.identity?.postgres;
  if (!identity || !fixture.identity.daemon || !fixture.child.pid) throw new Error('Owned PostgreSQL identity unavailable');
  function sql(statement) {
    if (fixture.child.exitCode !== null || fixture.child.signalCode !== null ||
        !isChild(fixture.child.pid, fixture.identity.daemon) || !isChild(fixture.identity.daemon, identity.fixturePid)) {
      throw new Error('Owned fixture process is not alive in its original ancestry');
    }
    const items = JSON.parse(run('docker', ['container', 'inspect', identity.containerId], { encoding: 'utf8' }));
    const item = items.length === 1 && items[0];
    if (!item || item.Id !== identity.containerId || !item.State?.Running ||
        item.Config?.Image !== 'pgvector/pgvector:pg16' || item.Config?.Labels?.[label] !== identity.nonce) {
      throw new Error('Owned PostgreSQL container identity mismatch');
    }
    return run('docker', ['exec', '-i', identity.containerId, 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-At', '-U', identity.username, '-d', identity.database],
      { input: statement, encoding: 'utf8' }).trim();
  }
  return {
    setupNavigationBot() {
      const result = sql("INSERT INTO bot(id,name,target,system_prompt) VALUES ('bot75-navigation-local','Navigation test local bot','LOCAL','Synthetic browser fixture') RETURNING id;");
      if (result !== 'bot75-navigation-local\nINSERT 0 1') throw new Error('Unexpected navigation bot insertion');
      return 'bot75-navigation-local';
    },
    async setup() { sql(await readFile(new URL('../e2e/owned-usage-fixture.sql', import.meta.url), 'utf8')); },
    setMembership(active) {
      const expected = active ? 'true' : 'false';
      const result = sql(`UPDATE workspace_member m SET active=${expected} FROM workspace w WHERE w.id='${workspace}' AND w.name='${marker}' AND m.workspace_id=w.id AND m.user_id='dev-user' AND m.active=${!active} RETURNING m.workspace_id;`);
      if (result !== `${workspace}\nUPDATE 1`) throw new Error('Unexpected synthetic membership transition');
    }
  };
}
