import { execFileSync } from 'node:child_process';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

export const CHECKED_IN_SQL_FILE = resolve(__dirname, 'synthetic-demo.sql');

const APPROVED_CONTAINER_ID_PATTERN = /^[0-9a-f]{64}$/;
const APPROVED_NONCE_PATTERN = /^[0-9a-f]{32}$/;
const APPROVED_IDENTIFIER_PATTERN = /^[a-z_][a-z0-9_]{0,62}$/;
const APPROVED_IMAGE = 'pgvector/pgvector:pg16';
const NONCE_LABEL = 'io.github.samzhu.bot.browser-fixture.nonce';

/**
 * Validates target PostgreSQL container using positive isolation.
 * Rejects wrong/missing nonce, non-64-hex ID, generic/mismatched image,
 * missing explicit DB/user, or foreign target with zero exec.
 */
export function verifyTargetContainer(options = {}, run = execFileSync) {
  const container = options.container?.trim();
  if (!container || !APPROVED_CONTAINER_ID_PATTERN.test(container)) {
    throw new Error('Target container must be an explicit 64-hex ID (/^[0-9a-f]{64}$/).');
  }

  const nonce = options.nonce?.trim();
  if (!nonce || !APPROVED_NONCE_PATTERN.test(nonce)) {
    throw new Error('Target container requires an explicit 32-hex dedicated nonce (/^[0-9a-f]{32}$/).');
  }

  const database = options.database?.trim();
  if (!database || !APPROVED_IDENTIFIER_PATTERN.test(database)) {
    throw new Error('Explicit target database name is required (/^[a-z_][a-z0-9_]{0,62}$/); defaults are rejected.');
  }

  const username = options.user?.trim();
  if (!username || !APPROVED_IDENTIFIER_PATTERN.test(username)) {
    throw new Error('Explicit target username is required (/^[a-z_][a-z0-9_]{0,62}$/); defaults are rejected.');
  }

  let inspectOutput;
  try {
    inspectOutput = run('docker', ['container', 'inspect', container], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe']
    });
  } catch (err) {
    throw new Error(`Failed to inspect target container "${container}": ${err.message}`);
  }

  let items;
  try {
    items = JSON.parse(inspectOutput);
  } catch (err) {
    throw new Error(`Invalid JSON from docker inspect for container "${container}": ${err.message}`);
  }

  if (!Array.isArray(items) || items.length !== 1) {
    throw new Error(`Expected exactly one container inspection item for "${container}", got ${items?.length}`);
  }

  const item = items[0];
  if (item.Id !== container) {
    throw new Error(`Container ID mismatch: requested "${container}" but inspected "${item.Id}".`);
  }

  if (item.State?.Running !== true) {
    throw new Error(`Target container "${container}" is not running.`);
  }

  if (item.Config?.Image !== APPROVED_IMAGE) {
    throw new Error(`Target container image "${item.Config?.Image}" is not the approved "${APPROVED_IMAGE}".`);
  }

  const labels = item.Config?.Labels || {};
  if (labels[NONCE_LABEL] !== nonce) {
    throw new Error(`Container label "${NONCE_LABEL}" mismatch: target is not the owned test-session PG.`);
  }

  return {
    containerId: container,
    image: APPROVED_IMAGE,
    database,
    username,
    nonce
  };
}

/**
 * Executes the checked-in synthetic SQL against verified isolated container via docker exec psql.
 * Zero execution permitted unless verifiedTarget passed positive validation.
 */
export function executeSyntheticSql(verifiedTarget, sqlContent, run = execFileSync) {
  if (!verifiedTarget?.containerId || !APPROVED_CONTAINER_ID_PATTERN.test(verifiedTarget.containerId)) {
    throw new Error('Cannot execute SQL: verifiedTarget 64-hex container ID is missing.');
  }
  if (!verifiedTarget.nonce || !APPROVED_NONCE_PATTERN.test(verifiedTarget.nonce)) {
    throw new Error('Cannot execute SQL: verifiedTarget nonce is missing or invalid.');
  }
  if (!verifiedTarget.database || !verifiedTarget.username) {
    throw new Error('Cannot execute SQL: verifiedTarget database or username is missing.');
  }

  const args = [
    'exec',
    '-i',
    verifiedTarget.containerId,
    'psql',
    '-X',
    '-v',
    'ON_ERROR_STOP=1',
    '-At',
    '-U',
    verifiedTarget.username,
    '-d',
    verifiedTarget.database
  ];

  return run('docker', args, {
    input: sqlContent,
    encoding: 'utf8',
    stdio: ['pipe', 'pipe', 'pipe']
  });
}
