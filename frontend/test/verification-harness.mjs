/**
 * Verification Guard Harness (Issue #14)
 *
 * Implements strict safety-by-default execution boundaries across:
 * 1. Node.js direct fetch calls (preventing CLI/unit/integration script leaks)
 * 2. Puppeteer browser in-page requests (preventing live UI send leaks)
 *
 * Guarantees zero un-opted paid model invocations during all ordinary verification.
 * Ordinary verification scripts have NO bypass flags or live paid model opt-ins.
 * Live paid smoke testing is reserved exclusively for backend RealModelSmokeIntegrationTest.
 */

const guardStats = {
  blockedCalls: 0,
  mockedCalls: 0,
  allowedNonGenerationCalls: 0
};

let originalNodeFetch = null;

export function getGuardStats() {
  return { ...guardStats };
}

export function resetGuardStats() {
  guardStats.blockedCalls = 0;
  guardStats.mockedCalls = 0;
  guardStats.allowedNonGenerationCalls = 0;
}

/**
 * Normalizes any fetch input (string, URL, or Request) and init options
 * into an absolute URL string and uppercase HTTP method.
 */
export function extractUrlAndMethod(input, init = {}) {
  let url = '';
  let method = 'GET';

  if (typeof input === 'string') {
    url = input;
  } else if (input instanceof URL) {
    url = input.toString();
  } else if (input && typeof input === 'object') {
    if ('url' in input && typeof input.url === 'string') {
      url = input.url;
    } else {
      url = String(input);
    }
    if ('method' in input && typeof input.method === 'string') {
      method = input.method;
    }
  }

  // init.method overrides input.method (conforming to Fetch API standard)
  if (init && init.method && typeof init.method === 'string') {
    method = init.method;
  }

  return { url, method: method.toUpperCase() };
}

/**
 * Deterministic matcher for generation endpoints.
 * Accepts (url, method) or (input, init).
 */
export function isGenerationRequest(input, initOrMethod = 'GET') {
  if (!input) return false;

  let url = '';
  let method = 'GET';

  if (typeof initOrMethod === 'string') {
    url = input instanceof URL ? input.toString() : (typeof input === 'object' && input.url ? input.url : String(input));
    method = initOrMethod;
  } else {
    const extracted = extractUrlAndMethod(input, initOrMethod || {});
    url = extracted.url;
    method = extracted.method;
  }

  if (method.toUpperCase() !== 'POST') return false;

  try {
    const cleanUrl = url.split('?')[0].split('#')[0];
    return /\/api\/conversations\/[^/]+\/runs$/.test(cleanUrl);
  } catch (_) {
    return false;
  }
}

/**
 * Creates a compliant synthetic SSE Response conforming to Spring AI / Herdr SSE wire contract.
 */
export function createSyntheticSseResponse(options = {}) {
  const runId = options.runId || `run-mock-${Date.now()}`;
  const userMessageId = options.userMessageId || `msg-user-${Date.now()}`;
  const assistantMessageId = options.assistantMessageId || `msg-assistant-${Date.now()}`;
  const deltaText = options.deltaText || 'Synthetic verification response from safe test harness.';

  const sseBody = [
    'event: run.started',
    `data: ${JSON.stringify({ seq: 1, runId, status: 'RUNNING', userMessageId })}`,
    '',
    'event: message.delta',
    `data: ${JSON.stringify({ seq: 2, runId, delta: deltaText })}`,
    '',
    'event: run.completed',
    `data: ${JSON.stringify({ seq: 3, runId, status: 'COMPLETED', messageId: assistantMessageId })}`,
    '',
    ''
  ].join('\n');

  return new Response(sseBody, {
    status: 200,
    headers: {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
      'X-Verification-Guard': 'MOCK_SYNTHETIC'
    }
  });
}

/**
 * Asserts that the target backend origin is running in safe mock mode (MockChatModel)
 * by querying the local-only capability endpoint:
 *   GET /api/internal/verification/status
 *
 * This endpoint is isolated in the backend's 'localDevelopment' sourceSet (never packaged in production)
 * and returns HTTP 200 only when:
 * 1. Profile includes 'local'
 * 2. Profile does NOT include 'real-ai'
 * 3. ChatModel bean is genuinely MockChatModel
 *
 * Always revalidates in real-time without process-global permanent trust caching,
 * ensuring any negative transition or configuration switch is reliably detected.
 *
 * If the target backend returns 404 or any non-mock marker (e.g. running real-ai),
 * throws [PAID_MODEL_BLOCKED] to abort generation before any network traffic is sent.
 */
export async function assertSafeBackendOrigin(baseUrl, fetchFn = originalNodeFetch || globalThis.fetch) {
  let origin;
  try {
    origin = new URL(baseUrl).origin;
  } catch (err) {
    throw new Error(`[PAID_MODEL_BLOCKED] Invalid backend URL: ${baseUrl}`);
  }

  const statusUrl = `${origin}/api/internal/verification/status`;

  let response;
  try {
    response = await fetchFn(statusUrl, {
      method: 'GET',
      headers: { 'Accept': 'application/json' }
    });
  } catch (err) {
    throw new Error(`[PAID_MODEL_BLOCKED] Unable to reach verification endpoint at ${statusUrl}: ${err.message}`);
  }

  if (response.status !== 200) {
    throw new Error(`[PAID_MODEL_BLOCKED] Target backend at ${origin} is NOT a verified safe mock backend (HTTP ${response.status}). Ordinary verification blocked from dispatching generation requests.`);
  }

  let body;
  try {
    body = await response.json();
  } catch (err) {
    throw new Error(`[PAID_MODEL_BLOCKED] Invalid JSON from verification status at ${statusUrl}`);
  }

  if (!body || body.mock !== true || body.modelClass !== 'MockChatModel') {
    throw new Error(`[PAID_MODEL_BLOCKED] Target backend at ${origin} returned unverified marker: ${JSON.stringify(body)}. Expected mock=true and modelClass=MockChatModel.`);
  }

  return true;
}

/**
 * Resolves non-secret verification origins.
 * Allows pointing ordinary verification to isolated test instances (e.g. port 4300/8181)
 * while defaulting to 4200/8080 (which immediately fail-closed if 8080 is running real-ai).
 */
export function getVerificationOrigins() {
  const frontendUrl = process.env.VERIFICATION_FRONTEND_URL || 'http://localhost:4200';
  const backendUrl = process.env.VERIFICATION_BACKEND_URL || 'http://localhost:8080';
  return { frontendUrl, backendUrl };
}

/**
 * Preflight gate check executed at the very entry of every verification script
 * BEFORE creating any conversation, dispatching any run, or launching long UI timeouts.
 *
 * If target backend is NOT a verified mock backend (e.g. running real-ai yielding 404),
 * immediately logs a clean diagnostic and terminates with exit code 1 (no UI timeout waiting).
 */
export async function assertSafeExecutionEnvironment(backendUrl = getVerificationOrigins().backendUrl) {
  try {
    await assertSafeBackendOrigin(backendUrl);
    console.log(`[VerificationPreflight] Backend at ${backendUrl} confirmed safe mock: HTTP 200 (MockChatModel).`);
    return true;
  } catch (err) {
    console.error(`\n❌ [SECURITY PREFLIGHT FAILED] Target backend at ${backendUrl} is NOT a verified mock backend!`);
    console.error(err.message);
    console.error(`Ordinary verification blocked from running against unsafe/paid models.`);
    process.exit(1);
  }
}

/**
 * Installs a transport-level guard on Node.js globalThis.fetch.
 *
 * Options:
 * - mode: 'mock' (default) | 'fail-closed' | 'verified-backend'
 * - allowNonGeneration: boolean (default true) - allows non-generation HTTP calls (e.g. GraphQL queries/mutations, seed checks)
 */
export function installNodeFetchGuard(options = {}) {
  if (!originalNodeFetch) {
    originalNodeFetch = globalThis.fetch;
  }

  const mode = options.mode || 'mock';
  const allowNonGeneration = options.allowNonGeneration !== false;

  // Valid modes are strictly: 'mock', 'fail-closed', 'verified-backend'.
  // Any unknown mode immediately fails closed.
  const isKnownMode = mode === 'mock' || mode === 'fail-closed' || mode === 'verified-backend';

  globalThis.fetch = async function guardedFetch(input, init = {}) {
    const { url, method } = extractUrlAndMethod(input, init);

    // Fail closed on unknown guard mode
    if (!isKnownMode) {
      guardStats.blockedCalls++;
      throw new Error(`[PAID_MODEL_BLOCKED] Unknown verification guard mode '${mode}': generation blocked.`);
    }

    if (isGenerationRequest(input, init)) {
      if (mode === 'fail-closed') {
        guardStats.blockedCalls++;
        throw new Error(`[PAID_MODEL_BLOCKED] Generation request blocked by verification harness: ${method} ${url}`);
      }

      if (mode === 'mock') {
        guardStats.mockedCalls++;
        return createSyntheticSseResponse(options);
      }

      if (mode === 'verified-backend') {
        // Must verify the backend origin before dispatching generation
        await assertSafeBackendOrigin(url, originalNodeFetch);
        return originalNodeFetch.apply(this, arguments);
      }
    }

    if (allowNonGeneration) {
      guardStats.allowedNonGenerationCalls++;
      return originalNodeFetch.apply(this, arguments);
    }

    throw new Error(`[UNMOCKED_NETWORK_CALL] Attempted unmocked non-generation call with allowNonGeneration=false: ${method} ${url}`);
  };

  return {
    restore: uninstallNodeFetchGuard
  };
}

/**
 * Restores original globalThis.fetch in Node.js.
 */
export function uninstallNodeFetchGuard() {
  if (originalNodeFetch) {
    globalThis.fetch = originalNodeFetch;
    originalNodeFetch = null;
  }
}

/**
 * In-memory synthetic state store for headless browser test execution without live backend dependencies.
 */
export function createSyntheticStore() {
  const conversations = new Map();

  return {
    getConversation(id) {
      return conversations.get(id) || null;
    },
    ensureConversation(id, title = 'New Conversation') {
      if (!conversations.has(id)) {
        conversations.set(id, {
          id,
          title,
          botId: 'bot-general',
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
          messages: [],
          runs: []
        });
      }
      return conversations.get(id);
    },
    addMessage(convId, role, content) {
      const conv = this.ensureConversation(convId);
      const msg = {
        id: `msg-${role.toLowerCase()}-${Date.now()}-${Math.random().toString(36).substring(2, 6)}`,
        conversationId: convId,
        runId: `run-${Date.now()}`,
        role: role.toUpperCase(),
        content,
        interrupted: false,
        createdAt: new Date().toISOString()
      };
      conv.messages.push(msg);
      conv.updatedAt = new Date().toISOString();
      return msg;
    },
    listConversations() {
      return Array.from(conversations.values());
    }
  };
}

/**
 * Installs a native Puppeteer request interception guard on a page.
 * Guarantees that in-browser user interactions (e.g. clicking Send) cannot hit live paid AI models.
 *
 * Implements official Puppeteer cooperative interception guidelines:
 * 1. Guards all resolutions with synchronous req.isInterceptResolutionHandled() checks.
 * 2. Generation requests are locked so competing/external request listeners cannot continue them.
 * 3. Provides a cooperative request dispatcher for downstream handlers without race conditions.
 *
 * Options:
 * - mode: 'mock' (default) | 'fail-closed' | 'verified-backend'
 * - customMocks: object mapping URL strings or patterns to handlers
 * - syntheticStore: store instance or boolean true
 */
export async function installBrowserGuard(page, options = {}) {
  await page.setRequestInterception(true);

  const mode = options.mode || 'mock';
  const customMocks = options.customMocks || {};
  const store = options.syntheticStore ? (options.syntheticStore === true ? createSyntheticStore() : options.syntheticStore) : null;
  const isKnownMode = mode === 'mock' || mode === 'fail-closed' || mode === 'verified-backend';
  const cooperativeHandlers = [];

  page.on('request', async req => {
    // 1. Cooperative guard helpers ensuring isInterceptResolutionHandled() check in same synchronous turn
    const safeRespond = (responseData) => {
      if (req.isInterceptResolutionHandled()) return false;
      req.respond(responseData);
      return true;
    };

    const safeContinue = (overrides) => {
      if (req.isInterceptResolutionHandled()) return false;
      req.continue(overrides);
      return true;
    };

    const safeAbort = (reason = 'failed') => {
      if (req.isInterceptResolutionHandled()) return false;
      req.abort(reason);
      return true;
    };

    const url = req.url();
    const method = req.method();
    const isGen = isGenerationRequest(url, method);
    let generationApproved = false;

    // 2. Wrap request.continue on generation requests to prevent rogue / competing listeners
    // from continuing unverified generation calls before the guard verifies safety
    const origContinue = req.continue.bind(req);
    req.continue = async function guardedContinue(...args) {
      if (isGen && !generationApproved) {
        console.warn(`[VerificationGuard] Blocked competing listener from continuing unverified generation request: ${url}`);
        return;
      }
      if (req.isInterceptResolutionHandled()) return;
      return origContinue(...args);
    };

    try {
      // 3. Guard Generation Requests (/api/conversations/*/runs)
      if (isGen) {
        if (!isKnownMode || mode === 'fail-closed') {
          guardStats.blockedCalls++;
          return safeRespond({
            status: 403,
            contentType: 'application/json',
            body: JSON.stringify({
              error: `[PAID_MODEL_BLOCKED] Generation request blocked by client verification guard (mode: ${mode}).`
            })
          });
        }

        if (mode === 'verified-backend') {
          try {
            await assertSafeBackendOrigin(url);
            generationApproved = true;
            return safeContinue();
          } catch (err) {
            guardStats.blockedCalls++;
            return safeRespond({
              status: 403,
              contentType: 'application/json',
              body: JSON.stringify({
                error: err.message
              })
            });
          }
        }

        // Mode: 'mock'
        guardStats.mockedCalls++;
        let postData = {};
        try {
          postData = JSON.parse(req.postData() || '{}');
        } catch (_) {}

        const userPrompt = postData.message || 'User test prompt';
        const delta1 = options.delta1 || (options.deltaText ? options.deltaText : '這是一次本機最小');
        const delta2 = options.delta2 || (options.deltaText ? '' : '聊天閉環驗證合成回應。');
        const fullDelta = options.deltaText || `${delta1}${delta2}`;
        const runId = options.runId || `run-mock-${Date.now()}`;

        // Authoritative message IDs shared between synthetic store and SSE frames
        let userMessageId = `msg-user-${Date.now()}`;
        let assistantMessageId = `msg-assistant-${Date.now()}`;

        // If synthetic store is active, update store state with identical IDs and metadata
        const convIdMatch = url.match(/\/api\/conversations\/([^/]+)\/runs/);
        const convId = convIdMatch ? convIdMatch[1] : 'conv-mock-1';
        if (store) {
          const userMsg = store.addMessage(convId, 'USER', userPrompt);
          userMessageId = userMsg.id;
          const assistantMsg = store.addMessage(convId, 'ASSISTANT', fullDelta);
          assistantMessageId = assistantMsg.id;
          const conv = store.ensureConversation(convId);
          conv.runs.push({ id: runId, status: 'COMPLETED', errorMessage: null });
        }

        const sseFramesList = [
          'event: run.started',
          `data: ${JSON.stringify({ seq: 1, runId, status: 'RUNNING', userMessageId })}`,
          '',
          'event: message.delta',
          `data: ${JSON.stringify({ seq: 2, runId, delta: delta1 })}`,
          ''
        ];

        if (delta2) {
          sseFramesList.push(
            'event: message.delta',
            `data: ${JSON.stringify({ seq: 3, runId, delta: delta2 })}`,
            ''
          );
        }

        const completedSeq = delta2 ? 4 : 3;
        sseFramesList.push(
          'event: run.completed',
          `data: ${JSON.stringify({ seq: completedSeq, runId, status: 'COMPLETED', messageId: assistantMessageId })}`,
          '',
          ''
        );

        const sseFrames = sseFramesList.join('\n');

        return safeRespond({
          status: 200,
          contentType: 'text/event-stream; charset=utf-8',
          headers: {
            'Cache-Control': 'no-cache',
            'Connection': 'keep-alive',
            'X-Verification-Guard': 'BROWSER_MOCK_SYNTHETIC'
          },
          body: sseFrames
        });
      }

      // 4. Non-generation requests: Check customMocks and cooperative handlers
      if (customMocks[url]) {
        return customMocks[url](req);
      }

      for (const handler of cooperativeHandlers) {
        await handler(req);
        if (req.isInterceptResolutionHandled()) return;
      }

      return safeContinue();
    } catch (err) {
      console.error('[VerificationGuard] Fatal interceptor error, failing closed:', err);
      safeAbort('failed');
    }
  });

  return {
    addRequestHandler(handler) {
      cooperativeHandlers.push(handler);
    },
    removeRequestHandler(handler) {
      const idx = cooperativeHandlers.indexOf(handler);
      if (idx !== -1) cooperativeHandlers.splice(idx, 1);
    }
  };
}
