import {
  Component,
  OnInit,
  computed,
  forwardRef,
  inject,
  signal,
  ElementRef,
  viewChild,
  WritableSignal
} from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  ChatState,
  CopilotChatView,
  CopilotChatInput,
  CopilotChatToolbarButton,
  CopilotChatSendButton,
  CopilotChatAssistantMessage,
  CopilotChatAssistantMessageToolbar,
  CopilotChatAssistantMessageCopyButton
} from '@copilotkit/angular';
import { Message, UserMessage, AssistantMessage } from '@ag-ui/core';
import { SseStreamParser } from './sse-parser';
import { DraftStore } from './draft-store';
import {
  ConversationListStore,
  ConversationView,
  PinMutationError,
  PinRefreshError
} from './conversation-list-store';
export type { ConversationView, PinMutationError, PinRefreshError };

export interface BotView {
  id: string;
  name: string;
  createdAt: string;
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
  isFromNewDraft?: boolean;
  submittedSnapshot?: string;
}

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule,
    CopilotChatView,
    CopilotChatInput,
    CopilotChatToolbarButton,
    CopilotChatSendButton,
    CopilotChatAssistantMessage,
    CopilotChatAssistantMessageToolbar,
    CopilotChatAssistantMessageCopyButton
  ],
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
  private readonly elementRef = inject(ElementRef);
  readonly chatInputRef = viewChild(CopilotChatInput);

  // Injected feature stores
  readonly draftStore = inject(DraftStore);
  readonly conversationStore = inject(ConversationListStore);

  // Concurrency lock for creation and submission
  readonly isSubmitting = signal<boolean>(false);

  // Generation counter to guard against late async responses overwriting current selection
  private selectionGeneration = 0;

  // ChatState abstract implementations
  readonly inputValue: WritableSignal<string> = signal('');

  // Application State
  readonly bots = signal<BotView[]>([]);

  // Delegated Conversation List & Pinning State (managed authoritatively by ConversationListStore)
  readonly pinnedConversations = this.conversationStore.pinnedConversations;
  readonly unpinnedConversations = this.conversationStore.unpinnedConversations;
  readonly conversations = this.conversationStore.conversations;
  readonly isPinnedCollapsed = this.conversationStore.isPinnedCollapsed;
  readonly isUnpinnedCollapsed = this.conversationStore.isUnpinnedCollapsed;
  readonly activeConversation = this.conversationStore.activeConversation;
  readonly pinPendingIds = this.conversationStore.pinPendingIds;
  readonly pinMutationError = this.conversationStore.pinMutationError;
  readonly pinRefreshError = this.conversationStore.pinRefreshError;
  readonly conversationOffset = this.conversationStore.conversationOffset;
  readonly hasMoreConversations = this.conversationStore.hasMoreConversations;
  readonly isLoadingMoreConversations = this.conversationStore.isLoadingMoreConversations;

  // Independent Dual-Section Pagination State
  readonly pinnedOffset = this.conversationStore.pinnedOffset;
  readonly hasMorePinned = this.conversationStore.hasMorePinned;
  readonly isLoadingMorePinned = this.conversationStore.isLoadingMorePinned;
  readonly unpinnedOffset = this.conversationStore.unpinnedOffset;
  readonly hasMoreUnpinned = this.conversationStore.hasMoreUnpinned;
  readonly isLoadingMoreUnpinned = this.conversationStore.isLoadingMoreUnpinned;

  readonly currentConversationId = signal<string | null>(null);
  readonly messages = signal<ExtendedMessage[]>([]);
  readonly isStreaming = signal<boolean>(false);
  readonly isCancelling = signal<boolean>(false);
  readonly activeRunId = signal<string | null>(null);
  readonly pendingRequest = signal<PendingRequestInfo | null>(null);
  readonly streamError = signal<string | null>(null);
  readonly isRecovering = signal<boolean>(false);

  // Bounded Pagination State (default limit 20, max cap 100)
  readonly messageOffset = signal<number>(20);
  readonly hasMoreMessages = signal<boolean>(false);
  readonly isLoadingEarlier = signal<boolean>(false);

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
    return this.conversationStore.findConversation(id);
  });

  readonly isDeepActiveConversation = computed<boolean>(() => {
    const active = this.activeConversation();
    const id = this.currentConversationId();
    if (!active || !id || active.id !== id) return false;
    return !this.pinnedConversations().some(c => c.id === id)
        && !this.unpinnedConversations().some(c => c.id === id);
  });

  readonly currentTitle = computed<string>(() => {
    const conv = this.currentConversation();
    return conv ? (conv.title || 'Untitled') : 'New Conversation';
  });

  readonly lastMessageInterrupted = computed<boolean>(() => {
    const msgs = this.messages();
    if (msgs.length === 0) return false;
    const last = msgs[msgs.length - 1];
    return last.role === 'assistant' && !!last.interrupted && !last.failed;
  });

  readonly lastMessageFailed = computed<boolean>(() => {
    const msgs = this.messages();
    if (msgs.length === 0) return false;
    const last = msgs[msgs.length - 1];
    return last.role === 'assistant' && !!last.failed;
  });

  async ngOnInit(): Promise<void> {
    await this.loadBots();
    await this.loadConversations();

    // Check localStorage for previously active conversation to survive reloads
    const savedId = this.getStorageItem('bot_active_conversation_id');
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

  /*
   * WHY / ARCHITECTURAL RATIONALE:
   * Local storage access can fail or throw SecurityError in restricted environments
   * (e.g. private browsing mode, disabled storage cookies, sandboxed iframes, or headless unit tests).
   * All conversation history, runs, and messages are authoritatively persisted on the server database.
   * Client-side localStorage is strictly a best-effort convenience mechanism to restore active conversation selection
   * across browser page reloads. A storage error is safely swallowed so that storage unavailability never breaks chat operations.
   */
  private getStorageItem(key: string): string | null {
    try {
      if (typeof window !== 'undefined' && window.localStorage) {
        return window.localStorage.getItem(key);
      }
    } catch (_) {}
    return null;
  }

  private setStorageItem(key: string, value: string): void {
    try {
      if (typeof window !== 'undefined' && window.localStorage) {
        window.localStorage.setItem(key, value);
      }
    } catch (_) {}
  }

  private removeStorageItem(key: string): void {
    try {
      if (typeof window !== 'undefined' && window.localStorage) {
        window.localStorage.removeItem(key);
      }
    } catch (_) {}
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

  loadConversations(): Promise<void> {
    return this.conversationStore.loadConversations(true);
  }

  loadMoreConversations(): Promise<void> {
    return this.conversationStore.loadMoreConversations();
  }

  loadMorePinned(): Promise<void> {
    return this.conversationStore.loadMorePinned();
  }

  loadMoreUnpinned(): Promise<void> {
    return this.conversationStore.loadMoreUnpinned();
  }

  togglePinnedSection(): void {
    this.conversationStore.togglePinnedSection();
  }

  toggleUnpinnedSection(): void {
    this.conversationStore.toggleUnpinnedSection();
  }

  isPinDisabled(convId?: string): boolean {
    return this.isStreaming() || this.isSubmitting() || this.conversationStore.isPinDisabled(convId);
  }

  isPinRefreshRetryDisabled(): boolean {
    return this.isStreaming() || this.isSubmitting() || this.conversationStore.isAnyPinPending() || this.conversationStore.isRetryingRefetch();
  }

  isPinPending(convId: string): boolean {
    return this.conversationStore.isPinPending(convId);
  }

  togglePin(conv: ConversationView): Promise<void> {
    if (this.isStreaming() || this.isSubmitting() || this.isPinDisabled(conv?.id)) {
      return Promise.resolve();
    }
    return this.conversationStore.togglePin(conv);
  }

  retryPinRefetch(): Promise<void> {
    if (this.isStreaming() || this.isSubmitting() || this.conversationStore.isAnyPinPending() || this.conversationStore.isRetryingRefetch()) {
      return Promise.resolve();
    }
    return this.conversationStore.retryPinRefetch();
  }

  retryPinMutation(): Promise<void> {
    if (this.isStreaming() || this.isSubmitting() || this.isPinDisabled()) {
      return Promise.resolve();
    }
    return this.conversationStore.retryPinMutation();
  }

  async selectConversation(id: string): Promise<void> {
    if (this.isStreaming() || this.isSubmitting()) return;

    // Save unsent input of current conversation or draft before switching
    const prevId = this.currentConversationId();
    if (prevId) {
      this.draftStore.setConversationDraft(prevId, this.inputValue());
    } else {
      this.draftStore.setDraft(this.inputValue());
    }

    const currentGen = ++this.selectionGeneration;
    this.currentConversationId.set(id);
    this.setStorageItem('bot_active_conversation_id', id);
    this.completedRunIds.clear();
    this.messageOffset.set(20);
    this.hasMoreMessages.set(false);
    this.streamError.set(null);
    this.isRecovering.set(false);

    // Restore unsent draft input for this conversation (if any) to signal and DOM
    this.restoreInput(this.draftStore.getConversationDraft(id));

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
       * If user rapidly switches conversations or clicks + New Chat before this fetch completes,
       * discard the outdated response so it doesn't overwrite the newly selected conversation or draft.
       */
      if (this.selectionGeneration !== currentGen || this.currentConversationId() !== id) {
        return;
      }

      if (json.data && json.data.conversation) {
        const conv = json.data.conversation;
        const view: ConversationView = {
          id: conv.id,
          botId: conv.botId,
          title: conv.title,
          pinned: !!conv.pinned,
          createdAt: conv.createdAt || '',
          updatedAt: conv.updatedAt || ''
        };
        this.conversationStore.setActiveConversation(view);
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

  /*
   * WHY / ARCHITECTURAL RATIONALE:
   * Deferred Conversation Creation.
   * Clicking "+ New Chat" switches the UI to an in-memory draft session without calling
   * the backend or adding an empty record to the database or sidebar.
   * Creation is deferred until the user submits their first valid non-empty message.
   * This prevents database pollution with empty/abandoned conversations while preserving instant client responsiveness.
   * Unsent draft text is preserved in DraftStore and restored when switching back.
   */
  async newConversation(): Promise<void> {
    if (this.isStreaming() || this.isSubmitting()) return;

    // Invalidate any in-flight asynchronous conversation selection
    this.selectionGeneration++;

    const prevId = this.currentConversationId();
    if (prevId) {
      this.draftStore.setConversationDraft(prevId, this.inputValue());
    } else {
      this.draftStore.setDraft(this.inputValue());
    }

    this.currentConversationId.set(null);
    this.conversationStore.setActiveConversation(null);
    this.removeStorageItem('bot_active_conversation_id');
    this.messages.set([]);
    this.messageOffset.set(20);
    this.hasMoreMessages.set(false);
    this.streamError.set(null);
    this.isRecovering.set(false);
    this.isCancelling.set(false);
    this.pendingRequest.set(null);

    // Restore unsent draft text to signal and DOM
    this.restoreInput(this.draftStore.getDraft());
  }

  /*
   * WHY: CopilotKit's CopilotChatInput.send() synchronously wipes input state:
   * 1) chatState.submitInput(trimmed) [fire & forget, not awaited]
   * 2) chatState.changeInput("") [synchronous]
   * 3) textAreaRef.setValue("") [synchronous native DOM wipe]
   * Because submitInput is async (awaiting conversation creation or run initiation),
   * a server error during create/submit requires restoring both the Angular signal and the
   * textarea DOM value. We prefer CopilotChatInput's official textAreaRef.setValue() API,
   * falling back to elementRef-scoped DOM query (ensuring no other textareas on the page are touched).
   */
  restoreInput(text: string): void {
    this.inputValue.set(text);

    // 1. Try official SDK controlled component API via ViewChild
    const inputCmp = this.chatInputRef();
    if (inputCmp && typeof inputCmp.textAreaRef === 'function') {
      const textAreaCmp = inputCmp.textAreaRef();
      if (textAreaCmp && typeof textAreaCmp.setValue === 'function') {
        textAreaCmp.setValue(text);
        return;
      }
    }

    // 2. Fallback scoped strictly to this App component's DOM tree (never global document)
    if (typeof this.elementRef?.nativeElement?.querySelector === 'function') {
      const applyToHostDom = () => {
        const textarea = this.elementRef.nativeElement.querySelector(
          'textarea[copilotChatTextarea], textarea[data-testid="copilot-chat-textarea"], textarea'
        ) as HTMLTextAreaElement | null;
        if (textarea && textarea.value !== text) {
          textarea.value = text;
          textarea.dispatchEvent(new Event('input', { bubbles: true }));
        }
      };
      applyToHostDom();
      if (typeof queueMicrotask === 'function') {
        queueMicrotask(applyToHostDom);
      }
    }
  }

  // --- ChatState Abstract Implementations ---

  override changeInput(value: string): void {
    this.inputValue.set(value);
    const id = this.currentConversationId();
    if (!id) {
      if (!this.isSubmitting() || value.trim().length > 0) {
        this.draftStore.setDraft(value);
      }
    } else {
      if (!this.isSubmitting() || value.trim().length > 0) {
        this.draftStore.setConversationDraft(id, value);
      }
    }
  }

  override async submitInput(value?: string, retryClientRequestId?: string, targetConvId?: string): Promise<void> {
    const rawInput = this.inputValue();
    const text = (value !== undefined && value.trim() ? value : rawInput).trim();
    if (!text || this.isStreaming() || this.isSubmitting()) return;

    // Snapshot exact raw input before submission so trailing whitespace/newlines are preserved on create/run failure
    const draftSnapshot = (rawInput && rawInput.trim() === text) ? rawInput : (value !== undefined ? value : text);

    /*
     * WHY: Concurrency lock for creation and submission.
     * Keeping NewChat and conversation selection disabled while isSubmitting is true
     * prevents split-brain state, duplicate conversation creation, and race conditions during in-flight network requests.
     */
    this.isSubmitting.set(true);

    const pending = this.pendingRequest();
    const isNewDraft = !targetConvId && !this.currentConversationId();
    // Preserve draft ownership across retries (e.g. if initial run returns 500 after successful create)
    const isFromNewDraft = retryClientRequestId ? (pending?.isFromNewDraft ?? false) : isNewDraft;
    const sourceConvId = targetConvId || this.currentConversationId();
    let convId = sourceConvId;
    const submitGen = this.selectionGeneration;
    const clientRequestId = retryClientRequestId || ('req-' + crypto.randomUUID());
    let tempUserId = 'temp-u-' + Date.now();
    let tempAssistantId = 'temp-a-' + Date.now();

    try {
      // 1. Deferred Creation: create conversation on first valid submission if starting from draft
      if (!convId) {
        let bot = this.seedBot();
        if (!bot) {
          await this.loadBots();
          bot = this.seedBot();
        }
        if (!bot) {
          this.streamError.set('No active bot available to start conversation');
          this.draftStore.setDraft(draftSnapshot);
          this.restoreInput(draftSnapshot);
          return;
        }

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

          if (!res.ok) {
            this.streamError.set(`建立對話失敗：HTTP ${res.status}`);
            this.draftStore.setDraft(draftSnapshot);
            this.restoreInput(draftSnapshot);
            return;
          }

          const json = await res.json();
          if (json.errors && json.errors.length > 0) {
            const errorMsg = json.errors[0]?.message || 'Failed to create conversation';
            this.streamError.set(`建立對話失敗：${errorMsg}`);
            this.draftStore.setDraft(draftSnapshot);
            this.restoreInput(draftSnapshot);
            return;
          }

          if (json.data && json.data.createConversation) {
            const newConv = json.data.createConversation;
            const createdView: ConversationView = {
              id: newConv.id,
              botId: newConv.botId,
              title: newConv.title,
              pinned: false,
              createdAt: newConv.createdAt || '',
              updatedAt: newConv.updatedAt || ''
            };
            this.conversationStore.addCreatedConversation(createdView);
            convId = newConv.id;

            // Selection guard: if user navigated away while create was in-flight, do not override current selection
            if (this.selectionGeneration !== submitGen) {
              return;
            }

            this.currentConversationId.set(newConv.id);
            this.setStorageItem('bot_active_conversation_id', newConv.id);
          } else {
            this.streamError.set('建立對話失敗：無回應資料');
            this.draftStore.setDraft(draftSnapshot);
            this.restoreInput(draftSnapshot);
            return;
          }
        } catch (err: any) {
          console.error('Failed to create conversation on backend:', err);
          this.streamError.set(`建立對話失敗，請重試：${err?.message || err}`);
          this.draftStore.setDraft(draftSnapshot);
          this.restoreInput(draftSnapshot);
          return;
        }
      }

      if (!convId) {
        return;
      }

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
        tempAssistantId,
        isFromNewDraft,
        submittedSnapshot: draftSnapshot
      });
      this.streamError.set(null);

      this.isStreaming.set(true);

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

      const contentType = response.headers?.get?.('content-type') || '';

      if (contentType.includes('application/json')) {
        const json = await response.json().catch(() => ({}));
        if (this.currentConversationId() !== convId) return;

        if (response.ok) {
          // Clear draft only for the submission source once officially accepted by server
          if (isFromNewDraft) {
            const curDraft = this.draftStore.getDraft();
            if (!curDraft || curDraft === draftSnapshot || curDraft.trim() === text) {
              this.draftStore.clearDraft();
            }
          } else if (sourceConvId) {
            const convDraft = this.draftStore.getConversationDraft(sourceConvId);
            if (!convDraft || convDraft === draftSnapshot || convDraft.trim() === text) {
              this.draftStore.deleteConversationDraft(sourceConvId);
            }
          }
          if (this.inputValue().trim() === text) {
            this.inputValue.set('');
          }

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
          // 409 Conflict: rejected by server, clear optimistic rows and restore input
          this.removeOptimisticMessages(tempUserId, tempAssistantId);
          this.pendingRequest.set(null);
          this.streamError.set(json.error || 'Another active run is in progress for this conversation');
          this.restoreInput(draftSnapshot);
          return;
        } else {
          throw new Error(json.error || `Request failed with status ${response.status}`);
        }
      }

      if (!response.ok || !response.body) {
        throw new Error(`Stream request failed with status ${response.status}`);
      }

      // Clear draft only for the submission source once officially accepted by server (200 SSE Stream)
      if (isFromNewDraft) {
        const curDraft = this.draftStore.getDraft();
        if (!curDraft || curDraft === draftSnapshot || curDraft.trim() === text) {
          this.draftStore.clearDraft();
        }
      } else if (sourceConvId) {
        const convDraft = this.draftStore.getConversationDraft(sourceConvId);
        if (!convDraft || convDraft === draftSnapshot || convDraft.trim() === text) {
          this.draftStore.deleteConversationDraft(sourceConvId);
        }
      }
      if (this.inputValue().trim() === text) {
        this.inputValue.set('');
      }

      await this.readSseStream(response.body, tempUserId, tempAssistantId, convId);
      if (this.currentConversationId() !== convId) return;
      this.pendingRequest.set(null);
    } catch (err: any) {
      console.error('Streaming or network error:', err);
      if (convId && this.currentConversationId() === convId) {
        this.isStreaming.set(false);
        await this.reconcileOrRecoverRun(convId, clientRequestId, tempUserId, tempAssistantId);
      }
    } finally {
      this.isSubmitting.set(false);
      if (convId && this.currentConversationId() === convId) {
        this.isStreaming.set(false);
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
}
