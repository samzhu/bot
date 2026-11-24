import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { startVerification } from '../e2e/threadplane-support.mjs';

test('session rejects an externally supplied backend before any request', async () => {
  let requests = 0;
  const spy = createServer((_req, res) => { requests++; res.writeHead(404).end(); });
  spy.listen(0, '127.0.0.1'); await once(spy, 'listening');
  const previous = process.env.BACKEND_URL;
  process.env.BACKEND_URL = `http://127.0.0.1:${spy.address().port}`;
  try {
    await assert.rejects(startVerification('external-origin'));
    assert.equal(requests, 0, 'external backend must receive no preflight or generation requests');
  } finally {
    if (previous === undefined) delete process.env.BACKEND_URL; else process.env.BACKEND_URL = previous;
    spy.closeAllConnections(); await new Promise(resolve => spy.close(resolve));
  }
});
