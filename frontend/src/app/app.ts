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

export type ExtendedMessage = Message & { interrupted?: boolean };

export interface SseEventPayload {
  eventType: string;
  dataStr: string;
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
                role
                content
                interrupted
                createdAt
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
              interrupted: !!m.interrupted
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
                role
                content
                interrupted
                createdAt
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
            interrupted: !!m.interrupted
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
      }
    } catch (err) {
      console.error('Failed to create conversation:', err);
    }
  }

  // --- ChatState Abstract Implementations ---

  override changeInput(value: string): void {
    this.inputValue.set(value);
  }

  override async submitInput(value: string): Promise<void> {
    const text = (value || this.inputValue()).trim();
    if (!text || this.isStreaming()) return;

    let convId = this.currentConversationId();
    if (!convId) {
      await this.newConversation();
      convId = this.currentConversationId();
      if (!convId) return;
    }

    const clientRequestId = 'req-' + crypto.randomUUID();
    const tempUserId = 'temp-u-' + Date.now();
    const tempAssistantId = 'temp-a-' + Date.now();

    // Optimistically push user message and placeholder assistant message
    const userMessage: UserMessage = {
      id: tempUserId,
      role: 'user',
      content: text
    };

    const assistantMessage: AssistantMessage = {
      id: tempAssistantId,
      role: 'assistant',
      content: ''
    };

    this.messages.update(prev => [...prev, userMessage, assistantMessage]);
    this.inputValue.set('');
    this.isStreaming.set(true);
    this.scrollToBottom();

    try {
      /*
       * RATIONALE: Native fetch POST SSE reader with TextDecoder stream mode.
       * Decodes bytes incrementally so multi-byte UTF-8 character boundaries (e.g. Traditional Chinese)
       * split across socket packets are safely preserved.
       */
      const response = await fetch(`/api/conversations/${convId}/runs`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'text/event-stream'
        },
        body: JSON.stringify({
          clientRequestId,
          message: text
        })
      });

      if (!response.ok || !response.body) {
        throw new Error(`Stream request failed with status ${response.status}`);
      }

      await this.readSseStream(response.body, tempUserId, tempAssistantId);
    } catch (err) {
      console.error('Streaming error:', err);
      // Append error indicator to the assistant message
      this.messages.update(prev =>
        prev.map(m => {
          if (m.id === tempAssistantId && m.role === 'assistant') {
            const currentContent = typeof m.content === 'string' ? m.content : '';
            const updated: AssistantMessage = {
              ...m,
              content: currentContent + '\n[Stream Error]'
            };
            return updated;
          }
          return m;
        })
      );
    } finally {
      this.isStreaming.set(false);
      this.scrollToBottom();
    }
  }

  /**
   * Reads and parses SSE stream chunks with strict UTF-8 & frame boundary handling.
   * Dispatches events without swallowing parse errors.
   */
  private async readSseStream(
    body: ReadableStream<Uint8Array>,
    tempUserId: string,
    tempAssistantId: string
  ): Promise<void> {
    const reader = body.getReader();
    const decoder = new TextDecoder('utf-8', { fatal: false });
    let buffer = '';

    while (true) {
      const { done, value: chunk } = await reader.read();
      if (done) break;

      buffer += decoder.decode(chunk, { stream: true });

      // Normalize CRLF to LF for consistent frame splitting
      buffer = buffer.replace(/\r\n/g, '\n');

      // Split SSE frames on standard double newline
      const frames = buffer.split('\n\n');
      buffer = frames.pop() || '';

      for (const frame of frames) {
        const parsed = this.parseSseFrame(frame);
        if (!parsed) continue;

        try {
          const data = JSON.parse(parsed.dataStr);
          this.handleSseEvent(parsed.eventType, data, tempUserId, tempAssistantId);
        } catch (parseErr) {
          console.error(`SSE JSON parse error for event ${parsed.eventType}:`, parsed.dataStr, parseErr);
          throw new Error(`Corrupted SSE JSON in event '${parsed.eventType}': ${parsed.dataStr}`);
        }
      }
    }

    // Flush any remaining characters in decoder
    const finalChunk = decoder.decode();
    if (finalChunk) {
      buffer += finalChunk.replace(/\r\n/g, '\n');
    }

    if (buffer.trim()) {
      const parsed = this.parseSseFrame(buffer);
      if (parsed) {
        try {
          const data = JSON.parse(parsed.dataStr);
          this.handleSseEvent(parsed.eventType, data, tempUserId, tempAssistantId);
        } catch (parseErr) {
          console.error(`SSE JSON parse error in final buffer:`, parsed.dataStr, parseErr);
          throw new Error(`Corrupted final SSE JSON: ${parsed.dataStr}`);
        }
      }
    }
  }

  private parseSseFrame(frame: string): SseEventPayload | null {
    const lines = frame.split('\n');
    let eventType = '';
    let dataStr = '';

    for (const line of lines) {
      const trimmed = line.trim();
      if (trimmed.startsWith('event:')) {
        eventType = trimmed.substring(6).trim();
      } else if (trimmed.startsWith('data:')) {
        dataStr = trimmed.substring(5).trim();
      }
    }

    if (!eventType || !dataStr) {
      return null;
    }

    return { eventType, dataStr };
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
         * RATIONALE: Update based on true DB status returned by cancel endpoint.
         * If model completed before cancel CAS, status is COMPLETED rather than CANCELLED.
         * Also align temporary message ID with authoritative DB messageId to prevent duplicate optimistic items.
         */
        if (outcome.status === 'CANCELLED') {
          const hasAssistant = Boolean(outcome.messageId);
          this.messages.update(prev =>
            prev.map(m => {
              if (m.role === 'assistant' && (m.id.startsWith('temp-a-') || (outcome.messageId && m.id === outcome.messageId) || m.id === runId)) {
                return {
                  ...m,
                  id: outcome.messageId || m.id,
                  interrupted: true
                };
              }
              return m;
            })
          );
          this.onRunTerminal(runId, hasAssistant ? 2 : 1);
        }
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
              interrupted: false
            };
            return updated;
          }
          return m;
        })
      );
      this.activeRunId.set(null);
      this.onRunTerminal(data.runId, 2);
    } else if (eventType === 'run.cancelled') {
      const messageId = data.messageId;
      const hasAssistant = Boolean(messageId);
      this.messages.update(prev =>
        prev.map(m => {
          if (m.role === 'assistant' && (m.id === tempAssistantId || (messageId && m.id === messageId) || m.id.startsWith('temp-a-'))) {
            const updated: ExtendedMessage = {
              ...m,
              id: messageId || m.id,
              interrupted: true
            };
            return updated;
          }
          return m;
        })
      );
      this.activeRunId.set(null);
      this.onRunTerminal(data.runId, hasAssistant ? 2 : 1);
    } else if (eventType === 'run.failed') {
      this.activeRunId.set(null);
    }
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
