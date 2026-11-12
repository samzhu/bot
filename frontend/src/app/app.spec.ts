import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { appConfig } from './app.config';
import { App } from './app';
import { DraftStore } from './draft-store';
import { ConversationListStore } from './conversation-list-store';
import { BotView } from './bot/bot.model';

describe('App', () => {
  const defaultTestLocalBot: BotView = {
    id: 'bot-1',
    name: 'General Assistant',
    description: '預設本地助理',
    instructions: 'You are a helpful assistant.',
    target: 'LOCAL',
    connectionStatus: 'NOT_APPLICABLE',
    executionMode: 'LOCAL',
    statusReason: null,
    canChat: true,
    createdAt: '2026-01-01',
    updatedAt: '2026-01-01'
  };

  beforeEach(async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string, opts?: any) => {
      let body: any = {};
      try {
        body = JSON.parse(opts?.body || '{}');
      } catch {}

      if (body.operationName === 'GetBots' || body.query?.includes('query GetBots') || body.query?.includes('bots(limit')) {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({
            data: {
              defaultLocalBot: defaultTestLocalBot,
              bots: [defaultTestLocalBot]
            }
          })
        });
      }

      if (body.operationName === 'GetDefaultLocalBot' || body.query?.includes('query GetDefaultLocalBot') || (body.query?.includes('defaultLocalBot') && !body.query?.includes('bots('))) {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({
            data: { defaultLocalBot: defaultTestLocalBot }
          })
        });
      }

      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({
          data: {
            bots: [defaultTestLocalBot],
            defaultLocalBot: defaultTestLocalBot,
            conversations: []
          }
        })
      });
    }));

    if (typeof globalThis.ResizeObserver === 'undefined') {
      globalThis.ResizeObserver = class {
        observe() {}
        unobserve() {}
        disconnect() {}
      } as any;
    }

    if (typeof Element !== 'undefined' && !Element.prototype.scrollTo) {
      Element.prototype.scrollTo = () => {};
    }

    if (typeof window !== 'undefined') {
      const store: Record<string, string> = {};
      Object.defineProperty(window, 'localStorage', {
        value: {
          getItem: (key: string) => store[key] || null,
          setItem: (key: string, val: string) => { store[key] = val; },
          removeItem: (key: string) => { delete store[key]; },
          clear: () => {
            for (const key of Object.keys(store)) {
              delete store[key];
            }
          }
        },
        configurable: true,
        writable: true
      });
    }

    await TestBed.configureTestingModule({
      imports: [App],
      providers: [appConfig.providers]
    }).compileComponents();
  });

  afterEach(() => {
    if (typeof window !== 'undefined' && window.localStorage) {
      window.localStorage.clear();
    }
    try {
      TestBed.inject(DraftStore)?.resetAll();
      TestBed.inject(ConversationListStore)?.resetAll();
    } catch {}
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it('should render workspace title', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.workspace-title')?.textContent).toContain('Herdr Bot');
  });

  it('should bind raw messages and status prompts without altering message content', async () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    await fixture.whenStable();

    app.messages.set([
      { id: 'u1', role: 'user', content: 'hello' },
      { id: 'a1', role: 'assistant', content: '**bold response**', interrupted: true }
    ]);
    fixture.detectChanges();

    // Verify messages signal retains raw content unchanged
    expect(app.messages()[1].content).toBe('**bold response**');
    expect(app.lastMessageInterrupted()).toBe(true);
    expect(app.lastMessageFailed()).toBe(false);

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('copilot-chat-view')).toBeTruthy();
    expect(compiled.querySelector('.recovering-banner')?.textContent).toContain('前次回應已中斷');
  });

  it('clicking + New Chat switches to in-memory draft without backend create or sidebar items', async () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    await vi.waitFor(() => expect(app.isInitializing()).toBe(false));

    const fetchSpy = vi.fn();
    vi.stubGlobal('fetch', fetchSpy);

    // Repeatedly click + New Chat
    await app.newConversation();
    await app.newConversation();
    await app.newConversation();

    // Zero backend calls made
    expect(fetchSpy).not.toHaveBeenCalled();
    // Zero conversation sidebar items added
    expect(app.conversations().length).toBe(0);
    expect(app.currentConversationId()).toBeNull();
    expect(app.currentTitle()).toBe('New Conversation');
  });

  it('preserves draft input across conversation switching without cross-contamination', async () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    await fixture.whenStable();
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const textarea = compiled.querySelector<HTMLTextAreaElement>('textarea')!;
    expect(textarea).toBeTruthy();

    // 1. In draft mode, user types into native textarea
    textarea.value = 'Draft note for new chat';
    textarea.dispatchEvent(new Event('input', { bubbles: true }));
    expect(app.inputValue()).toBe('Draft note for new chat');
    expect(app.draftStore.getDraft()).toBe('Draft note for new chat');

    // 2. Existing conversation setup and navigation
    app.unpinnedConversations.set([{
      id: 'conv-1',
      botId: 'bot-1',
      title: 'Existing Conv 1',
      pinned: false,
      createdAt: '2026-01-01',
      updatedAt: '2026-01-01'
    }]);

    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ data: { conversation: { id: 'conv-1', messages: [], runs: [] } } })
      });
    }));

    await app.selectConversation('conv-1');
    fixture.detectChanges();

    // Input should be empty for conv-1 initially
    expect(app.inputValue()).toBe('');

    // 3. Type in conv-1
    textarea.value = 'Unsent text in conv-1';
    textarea.dispatchEvent(new Event('input', { bubbles: true }));
    expect(app.inputValue()).toBe('Unsent text in conv-1');
    expect(app.draftStore.getConversationDraft('conv-1')).toBe('Unsent text in conv-1');

    // 4. Click + New Chat -> restores the original draft note intact
    await app.newConversation();
    fixture.detectChanges();

    expect(app.currentConversationId()).toBeNull();
    expect(app.inputValue()).toBe('Draft note for new chat');
    expect(textarea.value).toBe('Draft note for new chat');

    // conv-1 draft is still intact
    expect(app.draftStore.getConversationDraft('conv-1')).toBe('Unsent text in conv-1');
  });

  it('submitting empty or whitespace text does not trigger create or run', async () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    await vi.waitFor(() => expect(app.isInitializing()).toBe(false));

    const fetchSpy = vi.fn();
    vi.stubGlobal('fetch', fetchSpy);

    await app.submitInput('   ');
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(app.conversations().length).toBe(0);
  });

  it('first valid non-empty submit creates conversation once and starts run', async () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    await vi.waitFor(() => expect(app.isInitializing()).toBe(false));
    fixture.detectChanges();

    const fetchCalls: { url: string; body: any }[] = [];
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string, init?: any) => {
      const body = init?.body ? JSON.parse(init.body) : null;
      fetchCalls.push({ url, body });

      if (url === '/graphql') {
        if (body?.query?.includes('createConversation')) {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({
              data: {
                createConversation: {
                  id: 'conv-created-1',
                  botId: 'bot-1',
                  title: 'New Conversation',
                  createdAt: '2026-01-01',
                  updatedAt: '2026-01-01'
                }
              }
            })
          });
        }
        if (body?.query?.includes('defaultLocalBot') || body?.query?.includes('bots')) {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({
              data: {
                bots: [defaultTestLocalBot],
                defaultLocalBot: defaultTestLocalBot
              }
            })
          });
        }
        if (body?.query?.includes('conversations')) {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({
              data: { conversations: [] }
            })
          });
        }
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({ data: {} })
        });
      }

      if (url.startsWith('/api/conversations/conv-created-1/runs')) {
        return Promise.resolve({
          ok: true,
          status: 200,
          headers: new Headers({ 'content-type': 'application/json' }),
          json: () => Promise.resolve({
            id: 'run-1',
            runId: 'run-1',
            status: 'COMPLETED'
          })
        });
      }

      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({}) });
    }));

    const compiled = fixture.nativeElement as HTMLElement;
    const textarea = compiled.querySelector<HTMLTextAreaElement>('textarea')!;
    textarea.value = 'Hello world first submit';
    textarea.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();

    const sendBtn = compiled.querySelector<HTMLButtonElement>('button[aria-label="Send message"]')!;
    expect(sendBtn).toBeTruthy();
    expect(sendBtn.disabled).toBe(false);

    // Trigger send via native send button
    sendBtn.click();
    await fixture.whenStable();
    fixture.detectChanges();

    // Verified createConversation called once
    const createCall = fetchCalls.find(c => c.url === '/graphql' && c.body?.query?.includes('createConversation'));
    expect(createCall).toBeTruthy();

    // Verified conversation is set and active
    expect(app.currentConversationId()).toBe('conv-created-1');
    expect(app.conversations().length).toBe(1);

    // Verified run API called
    const runCall = fetchCalls.find(c => c.url.includes('/api/conversations/conv-created-1/runs'));
    expect(runCall).toBeTruthy();
    expect(runCall?.body?.message).toBe('Hello world first submit');

    // Verified draft is cleared after server acceptance
    expect(app.draftStore.getDraft()).toBe('');
  });

  describe('Initialization Barrier & Race Condition Guard', () => {
    it('blocks submitInput mutation during delayed initialization and preserves draft, then allows send and retains conversation once ready', async () => {
      let resolveConversationsFetch: (val: any) => void;
      const delayedConversationsPromise = new Promise(resolve => {
        resolveConversationsFetch = resolve;
      });

      const fetchCalls: { url: string; body: any }[] = [];
      vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string, init?: any) => {
        const body = init?.body ? JSON.parse(init.body) : null;
        fetchCalls.push({ url, body });

        if (url === '/graphql') {
          if (body?.operationName === 'GetBots' || body?.query?.includes('query GetBots')) {
            return Promise.resolve({
              ok: true,
              status: 200,
              json: () => Promise.resolve({
                data: {
                  bots: [defaultTestLocalBot],
                  defaultLocalBot: defaultTestLocalBot
                }
              })
            });
          }
          if (body?.query?.includes('conversations')) {
            return delayedConversationsPromise.then(() => ({
              ok: true,
              status: 200,
              json: () => Promise.resolve({ data: { conversations: [] } })
            }));
          }
          if (body?.query?.includes('createConversation')) {
            return Promise.resolve({
              ok: true,
              status: 200,
              json: () => Promise.resolve({
                data: {
                  createConversation: {
                    id: 'conv-race-1',
                    botId: 'bot-1',
                    title: 'New Conversation',
                    createdAt: '2026-01-01',
                    updatedAt: '2026-01-01'
                  }
                }
              })
            });
          }
        }

        if (url.startsWith('/api/conversations/conv-race-1/runs')) {
          return Promise.resolve({
            ok: true,
            status: 200,
            headers: new Headers({ 'content-type': 'application/json' }),
            json: () => Promise.resolve({ id: 'run-r1', status: 'COMPLETED' })
          });
        }

        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: {} }) });
      }));

      const fixture = TestBed.createComponent(App);
      const app = fixture.componentInstance;
      fixture.detectChanges();

      // Observable barrier: isInitializing is true, UI has aria-busy and loading hint
      expect(app.isInitializing()).toBe(true);
      const compiled = fixture.nativeElement as HTMLElement;
      expect(compiled.querySelector('.layout-container')?.getAttribute('aria-busy')).toBe('true');
      expect(compiled.querySelector('[data-testid="initial-loading-hint"]')).toBeTruthy();

      // Wait for default local bot metadata to resolve while conversations remain blocked
      await vi.waitFor(() => expect(app.defaultLocalBot()).toBeTruthy());
      expect(app.isInitializing()).toBe(true);

      // Attempt submit while initializing: MUST NOT trigger mutation and MUST preserve draft text
      const sendBtn = compiled.querySelector<HTMLButtonElement>('button[aria-label="Send message"]');
      if (sendBtn) {
        expect(sendBtn.disabled).toBe(true);
      }
      await app.submitInput('Input while initializing');
      const createCallsBeforeReady = fetchCalls.filter(c => c.url === '/graphql' && c.body?.query?.includes('createConversation'));
      expect(createCallsBeforeReady.length).toBe(0);
      expect(app.draftStore.getDraft()).toBe('Input while initializing');
      expect(app.inputValue()).toBe('Input while initializing');

      // Now resolve the delayed conversations fetch -> initialization completes
      resolveConversationsFetch!(null);
      await vi.waitFor(() => expect(app.isInitializing()).toBe(false));
      fixture.detectChanges();

      expect(compiled.querySelector('.layout-container')?.getAttribute('aria-busy')).toBe('false');
      expect(compiled.querySelector('[data-testid="initial-loading-hint"]')).toBeNull();

      // Submit once ready -> createConversation succeeds and sidebar retains new conversation
      await app.submitInput('Input once ready');
      const createCallsAfterReady = fetchCalls.filter(c => c.url === '/graphql' && c.body?.query?.includes('createConversation'));
      expect(createCallsAfterReady.length).toBe(1);
      expect(app.currentConversationId()).toBe('conv-race-1');
      expect(app.conversations().length).toBe(1);
      expect(app.conversations()[0].id).toBe('conv-race-1');
    });
  });

  it('SDK real send sequence: create failure restores signal, draftStore, and native DOM textarea value', async () => {
    // Simulate backend GraphQL failure for createConversation while allowing initial bot/conversation load
    const fetchCalls: Array<{ url: string; query?: string }> = [];
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string, init?: any) => {
      const body = init?.body ? JSON.parse(init.body) : null;
      fetchCalls.push({ url, query: body?.query });
      if (url === '/graphql' && body?.query?.includes('createConversation')) {
        return Promise.resolve({
          ok: false,
          status: 500,
          json: () => Promise.resolve({ errors: [{ message: 'Database connection failed' }] })
        });
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({
          data: {
            bots: [{
              id: 'bot-1',
              name: 'General Assistant',
              target: 'LOCAL',
              connectionStatus: 'NOT_APPLICABLE',
              executionMode: 'LOCAL',
              canChat: true,
              createdAt: '2026-01-01',
              updatedAt: '2026-01-01'
            }],
            defaultLocalBot: {
              id: 'bot-1',
              name: 'General Assistant',
              target: 'LOCAL',
              connectionStatus: 'NOT_APPLICABLE',
              executionMode: 'LOCAL',
              canChat: true,
              createdAt: '2026-01-01',
              updatedAt: '2026-01-01'
            },
            conversations: []
          }
        })
      });
    }));

    const initSpy = vi.spyOn(App.prototype, 'ngOnInit');
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    fixture.detectChanges();
    await initSpy.mock.results[0]?.value;

    const compiled = fixture.nativeElement as HTMLElement;
    const textarea = compiled.querySelector<HTMLTextAreaElement>('textarea')!;
    textarea.value = 'Draft message to preserve on error';
    textarea.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();

    const sendBtn = compiled.querySelector<HTMLButtonElement>('button[aria-label="Send message"]')!;
    expect(sendBtn).toBeTruthy();
    expect(sendBtn.disabled).toBe(false);

    // Native send click triggers CopilotKit's real sequence:
    // submitInput() -> changeInput("") -> textAreaRef.setValue("")
    sendBtn.click();
    await vi.waitFor(() => expect(app.streamError()).toContain('建立對話失敗'));
    fixture.detectChanges();

    // Draft text MUST be preserved in draftStore
    expect(app.draftStore.getDraft()).toBe('Draft message to preserve on error');

    // inputValue signal MUST be restored
    expect(app.inputValue()).toBe('Draft message to preserve on error');

    // DOM textarea value MUST be restored to the draft message
    expect(textarea.value).toBe('Draft message to preserve on error');
  });

  it('submitting in existing conversation clears that conversation draft without wiping new chat draft', async () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    await vi.waitFor(() => expect(app.isInitializing()).toBe(false));
    fixture.detectChanges();

    // Setup an isolated draft for new chat
    app.draftStore.setDraft('Preserved new chat draft');

    // Set active conversation with active local bot
    app.currentConversationId.set('conv-existing-99');
    app.activeConversationBot.set(defaultTestLocalBot);

    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      if (url.startsWith('/api/conversations/conv-existing-99/runs')) {
        return Promise.resolve({
          ok: true,
          status: 200,
          headers: new Headers({ 'content-type': 'application/json' }),
          json: () => Promise.resolve({
            id: 'run-99',
            status: 'COMPLETED'
          })
        });
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({}) });
    }));

    const compiled = fixture.nativeElement as HTMLElement;
    const textarea = compiled.querySelector<HTMLTextAreaElement>('textarea')!;
    textarea.value = 'Existing conv query';
    textarea.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();

    const sendBtn = compiled.querySelector<HTMLButtonElement>('button[aria-label="Send message"]')!;
    expect(sendBtn).toBeTruthy();
    expect(sendBtn.disabled).toBe(false);

    sendBtn.click();
    await fixture.whenStable();
    fixture.detectChanges();

    // Existing conv draft cleared
    expect(app.draftStore.getConversationDraft('conv-existing-99')).toBe('');

    // New chat draft is STILL completely intact!
    expect(app.draftStore.getDraft()).toBe('Preserved new chat draft');
  });

  it('concurrency lock isSubmitting deduplicates double submit', async () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    await vi.waitFor(() => expect(app.isInitializing()).toBe(false));

    let createCalls = 0;
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string, init?: any) => {
      const body = init?.body ? JSON.parse(init.body) : null;
      if (url === '/graphql') {
        if (body?.query?.includes('createConversation')) {
          createCalls++;
          await new Promise(r => setTimeout(r, 50));
          return {
            ok: true,
            status: 200,
            json: () => Promise.resolve({
              data: { createConversation: { id: 'conv-double', botId: 'bot-1', title: 'New Conversation' } }
            })
          };
        }
        return {
          ok: true,
          status: 200,
          json: () => Promise.resolve({ data: { bots: [{ id: 'bot-1' }] } })
        };
      }
      return {
        ok: true,
        status: 200,
        headers: new Headers({ 'content-type': 'application/json' }),
        json: () => Promise.resolve({ id: 'r1', status: 'COMPLETED' })
      };
    }));

    app.inputValue.set('Double click prompt');
    const p1 = app.submitInput('Double click prompt');
    const p2 = app.submitInput('Double click prompt');

    await Promise.all([p1, p2]);

    expect(createCalls).toBe(1);
  });

  it('asynchronous race guard prevents late selection response from overwriting current draft', async () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    await fixture.whenStable();

    let resolveSlowFetch: any;
    const slowFetchPromise = new Promise(resolve => {
      resolveSlowFetch = resolve;
    });

    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      if (url === '/graphql') {
        return slowFetchPromise.then(() => ({
          ok: true,
          status: 200,
          json: () => Promise.resolve({
            data: {
              conversation: {
                id: 'slow-conv',
                messages: [{ id: 'm-old', role: 'USER', content: 'Stale message' }],
                runs: []
              }
            }
          })
        }));
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({}) });
    }));

    // Start selecting slow-conv
    const selectPromise = app.selectConversation('slow-conv');

    // Immediately switch to + New Chat before fetch resolves
    await app.newConversation();

    // Now resolve the slow fetch
    resolveSlowFetch();
    await selectPromise;

    // Must remain on draft (null), not overwritten by slow-conv
    expect(app.currentConversationId()).toBeNull();
    expect(app.messages().length).toBe(0);
  });

  it('preserves exact raw input with trailing whitespace and newlines on create failure', async () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    await vi.waitFor(() => expect(app.isInitializing()).toBe(false));
    fixture.detectChanges();

    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string, init?: any) => {
      const body = init?.body ? JSON.parse(init.body) : null;
      if (url === '/graphql' && body?.query?.includes('createConversation')) {
        return Promise.resolve({
          ok: false,
          status: 500,
          json: () => Promise.resolve({ errors: [{ message: 'Server unavailable' }] })
        });
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ data: { bots: [{ id: 'bot-1' }] } })
      });
    }));

    const rawDraft = 'Multiline draft\nwith trailing spaces   \n\n';
    app.inputValue.set(rawDraft);

    // Call submitInput with trimmed text as CopilotKit does
    await app.submitInput(rawDraft.trim());
    fixture.detectChanges();

    // Verify raw whitespace/newline snapshot was restored to signal, draftStore, and DOM
    expect(app.inputValue()).toBe(rawDraft);
    expect(app.draftStore.getDraft()).toBe(rawDraft);
  });

  it('blocks + New Chat and conversation selection while isSubmitting is active', async () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    await vi.waitFor(() => expect(app.isInitializing()).toBe(false));

    app.unpinnedConversations.set([{
      id: 'conv-target',
      botId: 'bot-1',
      title: 'Target Conv',
      pinned: false,
      createdAt: '2026-01-01',
      updatedAt: '2026-01-01'
    }]);

    // Simulate active in-flight submission lock
    app.isSubmitting.set(true);

    // Attempt switching to another conversation
    await app.selectConversation('conv-target');
    // Blocked: current conversation must remain unchanged
    expect(app.currentConversationId()).toBeNull();

    // Attempt clicking + New Chat
    app.draftStore.setDraft('Initial Draft');
    await app.newConversation();
    // Blocked by isSubmitting lock
    expect(app.draftStore.getDraft()).toBe('Initial Draft');
  });

  it('clears original new draft on retry success after initial 500 run failure', async () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    await vi.waitFor(() => expect(app.isInitializing()).toBe(false));

    let runAttempts = 0;
    let createConvCalls = 0;
    const runPostUrls: string[] = [];
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string, init?: any) => {
      let body: any = null;
      try {
        body = init?.body ? JSON.parse(init.body) : null;
      } catch {}
      if (url === '/graphql') {
        if (body?.query?.includes('createConversation')) {
          createConvCalls++;
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({
              data: {
                createConversation: { id: 'conv-500-retry', botId: 'bot-1', title: 'New Conversation' }
              }
            })
          });
        }
        if (body?.query?.includes('defaultLocalBot') || body?.query?.includes('bots')) {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({
              data: {
                defaultLocalBot: defaultTestLocalBot,
                bots: [defaultTestLocalBot]
              }
            })
          });
        }
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({ data: {} })
        });
      }
      if (url.includes('/runs')) {
        runPostUrls.push(url);
        runAttempts++;
        if (runAttempts === 1) {
          // First run attempt fails with 500
          return Promise.resolve({
            ok: false,
            status: 500,
            headers: new Headers({ 'content-type': 'application/json' }),
            json: () => Promise.resolve({ error: 'LLM capacity exceeded' })
          });
        } else {
          // Retry attempt succeeds
          return Promise.resolve({
            ok: true,
            status: 200,
            headers: new Headers({ 'content-type': 'application/json' }),
            json: () => Promise.resolve({ id: 'run-success', status: 'COMPLETED' })
          });
        }
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({}) });
    }));

    const draftMsg = 'Draft message that initially fails run';
    app.inputValue.set(draftMsg);
    app.draftStore.setDraft(draftMsg);

    // First submit: conversation is created, but run returns 500
    await app.submitInput(draftMsg);

    // Conversation exists and pendingRequest is retained
    expect(app.currentConversationId()).toBe('conv-500-retry');
    expect(app.pendingRequest()?.isFromNewDraft).toBe(true);
    // Draft in store is still present since run failed
    expect(app.draftStore.getDraft()).toBe('Draft message that initially fails run');

    // Now retry pending request
    await app.retryPending();
    await fixture.whenStable();

    // Assert createConversation was only called once, and retry POST went to original conversation
    expect(createConvCalls).toBe(1);
    expect(runPostUrls).toEqual([
      '/api/conversations/conv-500-retry/runs',
      '/api/conversations/conv-500-retry/runs'
    ]);

    // On retry acceptance, the original new draft is properly cleared
    expect(app.draftStore.getDraft()).toBe('');
  });

  // ---------------------------------------------------------------------------------
  // Issue #10: Native toolbar buttons with inner accessible name and adjacent label
  // ---------------------------------------------------------------------------------
  it('should render toolbar buttons with stock icon button, inner accessible name and adjacent descriptive text without clipping', async () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    await fixture.whenStable();

    // Configure states to display all 5 toolbar action controls
    app.hasMoreConversations.set(true);
    app.hasMoreMessages.set(true);
    app.streamError.set('測試連線錯誤');
    app.pendingRequest.set({
      conversationId: 'conv-test-retry',
      clientRequestId: 'req-retry-1',
      message: 'retry test',
      tempUserId: 'temp-u1',
      tempAssistantId: 'temp-a1'
    });
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;

    // 1. New Chat: inner button has accessible title/aria-label, adjacent text container has 'New Chat'
    const newChatBtn = compiled.querySelector('[data-testid="new-chat-btn"] button') as HTMLButtonElement;
    expect(newChatBtn).toBeTruthy();
    expect(newChatBtn.getAttribute('aria-label')).toBe('+ New Chat');
    const newChatSvg = newChatBtn.querySelector('svg');
    expect(newChatSvg).toBeTruthy();
    expect(newChatSvg?.getAttribute('width')).toBe('16');
    expect(newChatSvg?.getAttribute('height')).toBe('16');
    const newChatContainer = compiled.querySelector('[data-testid="new-chat-btn"]')?.parentElement;
    expect(newChatContainer?.querySelector('.action-btn-text')?.textContent?.trim()).toBe('New Chat');

    // 2. Retry: inner button has accessible title/aria-label, adjacent text container has '重試'
    const retryBtn = compiled.querySelector('[data-testid="retry-btn"] button') as HTMLButtonElement;
    expect(retryBtn).toBeTruthy();
    expect(retryBtn.getAttribute('aria-label')).toBe('重試');
    const retrySvg = retryBtn.querySelector('svg');
    expect(retrySvg).toBeTruthy();
    expect(retrySvg?.getAttribute('width')).toBe('16');
    expect(retrySvg?.getAttribute('height')).toBe('16');
    const retryContainer = compiled.querySelector('.retry-action-container');
    expect(retryContainer?.querySelector('.action-btn-text')?.textContent?.trim()).toBe('重試');

    // 3. Conversation Pagination Loader: inner button has accessible label, adjacent text container has 'Load more conversations'
    const loadMoreConvBtn = compiled.querySelector('[data-testid="load-more-conv-btn"] button') as HTMLButtonElement;
    expect(loadMoreConvBtn).toBeTruthy();
    expect(loadMoreConvBtn.getAttribute('aria-label')).toBe('Load more conversations');
    const loadMoreConvSvg = loadMoreConvBtn.querySelector('svg');
    expect(loadMoreConvSvg).toBeTruthy();
    expect(loadMoreConvSvg?.getAttribute('width')).toBe('16');
    expect(loadMoreConvSvg?.getAttribute('height')).toBe('16');
    const loadMoreConvContainer = compiled.querySelector('.load-more-conv-container');
    expect(loadMoreConvContainer?.querySelector('.action-btn-text')?.textContent?.trim()).toBe('Load more conversations');

    // 4. Message History Pagination Loader: inner button has accessible label, adjacent text container has 'Load earlier messages'
    const loadMoreBtn = compiled.querySelector('[data-testid="load-more-btn"] button') as HTMLButtonElement;
    expect(loadMoreBtn).toBeTruthy();
    expect(loadMoreBtn.getAttribute('aria-label')).toBe('Load earlier messages');
    const loadMoreSvg = loadMoreBtn.querySelector('svg');
    expect(loadMoreSvg).toBeTruthy();
    expect(loadMoreSvg?.getAttribute('width')).toBe('16');
    expect(loadMoreSvg?.getAttribute('height')).toBe('16');
    const loadMoreContainer = compiled.querySelector('.load-more-container');
    expect(loadMoreContainer?.querySelector('.action-btn-text')?.textContent?.trim()).toBe('Load earlier messages');

    // 5. Stop Generation Action during streaming
    app.isStreaming.set(true);
    app.activeRunId.set('run-active-test');
    fixture.detectChanges();

    const stopBtn = compiled.querySelector('[data-testid="stop-btn"] button') as HTMLButtonElement;
    expect(stopBtn).toBeTruthy();
    expect(stopBtn.getAttribute('aria-label')).toBe('Stop generation');
    const stopSvg = stopBtn.querySelector('svg');
    expect(stopSvg).toBeTruthy();
    expect(stopSvg?.getAttribute('width')).toBe('16');
    expect(stopSvg?.getAttribute('height')).toBe('16');
    const stopContainer = compiled.querySelector('.stop-action-container');
    expect(stopContainer?.querySelector('.action-btn-text')?.textContent?.trim()).toBe('Stop');
  });

  // ---------------------------------------------------------------------------------
  // Issue #13: Assistant message rows render strictly supported actions (zero thumbs)
  // ---------------------------------------------------------------------------------
  it('should render assistant message with markdown and copy button but strictly without thumbs up/down buttons across states', async () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    await fixture.whenStable();

    // Historical messages: completed, cancelled/interrupted, failed
    app.messages.set([
      { id: 'u1', role: 'user', content: 'test question' },
      { id: 'a1', role: 'assistant', content: 'Completed **markdown** with `code block`' },
      { id: 'a2', role: 'assistant', content: 'Cancelled message', interrupted: true },
      { id: 'a3', role: 'assistant', content: 'Failed message', failed: true }
    ]);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;

    // Verify assistant messages render
    const assistantRows = compiled.querySelectorAll('[data-message-role="assistant"]');
    expect(assistantRows.length).toBe(3);

    // Verify Thumbs buttons are strictly absent from entire DOM
    const thumbsUp = compiled.querySelector('copilot-chat-assistant-message-thumbs-up-button, button[title="Thumbs up"], button[aria-label*="thumbs up" i]');
    const thumbsDown = compiled.querySelector('copilot-chat-assistant-message-thumbs-down-button, button[title="Thumbs down"], button[aria-label*="thumbs down" i]');
    expect(thumbsUp).toBeNull();
    expect(thumbsDown).toBeNull();

    // Verify native Markdown renderer and Copy button are present on completed message
    const completedRow = assistantRows[0];
    expect(completedRow.querySelector('copilot-chat-assistant-message-renderer')).toBeTruthy();
    expect(completedRow.querySelector('copilot-chat-assistant-message-copy-button')).toBeTruthy();
  });

  // ---------------------------------------------------------------------------------
  // Issue #10 Dynamic Scenario: Dynamic switching between Send and Stop during streaming
  // ---------------------------------------------------------------------------------
  it('should dynamically switch between Send button and Stop button during streaming', async () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    await fixture.whenStable();

    const compiled = fixture.nativeElement as HTMLElement;

    // Initially idle: Send button is present, Stop button is absent
    expect(compiled.querySelector('[data-testid="send-message-btn"]')).toBeTruthy();
    expect(compiled.querySelector('[data-testid="stop-btn"]')).toBeNull();

    // Transition to streaming
    app.isStreaming.set(true);
    app.activeRunId.set('run-dynamic-test');
    fixture.detectChanges();

    // Send button replaced by Stop button with icon and adjacent label
    expect(compiled.querySelector('[data-testid="send-message-btn"]')).toBeNull();
    const stopBtn = compiled.querySelector('[data-testid="stop-btn"] button') as HTMLButtonElement;
    expect(stopBtn).toBeTruthy();
    expect(stopBtn.getAttribute('aria-label')).toBe('Stop generation');
    expect(stopBtn.querySelector('svg')?.getAttribute('width')).toBe('16');
    expect(compiled.querySelector('.stop-action-container .action-btn-text')?.textContent?.trim()).toBe('Stop');

    // Transition to cancelling
    app.isCancelling.set(true);
    fixture.detectChanges();
    expect(stopBtn.getAttribute('aria-label')).toBe('Stopping generation...');
    expect(compiled.querySelector('.stop-action-container .action-btn-text')?.textContent?.trim()).toBe('Stopping...');

    // Stream completes: switches back to Send button
    app.isStreaming.set(false);
    app.isCancelling.set(false);
    app.activeRunId.set(null);
    fixture.detectChanges();

    expect(compiled.querySelector('[data-testid="send-message-btn"]')).toBeTruthy();
    expect(compiled.querySelector('[data-testid="stop-btn"]')).toBeNull();
  });

  // ---------------------------------------------------------------------------------
  // Issue #10 Dynamic Scenario: Dynamic display of Retry button in stream error banner
  // ---------------------------------------------------------------------------------
  it('should dynamically display Retry button in stream error banner when pending request fails', async () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    await fixture.whenStable();

    const compiled = fixture.nativeElement as HTMLElement;

    // Initially no error banner
    expect(compiled.querySelector('[data-testid="stream-error-banner"]')).toBeNull();
    expect(compiled.querySelector('[data-testid="retry-btn"]')).toBeNull();

    // Trigger error with pending request
    app.streamError.set('連線異常，串流中斷');
    app.pendingRequest.set({
      conversationId: 'conv-err-test',
      clientRequestId: 'req-err-1',
      message: 'test message',
      tempUserId: 'u-err',
      tempAssistantId: 'a-err'
    });
    fixture.detectChanges();

    // Error banner renders with Retry button
    const banner = compiled.querySelector('[data-testid="stream-error-banner"]');
    expect(banner).toBeTruthy();
    const retryBtn = banner?.querySelector('[data-testid="retry-btn"] button') as HTMLButtonElement;
    expect(retryBtn).toBeTruthy();
    expect(retryBtn.getAttribute('aria-label')).toBe('重試');
    expect(retryBtn.querySelector('svg')?.getAttribute('width')).toBe('16');
    expect(banner?.querySelector('.action-btn-text')?.textContent?.trim()).toBe('重試');

    // Once retry succeeds and pendingRequest is cleared
    app.streamError.set(null);
    app.pendingRequest.set(null);
    fixture.detectChanges();

    expect(compiled.querySelector('[data-testid="stream-error-banner"]')).toBeNull();
    expect(compiled.querySelector('[data-testid="retry-btn"]')).toBeNull();
  });

  // ---------------------------------------------------------------------------------
  // Issue #18: Bot-specific Drafts & Execution Capability
  // ---------------------------------------------------------------------------------
  describe('Issue #18: Bot-specific Drafts & Execution Capability', () => {
    const botA: BotView = {
      id: 'bot-a',
      name: 'Bot A (Active)',
      description: 'Active Bot',
      instructions: 'Assistant A',
      target: 'LOCAL',
      connectionStatus: 'NOT_APPLICABLE',
      executionMode: 'LOCAL',
      statusReason: null,
      canChat: true,
      createdAt: '2026-01-01',
      updatedAt: '2026-01-01'
    };

    const botBOff: BotView = {
      id: 'bot-b',
      name: 'Bot B (OFF)',
      description: 'GKE agent not connected',
      instructions: 'Assistant B',
      target: 'GKE_REMOTE',
      connectionStatus: 'NOT_CONNECTED',
      executionMode: 'UNAVAILABLE',
      statusReason: 'Agent offline',
      canChat: false,
      createdAt: '2026-01-01',
      updatedAt: '2026-01-01'
    };

    const botSim: BotView = {
      id: 'bot-sim',
      name: 'Bot Simulation',
      description: 'GKE agent simulated',
      instructions: 'Assistant Sim',
      target: 'GKE_REMOTE',
      connectionStatus: 'NOT_CONNECTED',
      executionMode: 'SPRING_AI_SIMULATION',
      statusReason: 'Local simulation active',
      canChat: true,
      createdAt: '2026-01-01',
      updatedAt: '2026-01-01'
    };

    it('selectConversation clears activeConversationBot immediately to prevent mistaken send during delayed metadata load, preserving conv-specific draft', async () => {
      const fixture = TestBed.createComponent(App);
      const app = fixture.componentInstance;
      await vi.waitFor(() => expect(app.isInitializing()).toBe(false));

      // Initially in Conversation A with Bot A
      app.currentConversationId.set('conv-a');
      app.activeConversationBot.set(botA);
      expect(app.canSend()).toBe(true);

      let resolveBotBLoad: ((val: any) => void) | null = null;
      const botBLoadPromise = new Promise(resolve => {
        resolveBotBLoad = resolve;
      });

      // Stub fetch: conv-b has botId 'bot-b'
      let mutationCalls = 0;
      vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string, opts?: any) => {
        let body: any = {};
        try { body = JSON.parse(opts?.body || '{}'); } catch {}

        if (url === '/graphql') {
          if (body.query?.includes('query GetConversation')) {
            return Promise.resolve({
              ok: true,
              status: 200,
              json: () => Promise.resolve({
                data: {
                  conversation: {
                    id: 'conv-b',
                    botId: 'bot-b',
                    title: 'Conversation B',
                    messages: [],
                    runs: []
                  }
                }
              })
            });
          }
          if (body.query?.includes('query GetBot')) {
            return botBLoadPromise.then(bot => ({
              ok: true,
              status: 200,
              json: () => Promise.resolve({ data: { bot } })
            }));
          }
          if (body.query?.includes('createConversation')) {
            mutationCalls++;
          }
        }
        if (url.includes('/runs')) {
          mutationCalls++;
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ id: 'run-x', status: 'COMPLETED' }) });
        }
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({}) });
      }));

      // Switch to conv-b
      const selectPromise = app.selectConversation('conv-b');

      // Immediately upon selectConversation, activeConversationBot MUST be cleared to null
      expect(app.activeConversationBot()).toBeNull();
      expect(app.canSend()).toBe(false);

      // Attempt to submit input while metadata is in-flight: MUST be refused without mutation
      const textForB = 'Draft text intended for B';
      app.inputValue.set(textForB);
      await app.submitInput(textForB);

      // Verify no mutations were emitted
      expect(mutationCalls).toBe(0);
      // Verify text is preserved in conv-b draft, NOT in legacy getDraft
      expect(app.draftStore.getConversationDraft('conv-b')).toBe(textForB);
      expect(app.draftStore.getDraft()).not.toBe(textForB);
      expect(app.streamError()).toBe('助理資料載入中或不存在，目前無法發送訊息');

      // Now resolve Bot B metadata with canChat: false
      resolveBotBLoad!(botBOff);
      await selectPromise;
      await fixture.whenStable();

      // Bot B is now authoritatively bound, canSend remains false
      expect(app.activeConversationBot()?.id).toBe('bot-b');
      expect(app.canSend()).toBe(false);

      // Attempt to submit again with loaded OFF bot: still refused without mutation
      await app.submitInput(textForB);
      expect(mutationCalls).toBe(0);
      expect(app.streamError()).toBe('助理目前處於未連線（停用）狀態，無法發送訊息');
      expect(app.draftStore.getConversationDraft('conv-b')).toBe(textForB);
    });

    it('late run failure on Bot A restores draft to Bot A without contaminating Bot B empty draft', async () => {
      const fixture = TestBed.createComponent(App);
      const app = fixture.componentInstance;
      await vi.waitFor(() => expect(app.isInitializing()).toBe(false));

      const draftMsgA = 'Draft message specifically for Bot A';

      // 1. Snapshot Bot A pending request info (e.g. from an in-flight submission)
      const pendingA: any = {
        conversationId: 'conv-a-late-fail',
        clientRequestId: 'req-fail-a',
        message: draftMsgA,
        tempUserId: 'u-1',
        tempAssistantId: 'a-1',
        isFromNewDraft: true,
        submittedSnapshot: draftMsgA,
        draftBotId: 'bot-a'
      };

      // 2. Clear any pre-saved draft for botA in store to verify restoration strictly comes from failure handler
      app.draftStore.clearBotDraft(botA.id);
      expect(app.draftStore.getBotDraft('bot-a')).toBe('');

      // 3. User navigates to Bot B draft (which is currently empty)
      app.openBotDraft(botBOff);
      expect(app.activeDraftBot()?.id).toBe('bot-b');
      expect(app.inputValue()).toBe('');
      expect(app.draftStore.getBotDraft('bot-b')).toBe('');

      // 4. Now Bot A's late run failure arrives
      (app as any).handleRunFailedOrCancelled(pendingA);

      // Bot A draft is restored in DraftStore
      expect(app.draftStore.getBotDraft('bot-a')).toBe(draftMsgA);

      // Current view (Bot B) input remains empty and Bot B draft is NOT contaminated
      // RATIONALE: if handleRunFailedOrCancelled loosely checked !convId without verifying currentBot()?.id === draftBotId,
      // it would incorrectly overwrite Bot B's visible input with Bot A's draft text.
      expect(app.inputValue()).toBe('');
      expect(app.draftStore.getBotDraft('bot-b')).toBe('');

      // When switching back to Bot A, its restored draft is presented
      app.openBotDraft(botA);
      expect(app.inputValue()).toBe(draftMsgA);
    });

    it('when local draft and GKE draft have identical text, GKE run completion does NOT clear local draft', async () => {
      const fixture = TestBed.createComponent(App);
      const app = fixture.componentInstance;
      await vi.waitFor(() => expect(app.isInitializing()).toBe(false));

      const identicalText = 'Identical draft text across local and GKE';

      // 1. Setup local draft with identicalText
      app.newConversation();
      app.inputValue.set(identicalText);
      app.draftStore.setDraft(identicalText);
      app.draftStore.setBotDraft(defaultTestLocalBot.id, identicalText);

      // 2. Open GKE draft, set identicalText, and submit
      app.openBotDraft(botSim);
      app.inputValue.set(identicalText);

      vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string, opts?: any) => {
        let body: any = {};
        try { body = JSON.parse(opts?.body || '{}'); } catch {}
        if (url === '/graphql') {
          if (body.query?.includes('createConversation')) {
            return Promise.resolve({
              ok: true,
              status: 200,
              json: () => Promise.resolve({
                data: {
                  createConversation: { id: 'conv-gke-success', botId: botSim.id, title: 'New Conversation' }
                }
              })
            });
          }
          if (body.query?.includes('defaultLocalBot') || body.query?.includes('bots')) {
            return Promise.resolve({
              ok: true,
              status: 200,
              json: () => Promise.resolve({ data: { defaultLocalBot: defaultTestLocalBot, bots: [defaultTestLocalBot] } })
            });
          }
        }
        if (url.includes('/runs')) {
          return Promise.resolve({
            ok: true,
            status: 200,
            headers: new Headers({ 'content-type': 'application/json' }),
            json: () => Promise.resolve({ id: 'run-gke-ok', status: 'COMPLETED' })
          });
        }
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({}) });
      }));

      await app.submitInput(identicalText);

      // GKE draft is cleared
      expect(app.draftStore.getBotDraft(botSim.id)).toBe('');

      // BUT local draft MUST NOT be cleared
      expect(app.draftStore.getDraft()).toBe(identicalText);
      expect(app.draftStore.getBotDraft(defaultTestLocalBot.id)).toBe(identicalText);
    });

    it('when local draft is empty, GKE run failure does NOT populate local draft', async () => {
      const fixture = TestBed.createComponent(App);
      const app = fixture.componentInstance;
      await vi.waitFor(() => expect(app.isInitializing()).toBe(false));

      // 1. Ensure local draft is completely empty
      app.newConversation();
      app.draftStore.clearDraft();
      app.draftStore.clearBotDraft(defaultTestLocalBot.id);
      expect(app.draftStore.getDraft()).toBe('');

      // 2. Open GKE draft, enter message, and submit with run returning 500
      const gkeMsg = 'GKE message that fails';
      app.openBotDraft(botSim);
      app.inputValue.set(gkeMsg);

      vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string, opts?: any) => {
        let body: any = {};
        try { body = JSON.parse(opts?.body || '{}'); } catch {}
        if (url === '/graphql') {
          if (body.query?.includes('createConversation')) {
            return Promise.resolve({
              ok: true,
              status: 200,
              json: () => Promise.resolve({
                data: {
                  createConversation: { id: 'conv-gke-fail', botId: botSim.id, title: 'New Conversation' }
                }
              })
            });
          }
          if (body.query?.includes('defaultLocalBot') || body.query?.includes('bots')) {
            return Promise.resolve({
              ok: true,
              status: 200,
              json: () => Promise.resolve({ data: { defaultLocalBot: defaultTestLocalBot, bots: [defaultTestLocalBot] } })
            });
          }
        }
        if (url.includes('/runs')) {
          return Promise.resolve({
            ok: false,
            status: 500,
            headers: new Headers({ 'content-type': 'application/json' }),
            json: () => Promise.resolve({ error: 'GKE run 500' })
          });
        }
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({}) });
      }));

      await app.submitInput(gkeMsg);

      // GKE draft is restored to botSim
      expect(app.draftStore.getBotDraft(botSim.id)).toBe(gkeMsg);

      // BUT local draft MUST remain completely empty
      expect(app.draftStore.getDraft()).toBe('');
      expect(app.draftStore.getBotDraft(defaultTestLocalBot.id)).toBe('');
    });

    it('openBotDraft switches to bot draft without backend createConversation and isolates draft by botId', async () => {
      const fixture = TestBed.createComponent(App);
      const app = fixture.componentInstance;
      await vi.waitFor(() => expect(app.isInitializing()).toBe(false));

      let createCalls = 0;
      vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string, opts?: any) => {
        let body: any = {};
        try { body = JSON.parse(opts?.body || '{}'); } catch {}
        if (body.query?.includes('createConversation')) {
          createCalls++;
        }
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({}) });
      }));

      // Open Bot A draft
      app.openBotDraft(botA);
      expect(createCalls).toBe(0);
      expect(app.currentConversationId()).toBeNull();
      expect(app.currentTitle()).toContain(botA.name);

      app.inputValue.set('Draft for Bot A');
      app.draftStore.setBotDraft(botA.id, 'Draft for Bot A');

      // Open Bot Sim draft
      app.openBotDraft(botSim);
      expect(createCalls).toBe(0);
      expect(app.currentConversationId()).toBeNull();
      expect(app.currentTitle()).toContain(botSim.name);
      expect(app.inputValue()).toBe('');

      app.inputValue.set('Draft for Bot Sim');
      app.draftStore.setBotDraft(botSim.id, 'Draft for Bot Sim');

      // Switching back to Bot A restores Bot A's draft
      app.openBotDraft(botA);
      expect(createCalls).toBe(0);
      expect(app.inputValue()).toBe('Draft for Bot A');

      // Switching to New Chat restores default local bot draft without backend create
      app.newConversation();
      expect(createCalls).toBe(0);
      expect(app.currentConversationId()).toBeNull();
    });

    it('displays UNAVAILABLE banner and disables Send button when bot cannot chat', async () => {
      const fixture = TestBed.createComponent(App);
      const app = fixture.componentInstance;
      await vi.waitFor(() => expect(app.isInitializing()).toBe(false));

      app.openBotDraft(botBOff);
      fixture.detectChanges();

      const compiled = fixture.nativeElement as HTMLElement;
      const banner = compiled.querySelector('[data-testid="disabled-bot-banner"]');
      expect(banner).toBeTruthy();
      expect(banner?.textContent).toContain('未連線（停用）');

      const sendHost = compiled.querySelector('[data-testid="send-message-btn"]') as HTMLElement;
      const innerBtn = sendHost?.querySelector('button') as HTMLButtonElement | null;
      const isDisabled = (sendHost as any)?.disabled ?? innerBtn?.disabled ?? sendHost?.hasAttribute('disabled');
      expect(isDisabled).toBe(true);
      expect(app.canSend()).toBe(false);
    });

    it('displays SPRING_AI_SIMULATION banner and enables Send button when bot is simulated', async () => {
      const fixture = TestBed.createComponent(App);
      const app = fixture.componentInstance;
      await vi.waitFor(() => expect(app.isInitializing()).toBe(false));

      app.openBotDraft(botSim);
      app.inputValue.set('Valid message text for simulation');
      fixture.detectChanges();

      const compiled = fixture.nativeElement as HTMLElement;
      const banner = compiled.querySelector('[data-testid="simulation-banner"]');
      expect(banner).toBeTruthy();
      expect(banner?.textContent).toContain('本機模擬模式：');

      const sendHost = compiled.querySelector('[data-testid="send-message-btn"]') as HTMLElement;
      const innerBtn = sendHost?.querySelector('button') as HTMLButtonElement | null;
      const isDisabled = (sendHost as any)?.disabled ?? innerBtn?.disabled ?? sendHost?.hasAttribute('disabled');
      expect(isDisabled).toBe(false);
      expect(app.canSend()).toBe(true);
    });

    it('history submission success or failure leaves unsubmitted new draft of the same bot untouched', async () => {
      const fixture = TestBed.createComponent(App);
      const app = fixture.componentInstance;
      await vi.waitFor(() => expect(app.isInitializing()).toBe(false));

      // 1. User writes an unsubmitted new draft for botA
      const unsubmittedNewDraft = 'Unsubmitted new draft for Bot A';
      app.openBotDraft(botA);
      app.inputValue.set(unsubmittedNewDraft);
      app.draftStore.setBotDraft(botA.id, unsubmittedNewDraft);

      // 2. User switches to existing conversation (conv-existing-1) which also uses botA
      app.currentConversationId.set('conv-existing-1');
      app.activeConversationBot.set(botA);
      const convInput = 'Message in existing conversation';
      app.inputValue.set(convInput);
      app.draftStore.setConversationDraft('conv-existing-1', convInput);

      let runStatusToReturn = 'COMPLETED';
      vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
        if (url.includes('/runs')) {
          if (runStatusToReturn === 'COMPLETED') {
            return Promise.resolve({
              ok: true,
              status: 200,
              headers: new Headers({ 'content-type': 'application/json' }),
              json: () => Promise.resolve({ id: 'run-history-success', status: 'COMPLETED' })
            });
          } else {
            return Promise.resolve({
              ok: false,
              status: 500,
              headers: new Headers({ 'content-type': 'application/json' }),
              json: () => Promise.resolve({ error: 'Failure in history run' })
            });
          }
        }
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({}) });
      }));

      // Submit in existing conversation: SUCCESS
      await app.submitInput(convInput);

      // conv-existing-1 draft is cleared on completion
      expect(app.draftStore.getConversationDraft('conv-existing-1')).toBe('');
      // BUT unsubmitted new draft for botA remains completely untouched!
      expect(app.draftStore.getBotDraft(botA.id)).toBe(unsubmittedNewDraft);

      // Now test FAILURE in another history conversation
      app.currentConversationId.set('conv-existing-2');
      app.activeConversationBot.set(botA);
      const failInput = 'Message that will fail in existing conv';
      app.inputValue.set(failInput);
      app.draftStore.setConversationDraft('conv-existing-2', failInput);

      runStatusToReturn = 'FAILED';
      await app.submitInput(failInput);

      // conv-existing-2 draft is restored on failure
      expect(app.draftStore.getConversationDraft('conv-existing-2')).toBe(failInput);
      // AND unsubmitted new draft for botA STILL remains completely untouched!
      expect(app.draftStore.getBotDraft(botA.id)).toBe(unsubmittedNewDraft);
    });

    it('keyboard capability guard: in OFF mode, unshifted Enter prevents mutation and retains exact raw text; Shift+Enter and IME are not intercepted', async () => {
      const fixture = TestBed.createComponent(App);
      const app = fixture.componentInstance;
      await vi.waitFor(() => expect(app.isInitializing()).toBe(false));

      let mutationCalls = 0;
      vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string, opts?: any) => {
        let body: any = {};
        try { body = JSON.parse(opts?.body || '{}'); } catch {}
        if (body.query?.includes('createConversation') || url.includes('/runs')) {
          mutationCalls++;
        }
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: {} }) });
      }));

      // 1. Open OFF bot draft
      app.openBotDraft(botBOff);
      fixture.detectChanges();

      const compiled = fixture.nativeElement as HTMLElement;
      const textarea = compiled.querySelector('textarea[copilotChatTextarea]') as HTMLTextAreaElement;
      expect(textarea).toBeTruthy();

      const rawOffText = 'Exact raw text for disabled bot\nwith multiple lines   ';
      textarea.value = rawOffText;
      textarea.dispatchEvent(new Event('input', { bubbles: true }));
      fixture.detectChanges();

      expect(app.inputValue()).toBe(rawOffText);

      // 2. Press unshifted Enter: capability guard must prevent default and prevent SDK send()
      const enterEvent = new KeyboardEvent('keydown', { key: 'Enter', shiftKey: false, cancelable: true, bubbles: true });
      textarea.dispatchEvent(enterEvent);
      fixture.detectChanges();

      // Assert event was prevented, no mutation occurred, and text was NOT cleared
      expect(enterEvent.defaultPrevented).toBe(true);
      expect(mutationCalls).toBe(0);
      expect(app.inputValue()).toBe(rawOffText);
      expect(textarea.value).toBe(rawOffText);
      expect(app.draftStore.getBotDraft(botBOff.id)).toBe(rawOffText);

      // 3. Shift+Enter: must NOT be prevented (allow newlines)
      const shiftEnterEvent = new KeyboardEvent('keydown', { key: 'Enter', shiftKey: true, cancelable: true, bubbles: true });
      textarea.dispatchEvent(shiftEnterEvent);
      expect(shiftEnterEvent.defaultPrevented).toBe(false);

      // 4. IME composition Enter (keyCode 229, key 'Process', or isComposing): must NOT be prevented by capability guard
      const imeEvent = new KeyboardEvent('keydown', { key: 'Process', keyCode: 229, isComposing: true, cancelable: true, bubbles: true });
      textarea.dispatchEvent(imeEvent);
      expect(imeEvent.defaultPrevented).toBe(false);
    });

    it('keyboard capability guard: in SIMULATION mode, unshifted Enter executes normal submission', async () => {
      const fixture = TestBed.createComponent(App);
      const app = fixture.componentInstance;
      await vi.waitFor(() => expect(app.isInitializing()).toBe(false));

      let runPostCalled = false;
      vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string, opts?: any) => {
        let body: any = {};
        try { body = JSON.parse(opts?.body || '{}'); } catch {}
        if (url === '/graphql') {
          if (body.query?.includes('createConversation')) {
            return Promise.resolve({
              ok: true,
              status: 200,
              json: () => Promise.resolve({
                data: {
                  createConversation: { id: 'conv-sim-enter', botId: botSim.id, title: 'New Conversation' }
                }
              })
            });
          }
          if (body.query?.includes('defaultLocalBot') || body.query?.includes('bots')) {
            return Promise.resolve({
              ok: true,
              status: 200,
              json: () => Promise.resolve({ data: { defaultLocalBot: defaultTestLocalBot, bots: [defaultTestLocalBot] } })
            });
          }
        }
        if (url.includes('/runs')) {
          runPostCalled = true;
          return Promise.resolve({
            ok: true,
            status: 200,
            headers: new Headers({ 'content-type': 'application/json' }),
            json: () => Promise.resolve({ id: 'run-sim-1', status: 'COMPLETED' })
          });
        }
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({}) });
      }));

      // Open Simulation bot draft
      app.openBotDraft(botSim);
      fixture.detectChanges();

      const compiled = fixture.nativeElement as HTMLElement;
      const textarea = compiled.querySelector('textarea[copilotChatTextarea]') as HTMLTextAreaElement;
      expect(textarea).toBeTruthy();

      const simQuery = 'Simulated query via keyboard';
      textarea.value = simQuery;
      textarea.dispatchEvent(new Event('input', { bubbles: true }));
      fixture.detectChanges();

      // Press Enter: in SIM mode, it delegates to SDK and submits
      const enterEvent = new KeyboardEvent('keydown', { key: 'Enter', shiftKey: false, cancelable: true, bubbles: true });
      textarea.dispatchEvent(enterEvent);
      await fixture.whenStable();

      expect(runPostCalled).toBe(true);
    });
  });
});
