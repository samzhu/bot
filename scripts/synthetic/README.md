# Standalone Synthetic Demonstration Data Script

Part of **Issue #76 (Twelve-Factor Database Provisioning, Run Recovery & Synthetic Data)**: Phase 3 Synthetic Data Script Slice.

## Purpose & 12-Factor Boundaries

In accordance with Twelve-Factor App principles (Factor X: Dev/Prod Parity, Factor V: Build/Release/Run, and Factor XII: Admin Processes):
- **Out of Production Runtime & Image**: This script and SQL fixture are completely excluded from `src/main/resources` and the production OCI container image.
- **Independent Invocation**: Demonstrations or development data setup are explicitly executed by developers or tests, not during normal application boot or startup initialization.
- **Release-Owned Schema & Default Bot**: Schema migrations (V1-V15) and default Bot (`bot-general`) provisioning are owned exclusively by the release provisioning step. The synthetic script strictly checks their presence; it performs zero DDL and inserts no default or extra remote bots.
- **Framework Ownership**: Spring AI's session tables (`ai_session`, `ai_session_index`, `ai_session_event`) are framework-owned; the synthetic script does not write raw SQL to them.
- **Single Checked-in Fixture**: Arbitrary SQL file arguments (`--sql-file`) are rejected. Only the version-controlled `synthetic-demo.sql` is permitted.
- **Public-First / Simulation**: Operates without invoking live AI models or external APIs; all provider attempts are marked with `simulation = TRUE`, `provider = 'FAKE'`, and `model = 'synthetic-demo'`.

## Positive Isolation & Hardened Rails

Before issuing any SQL statement, the runner performs positive isolation inspection on the target:
1. **Full 64-Hex Container ID**: Rejects any shorthand or non-64-hex container identifier (`/^[0-9a-f]{64}$/`).
2. **Dedicated Cryptographic Nonce**: Requires an explicit 32-hex nonce (`/^[0-9a-f]{32}$/`) matching the container's test-purpose label (`io.github.samzhu.bot.browser-fixture.nonce`).
3. **Log Hygiene**: Nonce is strictly verified in memory and never revealed in logs or stdout.
4. **Explicit Database and User**: Rejects defaults (`postgres`/`test`). The caller must explicitly pass `--database` and `--user`, which are strictly validated against identifier rules.
5. **Exact Image Verification**: Matches only the approved test image `pgvector/pgvector:pg16` (rejects generic prefixes or unrelated PostgreSQL tags).
6. **Pre-flight Release Boundary Check**:
   - Verifies tables exist (`conversation`, `bot`, `chat_run`, `message`, `workspace`, `workspace_member`, `provider_attempt`, `usage_snapshot`).
   - Verifies `bot-general` was provisioned by the release step.
7. **Workspace Isolation & Positive Payload Proving**:
   - Uses dedicated workspace ID `seed-workspace-demo-01` (`Synthetic Demonstration Workspace`).
   - Rejects if `dev-user` already owns an existing non-fixture personal workspace.
   - For all natural/fixed IDs (`seed-conv-completed-01`, `seed-run-completed-01`, `seed-usage-attempt-01`, `seed-usage-snapshot-01`), pre-flight checks prove exact synthetic payload match. Any mismatch aborts with `RAISE EXCEPTION` in the same transaction (preventing silent `ON CONFLICT` collision masking).
8. **Non-Destructive**: Contains zero `DROP`, `TRUNCATE`, or unscoped `DELETE` statements.

## Seeded Scope (Minimal Representative Dataset)

- **Dedicated Synthetic Workspace & Member**:
  - Workspace: `seed-workspace-demo-01` (`Synthetic Demonstration Workspace`, personal owner: `dev-user`).
  - Member: `seed-workspace-demo-01`, `dev-user`, `MANAGER`, active.
- **Representative Conversation & Run**:
  - `seed-conv-completed-01` (`示範：成功問答 (COMPLETED)`, pinned)
  - `seed-run-completed-01` (`COMPLETED`, local execution mode)
  - Messages: `seed-msg-completed-user-01` (user query), `seed-msg-completed-assistant-01` (Spring AI markdown with code block)
- **Usage Ledger (Simulation Proof)**:
  - `provider_attempt`: `seed-usage-attempt-01` (`simulation = TRUE`, `provider = 'FAKE'`, `model = 'synthetic-demo'`)
  - `usage_snapshot`: `seed-usage-snapshot-01` (`source_layer = 'FAKE_RAW'`, `capture_completeness = 'COMPLETE'`)
  - Linked projection state: `VALID`.

## Usage

```bash
# 1. View options and usage help
node scripts/synthetic/synthetic-demo.mjs --help

# 2. Dry-run: verify positive isolation without executing mutations
node scripts/synthetic/synthetic-demo.mjs \
  --container <64-hex-container-id> \
  --nonce <32-hex-nonce> \
  --database <db-name> \
  --user <username> \
  --dry-run

# 3. Apply synthetic demonstration data to the verified container
node scripts/synthetic/synthetic-demo.mjs \
  --container <64-hex-container-id> \
  --nonce <32-hex-nonce> \
  --database <db-name> \
  --user <username>

# 4. Check existing synthetic data status without mutating
node scripts/synthetic/synthetic-demo.mjs \
  --container <64-hex-container-id> \
  --nonce <32-hex-nonce> \
  --database <db-name> \
  --user <username> \
  --check

## Verification Gate Harness (/tmp/synthetic-demo-compare.mjs)

For gate verification of full database immutability and unrelated row invariance across execution runs, the temporary verification driver is kept in `/tmp/synthetic-demo-compare.mjs`:

```bash
# Take baseline snapshot before apply
node /tmp/synthetic-demo-compare.mjs \
  --container <64-hex-container-id> \
  --nonce <32-hex-nonce> \
  --database <db-name> \
  --user <username> \
  --save /tmp/snap-before.json

# After Run 1 (Apply), verify unrelated rows are 100% frozen and demo subset populated
node /tmp/synthetic-demo-compare.mjs \
  --container <64-hex-container-id> \
  --nonce <32-hex-nonce> \
  --database <db-name> \
  --user <username> \
  --compare /tmp/snap-before.json \
  --mode apply \
  --save /tmp/snap-run1.json

# After Run 2 (Repeat), verify 100% full-database immutability
node /tmp/synthetic-demo-compare.mjs \
  --container <64-hex-container-id> \
  --nonce <32-hex-nonce> \
  --database <db-name> \
  --user <username> \
  --compare /tmp/snap-run1.json \
  --mode repeat
```
  --nonce <32-hex-nonce> \
  --database <db-name> \
  --user <username> \
  --compare /tmp/snap-run1.json \
  --mode repeat
```
```

### Running Unit Tests

Run the test suite using native `node:test` (no Docker required):

```bash
node --test scripts/synthetic/synthetic-demo.test.mjs
```
