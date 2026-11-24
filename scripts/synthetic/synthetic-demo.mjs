#!/usr/bin/env node

/**
 * Standalone synthetic demonstration data runner.
 *
 * Implements Issue #76 Phase 3: standalone synthetic data script slice.
 *
 * Positive Isolation & 12-Factor boundaries:
 * - Independent invocation outside of runtime classpath and OCI packaging.
 * - Positive container verification: requires explicit 64-hex container ID,
 *   cryptographically verified 32-hex nonce matching test-purpose label,
 *   explicit database name, and explicit username with zero default fallback.
 * - Single checked-in fixture: arbitrarily provided SQL files are disallowed.
 * - Scope minimal: exactly one representative Conversation with Message history
 *   and synthetic simulation proof (simulation = TRUE, provider = 'FAKE').
 * - Release ownership: default bot ('bot-general') and schema migrations are
 *   owned by release provisioning; synthetic script strictly verifies presence.
 * - Safe & idempotent: ON CONFLICT DO NOTHING, zero DROP/TRUNCATE/DELETE.
 */

import { parseArgs } from 'node:util';
import { realpathSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve, dirname, basename } from 'node:path';
import {
  verifyTargetContainer,
  executeSyntheticSql,
  CHECKED_IN_SQL_FILE
} from './synthetic-demo-guard.mjs';

const __filename = fileURLToPath(import.meta.url);

const USAGE = `
Usage: node scripts/synthetic/synthetic-demo.mjs --container <64-hex-id> --nonce <32-hex-nonce> --database <db> --user <user> [options]

Required:
  --container <id>     Full 64-hex Docker container ID of the isolated test/dev PostgreSQL
  --nonce <nonce>      Required 32-hex nonce matching container test-purpose label
  --database <name>    Explicit target database name (no defaults permitted)
  --user <name>        Explicit target database username (no defaults permitted)

Options:
  --dry-run            Verify target container and positive isolation without running mutations
  --check              Check if synthetic demonstration data is present without modifying
  -h, --help           Show this help message

Examples:
  # Verify positive isolation without executing mutations:
  node scripts/synthetic/synthetic-demo.mjs \\
    --container 3e5f2a1b4c6d... (64 hex) \\
    --nonce a1b2c3d4... (32 hex) \\
    --database test \\
    --user postgres \\
    --dry-run

  # Apply synthetic demo data to verified isolated container:
  node scripts/synthetic/synthetic-demo.mjs \\
    --container 3e5f2a1b4c6d... (64 hex) \\
    --nonce a1b2c3d4... (32 hex) \\
    --database test \\
    --user postgres
`;

export async function runCli(argv = process.argv.slice(2), { run } = {}) {
  const options = {
    container: { type: 'string' },
    nonce: { type: 'string' },
    database: { type: 'string' },
    user: { type: 'string' },
    'dry-run': { type: 'boolean', default: false },
    check: { type: 'boolean', default: false },
    help: { type: 'boolean', short: 'h', default: false }
  };

  let values;
  try {
    const parsed = parseArgs({ args: argv, options, allowPositionals: false });
    values = parsed.values;
  } catch (err) {
    console.error(`Argument error: ${err.message}\n${USAGE}`);
    return { exitCode: 1, error: err.message };
  }

  if (values.help) {
    console.log(USAGE);
    return { exitCode: 0, help: true };
  }

  // 1. Positive isolation target container verification
  let verifiedTarget;
  try {
    verifiedTarget = verifyTargetContainer(
      {
        container: values.container,
        nonce: values.nonce,
        database: values.database,
        user: values.user
      },
      run
    );
  } catch (err) {
    console.error(`[FAIL-CLOSED] Target verification rejected: ${err.message}`);
    return { exitCode: 1, error: err.message };
  }

  console.log(`[VERIFIED] Target container: ${verifiedTarget.containerId.slice(0, 12)} (${verifiedTarget.image}) db=${verifiedTarget.database} user=${verifiedTarget.username}`);

  // 2. Check mode: verify presence of synthetic data without applying mutations
  if (values.check) {
    const checkSql = `
      SELECT json_build_object(
        'bot_count', (SELECT count(*) FROM bot WHERE id = 'bot-general'),
        'conv_count', (SELECT count(*) FROM conversation WHERE id = 'seed-conv-completed-01'),
        'run_count', (SELECT count(*) FROM chat_run WHERE id = 'seed-run-completed-01'),
        'msg_count', (SELECT count(*) FROM message WHERE conversation_id = 'seed-conv-completed-01'),
        'attempt_count', (SELECT count(*) FROM provider_attempt WHERE id = 'seed-usage-attempt-01' AND simulation = TRUE)
      )::text;
    `;
    try {
      const output = executeSyntheticSql(verifiedTarget, checkSql, run);
      console.log(`[CHECK] Synthetic data status:\n${output}`);
      return { exitCode: 0, checkOutput: output, target: verifiedTarget };
    } catch (err) {
      console.error(`[ERROR] Failed to query synthetic status: ${err.message}`);
      return { exitCode: 1, error: err.message };
    }
  }

  // 3. Dry-run mode: verify target without executing mutations
  if (values['dry-run']) {
    console.log(`[DRY-RUN] Target successfully verified. Positive isolation confirmed. Mutations skipped.`);
    return { exitCode: 0, dryRun: true, target: verifiedTarget };
  }

  // 4. Load checked-in SQL file only (no arbitrary file parameter)
  let sqlContent;
  try {
    sqlContent = await readFile(CHECKED_IN_SQL_FILE, 'utf8');
  } catch (err) {
    console.error(`[ERROR] Failed to read checked-in SQL fixture file at "${CHECKED_IN_SQL_FILE}": ${err.message}`);
    return { exitCode: 1, error: err.message };
  }

  // 5. Execute checked-in SQL against positively verified container
  try {
    const result = executeSyntheticSql(verifiedTarget, sqlContent, run);
    console.log(`[SUCCESS] Minimal synthetic demonstration data applied deterministically.`);
    if (result && result.trim()) {
      console.log(result.trim());
    }
    return { exitCode: 0, success: true, target: verifiedTarget, result };
  } catch (err) {
    console.error(`[ERROR] Execution failed: ${err.message}`);
    return { exitCode: 1, error: err.message };
  }
}

export function isMainExecution(argv1 = process.argv[1], currentFile = __filename) {
  if (!argv1) return false;
  try {
    return realpathSync(argv1) === realpathSync(currentFile);
  } catch {
    try {
      const realDir1 = realpathSync(dirname(argv1));
      const realDir2 = realpathSync(dirname(currentFile));
      return resolve(realDir1, basename(argv1)) === resolve(realDir2, basename(currentFile));
    } catch {
      return resolve(argv1) === resolve(currentFile);
    }
  }
}

// Direct execution guard (handles macOS /tmp -> /private/tmp symlink resolution)
if (isMainExecution(process.argv[1], __filename)) {
  const { exitCode } = await runCli(process.argv.slice(2));
  process.exit(exitCode);
}
