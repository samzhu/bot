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
  readonly messages = signal<Message[]>([]);
  readonly isStreaming = signal<boolean>(false);
  readonly copiedMessageId = signal<string | null>(null);

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
      const res = await fetch('/graphql', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          query: `query { conversations(limit: 50, offset: 0) { id botId title createdAt updatedAt } }`
        })
      });
      const json = await res.json();
      if (json.data && json.data.conversations) {
        this.conversations.set(json.data.conversations);
      }
    } catch (err) {
      console.error('Failed to load conversations:', err);
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

    try {
      const res = await fetch('/graphql', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          query: `query GetConversation($id: ID!) {
            conversation(id: $id) {
              id
              botId
              title
              messages(limit: 100, offset: 0) {
                id
                role
                content
                interrupted
                createdAt
              }
            }
          }`,
          variables: { id }
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
        const rawMessages: GraphQLMessageView[] = json.data.conversation.messages || [];
        /*
         * RATIONALE: The backend GraphQL API orders messages DESC (newest first) for paginated feeds.
         * The UI dialogue view requires chronological ASC order (oldest to newest); hence we reverse the list.
         * Furthermore, backend roles are uppercase 'USER' / 'ASSISTANT', so we normalize case to 'user' / 'assistant'.
         */
        const chronological = [...rawMessages].reverse().map(m => {
          const roleNormalized = (m.role || '').toLowerCase();
          if (roleNormalized === 'user') {
            const userMsg: UserMessage = {
              id: m.id,
              role: 'user',
              content: m.content
            };
            return userMsg;
          } else {
            const assistantMsg: AssistantMessage = {
              id: m.id,
              role: 'assistant',
              content: m.content
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

  async newConversation(): Promise<void> {
    if (this.isStreaming()) return;

    const bot = this.seedBot();
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

      await this.readSseStream(response.body, tempAssistantId);
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
          this.handleSseEvent(parsed.eventType, data, tempAssistantId);
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
          this.handleSseEvent(parsed.eventType, data, tempAssistantId);
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

  private handleSseEvent(eventType: string, data: any, tempAssistantId: string): void {
    if (eventType === 'run.started') {
      // Sequence 1 verified
    } else if (eventType === 'message.delta') {
      const delta = data.delta || '';
      this.messages.update(prev =>
        prev.map(m => {
          if (m.id === tempAssistantId && m.role === 'assistant') {
            const currentContent = typeof m.content === 'string' ? m.content : '';
            const updated: AssistantMessage = {
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
      if (messageId) {
        this.messages.update(prev =>
          prev.map(m => {
            if (m.id === tempAssistantId && m.role === 'assistant') {
              const updated: AssistantMessage = { ...m, id: messageId };
              return updated;
            }
            return m;
          })
        );
      }
    } else if (eventType === 'run.failed' || eventType === 'run.cancelled') {
      // Stream terminal failure or cancel
    }
  }

  // --- UI Helpers ---

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
    if (event.key === 'Enter' && !event.shiftKey) {
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
