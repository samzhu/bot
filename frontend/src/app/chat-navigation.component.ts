import { Component, computed, DestroyRef, effect, ElementRef, inject, input, OnInit, output, signal, viewChild } from '@angular/core';
import { A11yModule } from '@angular/cdk/a11y';
import { ChatSidenavComponent, ChatSidenavScrimComponent, ChatHistorySearchPaletteComponent, ChatThreadListComponent, type ChatSidenavMode, type Thread, type ThreadActionAdapter } from '@threadplane/chat';
import { ChatSearchStore } from './chat-search.store';
import { BotStore } from './bot/bot-store';
import { BotView, getBotExecutionModeDisplay } from './bot/bot.model';
import type { Workspace } from './workspace.store';
import { ConversationListStore, ConversationView } from './conversation-list-store';

@Component({
  selector: 'app-chat-navigation',
  standalone: true,
  imports: [A11yModule, ChatSidenavComponent, ChatSidenavScrimComponent, ChatHistorySearchPaletteComponent, ChatThreadListComponent],
  providers: [BotStore, ChatSearchStore],
  templateUrl: './chat-navigation.component.html',
  styleUrl: './chat-navigation.component.css'
})
export class ChatNavigationComponent implements OnInit {
  readonly workspaces = input<Workspace[]>([]);
  readonly workspaceId = input<string | null>(null);
  readonly workspaceLoading = input(false);
  readonly workspaceError = input<string | null>(null);
  readonly workspaceSelected = output<string>();
  readonly workspaceRetry = output<void>();
  setWorkspace(id: string | null): void { this.search.setWorkspace(id); this.conversationError.set(false); this.conversationLoading.set(false); }
  readonly activeConversationId = input<string | null>(null);
  readonly initializing = input(false);
  readonly conversationSelected = output<string>();
  readonly botSelected = output<BotView>();
  readonly modeSelected = output<'conversations' | 'bots'>();
  readonly newChatRequested = output<void>();
  readonly layoutModeChanged = output<ChatSidenavMode>();

  readonly conversations = inject(ConversationListStore);
  readonly search = inject(ChatSearchStore);
  readonly layoutMode = signal<ChatSidenavMode>('expanded');
  readonly navigationOpen = signal(false);
  private readonly drawer = viewChild<ElementRef<HTMLElement>>('drawer');
  private readonly searchAlert = viewChild<ElementRef<HTMLElement>>('searchAlert');
  private readonly palette = viewChild(ChatHistorySearchPaletteComponent);
  private readonly destroyRef = inject(DestroyRef);
  private drawerTrigger: HTMLElement | null = null;
  private searchTrigger: HTMLElement | null = null;
  private readonly botStore = inject(BotStore);
  readonly mode = signal<'conversations' | 'bots'>('conversations');
  readonly bots = signal<BotView[]>([]);
  readonly botsLoading = signal(false);
  readonly botsError = signal<string | null>(null);
  readonly hasMoreBots = signal(false);
  readonly conversationError = signal(false);
  readonly conversationLoading = signal(false);
  private botsLoaded = false;
  private botGeneration = 0;
  private retryBotTarget = 20;

  constructor() {
    if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
      const media = window.matchMedia('(max-width: 767px)');
      const update = () => { this.setLayoutMode(media.matches ? 'drawer' : 'expanded'); this.navigationOpen.set(false); };
      update(); media.addEventListener('change', update);
      this.destroyRef.onDestroy(() => media.removeEventListener('change', update));
    }
    effect(() => {
      if (this.search.error()) {
        // The palette and drawer must both be closed before an external retry can receive focus.
        if (this.layoutMode() === 'drawer') this.navigationOpen.set(false);
        setTimeout(() => { if (this.search.error() && !this.search.open()) this.searchAlert()?.nativeElement.focus(); });
      }
    });
  }

  ngOnInit(): void { this.layoutModeChanged.emit(this.layoutMode()); }

  openNavigation(): void {
    this.drawerTrigger = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    this.navigationOpen.set(true);
    setTimeout(() => this.drawer()?.nativeElement.focus());
  }
  setLayoutMode(mode: ChatSidenavMode): void { this.layoutMode.set(mode); this.layoutModeChanged.emit(mode); }
  setNavigationOpen(open: boolean): void {
    this.navigationOpen.set(open);
    if (!open) { this.search.closeSearch(); setTimeout(() => this.drawerTrigger?.focus()); }
  }
  openSearch(): void {
    this.searchTrigger = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    this.search.openSearch();
  }
  changeSearchQuery(value: string): void {
    if (!value.trim() && value) setTimeout(() => {
      if (!this.search.query()) this.palette()?.query.set('');
    });
    this.search.changeQuery(value);
  }
  closeSearch(): void {
    this.search.closeSearch();
    setTimeout(() => {
      const trigger = this.searchTrigger;
      if (trigger?.isConnected && !trigger.closest('[inert]')) trigger.focus();
      else this.drawerTrigger?.focus();
    });
  }
  onPaletteKeydown(event: KeyboardEvent): void {
    if (!this.search.open()) return;
    if (event.key === 'Escape' && !event.defaultPrevented) { event.preventDefault(); this.closeSearch(); return; }
    if (event.key !== 'Tab') return;
    // The SDK scrim is outside its modal dialog and Escape is handled only on the input;
    // enforce the dialog's keyboard boundary using its public ARIA role.
    const dialog = (event.currentTarget as HTMLElement).querySelector<HTMLElement>('[role="dialog"]');
    const controls = dialog ? Array.from(dialog.querySelectorAll<HTMLElement>('input:not([disabled]), button:not([disabled]), [tabindex="0"]')) : [];
    if (!controls.length) return;
    const active = document.activeElement;
    if (event.shiftKey && (active === controls[0] || !dialog?.contains(active))) { event.preventDefault(); controls[controls.length - 1].focus(); }
    else if (!event.shiftKey && (active === controls[controls.length - 1] || !dialog?.contains(active))) { event.preventDefault(); controls[0].focus(); }
  }
  newChat(): void {
    this.search.closeSearch();
    this.newChatRequested.emit();
    if (this.layoutMode() === 'drawer') this.setNavigationOpen(false);
  }

  readonly pinned = computed(() => this.toThreads(this.conversations.pinnedConversations()));
  readonly recent = computed(() => this.toThreads(this.conversations.unpinnedConversations()));
  readonly deepActive = computed(() => {
    const active = this.conversations.activeConversation();
    return active && active.id === this.activeConversationId() &&
      !this.conversations.conversations().some(item => item.id === active.id) ? this.toThreads([active]) : [];
  });
  readonly threadActions = computed<ThreadActionAdapter | null>(() => {
    if (this.conversations.isAnyPinPending() || this.conversations.pinRefreshError() || this.conversations.isRetryingRefetch()) return null;
    return { pin: id => this.setPinned(id, true), unpin: id => this.setPinned(id, false) };
  });

  selectMode(mode: 'conversations' | 'bots'): void {
    this.search.closeSearch();
    this.mode.set(mode);
    this.modeSelected.emit(mode);
    if (mode === 'bots' && !this.botsLoaded && !this.botsLoading()) this.refreshBots();
  }

  async loadConversations(): Promise<void> {
    if (this.conversationLoading()) return;
    this.conversationLoading.set(true);
    this.conversationError.set(false);
    try { await this.conversations.loadConversations(true); }
    catch { this.conversationError.set(true); }
    finally { this.conversationLoading.set(false); }
  }

  selectConversation(id: string): void { this.search.closeSearch(); this.conversationSelected.emit(id); if (this.layoutMode() === 'drawer') this.setNavigationOpen(false); }
  selectBot(bot: BotView): void { this.search.closeSearch(); this.botSelected.emit(bot); if (this.layoutMode() === 'drawer') this.setNavigationOpen(false); }
  botStatus(bot: BotView): string { return getBotExecutionModeDisplay(bot).badgeText; }

  refreshBots(): void { void this.loadBotRange(Math.max(20, this.bots().length)); }
  loadMoreBots(): void {
    if (this.botsLoading() || !this.hasMoreBots()) return;
    void this.loadBotRange(this.bots().length + 20);
  }
  retryBots(): void { void this.loadBotRange(this.retryBotTarget); }

  private async loadBotRange(target: number): Promise<void> {
    const generation = ++this.botGeneration;
    this.retryBotTarget = target;
    this.botsLoading.set(true);
    this.botsError.set(null);
    const items: BotView[] = [];
    try {
      let hasMore = false;
      for (let offset = 0; offset < target; offset += 20) {
        const page = await this.botStore.loadBots({ target: 'GKE_REMOTE', limit: 20, offset });
        if (generation !== this.botGeneration) return;
        items.push(...page);
        hasMore = this.botStore.hasMore();
        if (!hasMore || page.length < 20) break;
      }
      if (generation !== this.botGeneration) return;
      this.bots.set(items);
      this.hasMoreBots.set(hasMore);
      this.botsLoaded = true;
    } catch {
      if (generation === this.botGeneration) this.botsError.set('Bot 清單載入失敗，請重試。');
    } finally {
      if (generation === this.botGeneration) this.botsLoading.set(false);
    }
  }

  private toThreads(items: ConversationView[]): Thread[] {
    return items.map(item => ({ id: item.id, title: item.title, pinned: item.pinned, updatedAt: Date.parse(item.updatedAt) || undefined }));
  }
  private async setPinned(id: string, pinned: boolean): Promise<void> {
    const item = this.conversations.conversations().find(c => c.id === id) ?? this.conversations.activeConversation();
    if (!item || item.id !== id || item.pinned === pinned || this.conversations.isPinDisabled(id)) return;
    await this.conversations.togglePin(item);
    if (this.conversations.pinMutationError() || this.conversations.pinRefreshError()) throw new Error('Pin operation failed');
  }
}
