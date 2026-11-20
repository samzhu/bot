import { UsageComponent } from './usage.component';
import { WorkspaceStore } from './workspace.store';
import { ConversationTitleUpdates } from './conversation-title-updates';
import {
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
  viewChild
} from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  ConversationListStore,
  ConversationView,
  PinMutationError,
  PinRefreshError
} from './conversation-list-store';
export type { ConversationView, PinMutationError, PinRefreshError };

import { BotManagerComponent } from './bot/bot-manager.component';
import { ChatNavigationComponent } from './shell/chat-navigation.component';
import { ChatSidenavMode } from '@threadplane/chat';
import { ChatSurfaceComponent } from './chat-surface.component';
import { ChatAgUiTransport } from './chat-ag-ui-transport';
import { ChatSession, ChatSessionRegistry } from './chat-session-registry';
import { BotStore } from './bot/bot-store';
import {
  BotView,
  BotTarget,
  BotExecutionMode,
  BotConnectionStatus,
  getBotExecutionModeDisplay
} from './bot/bot.model';
export type { BotView, BotTarget, BotExecutionMode, BotConnectionStatus };

export interface GraphQLMessageView {
  id: string;
  conversationId: string;
  runId: string;
  role: string;
  content: string;
  interrupted: boolean;
  createdAt: string;
}

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule,
    BotManagerComponent,
    ChatNavigationComponent,
    ChatSurfaceComponent,
    UsageComponent
  ],
  host: { '(window:keydown)': 'onShortcut($event)', '(document:click)': 'onDocumentClick($event)', '(focusin)': 'rememberShellFocus($event)' },
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class App implements OnInit {
  readonly workspaces = new WorkspaceStore();
  readonly selectedWorkspaceName = computed(() => this.workspaces.workspaces().find(item => item.id === this.workspaces.selectedId())?.name ?? '');
  readonly selectedWorkspaceManager = computed(() => this.workspaces.workspaces().find(item => item.id === this.workspaces.selectedId())?.role === 'MANAGER');
  private readonly sourceWorkspaces = new WeakMap<ChatSession, string>();
  readonly navigation = viewChild(ChatNavigationComponent);
  readonly navigationLayout = signal<ChatSidenavMode>('expanded');
  readonly contextOpen = signal(true);
  readonly workspacePanelOpen = signal(false);
  readonly peekOpen = signal(false);
  peekTrigger: HTMLElement | null = null;
  pinPeek(): void {
    const active = document.activeElement;
    this.contextOpen.set(true);
    this.peekOpen.set(false);
    // Rendering removes the pin control; never override a newer deliberate focus change.
    setTimeout(() => {
      if (document.activeElement === active || document.activeElement === document.body) document.querySelector<HTMLElement>('#navigation-context button:not([disabled]), #navigation-context select:not([disabled])')?.focus();
    });
  }
  closePeek(restoreFocus = false): void {
    const active = document.activeElement;
    if (this.peekOpen() && this.workspacePanelOpen()) this.workspacePanelOpen.set(false);
    this.peekOpen.set(false);
    if (restoreFocus) setTimeout(() => {
      if (document.activeElement === active || document.activeElement === document.body) this.peekTrigger?.focus();
    });
  }
  // Responsive CSS can blur a control to BODY before the media-change callback runs.
  // Keep the last shell focus only to recover a now-invalid control without stealing new focus.
  private lastShellFocus: HTMLElement | null = null;
  rememberShellFocus(event: FocusEvent): void {
    if (event.target instanceof HTMLElement) this.lastShellFocus = event.target;
  }
  onLayoutModeChanged(mode: ChatSidenavMode): void {
    const previous = this.navigationLayout();
    const focused = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const active = focused === document.body ? this.lastShellFocus : focused;
    const wasModalHost = active?.matches('[role="dialog"][aria-label="側欄導覽"]') ?? false;
    this.navigationLayout.set(mode);
    if (previous !== mode) { this.workspacePanelOpen.set(false); this.closePeek(); }
    if (previous === mode || !active || (!this.lastShellFocus && focused === document.body)) return;
    requestAnimationFrame(() => {
      if (this.navigationLayout() !== mode || (document.activeElement !== active && document.activeElement !== document.body)) return;
      const rects = Array.from(active.getClientRects());
      const invalid = !active.isConnected || !!active.closest('[hidden], [inert]') ||
        getComputedStyle(active).display === 'none' || !rects.some(rect => rect.width > 0 && rect.height > 0);
      if (invalid || wasModalHost) {
        document.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-pressed="true"]:not([disabled])')?.focus();
      }
    });
  }
  onDocumentClick(event: MouseEvent): void {
    const target = event.target;
    if (this.peekOpen() && target instanceof Element && !target.closest('app-chat-navigation, .feature-rail')) this.closePeek();
  }
  toggleContext(event: Event): void {
    if (this.peekOpen()) { this.pinPeek(); return; }
    if (this.contextOpen() && document.getElementById('navigation-context')?.contains(document.activeElement)) {
      const selector = this.workspacePanelOpen() ? 'button[aria-label="工作區"]' : 'button[aria-pressed="true"]';
      document.querySelector<HTMLButtonElement>(`nav[aria-label="主要功能"] ${selector}`)?.focus();
    }
    if (this.contextOpen()) this.workspacePanelOpen.set(false);
    this.contextOpen.update(open => !open);
  }
  onShortcut(event: KeyboardEvent): void {
    if (event.key === 'Escape' && this.peekOpen() && !event.defaultPrevented) {
      event.preventDefault(); this.closePeek(true); return;
    }
    const target = event.target;
    if (!(event.metaKey || event.ctrlKey) || event.shiftKey || event.altKey || event.defaultPrevented || event.isComposing ||
        (target instanceof HTMLElement && (target.matches('input, textarea') || target.isContentEditable))) return;
    const key = event.key.toLowerCase();
    if (key === 'k') { event.preventDefault(); this.navigation()?.openSearch(); }
    else if (key === 'b' && this.navigationLayout() !== 'drawer' && this.currentView() !== 'usage') {
      event.preventDefault();
      this.toggleContext(event);
    }
  }
  cancelWorkspacePanel(restoreFocus = false): void {
    const active = document.activeElement;
    this.workspacePanelOpen.set(false); this.closePeek();
    if (restoreFocus) setTimeout(() => {
      if (document.activeElement === active || document.activeElement === document.body) {
        document.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="工作區"]')?.focus();
      }
    });
  }
  toggleWorkspacePanel(trigger: HTMLElement): void {
    if (this.workspacePanelOpen()) {
      if (this.navigationLayout() !== 'drawer') return;
      this.workspacePanelOpen.set(false);
      this.closePeek();
      if (this.navigationLayout() === 'drawer') this.navigation()?.setNavigationOpen(false);
      return;
    }
    this.workspacePanelOpen.set(true);
    if (this.navigationLayout() === 'drawer') this.navigation()?.openNavigation(trigger);
    else if (!this.contextOpen()) { this.peekTrigger = trigger; this.peekOpen.set(true); }
  }
  chooseWorkspace(id: string): void {
    if (id === this.workspaces.selectedId()) {
      this.cancelWorkspacePanel(true);
      if (this.navigationLayout() === 'drawer') this.navigation()?.setNavigationOpen(false);
      return;
    }
    if (!this.workspaces.workspaces().some(item => item.id === id)) return;
    const active = document.activeElement;
    this.workspacePanelOpen.set(false); this.closePeek(); this.navigation()?.closeForWorkspaceSelection();
    void this.selectWorkspace(id);
    setTimeout(() => {
      if (document.activeElement === active || document.activeElement === document.body) {
        document.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="工作區"]')?.focus();
      }
    });
  }
  recoverWorkspace(): void {
    const folder = document.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="工作區"]');
    if (folder && !this.workspacePanelOpen()) this.toggleWorkspacePanel(folder);
  }
  selectFeature(view: 'chat' | 'bots' | 'usage', event: Event): void {
    this.workspacePanelOpen.set(false);
    if (view === 'usage') this.closePeek();
    if (view !== 'usage') {
      if (this.navigationLayout() === 'drawer') this.navigation()?.openNavigation(event.currentTarget as HTMLElement);
      else if (!this.contextOpen()) {
        this.peekTrigger = event.currentTarget as HTMLElement;
        if (!this.peekOpen()) {
          this.peekOpen.set(true);
          if (event instanceof MouseEvent && event.detail === 0) setTimeout(() => {
            if (this.peekOpen() && document.activeElement === this.peekTrigger) document.querySelector<HTMLElement>('#navigation-context button:not([disabled]), #navigation-context select:not([disabled])')?.focus();
          });
        }
      }
    }
    this.currentView.set(view);
  }
  readonly chatTransport = new ChatAgUiTransport();
  private readonly sessionGeneration = signal(0);
  private readonly verifiedConversationId = signal<string | null>(null);
  private readonly sourceSelections = new WeakMap<ChatSession, number>();
  private readonly selectedSource = signal<ChatSession | null>(null);
  private readonly originatedConversations = new Set<string>();
  readonly chatSessions = new ChatSessionRegistry(this.chatTransport, () => crypto.randomUUID(), (key, id, source) => {
    const created = this.chatTransport.created.get(id);
    if (created && this.sourceWorkspaces.get(source) === this.workspaces.selectedId()) {
      this.conversationStore.addCreatedConversation({ ...created, pinned: false });
      this.originatedConversations.add(id);
      this.titleUpdates.watch(this.workspaces.selectedId(), [id]);
    }
    if (this.sourceSelections.get(source) === this.selectionGeneration && this.chatKey() === key) {
      if (created && this.sourceWorkspaces.get(source) === this.workspaces.selectedId()) this.conversationStore.setActiveConversation({ ...created, pinned: false });
      this.verifiedConversationId.set(id);
      this.currentConversationId.set(id);
      this.activeConversationBot.set(this.activeDraftBot() || this.seedBot());
      this.activeDraftBot.set(null);
      this.setStorageItem('bot_active_conversation_id', id);
    }
    this.sessionGeneration.update(n => n + 1);
  }, (id, source) => {
    const workspaceId = this.sourceWorkspaces.get(source);
    if (workspaceId && workspaceId === this.workspaces.selectedId() && this.originatedConversations.has(id)) {
      this.titleUpdates.watch(workspaceId, [id]);
    }
  });
  readonly chatKey = computed(() => this.currentConversationId() ? `conversation:${this.currentConversationId()}` : `draft:${JSON.stringify([this.workspaces.selectedId(), (this.activeDraftBot() || this.seedBot())?.id ?? 'none'])}`);
  readonly chatSession = computed(() => { this.sessionGeneration(); const id = this.currentConversationId(); if (id && this.verifiedConversationId() !== id) return this.chatSessions.session(`unverified:${id}`); return this.selectedSource() ?? this.chatSessions.session(this.chatKey()); });
  readonly chatAgent = computed(() => this.chatTransport.agent(this.currentConversationId() && this.verifiedConversationId() !== this.currentConversationId() ? `unverified:${this.currentConversationId()}` : this.currentConversationId() || this.chatKey()));
  readonly chatCanSend = computed(() => !this.isInitializing() && !!this.workspaces.selectedId() && (!this.currentConversationId() || this.verifiedConversationId() === this.currentConversationId()) && !this.chatSession().active() && !!this.currentBot()?.canChat);
  chatDraftChanged(text: string): void {
    this.chatSessions.editSource(this.chatSession(), text);
  }
  submitChat(raw: string): void {
    if (!this.chatCanSend() || this.chatAgent().isInputBlocked?.() || !raw.trim()) return;
    const key = this.chatKey();
    const source = this.chatSession();
    this.chatSessions.editSource(source, raw);
    this.selectedSource.set(source);
    this.sourceSelections.set(source, this.selectionGeneration);
    const workspaceId = this.workspaces.selectedId();
    if (!workspaceId) return;
    this.sourceWorkspaces.set(source, workspaceId);
    const task = this.chatSessions.submit(key, this.currentBot()?.id, source, workspaceId);
    const runId = source.runId;
    this.sessionGeneration.update(n => n + 1);
    void task.then(async () => {
      if (source.conversationId && runId && (source.runId !== runId || source.result()?.status !== 'UNKNOWN')) {
        try { await this.chatTransport.reconcile(source.conversationId, runId); } catch { /* Retain streamed data until the next explicit history load. */ }
      }
    });
  }
  checkChatStatus(): void {
    const key = this.chatKey();
    void this.chatSessions.reconcile(key).catch(() => {
      if (this.chatKey() === key) this.streamError.set('查詢執行狀態失敗，請稍後再試。');
    });
  }
  stopChat(): void {
    const key = this.chatKey();
    const source = this.chatSession();
    const runId = source.runId;
    const conversationId = source.conversationId;
    void this.chatSessions.stop(key).then(async result => {
      if (result?.status === 'REJECTED') { if (this.chatKey() === key) this.streamError.set('無權取消此執行，狀態尚未確認。'); return; }
      if (result?.status === 'UNKNOWN') { if (this.chatKey() === key) this.streamError.set('停止狀態尚未確認，請查詢原執行狀態。'); return; }
      if (conversationId && runId && source.result()?.status !== 'UNKNOWN') await this.chatTransport.reconcile(conversationId, runId);
    }).catch(() => {
      if (this.chatKey() === key) this.streamError.set('停止失敗，請再次確認執行狀態。');
    });
  }
  restoreFailedDraft(runId: string, raw: string): void {
    const key = this.chatKey();
    if (this.chatSessions.session(key).draft()) return;
    this.chatSessions.edit(key, raw);
    this.chatSessions.failedCreations(key).update(items => items.filter(item => item.runId !== runId));
  }
  async copyFailedDraft(raw: string): Promise<void> {
    try { await navigator.clipboard.writeText(raw); }
    catch { this.streamError.set('無法複製原稿。'); }
  }
  retryHistory(): void {
    const id = this.currentConversationId();
    if (id) void this.selectConversation(id);
  }

  // Injected feature stores
  readonly conversationStore = inject(ConversationListStore);
  readonly botStore = inject(BotStore);

  // View state: 'chat' for conversation view, 'bots' for Bot Manager view
  readonly currentView = signal<'chat' | 'bots' | 'usage'>('chat');

  // Generation counter to guard against late async responses overwriting current selection
  private selectionGeneration = 0;

  // Application State
  readonly bots = this.botStore.bots;
  readonly defaultLocalBot = this.botStore.defaultLocalBot;

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
  readonly isInitializing = signal<boolean>(true);
  readonly streamError = signal<string | null>(null);

  // Bounded Pagination State (default limit 20, max cap 100)
  readonly hasMoreMessages = signal<boolean>(false);
  readonly isLoadingEarlier = signal<boolean>(false);

  // Derived state: authoritative defaultLocalBot strictly from query, zero list guessing or fallback
  readonly seedBot = computed<BotView | null>(() => this.defaultLocalBot() || null);

  // Active draft Bot when working in unpersisted draft view (e.g. via openBotDraft or newConversation)
  readonly activeDraftBot = signal<BotView | null>(null);

  // Authoritatively loaded Bot for currently active conversation
  readonly activeConversationBot = signal<BotView | null>(null);

  // Unified active Bot for current view (either from active draft or active conversation)
  readonly currentBot = computed<BotView | null>(() => {
    if (this.currentConversationId()) {
      return this.activeConversationBot();
    }
    return this.activeDraftBot() || this.seedBot();
  });

  /*
   * WHY: General chat (default assistant) must not expose persona/target/connection labels (PDF p.110).
   * The default bot stays the internal binding (defaultLocalBot query, never list[0]); only a bot that is
   * explicitly different from it is surfaced as a "specified Bot" context in header/banner.
   */
  readonly specifiedBot = computed<BotView | null>(() => {
    const bot = this.currentConversationId() ? this.activeConversationBot() : this.activeDraftBot();
    // Only independent remote (GKE_REMOTE) Bots are a "specified Bot". LOCAL is the internal built-in
    // assistant class, so it is excluded by observable target even when the defaultLocalBot query
    // failed or was cleared to null (unknown default must not turn every bot into a specified one).
    // The id check is kept as an extra guard for a resolved default.
    return bot && bot.target === 'GKE_REMOTE' && bot.id !== this.defaultLocalBot()?.id ? bot : null;
  });

  // General new chat without a resolvable defaultLocalBot: explicit notice, send stays disabled.
  readonly isDefaultBotMissing = computed<boolean>(() =>
    !this.isInitializing() && !this.currentConversationId() && !this.activeDraftBot() && !this.defaultLocalBot()
  );

  getModeDisplay(bot: BotView) {
    return getBotExecutionModeDisplay(bot);
  }

  switchToChatView(): void {
    this.currentView.set('chat');
  }

  switchToBotManager(): void {
    this.currentView.set('bots');
  }

  openBotDraft(bot: BotView): void {
    this.currentView.set('chat');
    this.selectionGeneration++;
    this.selectedSource.set(null);


    this.currentConversationId.set(null);
    this.conversationStore.setActiveConversation(null);
    this.removeStorageItem('bot_active_conversation_id');
    this.activeDraftBot.set(bot);
    this.activeConversationBot.set(null);
    this.hasMoreMessages.set(false);
    this.streamError.set(null);

  }

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
    if (conv) return conv.title || 'Untitled';
    // Bot name is shown once in the header meta line, never duplicated in the title.
    return 'New Conversation';
  });

  private readonly titleUpdates = new ConversationTitleUpdates((workspaceId, id, title) => this.conversationStore.updateTitle(id, workspaceId, title));

  constructor() {
    inject(DestroyRef).onDestroy(() => this.titleUpdates.close());
  }

  async ngOnInit(): Promise<void> {
    const initialGeneration = this.selectionGeneration;
    try {
      await this.workspaces.load();
      const workspaceId = this.workspaces.selectedId();
      if (!workspaceId) return;
      this.conversationStore.setWorkspace(workspaceId);
      this.navigation()?.setWorkspace(workspaceId);
      await this.loadBots();
      const initialDraft = this.chatSession();
      const initialDraftVersion = initialDraft.draftVersion();
      try { await this.loadConversations(); }
      catch { if (this.workspaces.selectedId() === workspaceId) this.navigation()?.showConversationLoadError(); return; }
      if (this.selectionGeneration !== initialGeneration || this.workspaces.selectedId() !== workspaceId ||
          initialDraftVersion !== 0 || initialDraft.draftVersion() !== initialDraftVersion) return;

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
    } finally {
      this.isInitializing.set(false);
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
      await this.botStore.loadBots();
    } catch (err) {
      console.error('Failed to load bots via BotStore:', err);
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
    return this.conversationStore.isPinDisabled(convId);
  }

  isPinRefreshRetryDisabled(): boolean {
    return this.conversationStore.isAnyPinPending() || this.conversationStore.isRetryingRefetch();
  }

  isPinPending(convId: string): boolean {
    return this.conversationStore.isPinPending(convId);
  }

  togglePin(conv: ConversationView): Promise<void> {
    if (this.isPinDisabled(conv?.id)) {
      return Promise.resolve();
    }
    return this.conversationStore.togglePin(conv);
  }

  retryPinRefetch(): Promise<void> {
    if (this.conversationStore.isAnyPinPending() || this.conversationStore.isRetryingRefetch()) {
      return Promise.resolve();
    }
    return this.conversationStore.retryPinRefetch();
  }

  retryPinMutation(): Promise<void> {
    if (this.isPinDisabled()) {
      return Promise.resolve();
    }
    return this.conversationStore.retryPinMutation();
  }

  async selectWorkspace(id: string): Promise<void> {
    if (id === this.workspaces.selectedId() || !this.workspaces.workspaces().some(item => item.id === id)) return;
    const wasUsage = this.currentView() === 'usage';
    this.selectionGeneration++;
    this.workspaces.select(id);
    this.originatedConversations.clear();
    this.titleUpdates.watch(id, []);
    this.conversationStore.setWorkspace(id);
    this.navigation()?.setWorkspace(id);
    await this.newConversation();
    if (wasUsage) this.currentView.set('usage');
    try { await this.conversationStore.loadConversations(false); }
    catch { if (this.workspaces.selectedId() === id) this.navigation()?.showConversationLoadError(); }
  }
  async retryWorkspaces(): Promise<void> {
    await this.workspaces.load();
    const id = this.workspaces.selectedId();
    if (!id) return;
    this.titleUpdates.watch(id, []);
    this.conversationStore.setWorkspace(id); this.navigation()?.setWorkspace(id);
    await this.loadBots();
    try { await this.loadConversations(); }
    catch { if (this.workspaces.selectedId() === id) this.navigation()?.showConversationLoadError(); return; }
    const wasUsage = this.currentView() === 'usage';
    await this.newConversation();
    if (wasUsage) this.currentView.set('usage');
  }

  async selectConversation(id: string): Promise<void> {
    this.currentView.set('chat');

    const currentGen = ++this.selectionGeneration;
    this.selectedSource.set(null);
    this.verifiedConversationId.set(null);
    this.currentConversationId.set(id);
    this.activeDraftBot.set(null);
    this.activeConversationBot.set(null);
    this.setStorageItem('bot_active_conversation_id', id);
    this.hasMoreMessages.set(false);
    this.streamError.set(null);

    // Initial check if we already have the conversation in store
    const existingConv = this.conversationStore.findConversation(id);
    if (existingConv && existingConv.botId) {
      const targetBotId = existingConv.botId;
      const localMatch = this.botStore.bots().find(b => b.id === targetBotId) ||
        (this.defaultLocalBot()?.id === targetBotId ? this.defaultLocalBot() : null);
      if (localMatch) {
        this.activeConversationBot.set(localMatch);
      } else {
        this.botStore.loadBot(targetBotId).then(loaded => {
          if (this.selectionGeneration === currentGen && this.currentConversationId() === id) {
            if (loaded && loaded.id === targetBotId) {
              this.activeConversationBot.set(loaded);
            } else {
              this.activeConversationBot.set(null);
            }
          }
        }).catch(() => {
          if (this.selectionGeneration === currentGen && this.currentConversationId() === id) {
            this.activeConversationBot.set(null);
          }
        });
      }
    } else {
      this.activeConversationBot.set(null);
    }

    try {
      const limit = 20;
      const workspaceId = this.workspaces.selectedId();
      const titleRevision = this.conversationStore.titleReadRevision();
      const conversation = await this.chatTransport.hydrate(id, workspaceId ?? undefined);
      const json = { data: { conversation } };

      /*
       * RATIONALE: Asynchronous Race Guard.
       * If user rapidly switches conversations or clicks + New Chat before this fetch completes,
       * discard the outdated response so it doesn't overwrite the newly selected conversation or draft.
       */
      if (this.selectionGeneration !== currentGen || this.currentConversationId() !== id) {
        return;
      }

      if (conversation === null) {
        const pendingDraft = this.chatSession();
        await this.newConversation();
        // Keep text entered during verification in its own session, without replacing another draft.
        if (pendingDraft.draftVersion() > 0 && pendingDraft.draft()) this.selectedSource.set(pendingDraft);
        return;
      }

      if (json.data && json.data.conversation) {
        const conv = json.data.conversation;
        if (conv.workspaceId !== workspaceId) { this.currentConversationId.set(null); this.conversationStore.setActiveConversation(null); this.removeStorageItem('bot_active_conversation_id'); this.streamError.set('無法存取此工作區的對話。'); return; }
        this.verifiedConversationId.set(id);
        const view: ConversationView = {
          id: conv.id,
          botId: conv.botId,
          title: conv.title,
          pinned: !!conv.pinned,
          createdAt: conv.createdAt || '',
          updatedAt: conv.updatedAt || ''
        };
        this.conversationStore.setActiveConversation(view, titleRevision);
        // A selected pending title may have completed while this page was disconnected.
        if (workspaceId && conv.title === 'New Conversation') this.titleUpdates.watch(workspaceId, [id]);

        // Resolve conversation's Bot if not already resolved
        if (conv.botId) {
          const targetBotId = conv.botId;
          const currentLoaded = this.activeConversationBot();
          if (!currentLoaded || currentLoaded.id !== targetBotId) {
            const localMatch = this.botStore.bots().find(b => b.id === targetBotId) ||
              (this.defaultLocalBot()?.id === targetBotId ? this.defaultLocalBot() : null);
            if (localMatch) {
              this.activeConversationBot.set(localMatch);
            } else {
              this.botStore.loadBot(targetBotId).then(loaded => {
                if (this.selectionGeneration === currentGen && this.currentConversationId() === id) {
                  if (loaded && loaded.id === targetBotId) {
                    this.activeConversationBot.set(loaded);
                  } else {
                    this.activeConversationBot.set(null);
                  }
                }
              }).catch(() => {
                if (this.selectionGeneration === currentGen && this.currentConversationId() === id) {
                  this.activeConversationBot.set(null);
                }
              });
            }
          }
        } else {
          this.activeConversationBot.set(null);
        }
        const rawMessages: GraphQLMessageView[] = conv.messages || [];
        this.hasMoreMessages.set(rawMessages.length >= limit);

      }
    } catch (err) {
      if (this.selectionGeneration === currentGen && this.currentConversationId() === id) this.streamError.set('載入對話歷史失敗。');
    }
  }

  async loadEarlierMessages(): Promise<void> {
    const convId = this.currentConversationId();
    if (!convId || this.isLoadingEarlier() || !this.hasMoreMessages()) return;
    this.isLoadingEarlier.set(true);

    try {
      const limit = 20;
      const offset = this.chatTransport.historyOffset(convId);
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
        const statuses = new Map<string, string>(runs.map(run => [run.id, run.status]));
        this.chatTransport.mergeHistory(convId, rawMessages.map(message => ({...message, runStatus: statuses.get(message.runId)})), true);
        if (rawMessages.length < limit) {
          this.hasMoreMessages.set(false);
        }

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
   * Unsent draft text remains owned by its source session.
   */
  async newConversation(): Promise<void> {
    this.currentView.set('chat');

    // Invalidate any in-flight asynchronous conversation selection
    this.selectionGeneration++;
    this.selectedSource.set(null);


    const defaultBot = this.seedBot();
    this.currentConversationId.set(null);
    this.conversationStore.setActiveConversation(null);
    this.removeStorageItem('bot_active_conversation_id');
    this.activeDraftBot.set(defaultBot);
    this.activeConversationBot.set(null);
    this.hasMoreMessages.set(false);
    this.streamError.set(null);

  }
}
