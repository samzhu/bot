import { HttpAgent } from '@ag-ui/client';
import { toAgent, AgUiAgent } from '@threadplane/ag-ui';
import { computed, signal, WritableSignal } from '@angular/core';
import { Message, completeDelivery, staticDelivery } from '@threadplane/chat';
import { ChatSessionTransport, RunResult } from './chat-session-registry';

class IdentifiedHttpAgent extends HttpAgent {
  nextRunId = '';
  override runAgent(parameters?: Parameters<HttpAgent['runAgent']>[0], subscriber?: Parameters<HttpAgent['runAgent']>[1]) {
    return super.runAgent({ ...parameters, runId: this.nextRunId }, subscriber);
  }
}
interface AgentEntry { source: IdentifiedHttpAgent; agent: AgUiAgent; facade: AgUiAgent; history: WritableSignal<Message[]>; persistedIds: WritableSignal<Set<string>>; suppressed: WritableSignal<Set<string>>; authoritativeRuns: WritableSignal<Set<string>>; hydrating: WritableSignal<boolean>; historyError: WritableSignal<boolean>; userByRun: Map<string, string>; httpStatus: number | null; conflictCode: string | null; }

/** AG-UI is a transport boundary; the backend remains authoritative for history and prompts. */
export class ChatAgUiTransport implements ChatSessionTransport {
  private readonly entries = new Map<string, AgentEntry>();
  private readonly hydration = new Map<string, Promise<any>>();
  private readonly hydrated = new Map<string, any>();
  readonly created = new Map<string, { id: string; botId: string; title: string; createdAt: string; updatedAt: string }>();
  constructor(private readonly request: typeof fetch = (...args) => globalThis.fetch(...args)) {}

  private entry(conversationId: string): AgentEntry {
    let entry = this.entries.get(conversationId);
    if (!entry) {
      const target: AgentEntry = {} as AgentEntry;
      target.source = new IdentifiedHttpAgent({
        url: '/api/ag-ui', threadId: conversationId,
        fetch: async (url, init) => {
          // The user is appended before dispatch, including for HTTP errors
          // where RUN_STARTED is never delivered.
          const user = [...target.agent.messages()].reverse().find(message => message.role === 'user');
          if (user) target.userByRun.set(target.source.nextRunId, user.id);
          const response = await this.request(url, init);
          target.httpStatus = response.status;
          if ([400, 409, 503].includes(response.status)) {
            const body = await response.clone().json().catch(() => null);
            target.conflictCode = typeof body?.code === 'string' ? body.code : null;
          }
          return response;
        }
      });
      target.agent = toAgent(target.source, { telemetry: false });
      target.history = signal([]);
      target.persistedIds = signal(new Set());
      target.suppressed = signal(new Set());
      target.authoritativeRuns = signal(new Set());
      target.hydrating = signal(false);
      target.historyError = signal(false);
      target.userByRun = new Map();
      target.facade = {
        ...target.agent,
        isInputBlocked: computed(() => target.hydrating() || target.historyError() || !!target.agent.isInputBlocked?.()),
        messages: computed(() => {
          const history = target.history();
          const ids = new Set(history.map(m => m.id));
          const suppressed = target.suppressed();
          const live = target.agent.messages();
          const liveById = new Map(live.map(m => [m.id, m]));
          const authoritative = target.authoritativeRuns();
          return [...history.map(m => authoritative.has(String(m.extra?.['runId'])) ? m : liveById.get(m.id) ?? m), ...live.filter(m => !ids.has(m.id) && !suppressed.has(m.id))];
        })
      };
      target.httpStatus = null;
      target.conflictCode = null;
      this.entries.set(conversationId, target);
      entry = target;
    }
    return entry;
  }
  agent(conversationId: string): AgUiAgent { return this.entry(conversationId).facade; }
  hydrate(conversationId: string): Promise<any> {
    if (this.hydrated.has(conversationId)) return Promise.resolve(this.hydrated.get(conversationId));
    const existing = this.hydration.get(conversationId);
    if (existing) return existing;
    const entry = this.entry(conversationId);
    entry.hydrating.set(true);
    const task = (async () => {
      try {
        const data = await this.graphql(`query SessionHistory($id: ID!) { conversation(id: $id) { id botId title pinned createdAt updatedAt messages(limit: 20, offset: 0) { id runId role content interrupted createdAt } runs(limit: 50, offset: 0) { id status errorMessage } } }`, { id: conversationId });
        if (!Array.isArray(data?.conversation?.messages)) throw new Error('Missing conversation history');
        const statuses = new Map<string, string>((data.conversation.runs || []).map((run: {id: string; status: string}) => [run.id, run.status]));
        this.mergeHistory(conversationId, data.conversation.messages.map((row: {runId: string}) => ({...row, runStatus: statuses.get(row.runId)})));
        this.hydrated.set(conversationId, data.conversation);
        entry.historyError.set(false);
        return data.conversation;
      } catch (error) {
        entry.historyError.set(true);
        throw error;
      } finally { entry.hydrating.set(false); this.hydration.delete(conversationId); }
    })();
    this.hydration.set(conversationId, task);
    return task;
  }
  historyOffset(conversationId: string): number { return this.entry(conversationId).persistedIds().size; }
  mergeHistory(conversationId: string, rows: any[], prepend = false): void {
    const entry = this.entry(conversationId);
    entry.persistedIds.update(old => new Set([...old, ...rows.map(row => row.id).filter((id): id is string => typeof id === 'string' && id.length > 0)]));
    const messages: Message[] = [...rows].reverse().filter(row => row.role === 'USER' || row.role === 'ASSISTANT').map(row => ({
      id: row.id, role: row.role === 'USER' ? 'user' : 'assistant', content: row.content,
      delivery: row.role !== 'ASSISTANT' ? staticDelivery(row.id)
        : row.runStatus === 'FAILED' || row.failed ? completeDelivery(row.id, 'error')
        : row.runStatus === 'CANCELLED' ? completeDelivery(row.id, 'aborted')
        : row.interrupted ? completeDelivery(row.id, 'interrupted') : staticDelivery(row.id),
      extra: { runId: row.runId, interrupted: row.interrupted, runStatus: row.runStatus, createdAt: row.createdAt }
    }));
    entry.suppressed.update(old => {
      const next = new Set(old);
      for (const row of rows) if (row.role === 'USER' && entry.userByRun.has(row.runId)) next.add(entry.userByRun.get(row.runId)!);
      return next;
    });
    entry.history.update(old => {
      const existing = new Set(old.map(message => message.id));
      const byId = new Map((prepend ? messages.filter(message => !existing.has(message.id)) : old).map(message => [message.id, message]));
      for (const message of (prepend ? old : messages)) byId.set(message.id, message);
      const merged = [...byId.values()];
      // The server orders by createdAt and id; sorting the unique snapshots
      // preserves that order when hydration and reconciliation race.
      if (merged.every(message => typeof message.extra?.['createdAt'] === 'string' && Number.isFinite(Date.parse(message.extra['createdAt'] as string)))) {
        const fraction = (value: string) => (value.match(/\.(\d+)Z$/)?.[1] ?? '').padEnd(9, '0');
        merged.sort((a, b) => {
          const first = a.extra!['createdAt'] as string;
          const second = b.extra!['createdAt'] as string;
          const firstFraction = fraction(first), secondFraction = fraction(second);
          return Date.parse(first) - Date.parse(second) || (firstFraction < secondFraction ? -1 : firstFraction > secondFraction ? 1 : 0) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
        });
      }
      return merged;
    });
  }
  async createConversation(botId: string): Promise<string> {
    const data = await this.graphql(`mutation CreateConversation($botId: ID!, $title: String) { createConversation(botId: $botId, title: $title) { id botId title createdAt updatedAt } }`, { botId, title: 'New Conversation' });
    if (!data?.createConversation?.id) throw new Error('Missing conversation');
    this.created.set(data.createConversation.id, data.createConversation);
    return data.createConversation.id;
  }
  async run(conversationId: string, runId: string, raw: string, onDelta: (text: string) => void): Promise<RunResult> {
    const entry = this.entry(conversationId);
    entry.source.nextRunId = runId;
    entry.httpStatus = null;
    entry.conflictCode = null;
    let terminal: RunResult | null = null;
    const subscription = entry.agent.rawEvents$.subscribe(event => {
      if (event.type === 'RUN_STARTED') {
        const user = [...entry.agent.messages()].reverse().find(message => message.role === 'user');
        if (user) entry.userByRun.set(runId, user.id);
      }
      if (event.type === 'TEXT_MESSAGE_CONTENT' && typeof event['delta'] === 'string') onDelta(event['delta']);
      if (event.type === 'RUN_FINISHED') terminal = { status: (event['outcome'] as { type?: string } | undefined)?.type === 'cancelled' ? 'CANCELLED' : 'COMPLETED' };
      if (event.type === 'RUN_ERROR') terminal = { status: 'FAILED' };
    });
    const rejected = (): RunResult => {
      const id = entry.userByRun.get(runId);
      if (id) {
        entry.suppressed.update(ids => new Set([...ids, id]));
        entry.source.setMessages(entry.source.messages.filter(message => message.id !== id));
      }
      return { status: 'REJECTED' };
    };
    const responseResult = (): RunResult | null => {
      if ((entry.httpStatus === 409 && entry.conflictCode === 'RUN_CONFLICT') ||
          (entry.httpStatus === 400 && entry.conflictCode === 'INVALID_AG_UI_INPUT') ||
          (entry.httpStatus === 503 && entry.conflictCode === 'GKE_AGENT_NOT_CONNECTED')) return rejected();
      if (entry.httpStatus === 409 && entry.conflictCode === 'DUPLICATE_RUN') return { status: 'DUPLICATE' };
      return entry.httpStatus !== null && entry.httpStatus >= 400 ? { status: 'UNKNOWN' } : null;
    };
    try {
      await entry.agent.submit({ message: raw.trim() });
      return responseResult() ?? terminal ?? { status: 'UNKNOWN' };
    } catch {
      return responseResult() ?? { status: 'UNKNOWN' };
    } finally { subscription.unsubscribe(); }
  }
  async reconcile(conversationId: string, runId: string): Promise<RunResult> {
    const data = await this.graphql(`query ReconcileRun($id: ID!, $conversationId: ID!) { run(id: $id) { id status } conversation(id: $conversationId) { messages(limit: 20, offset: 0) { id runId role content interrupted createdAt } runs(limit: 50, offset: 0) { id status } } }`, { id: runId, conversationId });
    const status = data?.run?.status;
    if ((status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED') && !Array.isArray(data?.conversation?.messages)) return { status: 'UNKNOWN' };
    if ((status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED') && Array.isArray(data?.conversation?.messages)) {
      const statuses = new Map<string, string>((data.conversation.runs || []).map((run: {id: string; status: string}) => [run.id, run.status]));
      statuses.set(runId, status);
      this.mergeHistory(conversationId, data.conversation.messages.map((row: {runId: string}) => ({...row, runStatus: statuses.get(row.runId)})));
      this.entry(conversationId).authoritativeRuns.update(ids => new Set([...ids, runId]));
    }
    return { status: status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED' ? status : 'UNKNOWN' };
  }
  async cancel(_conversationId: string, runId: string): Promise<RunResult> {
    const res = await this.request(`/api/runs/${encodeURIComponent(runId)}/cancel`, { method: 'POST' });
    if (!res.ok) return { status: 'UNKNOWN' };
    const body = await res.json();
    return { status: body.status === 'COMPLETED' || body.status === 'FAILED' || body.status === 'CANCELLED' ? body.status : 'UNKNOWN' };
  }
  stopLocal(conversationId: string): void { void this.entries.get(conversationId)?.agent.stop(); }
  private async graphql(query: string, variables: Record<string, unknown>): Promise<any> {
    const res = await this.request('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ query, variables }) });
    if (!res.ok) throw new Error(`GraphQL HTTP ${res.status}`);
    const result = await res.json();
    if (result.errors?.length) throw new Error(result.errors[0].message);
    return result.data;
  }
}
