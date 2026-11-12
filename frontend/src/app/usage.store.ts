import { signal } from '@angular/core';

export interface NativeUsageValue { value: string | null; presence: 'PRESENT' | 'NULL_VALUE' | 'MISSING' | 'UNKNOWN'; meaning: string; }
export type UsageCostStatus = 'UNPRICED' | 'COMPLETE' | 'PARTIAL' | 'UNKNOWN' | 'NOT_DISPATCHED';
export interface UsageCostLineItem {
  category: string; nativeQuantity: string | null; unit: string; meaning: string;
  rate: string | null; rateBasis: string; exactAmount: string | null; storedAmount: string | null; reasons: string[];
}
export interface UsageCost {
  status: UsageCostStatus; amount: string | null; knownSubtotal: string | null; completeAmount: string | null; currency: string;
  simulation: boolean; needsReprice: boolean; sourceProjectionVersion: string; estimateSourceVersion: string | null;
  currentRevision: string | null; priceRevision: string | null; priceSnapshotId: string | null; priceSourceReference: string | null;
  priceEffectiveFrom: string | null; priceEffectiveUntil: string | null; calculatorVersion: string | null; sourceSnapshotId: string | null;
  sourceLayer: string | null; schemaVersion: string | null; adapterVersion: string | null; captureVersion: string | null;
  captureCompleteness: string | null; semantic: string | null; ordinal: string | null; reasons: string[]; lineItems: UsageCostLineItem[];
}
export interface UsageAttempt {
  id: string; workspaceId: string; runId: string | null; providerRequestId: string | null; providerResponseId: string | null; preparedAt: string; startedAt: string | null; finishedAt: string | null;
  provider: string; endpoint: string; model: string; channel: string; executionOutcome: string;
  usagePresence: string; captureCompleteness: string; projectionError: string | null; projectionState: string;
  projectionVersion: string; effectiveSnapshotId: string | null; attemptCoverage: 'VERIFIED' | 'UNKNOWN'; simulation: boolean;
  input: NativeUsageValue; output: NativeUsageValue; cacheRead: NativeUsageValue; cacheWrite: NativeUsageValue; cost: UsageCost;
}
export interface UsageSnapshot {
  id: string; eventId: string; ordinal: string; semantic: string; sourceLayer: string; schemaVersion: string;
  adapterVersion: string; captureVersion: string; captureCompleteness: string; rawState: string;
  rawUsage: string | null; captureReason: string | null; capturedAt: string;
}
interface Page<T> { nodes: T[]; pageInfo: { hasNextPage: boolean; endCursor: string | null }; }
const fields = `id workspaceId runId providerRequestId providerResponseId preparedAt startedAt finishedAt provider endpoint model channel executionOutcome usagePresence captureCompleteness projectionError projectionState projectionVersion effectiveSnapshotId attemptCoverage simulation input {value presence meaning} output {value presence meaning} cacheRead {value presence meaning} cacheWrite {value presence meaning} cost {status knownSubtotal completeAmount currency simulation needsReprice sourceProjectionVersion estimateSourceVersion currentRevision priceRevision priceSnapshotId priceSourceReference priceEffectiveFrom priceEffectiveUntil calculatorVersion sourceSnapshotId sourceLayer schemaVersion adapterVersion captureVersion captureCompleteness semantic ordinal reasons lineItems {category nativeQuantity unit meaning rate rateBasis exactAmount storedAmount reasons}}`;
const snapshotFields = `id eventId ordinal semantic sourceLayer schemaVersion adapterVersion captureVersion captureCompleteness rawState rawUsage captureReason capturedAt`;

export class UsageStore {
  readonly workspaceId = signal<string | null>(null);
  readonly attempts = signal<UsageAttempt[]>([]); readonly snapshots = signal<UsageSnapshot[]>([]);
  readonly loading = signal(false); readonly error = signal<string | null>(null);
  readonly hasMore = signal(false); readonly selectedAttemptId = signal<string | null>(null); readonly detailAttempt = signal<UsageAttempt | null>(null);
  readonly snapshotsLoading = signal(false); readonly snapshotsError = signal<string | null>(null); readonly snapshotsHasMore = signal(false);
  private cursor: string | null = null; private snapshotsCursor: string | null = null;
  private generation = 0; private snapshotGeneration = 0;
  constructor(private readonly request: typeof fetch = (...args) => globalThis.fetch(...args)) {}
  async setWorkspace(id: string | null): Promise<void> {
    this.generation++; this.snapshotGeneration++; this.workspaceId.set(id); this.attempts.set([]); this.snapshots.set([]); this.selectedAttemptId.set(null); this.detailAttempt.set(null);
    this.cursor = null; this.snapshotsCursor = null; this.hasMore.set(false); this.snapshotsHasMore.set(false);
    this.loading.set(false); this.error.set(null); this.snapshotsLoading.set(false); this.snapshotsError.set(null);
    if (id) await this.loadMore();
  }
  async retry(): Promise<void> { await this.setWorkspace(this.workspaceId()); }
  private async graphql(query: string, variables: Record<string, unknown>): Promise<any> {
    const response = await this.request('/graphql',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({query,variables})});
    if (!response.ok) throw new Error('HTTP error');
    const data = await response.json();
    if (data.errors?.length) throw Object.assign(new Error(data.errors[0].message),{extensions:data.errors[0].extensions});
    return data.data;
  }
  async loadMore(): Promise<void> {
    const workspaceId = this.workspaceId(); if (!workspaceId || this.loading()) return;
    const gen = this.generation; const after = this.cursor; this.loading.set(true); this.error.set(null);
    try {
      const data = await this.graphql(`query MyUsageAttempts($workspaceId: ID!, $first: Int, $after: String) { myUsageAttempts(workspaceId:$workspaceId, first:$first, after:$after) { nodes { ${fields} } pageInfo { hasNextPage endCursor } } }`, {workspaceId,first:20,after});
      const page: Page<UsageAttempt> = data?.myUsageAttempts;
      if (!page || !Array.isArray(page.nodes) || !page.pageInfo) throw new Error('Invalid usage response');
      if (gen !== this.generation) return;
      this.attempts.update(old => [...old,...page.nodes.filter(row => !old.some(item => item.id === row.id))]);
      this.cursor = page.pageInfo.endCursor; this.hasMore.set(page.pageInfo.hasNextPage);
    } catch {
      if (gen !== this.generation) return;
      this.attempts.set([]); this.hasMore.set(false); this.cursor = null; this.snapshots.set([]); this.selectedAttemptId.set(null); this.detailAttempt.set(null);
      this.snapshotGeneration++; this.error.set('用量載入失敗或無權存取，請重試。');
    } finally { if (gen === this.generation) this.loading.set(false); }
  }
  async selectAttempt(id: string): Promise<void> {
    if (!this.attempts().some(item => item.id === id)) return;
    this.snapshotGeneration++; this.selectedAttemptId.set(id); this.detailAttempt.set(null); this.snapshots.set([]); this.snapshotsCursor = null;
    this.snapshotsHasMore.set(false); this.snapshotsError.set(null); this.snapshotsLoading.set(false);
    await this.loadMoreSnapshots();
  }
  async loadMoreSnapshots(): Promise<void> {
    const workspaceId = this.workspaceId(), id = this.selectedAttemptId();
    if (!workspaceId || !id || this.snapshotsLoading()) return;
    const gen = this.generation, snapGen = this.snapshotGeneration, after = this.snapshotsCursor;
    this.snapshotsLoading.set(true); this.snapshotsError.set(null);
    try {
      const data = await this.graphql(`query MyUsageAttempt($workspaceId: ID!, $id: ID!, $first: Int, $after: String) { myUsageAttempt(workspaceId:$workspaceId,id:$id) { ${fields} snapshots(first:$first,after:$after) { nodes { ${snapshotFields} } pageInfo { hasNextPage endCursor } } } }`,{workspaceId,id,first:20,after});
      const page: Page<UsageSnapshot> | undefined = data?.myUsageAttempt?.snapshots;
      if (!page || data.myUsageAttempt.id !== id || !Array.isArray(page.nodes) || !page.pageInfo) throw new Error('Inaccessible usage');
      if (gen !== this.generation || snapGen !== this.snapshotGeneration) return;
      this.detailAttempt.set(data.myUsageAttempt);
      this.snapshots.update(old => [...old,...page.nodes.filter(row => !old.some(item => item.id === row.id))]);
      this.snapshotsCursor = page.pageInfo.endCursor; this.snapshotsHasMore.set(page.pageInfo.hasNextPage);
    } catch (error) {
      if (gen !== this.generation || snapGen !== this.snapshotGeneration) return;
      if ((error as {extensions?: {code?: string}})?.extensions?.code === 'WORKSPACE_ACCESS_DENIED') { this.clearDenied(); return; }
      this.snapshots.set([]); this.detailAttempt.set(null); this.snapshotsCursor = null; this.snapshotsHasMore.set(false);
      this.snapshotsError.set('明細載入失敗或無權存取，請重試。');
    } finally { if (gen === this.generation && snapGen === this.snapshotGeneration) this.snapshotsLoading.set(false); }
  }
  private clearDenied(): void {
    this.generation++; this.snapshotGeneration++; this.attempts.set([]); this.snapshots.set([]); this.selectedAttemptId.set(null); this.detailAttempt.set(null);
    this.cursor = null; this.snapshotsCursor = null; this.hasMore.set(false); this.snapshotsHasMore.set(false);
    this.loading.set(false); this.snapshotsLoading.set(false); this.snapshotsError.set(null);
    this.error.set('用量載入失敗或無權存取，請重試。');
  }
  async authorizeSnapshot(snapshotId: string): Promise<boolean> {
    const workspaceId = this.workspaceId(), id = this.selectedAttemptId();
    if (!workspaceId || !id || !this.snapshots().some(row => row.id === snapshotId)) return false;
    const gen = this.generation, snapGen = this.snapshotGeneration;
    try {
      const data = await this.graphql(`query AuthorizeUsageDownload($workspaceId: ID!, $id: ID!) { myUsageAttempt(workspaceId:$workspaceId,id:$id) { id } }`,{workspaceId,id});
      if (gen !== this.generation || snapGen !== this.snapshotGeneration) return false;
      if (data?.myUsageAttempt?.id !== id) throw new Error('Inaccessible usage');
      return this.snapshots().some(row => row.id === snapshotId);
    } catch (error) {
      if (gen === this.generation && snapGen === this.snapshotGeneration) {
        if ((error as {extensions?: {code?: string}})?.extensions?.code === 'WORKSPACE_ACCESS_DENIED') { this.clearDenied(); return false; }
        this.snapshots.set([]); this.detailAttempt.set(null); this.snapshotsHasMore.set(false); this.snapshotsCursor = null;
        this.snapshotsError.set('明細載入失敗或無權存取，請重試。');
      }
      return false;
    }
  }

}
