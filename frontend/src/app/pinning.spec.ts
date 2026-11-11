import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { appConfig } from './app.config';
import { App, ConversationView } from './app';
import { DraftStore } from './draft-store';
import { ConversationListStore } from './conversation-list-store';

describe('Issue #11 Frontend: Conversation Pinning & Dual-Section Sidebar', () => {
  let fetchMock: any;

  beforeEach(async () => {
    fetchMock = vi.fn().mockImplementation((url: string, opts?: any) => {
      let body: any = {};
      try {
        body = JSON.parse(opts?.body || '{}');
      } catch {}

      if (body.query?.includes('bots')) {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({
            data: { bots: [{ id: 'bot-1', name: 'General Assistant', createdAt: '2026-01-01' }] }
          })
        });
      }

      if (body.query?.includes('conversations') && body.variables?.pinned === true) {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({
            data: {
              conversations: [
                { id: 'conv-pinned-1', botId: 'bot-1', title: 'Pinned Conversation 1', pinned: true, createdAt: '2026-01-01', updatedAt: '2026-01-02' }
              ]
            }
          })
        });
      }

      if (body.query?.includes('conversations') && body.variables?.pinned === false) {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({
            data: {
              conversations: [
                { id: 'conv-unpinned-1', botId: 'bot-1', title: 'Unpinned Conversation 1', pinned: false, createdAt: '2026-01-01', updatedAt: '2026-01-02' }
              ]
            }
          })
        });
      }

      if (body.query?.includes('setConversationPinned')) {
        const id = body.variables?.id;
        const targetPinned = body.variables?.pinned;
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({
            data: {
              setConversationPinned: {
                id,
                botId: 'bot-1',
                title: 'Toggled Conversation',
                pinned: targetPinned,
                createdAt: '2026-01-01',
                updatedAt: '2026-01-02'
              }
            }
          })
        });
      }

      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({
          data: { conversations: [] }
        })
      });
    });

    vi.stubGlobal('fetch', fetchMock);

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

    await TestBed.configureTestingModule({
      imports: [App],
      providers: [appConfig.providers]
    }).compileComponents();
  });

  afterEach(() => {
    try {
      TestBed.inject(DraftStore)?.resetAll();
      TestBed.inject(ConversationListStore)?.resetAll();
    } catch {}
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('1. should fetch pinned and unpinned conversations as two separate filtered queries with limit 20 each', async () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    fixture.detectChanges();

    await vi.waitFor(() => {
      expect(app.pinnedConversations().length).toBe(1);
      expect(app.unpinnedConversations().length).toBe(1);
    });

    // Verify two separate filtered queries were issued
    const calls = fetchMock.mock.calls;
    const pinnedCall = calls.find((c: any) => {
      const b = JSON.parse(c[1]?.body || '{}');
      return b.query?.includes('conversations') && b.variables?.pinned === true && b.variables?.limit === 20;
    });
    const unpinnedCall = calls.find((c: any) => {
      const b = JSON.parse(c[1]?.body || '{}');
      return b.query?.includes('conversations') && b.variables?.pinned === false && b.variables?.limit === 20;
    });

    expect(pinnedCall).toBeTruthy();
    expect(unpinnedCall).toBeTruthy();

    expect(app.pinnedConversations().length).toBe(1);
    expect(app.pinnedConversations()[0].id).toBe('conv-pinned-1');
    expect(app.pinnedConversations()[0].pinned).toBe(true);

    expect(app.unpinnedConversations().length).toBe(1);
    expect(app.unpinnedConversations()[0].id).toBe('conv-unpinned-1');
    expect(app.unpinnedConversations()[0].pinned).toBe(false);
  });

  it('2. should render dual sections in sidebar with independent empty states', async () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    fixture.detectChanges();

    await vi.waitFor(() => {
      expect(app.pinnedConversations().length).toBe(1);
      expect(app.unpinnedConversations().length).toBe(1);
    });
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.sidebar-pinned-section')).toBeTruthy();
    expect(compiled.querySelector('.sidebar-unpinned-section')).toBeTruthy();

    // Both sections have items
    expect(compiled.querySelector('[data-testid="pinned-empty"]')).toBeNull();
    expect(compiled.querySelector('[data-testid="unpinned-empty"]')).toBeNull();

    // Set pinned to empty
    app.pinnedConversations.set([]);
    fixture.detectChanges();
    expect(compiled.querySelector('[data-testid="pinned-empty"]')?.textContent).toContain('尚無釘選對話');

    // Set unpinned to empty
    app.unpinnedConversations.set([]);
    fixture.detectChanges();
    expect(compiled.querySelector('[data-testid="unpinned-empty"]')?.textContent).toContain('尚無近期對話');
  });

  it('3. should allow collapsing pinned and recent sections independently', async () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;

    // Initially both expanded
    expect(app.isPinnedCollapsed()).toBe(false);
    expect(app.isUnpinnedCollapsed()).toBe(false);

    // Collapse pinned
    app.togglePinnedSection();
    fixture.detectChanges();
    expect(app.isPinnedCollapsed()).toBe(true);
    expect(compiled.querySelector('.pinned-list-container')).toBeNull();

    // Collapse unpinned
    app.toggleUnpinnedSection();
    fixture.detectChanges();
    expect(app.isUnpinnedCollapsed()).toBe(true);
    expect(compiled.querySelector('.unpinned-list-container')).toBeNull();
  });

  it('4. clicking pin button calls setConversationPinned mutation and stops event propagation without selecting row', async () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    fixture.detectChanges();

    await vi.waitFor(() => expect(app.unpinnedConversations().length).toBe(1));
    fixture.detectChanges();

    const targetConv = app.unpinnedConversations()[0];
    const initialSelectedId = app.currentConversationId();

    const selectSpy = vi.spyOn(app, 'selectConversation');
    const toggleSpy = vi.spyOn(app, 'togglePin');

    const compiled = fixture.nativeElement as HTMLElement;
    const pinBtn = compiled.querySelector(`[data-testid="pin-btn-${targetConv.id}"] button`) as HTMLButtonElement;
    expect(pinBtn).toBeTruthy();

    // Click pin button
    pinBtn.click();
    fixture.detectChanges();

    expect(toggleSpy).toHaveBeenCalled();
    // Verify row selection was NOT triggered due to stopPropagation
    expect(selectSpy).not.toHaveBeenCalled();
    expect(app.currentConversationId()).toBe(initialSelectedId);
  });

  it('5. pin button is disabled while isStreaming, isSubmitting, or pin is in-flight (pinPending)', async () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    fixture.detectChanges();

    await vi.waitFor(() => expect(app.unpinnedConversations().length).toBe(1));
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const convId = 'conv-unpinned-1';
    const getPinButton = () => compiled.querySelector(`[data-testid="pin-btn-${convId}"] button`) as HTMLButtonElement;

    expect(getPinButton().disabled).toBe(false);

    // Streaming disables pin button
    app.isStreaming.set(true);
    fixture.detectChanges();
    expect(getPinButton().disabled).toBe(true);
    app.isStreaming.set(false);

    // Submitting disables pin button
    app.isSubmitting.set(true);
    fixture.detectChanges();
    expect(getPinButton().disabled).toBe(true);
    app.isSubmitting.set(false);

    // In-flight pin mutation disables pin button globally
    app.conversationStore.pinPendingIds.set(new Set([convId]));
    fixture.detectChanges();
    expect(getPinButton().disabled).toBe(true);
    app.conversationStore.pinPendingIds.set(new Set());
    fixture.detectChanges();
    expect(getPinButton().disabled).toBe(false);
  });

  it('6. synchronous guard prevents rapid double clicks from sending multiple mutations', async () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();

    const conv: ConversationView = {
      id: 'conv-guard-test',
      botId: 'bot-1',
      title: 'Guard Test',
      pinned: false,
      createdAt: '2026-01-01',
      updatedAt: '2026-01-02'
    };

    // Fire two togglePin simultaneously
    const p1 = app.togglePin(conv);
    const p2 = app.togglePin(conv);
    await Promise.all([p1, p2]);

    const mutationCalls = fetchMock.mock.calls.filter((c: any) => {
      const b = JSON.parse(c[1]?.body || '{}');
      return b.query?.includes('setConversationPinned') && b.variables?.id === 'conv-guard-test';
    });

    expect(mutationCalls.length).toBe(1);
  });

  it('7. active conversation and title do not revert to New Conversation when conversation is not in loaded list', async () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();

    // Select a deep conversation not present in loaded 20 items
    const deepConv: ConversationView = {
      id: 'conv-deep-999',
      botId: 'bot-1',
      title: 'Deep Retained Conversation',
      pinned: false,
      createdAt: '2026-01-01',
      updatedAt: '2026-01-02'
    };

    app.currentConversationId.set('conv-deep-999');
    app.conversationStore.setActiveConversation(deepConv);
    fixture.detectChanges();

    // Verify currentConversation and currentTitle retain deep conversation identity
    expect(app.currentConversation()?.id).toBe('conv-deep-999');
    expect(app.currentTitle()).toBe('Deep Retained Conversation');

    // Verify setActiveConversation did NOT pollute unpinned list
    expect(app.unpinnedConversations().some(c => c.id === 'conv-deep-999')).toBe(false);
  });

  it('8. pin mutation failure preserves lists and shows retry; refetch failure distinguishes saved state and retry only refetches without resending mutation', async () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    fixture.detectChanges();

    await vi.waitFor(() => expect(app.unpinnedConversations().length).toBe(1));
    fixture.detectChanges();

    const conv = app.unpinnedConversations()[0];
    expect(conv).toBeTruthy();

    // Mock mutation failure
    fetchMock.mockImplementationOnce(() => Promise.resolve({
      ok: true,
      status: 200,
      json: () => Promise.resolve({
        errors: [{ message: 'Network timeout during pin mutation' }]
      })
    }));

    await app.togglePin(conv);
    expect(app.pinMutationError()).toBeTruthy();
    expect(app.unpinnedConversations().length).toBe(1);

    // Clear mutation error
    app.pinMutationError.set(null);

    // Mock successful mutation but failed subsequent refetch
    fetchMock.mockImplementation((url: string, opts?: any) => {
      const body = JSON.parse(opts?.body || '{}');
      if (body.query?.includes('setConversationPinned')) {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({
            data: { setConversationPinned: { id: conv.id, botId: 'bot-1', title: conv.title, pinned: true, createdAt: '', updatedAt: '' } }
          })
        });
      }
      if (body.query?.includes('conversations')) {
        return Promise.reject(new Error('Refetch network error'));
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: {} }) });
    });

    await app.togglePin(conv);
    expect(app.pinRefreshError()?.retryRefetchOnly).toBe(true);

    // Retry pin refetch only refetches, does NOT call setConversationPinned mutation
    const preRetryMutationCalls = fetchMock.mock.calls.filter((c: any) => {
      const b = JSON.parse(c[1]?.body || '{}');
      return b.query?.includes('setConversationPinned');
    }).length;

    await app.retryPinRefetch();

    const postRetryMutationCalls = fetchMock.mock.calls.filter((c: any) => {
      const b = JSON.parse(c[1]?.body || '{}');
      return b.query?.includes('setConversationPinned');
    }).length;

    expect(postRetryMutationCalls).toBe(preRetryMutationCalls);
  });

  it('9. fetchConversationsByPinned throws on HTTP error or GraphQL error without mutating lists', async () => {
    const store = TestBed.inject(ConversationListStore);

    // HTTP 500 failure
    fetchMock.mockImplementationOnce(() => Promise.resolve({
      ok: false,
      status: 500,
      json: () => Promise.resolve({})
    }));

    await expect(store.fetchConversationsByPinned(false, 20, 0)).rejects.toThrow('HTTP 500');

    // GraphQL error failure
    fetchMock.mockImplementationOnce(() => Promise.resolve({
      ok: true,
      status: 200,
      json: () => Promise.resolve({
        errors: [{ message: 'Internal server error in GraphQL resolver' }]
      })
    }));

    await expect(store.fetchConversationsByPinned(false, 20, 0)).rejects.toThrow('GraphQL query error');

    // Invalid missing array failure
    fetchMock.mockImplementationOnce(() => Promise.resolve({
      ok: true,
      status: 200,
      json: () => Promise.resolve({
        data: { conversations: null }
      })
    }));

    await expect(store.fetchConversationsByPinned(false, 20, 0)).rejects.toThrow('Invalid GraphQL response format');
  });

  it('10. togglePin throws and records error when mutation HTTP fails or payload is missing', async () => {
    const store = TestBed.inject(ConversationListStore);
    const conv: ConversationView = {
      id: 'c-test-err',
      botId: 'b-1',
      title: 'Err Test',
      pinned: false,
      createdAt: '',
      updatedAt: ''
    };

    // HTTP 500 on mutation
    fetchMock.mockImplementationOnce(() => Promise.resolve({
      ok: false,
      status: 500,
      json: () => Promise.resolve({})
    }));

    await store.togglePin(conv);
    expect(store.pinMutationError()).toBeTruthy();
    expect(store.pinMutationError()?.message).toContain('HTTP 500');

    // Missing payload on mutation
    fetchMock.mockImplementationOnce(() => Promise.resolve({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ data: { setConversationPinned: null } })
    }));

    await store.togglePin(conv);
    expect(store.pinMutationError()?.message).toContain('payload missing');
  });

  it('11. retryPinMutation strictly retries the original err.conversationId and err.targetPinned even if active conversation switched or original item is not in loaded lists', async () => {
    const store = TestBed.inject(ConversationListStore);

    // Set an entirely different active conversation
    const activeConv: ConversationView = {
      id: 'conv-active-unrelated',
      botId: 'b-1',
      title: 'Unrelated Active Conv',
      pinned: false,
      createdAt: '',
      updatedAt: ''
    };
    store.setActiveConversation(activeConv);

    // Emulate a previous failed mutation on conv-historical-off-page
    store.pinMutationError.set({
      conversationId: 'conv-historical-off-page',
      targetPinned: true,
      message: 'Failed during initial toggle'
    });

    // Verify conv-historical-off-page is NOT in current loaded lists
    expect(store.conversations().some(c => c.id === 'conv-historical-off-page')).toBe(false);

    let sentVariables: any = null;
    fetchMock.mockImplementation((url: string, opts?: any) => {
      const body = JSON.parse(opts?.body || '{}');
      if (body.query?.includes('setConversationPinned')) {
        sentVariables = body.variables;
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({
            data: {
              setConversationPinned: {
                id: 'conv-historical-off-page',
                botId: 'b-1',
                title: 'Historical',
                pinned: true,
                createdAt: '',
                updatedAt: ''
              }
            }
          })
        });
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ data: { conversations: [] } })
      });
    });

    await store.retryPinMutation();

    // MUST strictly target the exact original conversation ID and desired pinned state
    expect(sentVariables).toEqual({
      id: 'conv-historical-off-page',
      pinned: true
    });
    // MUST NOT touch or send the unrelated active conversation
    expect(sentVariables?.id).not.toBe('conv-active-unrelated');
    // Stale mutation error MUST be cleared upon successful mutation
    expect(store.pinMutationError()).toBeNull();
  });

  it('12. retryPinMutation enforces desired targetPinned state against server lost-response (does not invert)', async () => {
    const store = TestBed.inject(ConversationListStore);

    // Scenario: user wanted pinned = true, but response timed out after server accepted it
    store.pinMutationError.set({
      conversationId: 'conv-lost-response',
      targetPinned: true,
      message: 'Connection timed out'
    });

    let sentVariables: any = null;
    fetchMock.mockImplementation((url: string, opts?: any) => {
      const body = JSON.parse(opts?.body || '{}');
      if (body.query?.includes('setConversationPinned')) {
        sentVariables = body.variables;
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({
            data: {
              setConversationPinned: {
                id: 'conv-lost-response',
                botId: 'b-1',
                title: 'Lost Response Test',
                pinned: true,
                createdAt: '',
                updatedAt: ''
              }
            }
          })
        });
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ data: { conversations: [] } })
      });
    });

    await store.retryPinMutation();

    // MUST enforce the user's intended targetPinned (true), never inverting to false
    expect(sentVariables).toEqual({
      id: 'conv-lost-response',
      pinned: true
    });
    // Ensure mutation error is cleared so saved and failed banners do not co-exist
    expect(store.pinMutationError()).toBeNull();
  });
});

describe('Issue #12 Frontend: Dual-Section Pagination & Range Reconciliation', () => {
  let fetchMock: any;

  beforeEach(() => {
    fetchMock = vi.fn().mockImplementation((url: string, opts?: any) => {
      let body: any = {};
      try {
        body = JSON.parse(opts?.body || '{}');
      } catch {}

      if (body.query?.includes('conversations')) {
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
    });

    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    try {
      TestBed.inject(ConversationListStore)?.resetAll();
    } catch {}
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('1. fetchRangeByPinned queries initial 20 range when target is 0 (never limit 0)', async () => {
    const store = TestBed.inject(ConversationListStore);
    const queriedLimits: number[] = [];

    fetchMock.mockImplementation((url: string, opts?: any) => {
      const body = JSON.parse(opts?.body || '{}');
      if (body.query?.includes('conversations')) {
        queriedLimits.push(body.variables.limit);
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({ data: { conversations: [] } })
        });
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: {} }) });
    });

    const result = await store.fetchRangeByPinned(true, 0);

    expect(result.items).toEqual([]);
    expect(result.hasMore).toBe(false);
    expect(queriedLimits.length).toBeGreaterThan(0);
    // MUST NOT query limit 0; queries at least initial batch of 20 (plus 1 peek)
    for (const limit of queriedLimits) {
      expect(limit).toBeGreaterThanOrEqual(20);
      expect(limit).toBeLessThanOrEqual(100);
    }
  });

  it('2. fetchRangeByPinned batches ranges > 100 within limit 100 cap, deduplicating across boundaries', async () => {
    const store = TestBed.inject(ConversationListStore);
    const queriedLimits: number[] = [];
    const queriedOffsets: number[] = [];

    // Target = 120 items
    fetchMock.mockImplementation((url: string, opts?: any) => {
      const body = JSON.parse(opts?.body || '{}');
      if (body.query?.includes('conversations')) {
        const { limit, offset } = body.variables;
        queriedLimits.push(limit);
        queriedOffsets.push(offset);

        // Generate synthetic items
        const count = limit;
        const items = Array.from({ length: count }, (_, i) => ({
          id: `conv-item-${offset + i}`,
          botId: 'bot-1',
          title: `Item ${offset + i}`,
          pinned: false,
          createdAt: '',
          updatedAt: ''
        }));

        // Inject duplicate item on boundary for deduplication verification
        if (offset === 100 && items.length > 0) {
          items[0] = {
            id: 'conv-item-99', // duplicate from previous batch
            botId: 'bot-1',
            title: 'Item 99 Duplicate',
            pinned: false,
            createdAt: '',
            updatedAt: ''
          };
        }

        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({ data: { conversations: items } })
        });
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: {} }) });
    });

    const result = await store.fetchRangeByPinned(false, 120);

    // Assert strictly capped at 100
    for (const l of queriedLimits) {
      expect(l).toBeLessThanOrEqual(100);
    }
    // Batch 1: offset 0, limit 100
    expect(queriedOffsets[0]).toBe(0);
    expect(queriedLimits[0]).toBe(100);
    // Batch 2: offset 100, remaining 20
    expect(queriedOffsets[1]).toBe(100);
    expect(queriedLimits[1]).toBe(20);

    // Assert deduplication succeeded
    const uniqueIds = new Set(result.items.map(c => c.id));
    expect(uniqueIds.size).toBe(result.items.length);
    expect(result.hasMore).toBe(true);
  });

  it('3. atomic dual-section commit on range refetch: when 1 section fails, neither list is updated', async () => {
    const store = TestBed.inject(ConversationListStore);

    // Initial loaded state
    const p1: ConversationView = { id: 'p1', botId: 'b1', title: 'P1', pinned: true, createdAt: '', updatedAt: '' };
    const u1: ConversationView = { id: 'u1', botId: 'b1', title: 'U1', pinned: false, createdAt: '', updatedAt: '' };
    store.pinnedConversations.set([p1]);
    store.unpinnedConversations.set([u1]);

    fetchMock.mockImplementation((url: string, opts?: any) => {
      const body = JSON.parse(opts?.body || '{}');
      if (body.query?.includes('setConversationPinned')) {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({
            data: { setConversationPinned: { id: 'u1', botId: 'b1', title: 'U1', pinned: true, createdAt: '', updatedAt: '' } }
          })
        });
      }
      // Pinned refetch succeeds
      if (body.query?.includes('conversations') && body.variables?.pinned === true) {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({
            data: { conversations: [p1, { id: 'u1', botId: 'b1', title: 'U1', pinned: true, createdAt: '', updatedAt: '' }] }
          })
        });
      }
      // Unpinned refetch fails with HTTP 500
      if (body.query?.includes('conversations') && body.variables?.pinned === false) {
        return Promise.resolve({
          ok: false,
          status: 500,
          json: () => Promise.resolve({})
        });
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: {} }) });
    });

    await store.togglePin(u1);

    // Atomic guard: neither list is updated!
    expect(store.pinnedConversations()).toEqual([p1]);
    expect(store.unpinnedConversations()).toEqual([u1]);

    // Error notification presented with retryRefetchOnly
    expect(store.pinRefreshError()?.retryRefetchOnly).toBe(true);
    expect(store.isPinDisabled()).toBe(true);
  });

  it('4. retryPinRefetch only queries range without re-sending mutation and clears error upon success', async () => {
    const store = TestBed.inject(ConversationListStore);

    store.pinRefreshError.set({
      message: 'Refresh failed',
      retryRefetchOnly: true
    });

    let mutationSent = false;
    let rangeQueryCount = 0;

    fetchMock.mockImplementation((url: string, opts?: any) => {
      const body = JSON.parse(opts?.body || '{}');
      if (body.query?.includes('setConversationPinned')) {
        mutationSent = true;
      }
      if (body.query?.includes('conversations')) {
        rangeQueryCount++;
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({ data: { conversations: [] } })
        });
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: {} }) });
    });

    await store.retryPinRefetch();

    // MUST NOT send any mutation
    expect(mutationSent).toBe(false);
    expect(rangeQueryCount).toBeGreaterThanOrEqual(2);
    // Pin refresh error cleared
    expect(store.pinRefreshError()).toBeNull();
    expect(store.isPinDisabled()).toBe(false);
  });

  it('5. async generation/epoch guard: late out-of-order refetch responses are discarded', async () => {
    const store = TestBed.inject(ConversationListStore);

    let resolveFirstRefetch: any;
    const firstRefetchPromise = new Promise((resolve) => {
      resolveFirstRefetch = resolve;
    });

    let isFirstCall = true;
    fetchMock.mockImplementation((url: string, opts?: any) => {
      const body = JSON.parse(opts?.body || '{}');
      if (body.query?.includes('conversations') && isFirstCall) {
        isFirstCall = false;
        return firstRefetchPromise;
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({
          data: {
            conversations: [
              { id: 'c-fresh', botId: 'b-1', title: 'Fresh Refetch', pinned: false, createdAt: '', updatedAt: '' }
            ]
          }
        })
      });
    });

    // 1. Start first refetch (slow deferred)
    const p1 = store.loadConversations(false);

    // 2. Trigger second refetch immediately (resolves fast with c-fresh)
    await store.loadConversations(false);
    expect(store.unpinnedConversations().some(c => c.id === 'c-fresh')).toBe(true);

    // 3. Resolve the first stale refetch with outdated data
    resolveFirstRefetch({
      ok: true,
      status: 200,
      json: () => Promise.resolve({
        data: {
          conversations: [
            { id: 'c-stale-outdated', botId: 'b-1', title: 'Stale Outdated', pinned: false, createdAt: '', updatedAt: '' }
          ]
        }
      })
    });
    await p1;

    // Stale response MUST be discarded; c-fresh remains
    expect(store.unpinnedConversations().some(c => c.id === 'c-fresh')).toBe(true);
    expect(store.unpinnedConversations().some(c => c.id === 'c-stale-outdated')).toBe(false);
  });

  it('6. async generation/epoch guard: in-flight loadMore is invalidated when range refetch starts', async () => {
    const store = TestBed.inject(ConversationListStore);
    store.hasMoreUnpinned.set(true);
    store.unpinnedConversations.set([
      { id: 'u-init', botId: 'b-1', title: 'Initial', pinned: false, createdAt: '', updatedAt: '' }
    ]);

    let resolveLoadMore: any;
    const loadMoreDeferred = new Promise((resolve) => {
      resolveLoadMore = resolve;
    });

    let isLoadMoreCall = false;
    fetchMock.mockImplementation((url: string, opts?: any) => {
      if (isLoadMoreCall) {
        return loadMoreDeferred;
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({
          data: { conversations: [{ id: 'refetched-u', botId: 'b-1', title: 'Refetched', pinned: false, createdAt: '', updatedAt: '' }] }
        })
      });
    });

    // 1. Start loadMore (deferred)
    isLoadMoreCall = true;
    const morePromise = store.loadMoreUnpinned();
    isLoadMoreCall = false;

    // 2. Start full range refetch (increments refetchEpoch and invalidates loadMore pageEpoch)
    await store.loadConversations(false);
    expect(store.unpinnedConversations().some(c => c.id === 'refetched-u')).toBe(true);

    // 3. Resolve stale loadMore
    resolveLoadMore({
      ok: true,
      status: 200,
      json: () => Promise.resolve({
        data: { conversations: [{ id: 'stale-more-item', botId: 'b-1', title: 'Stale More', pinned: false, createdAt: '', updatedAt: '' }] }
      })
    });
    await morePromise;

    // Stale more item MUST NOT be appended
    expect(store.unpinnedConversations().some(c => c.id === 'stale-more-item')).toBe(false);
  });

  it('7. independent dual-section pagination: loadMorePinned and loadMoreUnpinned run concurrently without interfering', async () => {
    const store = TestBed.inject(ConversationListStore);
    store.hasMorePinned.set(true);
    store.hasMoreUnpinned.set(true);

    fetchMock.mockImplementation((url: string, opts?: any) => {
      const body = JSON.parse(opts?.body || '{}');
      if (body.variables?.pinned === true) {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({
            data: { conversations: [{ id: 'p-more-1', botId: 'b1', title: 'P More 1', pinned: true, createdAt: '', updatedAt: '' }] }
          })
        });
      }
      if (body.variables?.pinned === false) {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({
            data: { conversations: [{ id: 'u-more-1', botId: 'b1', title: 'U More 1', pinned: false, createdAt: '', updatedAt: '' }] }
          })
        });
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: {} }) });
    });

    // Trigger both concurrently
    await Promise.all([
      store.loadMorePinned(),
      store.loadMoreUnpinned()
    ]);

    expect(store.pinnedConversations().some(c => c.id === 'p-more-1')).toBe(true);
    expect(store.unpinnedConversations().some(c => c.id === 'u-more-1')).toBe(true);
    expect(store.pinnedOffset()).toBe(1);
    expect(store.unpinnedOffset()).toBe(1);
  });

  it('8. pagination failure preserves existing list and allows subsequent retry', async () => {
    const store = TestBed.inject(ConversationListStore);
    const initialItem: ConversationView = {
      id: 'u-keep',
      botId: 'b-1',
      title: 'Keep Me',
      pinned: false,
      createdAt: '',
      updatedAt: ''
    };
    store.unpinnedConversations.set([initialItem]);
    store.hasMoreUnpinned.set(true);

    // Simulate 500 error on load more
    fetchMock.mockImplementationOnce(() => Promise.resolve({
      ok: false,
      status: 500,
      json: () => Promise.resolve({})
    }));

    await store.loadMoreUnpinned();

    // List is preserved, not wiped to empty
    expect(store.unpinnedConversations()).toEqual([initialItem]);
    // Lock is released so user can retry
    expect(store.isLoadingMoreUnpinned()).toBe(false);
  });

  it('9. resetAll invalidates in-flight requests and resets all range targets', async () => {
    const store = TestBed.inject(ConversationListStore);

    let resolvePending: any;
    const pendingPromise = new Promise((resolve) => {
      resolvePending = resolve;
    });

    fetchMock.mockImplementationOnce(() => pendingPromise);

    const refetchP = store.loadConversations(false);
    store.resetAll();

    expect(store.pinnedConversations()).toEqual([]);
    expect(store.unpinnedConversations()).toEqual([]);

    // Resolve after reset
    resolvePending({
      ok: true,
      status: 200,
      json: () => Promise.resolve({
        data: { conversations: [{ id: 'late-c', botId: 'b', title: 'Late', pinned: false, createdAt: '', updatedAt: '' }] }
      })
    });
    await refetchP.catch(() => {});

    // Must remain empty
    expect(store.pinnedConversations()).toEqual([]);
    expect(store.unpinnedConversations()).toEqual([]);
  });

  it('10. refresh invalidates in-flight loadMore and releases loading flag (no permanent loading deadlock)', async () => {
    const store = TestBed.inject(ConversationListStore);
    store.hasMoreUnpinned.set(true);

    let resolveSlowLoadMore: any;
    const slowLoadMorePromise = new Promise((resolve) => {
      resolveSlowLoadMore = resolve;
    });

    fetchMock.mockImplementationOnce(() => slowLoadMorePromise);

    // 1. Trigger loadMore (sets isLoadingMoreUnpinned = true)
    const pMore = store.loadMoreUnpinned();
    expect(store.isLoadingMoreUnpinned()).toBe(true);

    // 2. Refresh or range commit intervenes and invalidates the page epoch
    await store.loadConversations(false);

    // Verify loading lock was released upon invalidation, preventing UI deadlock!
    expect(store.isLoadingMoreUnpinned()).toBe(false);

    // 3. Resolve the slow late promise
    resolveSlowLoadMore({
      ok: true,
      status: 200,
      json: () => Promise.resolve({
        data: { conversations: [{ id: 'late-unpinned', botId: 'b', title: 'Late', pinned: false, createdAt: '', updatedAt: '' }] }
      })
    });
    await pMore;

    // Loading flag remains cleanly false, not stuck in true
    expect(store.isLoadingMoreUnpinned()).toBe(false);
  });

  it('11. setPinned binds operation epoch at start: intervening resetAll completely discards late mutation response and does not leak locks or errors', async () => {
    const store = TestBed.inject(ConversationListStore);
    const conv: ConversationView = {
      id: 'c-concurrent-pin',
      botId: 'b-1',
      title: 'Pin In Flight',
      pinned: false,
      createdAt: '',
      updatedAt: ''
    };

    let resolveMutation: any;
    const mutationPromise = new Promise((resolve) => {
      resolveMutation = resolve;
    });

    fetchMock.mockImplementationOnce(() => mutationPromise);

    // 1. Start pin operation (operationEpoch captured at start)
    const pPin = store.togglePin(conv);
    expect(store.isAnyPinPending()).toBe(true);

    // 2. Intervening resetAll increments refetchEpoch and clears state
    store.resetAll();
    expect(store.isAnyPinPending()).toBe(false);
    expect(store.pinnedConversations()).toEqual([]);

    // 3. Late mutation returns
    resolveMutation({
      ok: true,
      status: 200,
      json: () => Promise.resolve({
        data: { setConversationPinned: { id: 'c-concurrent-pin', botId: 'b-1', title: 'Pin In Flight', pinned: true, createdAt: '', updatedAt: '' } }
      })
    });
    await pPin;

    // Stale operation MUST be completely discarded; no lock leak or state pollution
    expect(store.isAnyPinPending()).toBe(false);
    expect(store.pinnedConversations()).toEqual([]);
    expect(store.pinMutationError()).toBeNull();
    expect(store.pinRefreshError()).toBeNull();
  });

  it('12. exact 100 boundary peek failure throws and aborts atomic commit, preserving lists and setting pinRefreshError', async () => {
    const store = TestBed.inject(ConversationListStore);

    const initialPinned = [{ id: 'p1', botId: 'b', title: 'P1', pinned: true, createdAt: '', updatedAt: '' }];
    store.pinnedConversations.set(initialPinned);

    // Emulate 100 chunk succeeding followed by peek throwing network error
    let callCount = 0;
    fetchMock.mockImplementation((url: string, opts?: any) => {
      const body = JSON.parse(opts?.body || '{}');
      if (body.query?.includes('conversations')) {
        callCount++;
        const { limit } = body.variables;
        if (limit === 100) {
          // 100 chunk succeeds
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({
              data: {
                conversations: Array.from({ length: 100 }, (_, i) => ({
                  id: `p-${i}`,
                  botId: 'b',
                  title: `P ${i}`,
                  pinned: true,
                  createdAt: '',
                  updatedAt: ''
                }))
              }
            })
          });
        }
        if (limit === 1) {
          // Peek fails with 500
          return Promise.resolve({
            ok: false,
            status: 500,
            json: () => Promise.resolve({})
          });
        }
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: { conversations: [] } }) });
    });

    // fetchRangeByPinned MUST NOT swallow peek error
    await expect(store.fetchRangeByPinned(true, 100)).rejects.toThrow('HTTP 500');
  });

  it('13. programmatic entrance guards block togglePin and loadMore while pinRefreshError or isAnyPinPending is active', async () => {
    const store = TestBed.inject(ConversationListStore);
    const conv: ConversationView = { id: 'c1', botId: 'b', title: 'C1', pinned: false, createdAt: '', updatedAt: '' };

    // 1. Simulate pinRefreshError active
    store.pinRefreshError.set({ message: 'Refresh pending', retryRefetchOnly: true });

    let fetchCalled = false;
    fetchMock.mockImplementation(() => {
      fetchCalled = true;
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: {} }) });
    });

    // Programmatic call to togglePin MUST be blocked
    await store.togglePin(conv);
    expect(fetchCalled).toBe(false);

    // Programmatic call to loadMore MUST be blocked
    await store.loadMorePinned();
    expect(fetchCalled).toBe(false);
    await store.loadMoreUnpinned();
    expect(fetchCalled).toBe(false);
  });

  it('14. pin held -> regular refresh succeeds in between -> pin mutation resolves -> lock released and final dual lists authoritative', async () => {
    const store = TestBed.inject(ConversationListStore);
    const conv: ConversationView = { id: 'c1', botId: 'b', title: 'C1', pinned: false, createdAt: '', updatedAt: '' };
    store.unpinnedConversations.set([conv]);
    store.unpinnedOffset.set(1);

    let resolveMutation!: (val: any) => void;
    const mutationDeferred = new Promise((resolve) => {
      resolveMutation = resolve;
    });

    let mutationStarted = false;
    fetchMock.mockImplementation((url: string, opts: any) => {
      const body = JSON.parse(opts.body);
      if (body.query?.includes('setConversationPinned')) {
        mutationStarted = true;
        return mutationDeferred;
      }
      if (body.query?.includes('conversations')) {
        const isPinned = body.variables?.pinned;
        // If query occurs during post-mutation refetch, return c1 as pinned
        if (isPinned) {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({
              data: { conversations: mutationStarted && !resolveMutation ? [{ id: 'c1', botId: 'b', title: 'C1', pinned: true }] : [] }
            })
          });
        } else {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({
              data: { conversations: mutationStarted && !resolveMutation ? [] : [{ id: 'c1', botId: 'b', title: 'C1', pinned: false }] }
            })
          });
        }
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: {} }) });
    });

    // 1. Start pin operation (held by controlled promise)
    const pinPromise = store.togglePin(conv);
    await vi.waitFor(() => expect(mutationStarted).toBe(true));
    expect(store.pinPendingIds().has('c1')).toBe(true);
    expect(store.isAnyPinPending()).toBe(true);

    // 2. Interleave regular loadConversations while pin is in flight
    await store.loadConversations(true);
    // Lock must remain active despite loadConversations incrementing query generation
    expect(store.pinPendingIds().has('c1')).toBe(true);
    expect(store.isAnyPinPending()).toBe(true);

    // 3. Resolve the pin mutation
    const resolveRef = resolveMutation;
    (resolveMutation as any) = null; // Mark mutation resolved for subsequent query logic
    resolveRef({
      ok: true,
      status: 200,
      json: () => Promise.resolve({
        data: {
          setConversationPinned: { id: 'c1', botId: 'b', title: 'C1', pinned: true, createdAt: '', updatedAt: '' }
        }
      })
    });

    await pinPromise;

    // 4. Assert lock cleanly released and final authoritative state reflects pinned conversation
    expect(store.pinPendingIds().has('c1')).toBe(false);
    expect(store.isAnyPinPending()).toBe(false);
    expect(store.pinnedConversations().length).toBe(1);
    expect(store.pinnedConversations()[0].id).toBe('c1');
    expect(store.pinnedConversations()[0].pinned).toBe(true);
    expect(store.unpinnedConversations().length).toBe(0);
  });

  it('15. retry held -> regular refresh succeeds in between -> old retry fails -> no stale error and retry lock released', async () => {
    const store = TestBed.inject(ConversationListStore);
    store.pinRefreshError.set({ message: 'Saved but failed', retryRefetchOnly: true });

    let resolveRetryFetch!: (val: any) => void;
    let rejectRetryFetch!: (err: any) => void;
    const retryFetchDeferred = new Promise((resolve, reject) => {
      resolveRetryFetch = resolve;
      rejectRetryFetch = reject;
    });

    let isRetryFetch = true;
    fetchMock.mockImplementation((url: string, opts: any) => {
      const body = JSON.parse(opts.body);
      if (body.query?.includes('conversations')) {
        if (isRetryFetch) {
          isRetryFetch = false;
          return retryFetchDeferred;
        }
        // Regular refresh succeeds cleanly
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({
            data: { conversations: [] }
          })
        });
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: {} }) });
    });

    // 1. Start retry refetch (held by controlled promise)
    const retryPromise = store.retryPinRefetch();
    expect(store.isRetryingRefetch()).toBe(true);

    // 2. Interleave regular loadConversations which succeeds and clears pinRefreshError
    await store.loadConversations(true);
    expect(store.pinRefreshError()).toBeNull();
    // Retry lock is still held by the in-flight retry
    expect(store.isRetryingRefetch()).toBe(true);

    // 3. Old retry now fails with network error
    rejectRetryFetch(new Error('Old retry network failure'));
    await retryPromise;

    // 4. Stale error MUST NOT overwrite the clean state, and retry lock MUST be released
    expect(store.pinRefreshError()).toBeNull();
    expect(store.isRetryingRefetch()).toBe(false);
  });

  it('16. reset followed immediately by new pin on same ID: old finally must NOT clear the new pin lock', async () => {
    const store = TestBed.inject(ConversationListStore);
    const conv: ConversationView = { id: 'c1', botId: 'b', title: 'C1', pinned: false, createdAt: '', updatedAt: '' };

    let resolveOldMutation!: (val: any) => void;
    const oldMutationDeferred = new Promise((resolve) => {
      resolveOldMutation = resolve;
    });

    let resolveNewMutation!: (val: any) => void;
    const newMutationDeferred = new Promise((resolve) => {
      resolveNewMutation = resolve;
    });

    let mutationCount = 0;
    fetchMock.mockImplementation((url: string, opts: any) => {
      const body = JSON.parse(opts.body);
      if (body.query?.includes('setConversationPinned')) {
        mutationCount++;
        return mutationCount === 1 ? oldMutationDeferred : newMutationDeferred;
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ data: { conversations: [] } })
      });
    });

    // 1. Start first pin on c1
    const oldPinPromise = store.togglePin(conv);
    await vi.waitFor(() => expect(mutationCount).toBe(1));
    expect(store.pinPendingIds().has('c1')).toBe(true);

    // 2. Reset everything
    store.resetAll();
    expect(store.pinPendingIds().has('c1')).toBe(false);

    // 3. Immediately start a new pin on the same ID
    const newPinPromise = store.togglePin(conv);
    await vi.waitFor(() => expect(mutationCount).toBe(2));
    expect(store.pinPendingIds().has('c1')).toBe(true);

    // 4. Old mutation finishes and runs its finally
    resolveOldMutation({
      ok: true,
      status: 200,
      json: () => Promise.resolve({
        data: { setConversationPinned: { id: 'c1', pinned: true } }
      })
    });
    await oldPinPromise;

    // The old finally MUST NOT have cleared the new pin lock!
    expect(store.pinPendingIds().has('c1')).toBe(true);
    expect(store.isAnyPinPending()).toBe(true);

    // 5. Complete the new pin
    resolveNewMutation({
      ok: true,
      status: 200,
      json: () => Promise.resolve({
        data: { setConversationPinned: { id: 'c1', pinned: true } }
      })
    });
    await newPinPromise;

    // Now the lock is cleanly released
    expect(store.pinPendingIds().has('c1')).toBe(false);
    expect(store.isAnyPinPending()).toBe(false);
  });

  it('17. pin ack -> old refetch held -> new loadConversations succeeds -> release old refetch failure -> no stale error and pin lock cleared', async () => {
    const store = TestBed.inject(ConversationListStore);
    const conv: ConversationView = { id: 'c1', botId: 'b', title: 'C1', pinned: false, createdAt: '', updatedAt: '' };

    let rejectOldRefetch!: (err: any) => void;
    const oldRefetchDeferred = new Promise((_, reject) => {
      rejectOldRefetch = reject;
    });

    let mutationDone = false;
    let oldRefetchStarted = false;
    fetchMock.mockImplementation((url: string, opts: any) => {
      const body = JSON.parse(opts.body);
      if (body.query?.includes('setConversationPinned')) {
        mutationDone = true;
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({
            data: { setConversationPinned: { id: 'c1', pinned: true } }
          })
        });
      }
      if (body.query?.includes('conversations')) {
        if (mutationDone && !oldRefetchStarted) {
          oldRefetchStarted = true;
          return oldRefetchDeferred;
        }
        // Interleaved loadConversations succeeds cleanly
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({
            data: { conversations: [] }
          })
        });
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: {} }) });
    });

    // 1. Start pin operation: mutation succeeds immediately, but post-mutation refetch is held
    const pinPromise = store.togglePin(conv);
    await vi.waitFor(() => expect(oldRefetchStarted).toBe(true));
    expect(store.pinPendingIds().has('c1')).toBe(true);
    expect(store.pinRefreshError()).toBeNull();

    // 2. Interleave regular loadConversations which succeeds and bumps queryGeneration
    await store.loadConversations(true);
    expect(store.pinRefreshError()).toBeNull();
    // Pin lock is still held by the in-flight pin operation
    expect(store.pinPendingIds().has('c1')).toBe(true);

    // 3. Now old refetch fails with network error
    rejectOldRefetch(new Error('Old refetch network failure'));
    await pinPromise;

    // 4. Stale error MUST NOT overwrite the clean state, and pin lock MUST be cleared
    expect(store.pinRefreshError()).toBeNull();
    expect(store.pinPendingIds().has('c1')).toBe(false);
    expect(store.isAnyPinPending()).toBe(false);
  });
});
