import { Component, OnInit, computed, inject, output, signal, viewChild } from '@angular/core';
import { BotStore } from './bot-store';
import { BotEditorComponent } from './bot-editor.component';
import { BotTarget, BotView, getBotExecutionModeDisplay } from './bot.model';

// 管理清單目前限定「額外建立的獨立 Bot」（GKE_REMOTE）。LOCAL 預設助理屬於一般聊天內部設定，
// 由後端以 owner/search/sort/page 先套用此範圍，前端不得抓一頁後再刪除項目。
export const MANAGED_BOT_TARGET: BotTarget = 'GKE_REMOTE';

// 目前所有獨立 Bot 實際皆為 NOT_CONNECTED，只提供真實支援的狀態，不捏造 READY/在線。
// 此範圍內全數同為 NOT_CONNECTED，因此「未連線」不需額外後端條件，也不可在前端對單頁結果再過濾。
export type BotStatusFilter = 'ALL' | 'NOT_CONNECTED';

@Component({
  selector: 'app-bot-manager',
  standalone: true,
  imports: [BotEditorComponent],
  templateUrl: './bot-manager.component.html',
  styleUrl: './bot-manager.component.css'
})
export class BotManagerComponent implements OnInit {
  readonly botStore = inject(BotStore);

  // Emits selected bot when user requests to open draft chat
  readonly startChat = output<BotView>();

  // 表單責任已收斂至獨立 BotEditorComponent（右側面板）；manager 只負責開啟與保存後刷新。
  readonly editor = viewChild<BotEditorComponent>('editor');

  readonly searchQuery = signal<string>('');
  readonly selectedStatus = signal<BotStatusFilter>('ALL');
  readonly currentOffset = signal<number>(0);
  readonly pageSize = 20;
  // Store 也供首頁使用；只有本管理頁的查詢完成後，快取才具有正確管理範圍。
  readonly hasManagedResult = signal(false);

  readonly currentPage = computed(() => Math.floor(this.currentOffset() / this.pageSize) + 1);
  readonly canPrevPage = computed(() => this.currentOffset() > 0);
  readonly canNextPage = computed(() => this.botStore.hasMore());

  ngOnInit(): void {
    this.fetchBots().catch(err => {
      console.warn('Initial loadBots warning:', err);
    });
  }

  getModeDisplay(bot: BotView) {
    return getBotExecutionModeDisplay(bot);
  }

  initialOf(bot: BotView): string {
    return (bot.name.trim().charAt(0) || 'B').toUpperCase();
  }

  onOpenDraft(bot: BotView): void {
    this.startChat.emit(bot);
  }

  openCreate(trigger: HTMLElement): void {
    this.editor()?.openCreate(trigger);
  }

  openEdit(bot: BotView, trigger: HTMLElement): void {
    this.editor()?.openEdit(bot, trigger);
  }

  async fetchBots(): Promise<BotView[]> {
    const search = this.searchQuery().trim() || null;
    const bots = await this.botStore.loadBots({
      search,
      target: MANAGED_BOT_TARGET,
      limit: this.pageSize,
      offset: this.currentOffset()
    });
    this.hasManagedResult.set(true);
    return bots;
  }

  // 保存成功（mutation 已完成）後回第一頁並依目前條件刷新；刷新失敗只留 list retry，不重送 mutation。
  async onSaved(_bot: BotView): Promise<void> {
    this.currentOffset.set(0);
    try {
      await this.fetchBots();
    } catch (err: any) {
      console.warn('Post-mutation list refetch failed:', err);
    }
  }

  onSearchInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.searchQuery.set(input.value);
    this.currentOffset.set(0);
    this.fetchBots().catch(err => console.warn('Search fetch error:', err));
  }

  onStatusChange(event: Event): void {
    const select = event.target as HTMLSelectElement;
    this.selectedStatus.set(select.value as BotStatusFilter);
    this.currentOffset.set(0);
    this.fetchBots().catch(err => console.warn('Status change fetch error:', err));
  }

  prevPage(): void {
    if (!this.canPrevPage()) return;
    this.currentOffset.update(offset => Math.max(0, offset - this.pageSize));
    this.fetchBots().catch(err => console.warn('Paging fetch error:', err));
  }

  nextPage(): void {
    if (!this.canNextPage()) return;
    this.currentOffset.update(offset => offset + this.pageSize);
    this.fetchBots().catch(err => console.warn('Paging fetch error:', err));
  }
}
