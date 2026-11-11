import { Injectable, computed, signal } from '@angular/core';

export interface ConversationView {
  id: string;
  botId: string;
  title: string;
  pinned: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface PinMutationError {
  conversationId: string;
  targetPinned: boolean;
  message: string;
}

export interface PinRefreshError {
  message: string;
  retryRefetchOnly: boolean;
}

export interface RangeFetchResult {
  items: ConversationView[];
  hasMore: boolean;
}

@Injectable({
  providedIn: 'root'
})
export class ConversationListStore {
  // Dual Filtered Conversation Lists
  readonly pinnedConversations = signal<ConversationView[]>([]);
  readonly unpinnedConversations = signal<ConversationView[]>([]);

  // Combined read-only signal for backward compatibility and general lookups
  readonly conversations = computed<ConversationView[]>(() => [
    ...this.pinnedConversations(),
    ...this.unpinnedConversations()
  ]);

  // Section collapse state
  readonly isPinnedCollapsed = signal<boolean>(false);
  readonly isUnpinnedCollapsed = signal<boolean>(false);

  // Active conversation retention (preserves identity when conversation moves outside loaded window)
  readonly activeConversation = signal<ConversationView | null>(null);

  // In-flight pin mutation tracking & synchronous deduplication guard
  readonly pinPendingIds = signal<Set<string>>(new Set());
  private readonly pendingPinIds = new Set<string>();

  // Global lock signal: true whenever ANY pin mutation is in flight
  readonly isAnyPinPending = computed<boolean>(() => this.pinPendingIds().size > 0);

  // Error tracking for pin mutation vs refresh failure
  readonly pinMutationError = signal<PinMutationError | null>(null);
  readonly pinRefreshError = signal<PinRefreshError | null>(null);

  // Refetch retry in-flight guard
  readonly isRetryingRefetch = signal<boolean>(false);

  // Last reconciled range targets (to accurately retry range refetch without falling back to initial 20)
  private lastTargetPinnedRange = 20;
  private lastTargetUnpinnedRange = 20;

  // Independent Pagination State for Pinned Section
  readonly pinnedOffset = signal<number>(0);
  readonly hasMorePinned = signal<boolean>(false);
  readonly isLoadingMorePinned = signal<boolean>(false);

  // Independent Pagination State for Recent/Unpinned Section
  readonly unpinnedOffset = signal<number>(0);
  readonly hasMoreUnpinned = signal<boolean>(false);
  readonly isLoadingMoreUnpinned = signal<boolean>(false);

  // Aliases for backward compatibility
  readonly conversationOffset = this.unpinnedOffset;
  readonly hasMoreConversations = this.hasMoreUnpinned;
  readonly isLoadingMoreConversations = this.isLoadingMoreUnpinned;

  // Asynchronous Generation & Epoch Counters to discard out-of-order responses
  private queryGeneration = 0;
  private currentPinnedPageEpoch = 0;
  private currentUnpinnedPageEpoch = 0;

  // Pin operation token management (isolates pin locks from query epochs)
  private pinTokenCounter = 0;
  private readonly activePinTokens = new Map<string, number>();

  // Retry refetch token management (isolates retry lock from query epochs)
  private retryTokenCounter = 0;
  private activeRetryToken = 0;

  /**
   * Fetch a single page of conversations by pinned filter.
   * Respects backend validation (throws on !res.ok, errors, or invalid array).
   */
  async fetchConversationsByPinned(pinned: boolean, limit = 20, offset = 0): Promise<ConversationView[]> {
    const res = await fetch('/graphql', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        query: `query GetConversations($pinned: Boolean, $limit: Int, $offset: Int) {
          conversations(pinned: $pinned, limit: $limit, offset: $offset) {
            id
            botId
            title
            pinned
            createdAt
            updatedAt
          }
        }`,
        variables: { pinned, limit, offset }
      })
    });

    if (!res.ok) {
      throw new Error(`Failed to fetch conversations: HTTP ${res.status}`);
    }

    const json = await res.json();
    if (json.errors && json.errors.length > 0) {
      const msg = json.errors[0]?.message || 'GraphQL error fetching conversations';
      throw new Error(`GraphQL query error: ${msg}`);
    }

    if (!json.data || !Array.isArray(json.data.conversations)) {
      throw new Error('Invalid GraphQL response format: conversations array is missing');
    }

    return json.data.conversations;
  }

  /**
   * Reconcile a full loaded range from offset 0 to targetCount.
   * Guarantees:
   * 1. Target count is at least standard initial size of 20 (target = Math.max(20, targetCount)), never limit 0.
   * 2. Batched in chunks <= 100 to strictly respect server limit cap of 100.
   * 3. Exact 100 peek: on 100 boundaries, peeks 1 item to determine hasMore; throws on error without swallowing.
   * 4. Deduplicates items by id while preserving server order.
   */
  async fetchRangeByPinned(pinned: boolean, targetCount: number): Promise<RangeFetchResult> {
    const target = Math.max(20, targetCount);
    let offset = 0;
    const items: ConversationView[] = [];
    let hasMore = false;

    while (offset < target) {
      const remaining = target - offset;
      if (remaining <= 100) {
        // Query remaining batch
        const batchLimit = remaining;
        const batch = await this.fetchConversationsByPinned(pinned, batchLimit, offset);
        items.push(...batch);
        offset += batch.length;
        if (batch.length < batchLimit) {
          hasMore = false;
        } else {
          // Exactly hit target: peek 1 item to determine exact hasMore (no swallowing errors!)
          const peek = await this.fetchConversationsByPinned(pinned, 1, offset);
          hasMore = peek.length > 0;
        }
        break;
      } else {
        // Fetch chunk of 100 (server cap)
        const batchLimit = 100;
        const batch = await this.fetchConversationsByPinned(pinned, batchLimit, offset);
        items.push(...batch);
        offset += batch.length;
        if (batch.length < 100) {
          hasMore = false;
          break;
        }
      }
    }

    // Deduplicate while preserving chronological / sort order
    const seenIds = new Set<string>();
    const deduplicated: ConversationView[] = [];
    for (const item of items) {
      if (!seenIds.has(item.id)) {
        seenIds.add(item.id);
        deduplicated.push(item);
      }
    }

    return { items: deduplicated, hasMore };
  }

  /**
   * Shared atomic range-commit workflow.
   * Used by loadConversations, setPinned, and retryPinRefetch to guarantee identical behavior:
   * 1. Fetches ranges for both pinned and unpinned concurrently.
   * 2. Guards against stale generation epochs.
   * 3. Mutates signals in a single synchronous turn ONLY after both succeed.
   * 4. Clears pinRefreshError on success.
   */
  private async commitRangeRefetch(targetPinned: number, targetUnpinned: number, queryGen: number): Promise<boolean> {
    const [pinnedResult, unpinnedResult] = await Promise.all([
      this.fetchRangeByPinned(true, targetPinned),
      this.fetchRangeByPinned(false, targetUnpinned)
    ]);

    // Discard stale query results if a newer query generation started in the meantime
    if (queryGen !== this.queryGeneration) {
      return false;
    }

    // Single synchronous commit across both sections
    this.pinnedConversations.set(pinnedResult.items);
    this.pinnedOffset.set(pinnedResult.items.length);
    this.hasMorePinned.set(pinnedResult.hasMore);

    this.unpinnedConversations.set(unpinnedResult.items);
    this.unpinnedOffset.set(unpinnedResult.items.length);
    this.hasMoreUnpinned.set(unpinnedResult.hasMore);

    this.pinRefreshError.set(null);
    return true;
  }

  /**
   * Loads initial conversation lists or reconciles currently loaded ranges across sections.
   */
  async loadConversations(preserveRanges = true): Promise<void> {
    const queryGen = ++this.queryGeneration;
    // Invalidate any in-flight pagination requests and release their loading state
    this.invalidatePaginationRequests();

    const targetPinned = preserveRanges ? Math.max(20, this.pinnedConversations().length) : 20;
    const targetUnpinned = preserveRanges ? Math.max(20, this.unpinnedConversations().length) : 20;
    this.lastTargetPinnedRange = targetPinned;
    this.lastTargetUnpinnedRange = targetUnpinned;

    try {
      await this.commitRangeRefetch(targetPinned, targetUnpinned, queryGen);
    } catch (err) {
      if (queryGen !== this.queryGeneration) return;
      console.error('Failed to load conversations in ConversationListStore:', err);
      throw err;
    }
  }

  /**
   * Releases loading state for both pagination sections when superseded by a refresh or reset.
   */
  private invalidatePaginationRequests(): void {
    this.currentPinnedPageEpoch++;
    this.currentUnpinnedPageEpoch++;
    this.isLoadingMorePinned.set(false);
    this.isLoadingMoreUnpinned.set(false);
  }

  /**
   * Load more items for the Pinned section.
   * Independent pagination offset, generation epoch, and loading lock.
   */
  async loadMorePinned(): Promise<void> {
    // Entrance guard: blocked if currently loading, no more items, or pin mutation in progress
    if (this.isLoadingMorePinned() || !this.hasMorePinned() || this.isAnyPinPending() || this.pinRefreshError() !== null) {
      return;
    }

    this.isLoadingMorePinned.set(true);
    const pageEpoch = ++this.currentPinnedPageEpoch;
    const queryGen = this.queryGeneration;

    try {
      const limit = 20;
      const offset = this.pinnedOffset();
      // Peek 1 extra item (limit 21) to establish exact hasMore in a single query
      const more = await this.fetchConversationsByPinned(true, limit + 1, offset);

      if (pageEpoch !== this.currentPinnedPageEpoch || queryGen !== this.queryGeneration) {
        return;
      }

      const hasMore = more.length > limit;
      const itemsToAdd = hasMore ? more.slice(0, limit) : more;

      if (itemsToAdd.length > 0) {
        const existingIds = new Set(this.pinnedConversations().map(c => c.id));
        const unique = itemsToAdd.filter(c => !existingIds.has(c.id));
        this.pinnedConversations.update(prev => [...prev, ...unique]);
        this.pinnedOffset.update(o => o + unique.length);
      }
      this.hasMorePinned.set(hasMore);
    } catch (err) {
      if (pageEpoch === this.currentPinnedPageEpoch && queryGen === this.queryGeneration) {
        console.error('Failed to load more pinned conversations:', err);
      }
    } finally {
      if (pageEpoch === this.currentPinnedPageEpoch) {
        this.isLoadingMorePinned.set(false);
      }
    }
  }

  /**
   * Load more items for the Recent / Unpinned section.
   * Independent pagination offset, generation epoch, and loading lock.
   */
  async loadMoreUnpinned(): Promise<void> {
    // Entrance guard: blocked if currently loading, no more items, or pin mutation in progress
    if (this.isLoadingMoreUnpinned() || !this.hasMoreUnpinned() || this.isAnyPinPending() || this.pinRefreshError() !== null) {
      return;
    }

    this.isLoadingMoreUnpinned.set(true);
    const pageEpoch = ++this.currentUnpinnedPageEpoch;
    const queryGen = this.queryGeneration;

    try {
      const limit = 20;
      const offset = this.unpinnedOffset();
      // Peek 1 extra item (limit 21) to establish exact hasMore in a single query
      const more = await this.fetchConversationsByPinned(false, limit + 1, offset);

      if (pageEpoch !== this.currentUnpinnedPageEpoch || queryGen !== this.queryGeneration) {
        return;
      }

      const hasMore = more.length > limit;
      const itemsToAdd = hasMore ? more.slice(0, limit) : more;

      if (itemsToAdd.length > 0) {
        const existingIds = new Set(this.unpinnedConversations().map(c => c.id));
        const unique = itemsToAdd.filter(c => !existingIds.has(c.id));
        this.unpinnedConversations.update(prev => [...prev, ...unique]);
        this.unpinnedOffset.update(o => o + unique.length);
      }
      this.hasMoreUnpinned.set(hasMore);
    } catch (err) {
      if (pageEpoch === this.currentUnpinnedPageEpoch && queryGen === this.queryGeneration) {
        console.error('Failed to load more unpinned conversations:', err);
      }
    } finally {
      if (pageEpoch === this.currentUnpinnedPageEpoch) {
        this.isLoadingMoreUnpinned.set(false);
      }
    }
  }

  // Alias for backward compatibility
  async loadMoreConversations(): Promise<void> {
    return this.loadMoreUnpinned();
  }

  togglePinnedSection(): void {
    this.isPinnedCollapsed.update(v => !v);
  }

  toggleUnpinnedSection(): void {
    this.isUnpinnedCollapsed.update(v => !v);
  }

  /**
   * Disabled guard for pin action.
   * Locked if any pin mutation is in flight, if refetch retry is in progress,
   * or if list refresh is pending error recovery (preventing toggling stale UI state).
   */
  isPinDisabled(convId?: string): boolean {
    return this.isAnyPinPending() || this.pinRefreshError() !== null || this.isRetryingRefetch();
  }

  isPinPending(convId: string): boolean {
    return this.pinPendingIds().has(convId);
  }

  async togglePin(conv: ConversationView): Promise<void> {
    if (!conv || !conv.id) return;
    // Programmatic entrance guard: block if disabled (e.g. pending refresh error)
    if (this.isPinDisabled(conv.id)) return;
    const targetPinned = !conv.pinned;
    await this.setPinned(conv.id, targetPinned);
  }

  async retryPinMutation(): Promise<void> {
    if (this.isPinDisabled()) return;
    const err = this.pinMutationError();
    if (!err) return;
    await this.setPinned(err.conversationId, err.targetPinned);
  }

  /**
   * Executes authoritative pin mutation on backend followed by range reconciliation.
   */
  private async setPinned(conversationId: string, targetPinned: boolean): Promise<void> {
    if (!conversationId) return;
    // Entrance guard: block if mutation or retry in progress or refresh error active
    if (this.pendingPinIds.size > 0 || this.pinRefreshError() !== null || this.isRetryingRefetch()) return;

    // Grab operation token at the very START of the operation
    const opToken = ++this.pinTokenCounter;
    this.activePinTokens.set(conversationId, opToken);
    this.pendingPinIds.add(conversationId);
    this.pinPendingIds.set(new Set(this.pendingPinIds));
    this.pinMutationError.set(null);

    // Invalidate and cancel any in-flight pagination requests immediately
    this.invalidatePaginationRequests();

    // Compute range targets: at least initial 20, preserving full currently loaded volume
    const targetPinnedRange = Math.max(20, this.pinnedConversations().length);
    const targetUnpinnedRange = Math.max(20, this.unpinnedConversations().length);
    this.lastTargetPinnedRange = targetPinnedRange;
    this.lastTargetUnpinnedRange = targetUnpinnedRange;

    try {
      const res = await fetch('/graphql', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          query: `mutation SetConversationPinned($id: ID!, $pinned: Boolean!) {
            setConversationPinned(id: $id, pinned: $pinned) {
              id
              botId
              title
              pinned
              createdAt
              updatedAt
            }
          }`,
          variables: { id: conversationId, pinned: targetPinned }
        })
      });

      // Guard against discarded operation if resetAll() occurred or superseded
      if (this.activePinTokens.get(conversationId) !== opToken) return;

      if (!res.ok) {
        throw new Error(`Failed to set pin state: HTTP ${res.status}`);
      }

      const json = await res.json();
      if (this.activePinTokens.get(conversationId) !== opToken) return;

      if (json.errors && json.errors.length > 0) {
        throw new Error(json.errors[0]?.message || 'Failed to update pin state');
      }

      const updated = json.data?.setConversationPinned;
      if (!updated || typeof updated.id !== 'string') {
        throw new Error('Invalid mutation response: setConversationPinned payload missing');
      }

      // Verified mutation saved in server DB: clear any previous mutation error
      this.pinMutationError.set(null);

      if (this.activeConversation()?.id === conversationId) {
        this.activeConversation.update(c => c ? { ...c, pinned: targetPinned } : null);
      }

      // Mutation acknowledged by server! Launch fresh query epoch to reconcile authoritative ranges
      const queryGen = ++this.queryGeneration;
      try {
        await this.commitRangeRefetch(targetPinnedRange, targetUnpinnedRange, queryGen);
      } catch (refreshErr: any) {
        if (this.activePinTokens.get(conversationId) !== opToken || queryGen !== this.queryGeneration) return;
        console.error('Pin mutation succeeded on server but range refetch failed:', refreshErr);
        // Distinguish saved state: mutation is NOT reverted, only prompt user to retry refresh
        this.pinRefreshError.set({
          message: '已儲存釘選狀態，但列表重新載入失敗。',
          retryRefetchOnly: true
        });
      }
    } catch (err: any) {
      if (this.activePinTokens.get(conversationId) !== opToken) return;
      console.error('Failed to pin/unpin conversation:', err);
      this.pinMutationError.set({
        conversationId,
        targetPinned,
        message: err.message || '釘選操作失敗，請重試。'
      });
    } finally {
      if (this.activePinTokens.get(conversationId) === opToken) {
        this.activePinTokens.delete(conversationId);
        this.pendingPinIds.delete(conversationId);
        this.pinPendingIds.set(new Set(this.pendingPinIds));
      }
    }
  }

  /**
   * Retries range refetch only, without re-sending the pin mutation.
   * Has concurrency lock to prevent rapid double invocation.
   */
  async retryPinRefetch(): Promise<void> {
    if (this.isRetryingRefetch()) return;
    const token = ++this.retryTokenCounter;
    this.activeRetryToken = token;
    this.isRetryingRefetch.set(true);

    const queryGen = ++this.queryGeneration;
    this.invalidatePaginationRequests();

    try {
      await this.commitRangeRefetch(this.lastTargetPinnedRange, this.lastTargetUnpinnedRange, queryGen);
    } catch (err) {
      if (this.activeRetryToken !== token || queryGen !== this.queryGeneration) return;
      console.error('Retry range refetch failed:', err);
      this.pinRefreshError.set({
        message: '已儲存釘選狀態，但列表重新載入失敗。',
        retryRefetchOnly: true
      });
    } finally {
      if (this.activeRetryToken === token) {
        this.activeRetryToken = 0;
        this.isRetryingRefetch.set(false);
      }
    }
  }

  setActiveConversation(conv: ConversationView | null): void {
    // Retain strictly as independent metadata, never injecting into paginated list
    this.activeConversation.set(conv);
  }

  addCreatedConversation(conv: ConversationView): void {
    this.activeConversation.set(conv);
    this.unpinnedConversations.update(prev => {
      if (prev.some(c => c.id === conv.id)) return prev;
      return [conv, ...prev];
    });
    this.unpinnedOffset.update(o => o + 1);
  }

  findConversation(id: string): ConversationView | null {
    const active = this.activeConversation();
    if (active && active.id === id) return active;
    return this.pinnedConversations().find(c => c.id === id)
      || this.unpinnedConversations().find(c => c.id === id)
      || null;
  }

  resetAll(): void {
    this.queryGeneration++;
    this.activePinTokens.clear();
    this.activeRetryToken = 0;
    this.invalidatePaginationRequests();
    this.pinnedConversations.set([]);
    this.unpinnedConversations.set([]);
    this.activeConversation.set(null);
    this.pendingPinIds.clear();
    this.pinPendingIds.set(new Set());
    this.pinMutationError.set(null);
    this.pinRefreshError.set(null);
    this.isRetryingRefetch.set(false);
    this.pinnedOffset.set(0);
    this.hasMorePinned.set(false);
    this.unpinnedOffset.set(0);
    this.hasMoreUnpinned.set(false);
    this.isPinnedCollapsed.set(false);
    this.isUnpinnedCollapsed.set(false);
    this.lastTargetPinnedRange = 20;
    this.lastTargetUnpinnedRange = 20;
  }
}
