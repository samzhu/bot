import { signal } from '@angular/core';

export interface Workspace { id: string; name: string; role: 'MANAGER' | 'MEMBER'; personal: boolean; }

export class WorkspaceStore {
  readonly workspaces = signal<Workspace[]>([]);
  readonly selectedId = signal<string | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  private generation = 0;
  constructor(private readonly request: typeof fetch = (...args) => globalThis.fetch(...args)) {}
  async load(): Promise<void> {
    const generation = ++this.generation;
    this.loading.set(true); this.error.set(null);
    try {
      const response = await this.request('/graphql', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({query:'query Workspaces { workspaces { id name role personal } }'})});
      if (!response.ok) throw new Error('workspace load failed');
      const result = await response.json();
      if (result.errors?.length || !Array.isArray(result.data?.workspaces)) throw new Error('workspace load failed');
      const items = result.data.workspaces as Workspace[];
      if (items.length && items.filter(item => item.personal).length !== 1) throw new Error('personal workspace unavailable');
      if (generation !== this.generation) return;
      this.workspaces.set(items);
      if (!items.some(item => item.id === this.selectedId())) this.selectedId.set(items.find(item => item.personal)?.id ?? null);
    } catch {
      if (generation !== this.generation) return;
      this.workspaces.set([]); this.selectedId.set(null); this.error.set('工作區載入失敗，請重試。');
    } finally { if (generation === this.generation) this.loading.set(false); }
  }
  select(id: string): void { if (this.workspaces().some(item => item.id === id)) this.selectedId.set(id); }
}
