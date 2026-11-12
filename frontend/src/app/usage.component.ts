import { Component, Input, OnChanges, OnDestroy, output } from '@angular/core';
import { NativeUsageValue, UsageSnapshot, UsageStore } from './usage.store';

@Component({
  selector: 'app-usage', standalone: true,
  template: `
    <section class="usage" role="region" tabindex="0" aria-label="個人用量內容，可垂直捲動">
      <button type="button" (click)="back.emit()">返回對話</button>
      <h1>個人用量</h1>
      @if (store.loading()) { <p role="status">載入用量中…</p> }
      @if (store.error()) { <p role="alert">{{store.error()}} <button type="button" (click)="store.retry()">重試</button></p> }
      @if (!store.loading() && !store.error() && !store.attempts().length) { <p>目前沒有用量紀錄。</p> }
      @if (store.attempts().length) {
        <div class="table-scroll" role="region" tabindex="0" aria-label="個人用量表格，可水平捲動">
          <table><caption>個人用量</caption><thead><tr><th scope="col">登記時間</th><th scope="col">供應商／模型</th><th scope="col">Input</th><th scope="col">Output</th><th scope="col">Cache read</th><th scope="col">Cache write</th><th scope="col">費用</th><th scope="col">明細</th></tr></thead>
          <tbody>@for (row of store.attempts(); track row.id) {
            <tr><th scope="row">{{row.preparedAt}}</th><td>{{row.provider}} / {{row.model}} @if (row.simulation) { <strong>模擬</strong> } <small>逐次呼叫涵蓋：{{row.attemptCoverage === 'VERIFIED' ? '已驗證' : '未知'}}</small></td>
              <td>{{value(row.input)}} <small>{{row.input.meaning}}</small></td><td>{{value(row.output)}} <small>{{row.output.meaning}}</small></td>
              <td>{{value(row.cacheRead)}} <small>{{row.cacheRead.meaning}}</small></td><td>{{value(row.cacheWrite)}} <small>{{row.cacheWrite.meaning}}</small></td>
              <td>{{row.cost.status}} · {{row.cost.amount ?? '未知'}} {{row.cost.currency}}</td>
              <td><button type="button" (click)="store.selectAttempt(row.id)" [attr.aria-label]="'查看 '+row.provider+' '+row.model+' '+row.preparedAt+' 的明細'">查看明細</button></td></tr>
          }</tbody></table>
        </div>
        @if (store.hasMore()) { <button type="button" [disabled]="store.loading()" (click)="store.loadMore()">載入更多用量</button> }
      }
      @if (store.selectedAttemptId()) {
        <section aria-labelledby="snapshot-title"><h2 id="snapshot-title">原生用量明細</h2>
          @if (selectedAttempt(); as attempt) {
            <p>供應商 {{attempt.provider}} · 模型 {{attempt.model}} · Endpoint {{attempt.endpoint}} · 渠道 {{attempt.channel}}</p>
            <p>Run {{attempt.runId ?? '未提供'}} · 登記時間 {{attempt.preparedAt}} · 派送時間：{{attempt.startedAt ?? (attempt.executionOutcome === 'IN_PROGRESS' ? '尚未派送' : '未派送')}} · 結束 {{attempt.finishedAt ?? '未提供'}}</p>
            <p>結果 {{attempt.executionOutcome}} · 用量 {{attempt.usagePresence}} · 擷取 {{attempt.captureCompleteness}}</p>
            <p>投影 {{attempt.projectionState}} · 版本 {{attempt.projectionVersion}} · 生效快照 {{attempt.effectiveSnapshotId ?? '無'}}</p>
            @if (attempt.projectionError) { <p>{{attempt.projectionError}}</p> }
            <p>Provider request ID：{{attempt.providerRequestId ?? '未提供'}} · response ID：{{attempt.providerResponseId ?? '未提供'}}</p>
          }
          @if (store.snapshotsLoading()) { <p role="status">載入明細中…</p> }
          @if (store.snapshotsError()) { <p role="alert">{{store.snapshotsError()}} <button type="button" (click)="store.loadMoreSnapshots()">重試</button></p> }
          @if (!store.snapshotsLoading() && !store.snapshotsError() && !store.snapshots().length) { <p>尚無原生用量快照。</p> }
          @for (snapshot of store.snapshots(); track snapshot.id) {
            <article><h3>快照 {{snapshot.ordinal}}</h3>
              <p>事件 {{snapshot.eventId}} · 擷取時間 {{snapshot.capturedAt}}</p>
              <p>{{snapshot.semantic}} · {{snapshot.sourceLayer}} · {{snapshot.captureCompleteness}} · {{snapshot.rawState}}</p>
              <p>Schema {{snapshot.schemaVersion}} · Adapter {{snapshot.adapterVersion}} · Capture {{snapshot.captureVersion}}</p>
              @if (snapshot.captureReason) { <p>{{snapshot.captureReason}}</p> }
              @if (snapshot.rawState === 'TEXT_FALLBACK') { <p>JSONB_UNSUPPORTED：此資料無法用 JSONB 表示，不能作為可計價投影。</p> }
              @if (snapshot.rawUsage !== null) { @if (snapshot.rawUsage === 'null') { <p>JSON null</p> } <pre>{{snapshot.rawUsage}}</pre><button type="button" (click)="download(snapshot)">下載原生用量 JSON</button> }
              @else { <p>未取得原生用量。</p> }
            </article>
          }
          @if (store.snapshotsHasMore()) { <button type="button" [disabled]="store.snapshotsLoading()" (click)="store.loadMoreSnapshots()">載入更多快照</button> }
        </section>
      }
    </section>
  `,
  styles: [`:host{display:flex;flex:1 1 auto;flex-direction:column;min-height:0;min-width:0;overflow:hidden}.usage{flex:1 1 auto;min-height:0;min-width:0;padding:1rem;overflow:auto}.table-scroll{overflow:auto;max-inline-size:100%}table{border-collapse:separate;border-spacing:0}th,td{padding:.5rem;text-align:start;vertical-align:top;white-space:nowrap}thead th{position:sticky;inset-block-start:0;background:Canvas}tbody th{position:sticky;inset-inline-start:0;background:Canvas}small{display:block;white-space:normal}pre{white-space:pre-wrap;overflow-wrap:anywhere}`]
})
export class UsageComponent implements OnChanges, OnDestroy {
  @Input() workspaceId: string | null = null;
  readonly back = output<void>(); readonly store = new UsageStore();
  ngOnChanges(): void { void this.store.setWorkspace(this.workspaceId); }
  ngOnDestroy(): void { void this.store.setWorkspace(null); }
  selectedAttempt() { return this.store.detailAttempt(); }
  value(value: NativeUsageValue): string {
    if (value.presence === 'PRESENT') return value.value ?? '未知';
    if (value.presence === 'NULL_VALUE') return 'null';
    if (value.presence === 'MISSING') return '未提供';
    return '未知';
  }
  async download(snapshot: UsageSnapshot): Promise<void> {
    if (snapshot.rawUsage === null || !await this.store.authorizeSnapshot(snapshot.id)) return;
    const url = URL.createObjectURL(new Blob([snapshot.rawUsage], {type:'application/json;charset=utf-8'}));
    const link = document.createElement('a'); link.href = url; link.download = `usage-${snapshot.id}.json`; link.click(); URL.revokeObjectURL(url);
  }
}
