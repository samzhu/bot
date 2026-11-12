import { test, describe, it, before, after, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {
  installNodeFetchGuard,
  uninstallNodeFetchGuard,
  isGenerationRequest,
  extractUrlAndMethod,
  assertSafeBackendOrigin,
  createSyntheticSseResponse,
  getGuardStats,
  resetGuardStats
} from './verification-harness.mjs';

describe('Verification Guard Harness (Issue #14)', () => {
  let trapServer;
  let trapPort;
  let trapUrl;
  let trapReceivedRequests = [];
  let verificationMarkerStatus = 200;
  let verificationMarkerBody = { status: 'SAFE_MOCK', mock: true, modelClass: 'MockChatModel' };

  before(async () => {
    // Spin up an ephemeral local HTTP trap server on a random port
    trapServer = http.createServer((req, res) => {
      let body = '';
      req.on('data', chunk => { body += chunk; });
      req.on('end', () => {
        trapReceivedRequests.push({
          method: req.method,
          url: req.url,
          headers: req.headers,
          body
        });

        if (req.url === '/api/internal/verification/status') {
          res.writeHead(verificationMarkerStatus, { 'Content-Type': 'application/json' });
          if (verificationMarkerStatus === 200) {
            res.end(JSON.stringify(verificationMarkerBody));
          } else {
            res.end(JSON.stringify({ error: 'Not Found / Real AI' }));
          }
          return;
        }

        if (req.url.includes('/runs')) {
          res.writeHead(200, { 'Content-Type': 'text/event-stream' });
          res.end('event: run.started\ndata: {"seq":1}\n\n');
        } else {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ data: { ok: true } }));
        }
      });
    });

    await new Promise(resolve => trapServer.listen(0, '127.0.0.1', resolve));
    trapPort = trapServer.address().port;
    trapUrl = `http://127.0.0.1:${trapPort}`;
  });

  after(async () => {
    if (trapServer) {
      await new Promise(resolve => trapServer.close(resolve));
    }
  });

  beforeEach(() => {
    trapReceivedRequests = [];
    verificationMarkerStatus = 200;
    verificationMarkerBody = { status: 'SAFE_MOCK', mock: true, modelClass: 'MockChatModel' };
    resetGuardStats();
    uninstallNodeFetchGuard();
  });

  it('AG-UI trap sees unguarded POST but default guard blocks it', async () => {
    const endpoint = `${trapUrl}/api/ag-ui?test=1`;
    await fetch(endpoint, { method: 'POST', body: '{}' });
    assert.equal(trapReceivedRequests.filter(r => r.url.startsWith('/api/ag-ui')).length, 1);
    trapReceivedRequests = [];
    installNodeFetchGuard();
    await assert.rejects(fetch(new Request(endpoint, { method: 'POST', body: '{}' })), /PAID_MODEL_BLOCKED/);
    assert.equal(trapReceivedRequests.filter(r => r.url.startsWith('/api/ag-ui')).length, 0);
  });

  it('AG-UI Node mock emits standard events and does not call trap', async () => {
    installNodeFetchGuard({ mode: 'mock' });
    const input = { threadId: 'thread-node', runId: 'run-node', messages: [{ id: 'user-node', role: 'user', content: 'hello' }] };
    const response = await fetch(new Request(`${trapUrl}/api/ag-ui?mock=1`, { method: 'POST', body: JSON.stringify(input) }));
    const events = (await response.text()).split('\n').filter(line => line.startsWith('data: ')).map(line => JSON.parse(line.slice(6)));
    assert.deepEqual(events.map(e => e.type), ['RUN_STARTED', 'TEXT_MESSAGE_START', 'TEXT_MESSAGE_CONTENT', 'TEXT_MESSAGE_END', 'RUN_FINISHED']);
    assert.equal(events[0].threadId, input.threadId);
    assert.equal(events[0].runId, input.runId);
    assert.equal(events[1].messageId, events[2].messageId);
    assert.equal(events[1].messageId, events[3].messageId);
    assert.equal(trapReceivedRequests.filter(r => r.url.startsWith('/api/ag-ui')).length, 0);
  });

  it('AG-UI verified mode rejects invalid status without generation traffic', async () => {
    verificationMarkerBody.status = 'UNSAFE';
    installNodeFetchGuard({ mode: 'verified-backend' });
    await assert.rejects(fetch(`${trapUrl}/api/ag-ui`, { method: 'POST', body: '{}' }), /PAID_MODEL_BLOCKED/);
    assert.equal(trapReceivedRequests.filter(r => r.url.startsWith('/api/ag-ui')).length, 0);
  });

  it('RED STEP PROOF: Unguarded Node fetch leaks live generation to backend trap', async () => {
    // An unguarded request to /api/conversations/*/runs reaches the trap server
    const targetUrl = `${trapUrl}/api/conversations/test-conv/runs`;
    const res = await fetch(targetUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: 'Live leak prompt' })
    });

    assert.equal(res.status, 200);
    // Authoritative check: trap server received count
    const generationRequests = trapReceivedRequests.filter(r => r.url.includes('/runs'));
    assert.equal(generationRequests.length, 1, 'Trap server must receive exactly 1 request when unguarded');
    assert.match(generationRequests[0].url, /\/api\/conversations\/test-conv\/runs/);
    assert.match(generationRequests[0].body, /Live leak prompt/);
  });

  it('GREEN STEP: Guard in fail-closed mode blocks Node fetch generation before reaching network (0 trap calls)', async () => {
    installNodeFetchGuard({ mode: 'fail-closed' });

    const targetUrl = `${trapUrl}/api/conversations/test-conv/runs`;

    await assert.rejects(
      async () => {
        await fetch(targetUrl, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ message: 'Should be blocked' })
        });
      },
      err => {
        assert.match(err.message, /PAID_MODEL_BLOCKED/);
        return true;
      }
    );

    // Authoritative proof: Trap server received ZERO generation requests
    const generationRequests = trapReceivedRequests.filter(r => r.url.includes('/runs'));
    assert.equal(generationRequests.length, 0, 'Trap server must receive ZERO generation requests when guarded in fail-closed mode');
    const stats = getGuardStats();
    assert.equal(stats.blockedCalls, 1);
  });

  it('GREEN STEP: Guard in mock mode provides synthetic SSE without touching network (0 trap calls)', async () => {
    installNodeFetchGuard({ mode: 'mock' });

    const targetUrl = `${trapUrl}/api/conversations/test-conv/runs`;
    const res = await fetch(targetUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: 'Mock me' })
    });

    assert.equal(res.status, 200);
    assert.match(res.headers.get('content-type'), /text\/event-stream/);
    const text = await res.text();
    assert.match(text, /event: run\.started/);
    assert.match(text, /event: message\.delta/);
    assert.match(text, /event: run\.completed/);

    // Authoritative proof: Trap server received ZERO generation requests
    const generationRequests = trapReceivedRequests.filter(r => r.url.includes('/runs'));
    assert.equal(generationRequests.length, 0, 'Trap server must receive ZERO requests in mock mode');
    const stats = getGuardStats();
    assert.equal(stats.mockedCalls, 1);
  });

  it('GREEN STEP: Fetch input normalization handles Request objects, URL objects, and init method overrides', async () => {
    installNodeFetchGuard({ mode: 'fail-closed' });

    const runEndpoint = `${trapUrl}/api/conversations/test-conv/runs`;

    // 1. fetch(new Request(url, { method: 'POST' }))
    await assert.rejects(
      async () => {
        const req = new Request(runEndpoint, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ message: 'Request object' })
        });
        await fetch(req);
      },
      err => {
        assert.match(err.message, /PAID_MODEL_BLOCKED/);
        return true;
      }
    );

    // 2. fetch(new URL(url), { method: 'POST' })
    await assert.rejects(
      async () => {
        await fetch(new URL(runEndpoint), {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ message: 'URL object' })
        });
      },
      err => {
        assert.match(err.message, /PAID_MODEL_BLOCKED/);
        return true;
      }
    );

    // 3. fetch(new Request(url, { method: 'GET' }), { method: 'POST' }) - init overrides Request method
    await assert.rejects(
      async () => {
        const req = new Request(runEndpoint, { method: 'GET' });
        await fetch(req, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ message: 'Init override' })
        });
      },
      err => {
        assert.match(err.message, /PAID_MODEL_BLOCKED/);
        return true;
      }
    );

    // Authoritative proof: ZERO generation requests reached trap server
    const generationRequests = trapReceivedRequests.filter(r => r.url.includes('/runs'));
    assert.equal(generationRequests.length, 0, 'Trap server must receive ZERO generation requests across all fetch input shapes');
    assert.equal(getGuardStats().blockedCalls, 3);
  });

  it('GREEN STEP: Unknown guard mode fails closed immediately without leaking (0 trap calls)', async () => {
    installNodeFetchGuard({ mode: 'unrecognized-mode-xyz' });

    const targetUrl = `${trapUrl}/api/conversations/test-conv/runs`;

    await assert.rejects(
      async () => {
        await fetch(targetUrl, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ message: 'Unknown mode probe' })
        });
      },
      err => {
        assert.match(err.message, /Unknown verification guard mode/);
        return true;
      }
    );

    const generationRequests = trapReceivedRequests.filter(r => r.url.includes('/runs'));
    assert.equal(generationRequests.length, 0, 'Trap server must receive ZERO requests when unknown mode fails closed');
  });

  it('GREEN STEP: allowNonGeneration accurately permits non-generation calls (queries/mutations) and blocks when false', async () => {
    installNodeFetchGuard({ mode: 'fail-closed', allowNonGeneration: true });

    // 1. GraphQL Mutation (non-generation) is permitted
    const mutationRes = await fetch(`${trapUrl}/graphql`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: 'mutation { createConversation(botId: "bot-general") { id } }' })
    });
    assert.equal(mutationRes.status, 200);
    assert.equal(trapReceivedRequests.length, 1);
    assert.match(trapReceivedRequests[0].body, /createConversation/);

    // 2. Generation attempt in same session is immediately blocked
    await assert.rejects(
      async () => {
        await fetch(`${trapUrl}/api/conversations/conv-1/runs`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ message: 'Blocked generation' })
        });
      },
      err => {
        assert.match(err.message, /PAID_MODEL_BLOCKED/);
        return true;
      }
    );

    // 3. Strict offline mode (allowNonGeneration: false) blocks non-generation calls too
    installNodeFetchGuard({ mode: 'fail-closed', allowNonGeneration: false });
    await assert.rejects(
      async () => {
        await fetch(`${trapUrl}/graphql`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ query: 'query { bots { id } }' })
        });
      },
      err => {
        assert.match(err.message, /UNMOCKED_NETWORK_CALL/);
        return true;
      }
    );

    // Authoritative proof: Generation requests reached trap server is strictly ZERO
    const generationRequests = trapReceivedRequests.filter(r => r.url.includes('/runs'));
    assert.equal(generationRequests.length, 0);
  });

  it('GREEN STEP: verified-backend mode permits generation only on verified mock backend, blocks on real-ai (404)', async () => {
    installNodeFetchGuard({ mode: 'verified-backend' });

    // Case 1: Backend is verified safe mock -> generation allowed
    verificationMarkerStatus = 200;
    verificationMarkerBody = { status: 'SAFE_MOCK', mock: true, modelClass: 'MockChatModel' };

    const validRes = await fetch(`${trapUrl}/api/conversations/conv-safe/runs`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: 'Safe prompt to verified mock backend' })
    });
    assert.equal(validRes.status, 200);

    // Trap server received the verification check, then the generation run
    assert.equal(trapReceivedRequests.some(r => r.url === '/api/internal/verification/status'), true);
    assert.equal(trapReceivedRequests.some(r => r.url === '/api/conversations/conv-safe/runs'), true);

    // Reset trap history
    trapReceivedRequests = [];

    // Case 2: Backend returns 404 (simulating real-ai active or production JAR without endpoint)
    verificationMarkerStatus = 404;

    await assert.rejects(
      async () => {
        await fetch(`${trapUrl}/api/conversations/conv-real/runs`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ message: 'Unsafe prompt targeting real-ai backend' })
        });
      },
      err => {
        assert.match(err.message, /PAID_MODEL_BLOCKED/);
        assert.match(err.message, /NOT a verified safe mock backend/);
        return true;
      }
    );

    // Authoritative proof: In Case 2, ZERO generation requests reached the backend!
    const unsafeGenRequests = trapReceivedRequests.filter(r => r.url === '/api/conversations/conv-real/runs');
    assert.equal(unsafeGenRequests.length, 0, 'Unverified / real-ai backend must NEVER receive generation runs');
  });

  it('GREEN STEP: Request matcher and extractor accurately identify generation endpoints across shapes', () => {
    assert.equal(isGenerationRequest('http://localhost:8080/api/ag-ui', 'POST'), true);
    assert.equal(isGenerationRequest('/api/ag-ui?source=test', 'POST'), true);
    assert.equal(isGenerationRequest('/api/ag-ui', 'GET'), false);
    assert.equal(isGenerationRequest('http://localhost:8080/api/conversations/123/runs', 'POST'), true);
    assert.equal(isGenerationRequest('http://127.0.0.1:4200/api/conversations/abc-def/runs', 'POST'), true);
    assert.equal(isGenerationRequest('/api/conversations/conv-1/runs', 'POST'), true);

    // Non-generation requests must return false
    assert.equal(isGenerationRequest('http://localhost:8080/api/conversations/123/runs', 'GET'), false);
    assert.equal(isGenerationRequest('http://localhost:8080/graphql', 'POST'), false);
    assert.equal(isGenerationRequest('http://localhost:8080/api/runs/123/cancel', 'POST'), false);

    // extractUrlAndMethod verification
    const reqObj = new Request('http://localhost:8080/api/conversations/c1/runs', { method: 'POST' });
    const extracted1 = extractUrlAndMethod(reqObj);
    assert.equal(extracted1.url, 'http://localhost:8080/api/conversations/c1/runs');
    assert.equal(extracted1.method, 'POST');

    const extracted2 = extractUrlAndMethod(new URL('http://localhost:8080/graphql'), { method: 'post' });
    assert.equal(extracted2.url, 'http://localhost:8080/graphql');
    assert.equal(extracted2.method, 'POST');
  });

  it('GREEN STEP: Synthetic SSE frames use valid JSON.stringify and metadata is consistent', async () => {
    const res = createSyntheticSseResponse({
      runId: 'run-test-json',
      userMessageId: 'user-msg-json',
      assistantMessageId: 'assistant-msg-json',
      deltaText: 'Special quotes "hello" and \n newlines'
    });

    const text = await res.text();
    const dataLines = text.split('\n').filter(l => l.startsWith('data: ')).map(l => l.substring(6));
    assert.equal(dataLines.length, 3);

    const frame1 = JSON.parse(dataLines[0]);
    assert.equal(frame1.runId, 'run-test-json');
    assert.equal(frame1.userMessageId, 'user-msg-json');
    assert.equal(frame1.status, 'RUNNING');

    const frame2 = JSON.parse(dataLines[1]);
    assert.equal(frame2.runId, 'run-test-json');
    assert.equal(frame2.delta, 'Special quotes "hello" and \n newlines');

    const frame3 = JSON.parse(dataLines[2]);
    assert.equal(frame3.runId, 'run-test-json');
    assert.equal(frame3.messageId, 'assistant-msg-json');
    assert.equal(frame3.status, 'COMPLETED');
  });
});


test('AG-UI matcher normalizes paths and excludes unrelated routes', () => {
  assert.equal(isGenerationRequest('http://localhost/a/../api/ag-ui?x=1#part', 'POST'), true);
  assert.equal(isGenerationRequest('/other/api/ag-ui', 'POST'), false);
  assert.equal(isGenerationRequest('/api/ag-ui#fragment', 'GET'), false);
  assert.equal(isGenerationRequest('/api/runs/x/cancel', 'POST'), false);
});

test('safe backend requires all three markers', async () => {
  for (const body of [
    { status: 'UNSAFE', mock: true, modelClass: 'MockChatModel' },
    { status: 'SAFE_MOCK', mock: false, modelClass: 'MockChatModel' },
    { status: 'SAFE_MOCK', mock: true, modelClass: 'RealModel' }
  ]) {
    await assert.rejects(assertSafeBackendOrigin('http://localhost:1', async () => new Response(JSON.stringify(body), { status: 200 })), /PAID_MODEL_BLOCKED/);
  }
  assert.equal(await assertSafeBackendOrigin('http://localhost:1', async () => new Response(JSON.stringify({ status: 'SAFE_MOCK', mock: true, modelClass: 'MockChatModel' }), { status: 200 })), true);
});
