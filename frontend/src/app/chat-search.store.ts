import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import type { ThreadMatch } from '@threadplane/chat';

@Injectable()
export class ChatSearchStore {
  readonly open = signal(false);
  readonly query = signal('');
  readonly results = signal<ThreadMatch[]>([]);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly validationError = signal(false);
  private generation = 0;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private controller: AbortController | undefined;
  private readonly destroyRef = inject(DestroyRef);

  constructor() { this.destroyRef.onDestroy(() => this.invalidate()); }
  openSearch(): void { this.invalidate(); this.error.set(null); this.validationError.set(false); this.open.set(true); this.changeQuery(this.query()); }
  editInvalidQuery(value: string): void {
    this.invalidate();
    this.loading.set(false);
    this.results.set([]);
    this.query.set(value);
  }
  closeSearch(): void { this.invalidate(); this.open.set(false); this.loading.set(false); this.error.set(null); this.validationError.set(false); }
  changeQuery(value: string): void {
    this.invalidate();
    const query = value.trim() ? value : '';
    this.query.set(query); this.results.set([]); this.error.set(null); this.validationError.set(false);
    const trimmed = query.trim();
    if (!trimmed) { this.loading.set(false); return; }
    if (trimmed.length > 100) { this.loading.set(false); this.open.set(false); this.validationError.set(true); this.error.set('搜尋文字不可超過 100 字元。'); return; }
    this.loading.set(true);
    const generation = this.generation;
    this.timer = setTimeout(() => { void this.search(trimmed, generation, false); }, 250);
  }
  async retry(): Promise<void> {
    const trimmed = this.query().trim();
    if (!trimmed || trimmed.length > 100 || this.loading()) return;
    this.invalidate(); this.open.set(false); this.loading.set(true);
    await this.search(trimmed, this.generation, true);
  }
  private invalidate(): void {
    this.generation++; clearTimeout(this.timer); this.controller?.abort(); this.controller = undefined;
  }
  private async search(search: string, generation: number, reopen: boolean): Promise<void> {
    const controller = new AbortController(); this.controller = controller;
    try {
      const response = await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json' }, signal: controller.signal,
        body: JSON.stringify({ query: 'query SearchConversations($search: String, $limit: Int, $offset: Int) { conversations(search: $search, limit: $limit, offset: $offset) { id title } }', variables: { search, limit: 20, offset: 0 } }) });
      if (!response.ok) throw new Error('search failed');
      const json = await response.json();
      if (json.errors?.length || !Array.isArray(json.data?.conversations)) throw new Error('search failed');
      if (generation !== this.generation) return;
      this.results.set(json.data.conversations.map((item: { id: string; title: string }) => ({ id: item.id, title: item.title })));
      this.error.set(null); this.validationError.set(false); if (reopen) this.open.set(true);
    } catch {
      if (generation !== this.generation) return;
      this.open.set(false); this.results.set([]); this.error.set('搜尋對話失敗，請重試。');
    } finally { if (generation === this.generation) this.loading.set(false); }
  }
}
