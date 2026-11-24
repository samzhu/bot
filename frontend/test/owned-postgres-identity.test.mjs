import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parsePostgresMarker } from './owned-postgres-identity.mjs';

const nonce = 'b'.repeat(32);
const marker = `BROWSER_FIXTURE_POSTGRES ${'a'.repeat(64)} test test 123 ${nonce}`;
test('accepts one complete marker belonging to the owned fixture process and session', () => {
  assert.deepEqual(parsePostgresMarker([marker], 123, nonce), { containerId: 'a'.repeat(64), database: 'test', username: 'test', fixturePid: 123, nonce });
});
test('rejects missing, duplicate, malformed, foreign-process and foreign-session markers', () => {
  for (const lines of [[], [marker, marker], [marker.replace('test', 'bad/name')], [marker.replace('123', '124')], [marker.replace(nonce, 'c'.repeat(32))], ['BROWSER_FIXTURE_POSTGRES invalid']]) {
    assert.throws(() => parsePostgresMarker(lines, 123, nonce));
  }
});
