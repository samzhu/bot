import {
  Component,
  OnInit,
  computed,
  forwardRef,
  signal,
  WritableSignal,
  ElementRef,
  ViewChild
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { ChatState, CopilotChatView } from '@copilotkit/angular';
import { Message, UserMessage, AssistantMessage } from '@ag-ui/core';
import { SseStreamParser } from './sse-parser';

export interface BotView {
  id: string;
  name: string;
  createdAt: string;
}

export interface ConversationView {
  id: string;
  botId: string;
  title: string;
  createdAt: string;
  updatedAt: string;
}

export interface GraphQLMessageView {
  id: string;
  conversationId: string;
  runId: string;
  role: string;
  content: string;
  interrupted: boolean;
  createdAt: string;
}

export type ExtendedMessage = Message & {
  interrupted?: boolean;
  failed?: boolean;
};

export interface PendingRequestInfo {
  conversationId: string;
  clientRequestId: string;
  message: string;
  runId?: string;
  tempUserId: string;
  tempAssistantId: string;
}

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, CopilotChatView],
  providers: [
    /*
     * RATIONALE: CopilotChatView expects a ChatState provider in DI hierarchy.
     * By extending ChatState directly in App and aliasing via forwardRef + useExisting,
     * we retain a pure layout-only presentation layer without instantiating any external CopilotKit Runtime
     * or sending data to third-party cloud services.
     */
    { provide: ChatState, useExisting: forwardRef(() => App) }
  ],
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class App extends ChatState implements OnInit {
  @ViewChild('messagesContainer') private messagesContainer?: ElementRef<HTMLDivElement>;

  // ChatState abstract implementations
  readonly inputValue: WritableSignal<string> = signal('');

  // Application State
  readonly bots = signal<BotView[]>([]);
  readonly conversations = signal<ConversationView[]>([]);
  readonly currentConversationId = signal<string | null>(null);
  readonly messages = signal<ExtendedMessage[]>([]);
  readonly isStreaming = signal<boolean>(false);
  readonly isCancelling = signal<boolean>(false);
  readonly activeRunId = signal<string | null>(null);
  readonly copiedMessageId = signal<string | null>(null);
  readonly pendingRequest = signal<PendingRequestInfo | null>(null);
  readonly streamError = signal<string | null>(null);
  readonly isRecovering = signal<boolean>(false);

  // Bounded Pagination State (default limit 20, max cap 100)
  readonly messageOffset = signal<number>(20);
  readonly hasMoreMessages = signal<boolean>(false);
  readonly isLoadingEarlier = signal<boolean>(false);

  readonly conversationOffset = signal<number>(0);
  readonly hasMoreConversations = signal<boolean>(false);
  readonly isLoadingMoreConversations = signal<boolean>(false);

  private readonly completedRunIds = new Set<string>();

  private onRunTerminal(runId: string | null, messageCount: number): void {
    if (runId && this.completedRunIds.has(runId)) {
      return;
    }
    if (runId) {
      this.completedRunIds.add(runId);
    }
    this.messageOffset.update(offset => offset + messageCount);
  }

  // Derived state
  readonly seedBot = computed<BotView | null>(() => this.bots()[0] || null);

  readonly currentConversation = computed<ConversationView | null>(() => {
    const id = this.currentConversationId();
    if (!id) return null;
    return this.conversations().find(c => c.id === id) || null;
  });

  readonly currentTitle = computed<string>(() => {
    const conv = this.currentConversation();
    return conv ? conv.title : 'New Conversation';
  });

  async ngOnInit(): Promise<void> {
    await this.loadBots();
    await this.loadConversations();

    // Check localStorage for previously active conversation to survive reloads
    const savedId = localStorage.getItem('bot_active_conversation_id');
    const existing = this.conversations().find(c => c.id === savedId);

    if (existing) {
      await this.selectConversation(existing.id);
    } else if (savedId) {
      // Saved conversation is beyond the initial paginated list; load directly by id
      await this.selectConversation(savedId);
    } else if (this.conversations().length > 0) {
      await this.selectConversation(this.conversations()[0].id);
    } else if (this.seedBot()) {
      await this.newConversation();
    }
  }

  // --- GraphQL Operations ---

  async loadBots(): Promise<void> {
    try {
      const res = await fetch('/graphql', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          query: `query { bots(limit: 20, offset: 0) { id name createdAt } }`
        })
      });
      const json = await res.json();
      if (json.data && json.data.bots) {
        this.bots.set(json.data.bots);
      }
    } catch (err) {
      console.error('Failed to load bots:', err);
    }
  }

  async loadConversations(): Promise<void> {
    try {
      const limit = 20;
      const res = await fetch('/graphql', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          query: `query GetConversations($limit: Int, $offset: Int) {
            conversations(limit: $limit, offset: $offset) { id botId title createdAt updatedAt }
          }`,
          variables: { limit, offset: 0 }
        })
      });
      const json = await res.json();
      if (json.data && json.data.conversations) {
        const list: ConversationView[] = json.data.conversations;
        this.conversations.set(list);
        this.conversationOffset.set(list.length);
        this.hasMoreConversations.set(list.length >= limit);
      }
    } catch (err) {
      console.error('Failed to load conversations:', err);
    }
  }

  async loadMoreConversations(): Promise<void> {
    if (this.isLoadingMoreConversations() || !this.hasMoreConversations()) return;
    this.isLoadingMoreConversations.set(true);
    try {
      const limit = 20;
      const offset = this.conversationOffset();
      const res = await fetch('/graphql', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          query: `query GetConversations($limit: Int, $offset: Int) {
            conversations(limit: $limit, offset: $offset) { id botId title createdAt updatedAt }
          }`,
          variables: { limit, offset }
        })
      });
      const json = await res.json();
      if (json.data && json.data.conversations) {
        const more: ConversationView[] = json.data.conversations;
        if (more.length > 0) {
          const existingIds = new Set(this.conversations().map(c => c.id));
          const unique = more.filter(c => !existingIds.has(c.id));
          this.conversations.update(prev => [...prev, ...unique]);
          this.conversationOffset.update(o => o + more.length);
        }
        if (more.length < limit) {
          this.hasMoreConversations.set(false);
        }
      }
    } catch (err) {
      console.error('Failed to load more conversations:', err);
    } finally {
      this.isLoadingMoreConversations.set(false);
    }
  }

  async selectConversation(id: string): Promise<void> {
    /*
     * RATIONALE: In Ticket 01, switching conversation while actively streaming is disallowed
     * to prevent corrupted UI message state and concurrent stream interference.
     */
    if (this.isStreaming()) return;

    this.currentConversationId.set(id);
    localStorage.setItem('bot_active_conversation_id', id);
    this.completedRunIds.clear();
    this.messageOffset.set(20);
    this.hasMoreMessages.set(false);
    this.streamError.set(null);
    this.isRecovering.set(false);

    try {
      const limit = 20;
      const res = await fetch('/graphql', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          query: `query GetConversation($id: ID!, $limit: Int, $offset: Int) {
            conversation(id: $id) {
              id
              botId
              title
              messages(limit: $limit, offset: $offset) {
                id
                runId
                role
                content
                interrupted
                createdAt
              }
              runs(limit: 20, offset: 0) {
                id
                status
                errorMessage
              }
            }
          }`,
          variables: { id, limit, offset: 0 }
        })
      });

      const json = await res.json();

      /*
       * RATIONALE: Asynchronous Race Guard.
       * If user rapidly switches conversations before this fetch completes,
       * discard the outdated response so it doesn't overwrite the newly selected conversation.
       */
      if (this.currentConversationId() !== id) {
        return;
      }

      if (json.data && json.data.conversation) {
        const conv = json.data.conversation;
        if (!this.conversations().some(c => c.id === id)) {
          this.conversations.update(prev => [{
            id: conv.id,
            botId: conv.botId,
            title: conv.title,
            createdAt: conv.createdAt || '',
            updatedAt: conv.updatedAt || ''
          }, ...prev]);
        }
        const rawMessages: GraphQLMessageView[] = conv.messages || [];
        const runs: any[] = conv.runs || [];
        const failedRunIds = new Set(runs.filter(r => r.status === 'FAILED').map(r => r.id));
        this.hasMoreMessages.set(rawMessages.length >= limit);
        this.messageOffset.set(rawMessages.length);

        /*
         * RATIONALE: The backend GraphQL API orders messages DESC (newest first) for paginated feeds.
         * The UI dialogue view requires chronological ASC order (oldest to newest); hence we reverse the list.
         * Furthermore, backend roles are uppercase 'USER' / 'ASSISTANT', so we normalize case to 'user' / 'assistant'.
         */
        const chronological: ExtendedMessage[] = [...rawMessages].reverse().map(m => {
          const roleNormalized = (m.role || '').toLowerCase();
          if (roleNormalized === 'user') {
            const userMsg: ExtendedMessage = {
              id: m.id,
              role: 'user',
              content: m.content
            };
            return userMsg;
          } else {
            const assistantMsg: ExtendedMessage = {
              id: m.id,
              role: 'assistant',
              content: m.content,
              interrupted: !!m.interrupted,
              failed: failedRunIds.has(m.runId)
            };
            return assistantMsg;
          }
        });
        this.messages.set(chronological);
        this.scrollToBottom();
      }
    } catch (err) {
      console.error('Failed to select conversation:', err);
    }
  }

  async loadEarlierMessages(): Promise<void> {
    const convId = this.currentConversationId();
    if (!convId || this.isLoadingEarlier() || !this.hasMoreMessages() || this.isStreaming()) return;
    this.isLoadingEarlier.set(true);

    try {
      const limit = 20;
      const offset = this.messageOffset();
      const res = await fetch('/graphql', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          query: `query GetEarlierMessages($id: ID!, $limit: Int, $offset: Int) {
            conversation(id: $id) {
              id
              messages(limit: $limit, offset: $offset) {
                id
                runId
                role
                content
                interrupted
                createdAt
              }
              runs(limit: 20, offset: 0) {
                id
                status
              }
            }
          }`,
          variables: { id: convId, limit, offset }
        })
      });

      const json = await res.json();
      if (this.currentConversationId() !== convId) return;

      if (json.data && json.data.conversation) {
        const rawMessages: GraphQLMessageView[] = json.data.conversation.messages || [];
        const runs: any[] = json.data.conversation.runs || [];
        const failedRunIds = new Set(runs.filter(r => r.status === 'FAILED').map(r => r.id));
        if (rawMessages.length < limit) {
          this.hasMoreMessages.set(false);
        }
        this.messageOffset.update(o => o + rawMessages.length);

        const currentMessages = this.messages();
        const existingIds = new Set(currentMessages.map(m => m.id));

        const deduplicatedNew: ExtendedMessage[] = [];
        for (const m of [...rawMessages].reverse()) {
          if (existingIds.has(m.id)) {
            continue;
          }
          const roleNormalized = (m.role || '').toLowerCase();
          const targetRole = (roleNormalized === 'user' ? 'user' : 'assistant') as 'user' | 'assistant';
          // Reconcile if a rendered optimistic message still held a temp ID with matching content & role
          const tempMatch = currentMessages.find(
            cur => cur.id.startsWith('temp-') && cur.role === targetRole && cur.content === m.content
          );
          if (tempMatch) {
            tempMatch.id = m.id;
            existingIds.add(m.id);
            continue;
          }
          deduplicatedNew.push({
            id: m.id,
            role: targetRole,
            content: m.content,
            interrupted: !!m.interrupted,
            failed: failedRunIds.has(m.runId)
          });
        }

        this.messages.update(prev => [...deduplicatedNew, ...prev]);
      }
    } catch (err) {
      console.error('Failed to load earlier messages:', err);
    } finally {
      this.isLoadingEarlier.set(false);
    }
  }

  async newConversation(): Promise<void> {
    if (this.isStreaming()) return;

    let bot = this.seedBot();
    if (!bot) {
      await this.loadBots();
      bot = this.seedBot();
    }
    if (!bot) return;

    try {
      const res = await fetch('/graphql', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          query: `mutation CreateConversation($botId: ID!, $title: String) {
            createConversation(botId: $botId, title: $title) {
              id
              botId
              title
              createdAt
              updatedAt
            }
          }`,
          variables: {
            botId: bot.id,
            title: 'New Conversation'
          }
        })
      });

      const json = await res.json();
      if (json.data && json.data.createConversation) {
        const newConv = json.data.createConversation;
        this.conversations.update(prev => [newConv, ...prev]);
        this.currentConversationId.set(newConv.id);
        localStorage.setItem('bot_active_conversation_id', newConv.id);
        this.messages.set([]);
        this.messageOffset.set(20);
        this.hasMoreMessages.set(false);
        this.streamError.set(null);
        this.isRecovering.set(false);
      }
    } catch (err) {
      console.error('Failed to create conversation:', err);
    }
  }

  // --- ChatState Abstract Implementations ---

  override changeInput(value: string): void {
    this.inputValue.set(value);
  }

  override async submitInput(value?: string, retryClientRequestId?: string, targetConvId?: string): Promise<void> {
    const text = (value || this.inputValue()).trim();
    if (!text || this.isStreaming()) return;

    let convId = targetConvId || this.currentConversationId();
    if (!convId) {
      await this.newConversation();
      convId = this.currentConversationId();
      if (!convId) return;
    }

    const clientRequestId = retryClientRequestId || ('req-' + crypto.randomUUID());

    let tempUserId = 'temp-u-' + Date.now();
    let tempAssistantId = 'temp-a-' + Date.now();

    if (!retryClientRequestId) {
      const userMessage: ExtendedMessage = {
        id: tempUserId,
        role: 'user',
        content: text
      };

      const assistantMessage: ExtendedMessage = {
        id: tempAssistantId,
        role: 'assistant',
        content: ''
      };

      this.messages.update(prev => [...prev, userMessage, assistantMessage]);
      this.inputValue.set('');
    } else {
      const pending = this.pendingRequest();
      if (pending && pending.tempUserId) {
        tempUserId = pending.tempUserId;
      } else {
        const existingUser = this.messages().find(
          m => m.role === 'user' && m.id.startsWith('temp-u-')
        );
        if (existingUser) tempUserId = existingUser.id;
      }
      if (pending && pending.tempAssistantId) {
        tempAssistantId = pending.tempAssistantId;
      } else {
        const existingAssistant = this.messages().find(
          m => m.role === 'assistant' && (m.id.startsWith('temp-a-') || !m.content)
        );
        if (existingAssistant) tempAssistantId = existingAssistant.id;
      }
    }

    this.pendingRequest.set({
      conversationId: convId,
      clientRequestId,
      message: text,
      tempUserId,
      tempAssistantId
    });
    this.streamError.set(null);

    this.isStreaming.set(true);
    this.scrollToBottom();

    try {
      /*
       * RATIONALE: Content-Type First Inspection.
       * If backend returns application/json (200 duplicate run or 409 conflict),
       * process synchronously as metadata rather than treating it as an SSE stream.
       */
      const response = await fetch(`/api/conversations/${convId}/runs`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'text/event-stream, application/json'
        },
        body: JSON.stringify({
          clientRequestId,
          message: text
        })
      });

      if (this.currentConversationId() !== convId) return;

      const contentType = response.headers.get('content-type') || '';

      if (contentType.includes('application/json')) {
        const json = await response.json().catch(() => ({}));
        if (this.currentConversationId() !== convId) return;

        if (response.ok) {
          // EXISTING duplicate run (200 JSON with authoritative run metadata)
          const runId = json.runId || json.id;
          const result = await this.reconcileAuthoritativeMessages(convId, runId, tempUserId, tempAssistantId);
          if (this.currentConversationId() !== convId) return;

          if (result.isTerminal) {
            this.pendingRequest.set(null);
            this.activeRunId.set(null);
            if (result.status === 'FAILED') {
              this.streamError.set(result.errorMessage ? `執行失敗: ${result.errorMessage}` : '執行失敗');
            } else {
              this.streamError.set(null);
            }
          } else {
            // Still active on backend: enter bounded polling / recovery
            this.activeRunId.set(runId);
            await this.pollActiveRunUntilTerminal(convId, runId, tempUserId, tempAssistantId);
          }
          return;
        } else if (response.status === 409) {
          // 409 Conflict: rejected by server, clear optimistic rows and pending retry state
          this.removeOptimisticMessages(tempUserId, tempAssistantId);
          this.pendingRequest.set(null);
          this.streamError.set(json.error || 'Another active run is in progress for this conversation');
          return;
        } else {
          throw new Error(json.error || `Request failed with status ${response.status}`);
        }
      }

      if (!response.ok || !response.body) {
        throw new Error(`Stream request failed with status ${response.status}`);
      }

      await this.readSseStream(response.body, tempUserId, tempAssistantId, convId);
      if (this.currentConversationId() !== convId) return;
      this.pendingRequest.set(null);
    } catch (err: any) {
      console.error('Streaming or network error:', err);
      if (this.currentConversationId() === convId) {
        this.isStreaming.set(false);
        await this.reconcileOrRecoverRun(convId, clientRequestId, tempUserId, tempAssistantId);
      }
    } finally {
      if (this.currentConversationId() === convId) {
        this.isStreaming.set(false);
        this.scrollToBottom();
      }
    }
  }

  retryPending(): void {
    const pending = this.pendingRequest();
    if (!pending || this.isStreaming() || this.isRecovering()) return;
    if (this.currentConversationId() !== pending.conversationId) {
      this.selectConversation(pending.conversationId);
    }
    this.submitInput(pending.message, pending.clientRequestId, pending.conversationId);
  }

  private removeOptimisticMessages(tempUserId: string, tempAssistantId: string): void {
    this.messages.update(prev =>
      prev.filter(m => m.id !== tempUserId && m.id !== tempAssistantId)
    );
  }

  /**
   * Reads and parses SSE stream chunks via SseStreamParser.
   * Asserts UTF-8 code point continuity, strict seq monotonic increment, runId consistency,
   * single terminal event, and detects premature EOF without swallowing errors.
   */
  private async readSseStream(
    body: ReadableStream<Uint8Array>,
    tempUserId: string,
    tempAssistantId: string,
    convId: string
  ): Promise<void> {
    const reader = body.getReader();
    try {
      const parser = new SseStreamParser({
        onEvent: (evt) => {
          if (this.currentConversationId() !== convId) return;
          this.handleSseEvent(evt.event, evt.data, tempUserId, tempAssistantId);
        },
        onError: (err) => {
          console.error('SSE parser error callback:', err);
        }
      });

      while (true) {
        const { done, value: chunk } = await reader.read();
        if (done) break;
        if (chunk) {
          parser.feed(chunk);
        }
      }
      parser.finish();
    } finally {
      try {
        await reader.cancel();
      } catch (_) {}
      reader.releaseLock();
    }
  }

  /**
   * Reconciles authoritative messages and run state with the backend server.
   * Handles network errors, seq gaps, and premature EOF without falsely marking cancelled.
   */
  private async reconcileOrRecoverRun(
    convId: string,
    clientRequestId: string,
    tempUserId: string,
    tempAssistantId: string
  ): Promise<void> {
    if (this.currentConversationId() !== convId) return;
    this.isRecovering.set(true);
    try {
      const res = await fetch('/graphql', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          query: `query GetRecovery($id: ID!) {
            conversation(id: $id) {
              id
              runs(limit: 20, offset: 0) {
                id
                clientRequestId
                status
                errorMessage
              }
            }
          }`,
          variables: { id: convId }
        })
      });

      if (!res.ok) throw new Error(`GraphQL query failed with HTTP ${res.status}`);
      const json = await res.json();
      if (json.errors?.length) throw new Error(json.errors[0].message);

      if (this.currentConversationId() !== convId) return;

      const runs: any[] = json.data?.conversation?.runs || [];
      const matchedRun = runs.find(r => r.clientRequestId === clientRequestId);

      if (matchedRun) {
        if (matchedRun.status !== 'PENDING' && matchedRun.status !== 'RUNNING') {
          // Authoritative terminal state reached in DB
          const result = await this.reconcileAuthoritativeMessages(convId, matchedRun.id, tempUserId, tempAssistantId);
          if (this.currentConversationId() !== convId) return;

          if (result.isTerminal) {
            this.pendingRequest.set(null);
            this.activeRunId.set(null);
            if (matchedRun.status === 'FAILED') {
              this.streamError.set(`執行失敗: ${matchedRun.errorMessage || '未知錯誤'}`);
            } else {
              this.streamError.set(null);
            }
            return;
          }
        }

        // Run is still PENDING or RUNNING on backend; perform bounded polling
        this.activeRunId.set(matchedRun.id);
        const polled = await this.pollActiveRunUntilTerminal(convId, matchedRun.id, tempUserId, tempAssistantId);
        if (polled) return;
      }

      if (this.currentConversationId() === convId) {
        this.streamError.set('連線中斷，可點擊重試');
      }
    } catch (e: any) {
      console.error('Failed to recover run:', e);
      if (this.currentConversationId() === convId) {
        this.streamError.set('連線中斷，可點擊重試');
      }
    } finally {
      if (this.currentConversationId() === convId) {
        this.isRecovering.set(false);
      }
    }
  }

  private async pollActiveRunUntilTerminal(
    convId: string,
    runId: string,
    tempUserId?: string,
    tempAssistantId?: string
  ): Promise<boolean> {
    for (let i = 0; i < 5; i++) {
      await new Promise(resolve => setTimeout(resolve, 800));
      if (this.currentConversationId() !== convId) return false;

      try {
        const pollRes = await fetch('/graphql', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            query: `query PollRun($id: ID!) {
              run(id: $id) { id status errorMessage }
            }`,
            variables: { id: runId }
          })
        });
        if (!pollRes.ok) continue;
        const pollJson = await pollRes.json();
        const polledRun = pollJson.data?.run;
        if (polledRun && polledRun.status !== 'PENDING' && polledRun.status !== 'RUNNING') {
          const result = await this.reconcileAuthoritativeMessages(convId, runId, tempUserId, tempAssistantId);
          if (this.currentConversationId() !== convId) return false;

          if (result.isTerminal) {
            this.pendingRequest.set(null);
            this.activeRunId.set(null);
            if (polledRun.status === 'FAILED') {
              this.streamError.set(polledRun.errorMessage ? `執行失敗: ${polledRun.errorMessage}` : '執行失敗');
            } else {
              this.streamError.set(null);
            }
            return true;
          }
        }
      } catch (e) {
        console.error('Polling error:', e);
      }
    }
    return false;
  }

  private async reconcileAuthoritativeMessages(
    convId: string,
    runId?: string,
    tempUserId?: string,
    tempAssistantId?: string
  ): Promise<{ status?: string; isTerminal?: boolean; errorMessage?: string }> {
    const hasRun = Boolean(runId);
    const query = hasRun
      ? `query GetAuthoritativeMessagesAndRun($convId: ID!, $runId: ID!) {
          conversation(id: $convId) {
            id
            messages(limit: 50, offset: 0) {
              id
              runId
              role
              content
              interrupted
              createdAt
            }
          }
          run(id: $runId) {
            id
            status
            errorMessage
          }
        }`
      : `query GetAuthoritativeMessages($convId: ID!) {
          conversation(id: $convId) {
            id
            messages(limit: 50, offset: 0) {
              id
              runId
              role
              content
              interrupted
              createdAt
            }
          }
        }`;

    const res = await fetch('/graphql', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        query,
        variables: hasRun ? { convId, runId } : { convId }
      })
    });

    if (!res.ok) {
      throw new Error(`GraphQL query failed with HTTP status ${res.status}`);
    }

    const json = await res.json();
    if (json.errors && json.errors.length > 0) {
      throw new Error(json.errors[0].message || 'GraphQL reconciliation error');
    }

    if (this.currentConversationId() !== convId) {
      return { isTerminal: false };
    }

    const runData = json.data?.run;
    const runStatus = runData?.status;
    const isTerminal = !hasRun || (runStatus && runStatus !== 'PENDING' && runStatus !== 'RUNNING');

    if (json.data && json.data.conversation) {
      const raw: GraphQLMessageView[] = json.data.conversation.messages || [];
      const serverChronological: ExtendedMessage[] = [...raw].reverse().map(m => {
        const roleNormalized = (m.role || '').toLowerCase();
        const isRunFailed = runStatus === 'FAILED' && m.runId === runId;
        return {
          id: m.id,
          role: (roleNormalized === 'user' ? 'user' : 'assistant') as 'user' | 'assistant',
          content: m.content,
          interrupted: !!m.interrupted,
          failed: isRunFailed
        };
      });

      // Merge with existing messages preserving earlier loaded history
      this.messages.update(current => {
        if (serverChronological.length === 0) {
          return current;
        }

        const oldestServerId = serverChronological[0].id;
        const oldestServerIdx = current.findIndex(m => m.id === oldestServerId);

        let prefix: ExtendedMessage[] = [];
        if (oldestServerIdx > 0) {
          // Preserve messages loaded before the server slice
          prefix = current.slice(0, oldestServerIdx);
        }

        // If the run is still active, keep any active temp messages at the end
        if (!isTerminal) {
          const activeTemps = current.filter(
            m => (m.id === tempUserId || m.id === tempAssistantId || m.id.startsWith('temp-')) &&
                 !serverChronological.some(sm => sm.id === m.id)
          );
          return [...prefix, ...serverChronological, ...activeTemps];
        }

        return [...prefix, ...serverChronological];
      });

      // Update messageOffset to count of authoritative server messages held in UI
      const currentMsgs = this.messages();
      const authoritativeCount = currentMsgs.filter(m => !m.id.startsWith('temp-')).length;
      this.messageOffset.set(authoritativeCount);

      if (isTerminal) {
        this.activeRunId.set(null);
      }
      this.scrollToBottom();
    }

    return {
      status: runStatus,
      isTerminal: !!isTerminal,
      errorMessage: runData?.errorMessage
    };
  }

  async cancelRun(): Promise<void> {
    const runId = this.activeRunId();
    if (!runId || this.isCancelling()) return;

    this.isCancelling.set(true);
    try {
      const res = await fetch(`/api/runs/${runId}/cancel`, {
        method: 'POST'
      });
      if (res.ok) {
        const outcome = await res.json();
        /*
         * RATIONALE: True DB status alignment.
         * The cancel endpoint returns { runId, status, cancelled }.
         * Authoritative message ID, content, and offset come via the SSE terminal event
         * (or GraphQL history reconciliation if connection disconnected).
         * We do not invent or delete messages based on nonexistent outcome.messageId fields.
         */
      }
    } catch (err) {
      console.error('Failed to cancel run:', err);
    } finally {
      this.isCancelling.set(false);
    }
  }

  private handleSseEvent(eventType: string, data: any, tempUserId: string, tempAssistantId: string): void {
    if (eventType === 'run.started') {
      if (data && data.runId) {
        this.activeRunId.set(data.runId);
        if (this.pendingRequest()) {
          this.pendingRequest.update(p => p ? { ...p, runId: data.runId } : null);
        }
      }
      if (data && data.userMessageId && tempUserId) {
        this.messages.update(prev =>
          prev.map(m => (m.id === tempUserId ? { ...m, id: data.userMessageId } : m))
        );
      }
    } else if (eventType === 'message.delta') {
      const delta = data.delta || '';
      this.messages.update(prev =>
        prev.map(m => {
          if (m.id === tempAssistantId && m.role === 'assistant') {
            const currentContent = typeof m.content === 'string' ? m.content : '';
            const updated: ExtendedMessage = {
              ...m,
              content: currentContent + delta
            };
            return updated;
          }
          return m;
        })
      );
      this.scrollToBottom();
    } else if (eventType === 'run.completed') {
      const messageId = data.messageId;
      this.messages.update(prev =>
        prev.map(m => {
          if (m.role === 'assistant' && (m.id === tempAssistantId || (messageId && m.id === messageId) || m.id.startsWith('temp-a-'))) {
            const updated: ExtendedMessage = {
              ...m,
              id: messageId || m.id,
              interrupted: false,
              failed: false
            };
            return updated;
          }
          return m;
        })
      );
      this.activeRunId.set(null);
      this.onRunTerminal(data.runId, 2);
    } else if (eventType === 'run.cancelled' || eventType === 'run.failed') {
      const messageId = data.messageId;
      const hasAssistant = Boolean(messageId);
      this.messages.update(prev => {
        const assistant = prev.find(
          m => m.role === 'assistant' && (m.id === tempAssistantId || (messageId && m.id === messageId) || m.id.startsWith('temp-a-'))
        );
        if (!hasAssistant || !assistant || !assistant.content) {
          // Zero delta: remove empty assistant placeholder!
          return prev.filter(m => m !== assistant);
        }
        return prev.map(m => {
          if (m === assistant) {
            return {
              ...m,
              id: messageId || m.id,
              interrupted: true,
              failed: eventType === 'run.failed'
            };
          }
          return m;
        });
      });
      this.activeRunId.set(null);
      this.onRunTerminal(data.runId, hasAssistant ? 2 : 1);
      if (eventType === 'run.failed') {
        this.streamError.set(data.reason ? `生成失敗: ${data.reason}` : '生成失敗');
      }
    }
  }

  isFailed(msg: Message): boolean {
    return !!(msg as any).failed;
  }

  // --- UI Helpers ---

  isInterrupted(msg: Message): boolean {
    return !!(msg as any).interrupted;
  }

  copyMessage(content: string | any, msgId: string): void {
    const text = typeof content === 'string' ? content : JSON.stringify(content);
    navigator.clipboard.writeText(text);
    this.copiedMessageId.set(msgId);
    setTimeout(() => {
      if (this.copiedMessageId() === msgId) {
        this.copiedMessageId.set(null);
      }
    }, 2000);
  }

  onKeydown(event: KeyboardEvent): void {
    /*
     * RATIONALE: IME Composition Guard.
     * When user is composing Chinese/Japanese IME candidates, isComposing is true
     * and keyCode is 229; pressing Enter to select a candidate must NOT submit the input.
     */
    if (event.isComposing || event.keyCode === 229) {
      return;
    }
    if (event.key === 'Enter') {
      if (event.shiftKey) {
        // Shift + Enter: standard multiline newline
        return;
      }
      event.preventDefault();
      this.submitInput(this.inputValue());
    }
  }

  private scrollToBottom(): void {
    setTimeout(() => {
      if (this.messagesContainer) {
        const el = this.messagesContainer.nativeElement;
        el.scrollTop = el.scrollHeight;
      }
    }, 50);
  }
}
