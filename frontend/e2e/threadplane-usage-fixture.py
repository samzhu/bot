#!/usr/bin/env python3
"""Guarded fixture setup and temporary revocation for the dedicated #40 Usage database.

Required: USAGE_E2E_CONTAINER (name), USAGE_E2E_CONTAINER_ID (full 64-char ID),
USAGE_E2E_DB_USER. The container must have io.bot.task=issue40-usage-e2e and use
our fixed PG18/pgvector digest. The dedicated bot40_usage_e2e database and its
marked active synthetic workspace/member must already exist. Never points at a
host/port or uses a fallback database. Commands: setup, status, inactive -- CMD.
"""
import fcntl
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys

DATABASE = 'bot40_usage_e2e'
WORKSPACE = '32000000-0000-4000-8000-000000000002'
MARKER = 'ISSUE40 synthetic usage workspace'
IMAGE = 'pgvector/pgvector@sha256:5895949dfcdf308eb0cd31c950c93c14895c77f33fbedfecf1fa511ab1d4bcd2'

def run(args, **kw):
    return subprocess.run(args, check=True, text=True, **kw)

def connection():
    name = os.environ.get('USAGE_E2E_CONTAINER', '')
    expected = os.environ.get('USAGE_E2E_CONTAINER_ID', '')
    user = os.environ.get('USAGE_E2E_DB_USER', '')
    if not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}', name) or not re.fullmatch(r'[0-9a-f]{64}', expected) or not re.fullmatch(r'[a-zA-Z_][a-zA-Z0-9_]{0,62}', user):
        raise RuntimeError('Explicit container name, full container ID and DB user are required')
    item = json.loads(run(['docker', 'inspect', name], capture_output=True).stdout)[0]
    if (item['Id'] != expected or item['Config']['Image'] != IMAGE or
            item['Config'].get('Labels', {}).get('io.bot.task') != 'issue40-usage-e2e' or
            not item['State']['Running']):
        raise RuntimeError('Dedicated task container identity, image, label or state mismatch')
    return name, user

def sql(query):
    name, user = connection()  # Recheck identity before every invocation.
    return run(['docker', 'exec', '-i', name, 'psql', '-X', '-U', user, '-d', DATABASE,
                '-v', 'ON_ERROR_STOP=1', '-At'], input=query, capture_output=True).stdout.strip()

def status():
    out = sql("SELECT current_database() || '|' || w.name || '|' || m.active FROM workspace w JOIN workspace_member m ON m.workspace_id=w.id WHERE w.id='" + WORKSPACE + "' AND m.user_id='dev-user';")
    valid = (DATABASE + '|' + MARKER + '|true', DATABASE + '|' + MARKER + '|false')
    if out not in valid:
        raise RuntimeError('Dedicated synthetic workspace or membership not present')
    return out == valid[0]

def set_active(value):
    out = sql("UPDATE workspace_member m SET active=" + ('true' if value else 'false') +
              " FROM workspace w WHERE current_database()='" + DATABASE + "' AND w.id='" + WORKSPACE +
              "' AND w.name='" + MARKER + "' AND m.workspace_id=w.id AND m.user_id='dev-user' RETURNING m.workspace_id;")
    if out.splitlines() != [WORKSPACE, 'UPDATE 1'] or status() != value:
        raise RuntimeError('Unexpected membership update result')

def interrupt(signum, _frame):
    raise KeyboardInterrupt('signal ' + str(signum))

def main():
    # Lock is scoped by full container ID so simultaneous runners cannot restore each other.
    _, _ = connection()
    lock_path = '/tmp/usage-e2e-' + os.environ['USAGE_E2E_CONTAINER_ID'] + '.lock'
    with open(lock_path, 'w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        args = sys.argv[1:]
        if args == ['status']:
            print('active=' + str(status()).lower(), flush=True)
        elif args == ['setup']:
            if not status():
                raise RuntimeError('Fixture membership must be active')
            sql(Path(__file__).with_name('threadplane-usage-fixture.sql').read_text())
            print('synthetic simulation fixture ready', flush=True)
        elif len(args) >= 3 and args[:2] == ['inactive', '--']:
            if not status():
                raise RuntimeError('Membership already inactive; refusing to change')
            signal.signal(signal.SIGTERM, interrupt)
            try:
                set_active(False)
                print('membership inactive', flush=True)
                result = subprocess.run(args[2:])
            finally:
                set_active(True)
                print('membership restored active=true', flush=True)
            sys.exit(result.returncode)
        else:
            raise SystemExit('usage: threadplane-usage-fixture.py setup | status | inactive -- COMMAND')

if __name__ == '__main__':
    main()
