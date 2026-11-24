// Accept identity only from the stdout of the runner-owned fixture process.
export function parsePostgresMarker(lines, fixturePid, nonce) {
  const markers = lines.filter(line => line.startsWith('BROWSER_FIXTURE_POSTGRES'));
  if (markers.length !== 1) throw new Error('Expected exactly one PostgreSQL identity marker');
  const match = /^BROWSER_FIXTURE_POSTGRES ([0-9a-f]{64}) ([a-z_][a-z0-9_]{0,62}) ([a-z_][a-z0-9_]{0,62}) ([1-9][0-9]*) ([0-9a-f]{32})$/.exec(markers[0]);
  if (!match || Number(match[4]) !== fixturePid || match[5] !== nonce) throw new Error('Invalid PostgreSQL identity marker');
  return { containerId: match[1], database: match[2], username: match[3], fixturePid, nonce };
}
