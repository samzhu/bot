import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { appConfig } from './app.config';
import { App } from './app';
import { DraftStore } from './draft-store';
import { ConversationListStore } from './conversation-list-store';

describe('App', () => {
  beforeEach(async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({
          data: {
            bots: [{ id: 'bot-1', name: 'General Assistant', createdAt: '2026-01-01' }],
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
    await fixture.whenStable();

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
    await fixture.whenStable();

    const fetchSpy = vi.fn();
    vi.stubGlobal('fetch', fetchSpy);

    await app.submitInput('   ');
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(app.conversations().length).toBe(0);
  });

  it('first valid non-empty submit creates conversation once and starts run', async () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    await fixture.whenStable();
    fixture.detectChanges();

    const fetchCalls: { url: string; body: any }[] = [];
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string, init?: any) => {
      const body = init?.body ? JSON.parse(init.body) : null;
      fetchCalls.push({ url, body });

      if (url === '/graphql') {
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
            bots: [{ id: 'bot-1', name: 'General Assistant', createdAt: '2026-01-01' }],
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
    await fixture.whenStable();
    fixture.detectChanges();

    // Setup an isolated draft for new chat
    app.draftStore.setDraft('Preserved new chat draft');

    // Set active conversation
    app.currentConversationId.set('conv-existing-99');

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
    await fixture.whenStable();

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
    await fixture.whenStable();
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
    await fixture.whenStable();

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
    await fixture.whenStable();

    let runAttempts = 0;
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string, init?: any) => {
      const body = init?.body ? JSON.parse(init.body) : null;
      if (url === '/graphql') {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({
            data: {
              createConversation: { id: 'conv-500-retry', botId: 'bot-1', title: 'New Conversation' },
              bots: [{ id: 'bot-1' }]
            }
          })
        });
      }
      if (url.includes('/runs')) {
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
    app.retryPending();
    await fixture.whenStable();

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
});
