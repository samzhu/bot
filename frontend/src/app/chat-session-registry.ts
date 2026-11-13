import { signal, WritableSignal } from '@angular/core';

export type BudgetDenialReason = 'MONTH_CAP' | 'MONTH_UNKNOWN';
export type RunResult = { status: 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'REJECTED' | 'DUPLICATE' | 'UNKNOWN'; reason?: BudgetDenialReason };
export interface ChatSessionTransport {
  createConversation(botId: string, workspaceId?: string): Promise<string>;
  run(conversationId: string, runId: string, raw: string, onDelta: (text: string) => void): Promise<RunResult>;
  reconcile(conversationId: string, runId: string): Promise<RunResult>;
  cancel(conversationId: string, runId: string): Promise<RunResult>;
  stopLocal?(conversationId: string, runId: string): void;
}
export interface ChatSession {
  draft: WritableSignal<string>;
  draftVersion: WritableSignal<number>;
  partial: WritableSignal<string>;
  active: WritableSignal<boolean>;
  result: WritableSignal<RunResult | null>;
  conversationId: string | null;
  runId: string | null;
}

/** Run ownership is independent of whichever session the view currently projects. */
export class ChatSessionRegistry {
  private readonly sessions = new Map<string, ChatSession>();
  private readonly pending = new WeakMap<ChatSession, { raw: string; version: number; runId: string; settled: boolean; key: string }>();
  private readonly creationFailures = new Map<string, WritableSignal<{ runId: string; raw: string }[]>>();
  private readonly checks = new WeakMap<ChatSession, { runId: string; task: Promise<void> }>();
  constructor(private readonly transport: ChatSessionTransport, private readonly newRunId: () => string = () => crypto.randomUUID(), private readonly onCreated?: (sourceKey: string, conversationId: string, source: ChatSession) => void) {}
  session(key: string): ChatSession {
    let session = this.sessions.get(key);
    if (!session) {
      session = { draft: signal(''), draftVersion: signal(0), partial: signal(''), active: signal(false), result: signal(null), conversationId: key.startsWith('conversation:') ? key.slice('conversation:'.length) : null, runId: null };
      this.sessions.set(key, session);
    }
    return session;
  }
  edit(key: string, raw: string): void {
    this.editSource(this.session(key), raw);
  }
  editSource(source: ChatSession, raw: string): void {
    source.draft.set(raw);
    source.draftVersion.update(version => version + 1);
  }
  failedCreations(key: string): WritableSignal<{ runId: string; raw: string }[]> {
    let failures = this.creationFailures.get(key);
    if (!failures) { failures = signal([]); this.creationFailures.set(key, failures); }
    return failures;
  }
  private settle(source: ChatSession, runId: string, result: RunResult): void {
    const pending = this.pending.get(source);
    if (!pending || pending.runId !== runId || pending.settled) return;
    source.result.set(result);
    if (result.status === 'UNKNOWN' || result.status === 'DUPLICATE') return;
    pending.settled = true;
    if ((result.status === 'FAILED' || result.status === 'CANCELLED' || result.status === 'REJECTED') && source.draftVersion() === pending.version && !source.draft()) source.draft.set(pending.raw);
    else if (result.status === 'REJECTED') this.failedCreations(source.conversationId ? `conversation:${source.conversationId}` : pending.key).update(items => [...items, {runId, raw: pending.raw}]);
    source.active.set(false);
  }
  async submit(key: string, botId?: string, source = this.session(key), workspaceId?: string): Promise<void> {
    const raw = source.draft();
    if (source.active() || !raw.trim()) return;
    const version = source.draftVersion();
    // A new draft must remain independent even while the old creation is pending.
    if (!source.conversationId && this.sessions.get(key) === source) this.sessions.delete(key);
    const runId = this.newRunId();
    this.pending.set(source, { raw, version, runId, settled: false, key });
    source.runId = runId;
    source.active.set(true);
    source.result.set(null);
    source.partial.set('');
    // Clearing is not a user edit; a later edit, even identical text, wins.
    source.draft.set('');
    let dispatched = false;
    try {
      if (!source.conversationId) {
        if (!botId) throw new Error('A bot is required to create a conversation');
        source.conversationId = await this.transport.createConversation(botId, workspaceId);
        this.sessions.set(`conversation:${source.conversationId}`, source);
        this.onCreated?.(key, source.conversationId, source);
      }
      dispatched = true;
      let result = await this.transport.run(source.conversationId, runId, raw, delta => {
        const pending = this.pending.get(source);
        if (pending?.runId === runId && !pending.settled) source.partial.update(text => text + delta);
      });
      if (this.pending.get(source)?.runId !== runId || this.pending.get(source)?.settled) return;
      if (result.status === 'DUPLICATE' || result.status === 'UNKNOWN') result = await this.transport.reconcile(source.conversationId, runId);
      this.settle(source, runId, result);
    } catch {
      let result: RunResult = { status: 'UNKNOWN' };
      if (dispatched && source.conversationId) {
        try { result = await this.transport.reconcile(source.conversationId, runId); } catch { /* Keep the run unresolved. */ }
      }
      if (!dispatched && !source.conversationId) {
        const replacement = this.sessions.get(key);
        if (!replacement) this.sessions.set(key, source);
        else if (replacement !== source) this.failedCreations(key).update(items => [...items, { runId, raw }]);
        this.settle(source, runId, { status: 'FAILED' });
      } else {
        this.settle(source, runId, result);
      }
    }
  }
  reconcile(key: string): Promise<void> {
    const source = this.session(key);
    if (!source.active() || !source.conversationId || !source.runId) return Promise.resolve();
    const runId = source.runId;
    const conversationId = source.conversationId;
    const existing = this.checks.get(source);
    if (existing?.runId === runId) return existing.task;
    const task = (async () => {
      try {
        const result = await this.transport.reconcile(conversationId, runId);
        this.settle(source, runId, result);
      } finally {
        if (this.checks.get(source)?.runId === runId) this.checks.delete(source);
      }
    })();
    this.checks.set(source, { runId, task });
    return task;
  }
  async stop(key: string): Promise<RunResult | undefined> {
    const source = this.session(key);
    if (!source.active() || !source.conversationId || !source.runId) return;
    const runId = source.runId;
    const result = await this.transport.cancel(source.conversationId, runId);
    // Aborting the reader before server confirmation may change the server outcome.
    if (this.pending.get(source)?.runId === runId && !this.pending.get(source)?.settled && (result.status === 'COMPLETED' || result.status === 'FAILED' || result.status === 'CANCELLED')) {
      this.settle(source, runId, result);
      this.transport.stopLocal?.(source.conversationId, runId);
    }
    return result;
  }
}
