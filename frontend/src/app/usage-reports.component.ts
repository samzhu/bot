import { Component, ElementRef, Input, OnChanges, OnDestroy, OnInit, SimpleChanges, ViewChild } from '@angular/core';
import { UsageAttempt, UsageSnapshot } from './usage.store';
import { UsageReportsStore, UsageReport, UsageReportMetric } from './usage-reports.store';
import { CostExplanationComponent } from './cost-explanation.component';

@Component({
  selector:'app-usage-reports',standalone:true,imports:[CostExplanationComponent],
  template:`
    <section class="reports" aria-label="用量報表">
      @if (manager) {
        <nav class="report-scopes" aria-label="報表範圍">
          <button type="button" [attr.aria-pressed]="store.mode()==='mine'" (click)="changeMode('mine')">本人</button>
          <button type="button" [attr.aria-pressed]="store.mode()==='workspace'" (click)="changeMode('workspace')">工作區</button>
        </nav>
      }
      <h2>{{store.mode()==='workspace' ? '工作區用量報表' : '個人用量報表'}}</h2>
      <form (submit)="submitFilters($event)">
        <fieldset><legend>報表篩選</legend>
          <label for="report-period">期間</label>
          <select id="report-period" [value]="store.periodDays()" (change)="store.setPeriodDays($any($event.target).value === '30' ? 30 : 7)">
            <option value="7">最近 7 日</option><option value="30">最近 30 日</option>
          </select>
          <label for="report-provider">Provider</label>
          <input id="report-provider" type="text" maxlength="200" [value]="store.provider()" (input)="store.setProvider($any($event.target).value)" aria-describedby="report-provider-help">
          <small id="report-provider-help">留空表示不依 Provider 篩選。</small>
          <label for="report-model">Model</label>
          <input id="report-model" type="text" maxlength="200" [value]="store.model()" (input)="store.setModel($any($event.target).value)" aria-describedby="report-model-help">
          <small id="report-model-help">留空表示不依 Model 篩選。</small>
          @if (store.mode()==='workspace') {
            <label for="report-member-id">成員 ID</label>
            <input id="report-member-id" type="text" maxlength="200" [value]="store.memberId()" (input)="store.setMemberId($any($event.target).value)" aria-describedby="report-member-help">
            <small id="report-member-help">選填；只篩選目前工作區內的成員資料，不授予額外權限。</small>
            <label class="check"><input type="checkbox" [checked]="store.unassignedOnly()" (change)="store.setUnassignedOnly($any($event.target).checked)">僅看未歸屬用量</label>
          }
          <label class="check"><input type="checkbox" [checked]="store.simulation()" (change)="store.setSimulation($any($event.target).checked)">僅看模擬資料</label>
          <button type="submit" [disabled]="store.loading()">套用篩選</button>
        </fieldset>
      </form>
      @if (store.error()) { <div #errorSummary tabindex="-1" role="alert"><p>報表載入失敗{{store.errorCode() ? ' ('+store.errorCode()+')' : ''}}：{{store.error()}}</p><button type="button" (click)="store.retry()">重試</button></div> }
      @if (store.loading()) { <p role="status">載入報表中…</p> }
      @if (store.report(); as report) {
        @if (report.simulation) { <p class="simulation" role="status">模擬資料；不代表真實費用。</p> }
        <p>查詢期間（{{report.timeZone}}）：{{report.from}} 至 {{report.to}}；資料讀取時間：{{report.asOf}}</p>
        <p class="known-trend-note">圖表只呈現已知 USD 小計；無法計算的費用不會顯示為零。</p>
        <dl class="summary">
          <dt>報表狀態</dt><dd>{{statusLabel(report.summary.status)}}</dd>
          <dt>已記錄派送嘗試：</dt><dd>{{report.summary.recordedAttemptCount}}</dd>
          <dt>逐次涵蓋未知</dt><dd>{{report.summary.coverageUnknownCount}}</dd>
          <dt>費用未知筆數</dt><dd>{{report.summary.unknownCount}}</dd>
          <dt>未歸屬筆數</dt><dd>{{report.summary.unassignedCount}}</dd>
          <dt>已知小計</dt><dd>{{money(report.summary.currency,report.summary.knownSubtotal)}}</dd>
          <dt>完整總額</dt><dd>{{report.summary.completeTotal === null ? '完整總額未知' : money(report.summary.currency,report.summary.completeTotal)}}</dd>
          @if (showShare(report) && report.summary.sharePercent !== null) { <dt>目前篩選範圍占比</dt><dd>{{report.summary.sharePercent}}%</dd> }
          @if (store.mode()==='workspace' && (store.memberId().trim() || store.unassignedOnly())) { <dt>成員費用</dt><dd>成員篩選結果只顯示絕對值。</dd> }
        </dl>
        <section aria-labelledby="daily-title">
          <h3 id="daily-title">每日已知 USD 趨勢</h3>
          <figure aria-describedby="daily-description">
            <figcaption id="daily-description">柱狀僅呈現每個日期的已知 USD 小計；無已知小計的日期不繪製柱狀。</figcaption>
            <div class="bars" aria-hidden="true">
              @for (bucket of report.daily; track bucket.day) {
                @if (bucket.knownSubtotal !== null && validRatio(bucket.visualRatioPercent)) {
                  <div class="bar-row"><span>{{bucket.day}}</span><span class="bar-track"><span data-testid="known-usd-bar" class="bar" [style.width.%]="bucket.visualRatioPercent"></span></span></div>
                }
              }
            </div>
          </figure>
          <div class="table-scroll" role="region" tabindex="0" aria-label="每日費用趨勢資料表，可水平捲動">
            <table><caption>每日費用趨勢資料</caption><thead><tr><th scope="col">台北日期</th><th scope="col">已知小計（{{report.summary.currency}}）</th><th scope="col">完整費用</th><th scope="col">未知筆數</th><th scope="col">已記錄派送嘗試</th></tr></thead>
              <tbody>@for (bucket of report.daily; track bucket.day) { <tr><th scope="row">{{bucket.day}}</th><td>{{money(report.summary.currency,bucket.knownSubtotal)}}</td><td>{{bucket.completeTotal === null ? '未知' : money(report.summary.currency,bucket.completeTotal)}}</td><td>{{bucket.unknownCount}}</td><td>{{bucket.recordedAttemptCount}}</td></tr> }</tbody>
            </table>
          </div>
        </section>
        @if (report.nativeGroups.length) {
          <div class="table-scroll" role="region" tabindex="0" aria-label="Provider 原生用量分組，可水平捲動">
            <table><caption>Provider 原生用量（不同分組不互相加總）</caption><thead><tr><th scope="col">Provider／Endpoint／Model／Schema</th><th scope="col">Input</th><th scope="col">Output</th><th scope="col">Cache read</th><th scope="col">Cache write</th></tr></thead><tbody>
              @for (group of report.nativeGroups; track group.provider+group.endpoint+group.model+group.schemaVersion) {
                <tr><th scope="row">{{group.provider}} / {{group.endpoint}} / {{group.model}} / {{group.schemaVersion}}</th><td>{{metric(group.input)}}</td><td>{{metric(group.output)}}</td><td>{{metric(group.cacheRead)}}</td><td>{{metric(group.cacheWrite)}}</td></tr>
              }
            </tbody></table>
          </div>
        }
        @if (report.attempts.nodes.length) {
          <div class="table-scroll" role="region" tabindex="0" aria-label="報表中的派送嘗試，可水平捲動">
            <table><caption>派送嘗試</caption><thead><tr><th scope="col">登記時間</th><th scope="col">Provider／Model</th><th scope="col">結果</th><th scope="col">涵蓋</th><th scope="col">費用估算</th><th scope="col">明細</th></tr></thead><tbody>
              @for (attempt of report.attempts.nodes; track attempt.id) {
                <tr><th scope="row">{{attempt.preparedAt}}</th><td>{{attempt.provider}} / {{attempt.model}} @if (attempt.simulation) { <strong>模擬</strong> }</td><td>{{attempt.executionOutcome}}</td><td>{{attempt.attemptCoverage==='VERIFIED'?'已驗證':'未知'}}</td><td><app-cost-explanation [cost]="attempt.cost" [compact]="true" /></td><td><button type="button" (click)="store.selectAttempt(attempt.id)" [attr.aria-label]="'查看 '+attempt.provider+' '+attempt.model+' 的報表明細'">查看</button></td></tr>
              }
            </tbody></table>
          </div>
          @if (store.hasMore()) { <button type="button" [disabled]="store.loading() || store.dirty()" (click)="store.loadMore()">載入更多派送嘗試</button> }
        } @else if (report.summary.recordedAttemptCount==='0') { <p>此範圍沒有已記錄派送嘗試。</p> }
      }
      @if (store.selectedAttemptId()) {
        <section aria-labelledby="report-detail-title"><h3 id="report-detail-title">報表原生用量明細</h3><button type="button" (click)="store.closeDetail()">關閉明細</button>
          @if (store.snapshotsLoading()) { <p role="status">載入明細中…</p> }
          @if (store.error()) { <p role="alert">{{store.error()}}</p> }
          @if (store.detailAttempt(); as attempt) {
            <p>{{attempt.provider}} / {{attempt.model}} · 結果 {{attempt.executionOutcome}} · 派送時間 {{attempt.startedAt ?? '未派送'}} · 逐次呼叫涵蓋 {{attempt.attemptCoverage==='VERIFIED'?'已驗證':'未知'}}</p>
            <p>Provider request ID：{{attempt.providerRequestId ?? '未提供'}} · response ID：{{attempt.providerResponseId ?? '未提供'}}</p>
            <app-cost-explanation [cost]="attempt.cost" />
          }
          @if (store.snapshotsError()) { <p role="alert">{{store.snapshotsError()}} <button type="button" (click)="store.retrySnapshots()">重試載入快照</button></p> }
          @for (snapshot of store.snapshots(); track snapshot.id) {
            <article><h4>快照 {{snapshot.ordinal}}</h4><p>{{snapshot.semantic}} · {{snapshot.sourceLayer}} · {{snapshot.captureCompleteness}} · {{snapshot.rawState}}</p>
              @if (snapshot.rawState==='TEXT_FALLBACK') { <p>JSONB_UNSUPPORTED：此資料不能作為可計價投影。</p> }
              @if (snapshot.rawUsage!==null) { @if (snapshot.rawUsage==='null') { <p>JSON null</p> } <pre>{{snapshot.rawUsage}}</pre><button type="button" (click)="download(snapshot)">下載原生用量 JSON</button> } @else { <p>未取得原生用量。</p> }
            </article>
          }
          @if (store.snapshotsHasMore()) { <button type="button" [disabled]="store.snapshotsLoading()" (click)="store.loadMoreSnapshots()">載入更多快照</button> }
        </section>
      }
    </section>
  `,
  styles:[`
    :host{display:block;min-width:0}
    .reports{min-width:0;padding-block:.5rem}
    form fieldset{display:flex;flex-wrap:wrap;align-items:end;gap:.5rem .75rem}
    form label{font-weight:600} form small{flex-basis:100%} input[type=text]{max-inline-size:16rem}
    .check{display:flex;align-items:center;gap:.35rem}
    .summary{display:grid;grid-template-columns:minmax(9rem,max-content) minmax(0,1fr);gap:.35rem .75rem}
    .summary dt{font-weight:700}.summary dd{margin:0;overflow-wrap:anywhere}
    .table-scroll{overflow:auto;max-inline-size:100%;margin-block:1rem}
    table{border-collapse:separate;border-spacing:0} th,td{padding:.5rem;text-align:start;vertical-align:top;white-space:nowrap}
    tbody th{position:sticky;inset-inline-start:0;background:Canvas} pre{white-space:pre-wrap;overflow-wrap:anywhere}
    figure{margin:0 0 1rem}.bars{display:grid;gap:.25rem;margin-block:.75rem;max-inline-size:42rem}.bar-row{display:grid;grid-template-columns:9rem minmax(0,1fr);gap:.5rem;align-items:center}.bar-track{display:block;min-block-size:.75rem;border:1px solid currentColor}.bar{display:block;min-block-size:.75rem;background:currentColor}
    .simulation{font-weight:700;border:2px solid currentColor;padding:.5rem}
  `]
})
export class UsageReportsComponent implements OnInit,OnChanges,OnDestroy {
  @Input() workspaceId:string|null=null; @Input() manager=false;
  @ViewChild('errorSummary') private errorSummary?:ElementRef<HTMLElement>;
  readonly store=new UsageReportsStore();
  ngOnInit():void{void this.store.open(this.workspaceId,this.manager);}
  ngOnChanges(changes:SimpleChanges):void{if(!changes['workspaceId']?.firstChange||!changes['manager']?.firstChange)void this.store.changeScope(this.workspaceId,this.manager);}
  ngOnDestroy():void{this.store.dispose();}
  async changeMode(mode:'mine'|'workspace'):Promise<void>{await this.store.setMode(mode);}
  async submitFilters(event:Event):Promise<void>{event.preventDefault();await this.store.applyFilters();if(this.store.error())queueMicrotask(()=>this.errorSummary?.nativeElement.focus());}
  statusLabel(status:string):string{return ({EMPTY:'無資料',COMPLETE:'完整',PARTIAL:'部分完整',UNKNOWN:'未知'} as Record<string,string>)[status]??'未知';}
  money(currency:string,value:string|null):string{return value===null?'尚無已知費用':`${currency} ${value}`;}
  metric(value:UsageReportMetric):string{return `${value.knownValue??'未知'} ${value.unit}；未知 ${value.unknownCount}；${value.meaning}`;}
  showShare(report:UsageReport):boolean{return this.store.mode()==='workspace'&&!this.store.memberId().trim()&&!this.store.unassignedOnly()&&report.summary.status==='COMPLETE'&&report.summary.completeTotal!==null&&report.summary.unknownCount==='0'&&report.summary.coverageUnknownCount==='0'&&report.summary.unassignedCount==='0'&&report.summary.sharePercent!==null;}
  validRatio(value:number|null):boolean{return value!==null&&Number.isInteger(value)&&value>=0&&value<=100;}
  async download(snapshot:UsageSnapshot):Promise<void>{if(snapshot.rawUsage===null||!await this.store.authorizeSnapshot(snapshot.id))return;const url=URL.createObjectURL(new Blob([snapshot.rawUsage],{type:'application/json;charset=utf-8'}));const anchor=document.createElement('a');anchor.href=url;anchor.download=`usage-${snapshot.id}.json`;anchor.click();URL.revokeObjectURL(url);}
}
