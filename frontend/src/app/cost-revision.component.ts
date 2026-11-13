import { Component, ElementRef, Input, OnChanges, OnDestroy, SimpleChanges, ViewChild, signal } from '@angular/core';
import { CostRevisionStore, GRAPHQL_COST_REVISION_TRANSPORT, GraphQLCostRevisionTransport, CostRevisionTransport } from './cost-revision.store';

const LONG_MAX='9223372036854775807';
const isLongString=(value:string)=>/^(0|[1-9][0-9]*)$/.test(value)&&(value.length<LONG_MAX.length||(value.length===LONG_MAX.length&&value<=LONG_MAX));
const amountPattern='(?:0|[1-9][0-9]{0,17})(?:\\.[0-9]{1,12})?';

@Component({
  selector:'app-cost-revision',standalone:true,
  template:`
    <section class="revision-host" role="region" tabindex="0" aria-label="工作區費用修訂管理，可垂直捲動">
      <div class="visually-hidden" aria-live="polite" aria-atomic="true">{{fieldAnnouncement()}}</div>
      <h1>費用修訂管理</h1>
      @if (workspaceId && manager) {
        @if(isControlledTransport()){<p class="fixture-notice"><strong>受控假資料預覽</strong>：使用本機 controlled transport，不呼叫模型或真實後端。</p>}
        <button type="button" data-action="refresh" [disabled]="store.loading()" (click)="store.refresh()">重新載入預覽</button>
        <label class="simulation-filter" for="simulation-filter"><input id="simulation-filter" name="simulation" type="checkbox" aria-describedby="simulation-filter-hint" [checked]="store.simulation()" (change)="store.setSimulation(($any($event.target)).checked)">僅看模擬資料</label>
        <p id="simulation-filter-hint">真實與模擬 attempt 分開查詢；切換會清除目前列表、明細、修訂與分頁游標。</p>
        @if (store.loading()) { <p>載入待確認項目…</p> }
        @if (store.error()) { <p role="alert">{{store.error()}} <button type="button" [disabled]="store.loading()" (click)="store.refresh()">重試</button></p> }
        @if(store.cursorNotice()){<p role="status">{{store.cursorNotice()}}</p>}
        @if (!store.loading() && !store.error() && !store.attempts().length) { <p>目前沒有待確認費用。</p> }
        @if (store.attempts().length) {
          <div class="table-scroll" role="region" tabindex="0" aria-label="待確認費用表格，可水平捲動">
            <table><caption>待確認費用</caption><thead><tr><th scope="col">供應商／模型</th><th scope="col">狀態</th><th scope="col">目前金額資訊</th><th scope="col">Source version</th><th scope="col">操作</th></tr></thead>
              <tbody>@for (attempt of store.attempts(); track attempt?.id) {
                <tr><th scope="row">{{attempt.provider}} / {{attempt.model}} @if(attempt.simulation){<strong>模擬資料</strong>}</th><td>{{displayStatus(attempt)}} @if(attempt.needsReprice || attempt.status==='STALE'){ <strong>來源已變更，舊金額不作目前值</strong> }</td>
                  <td>@if(attempt.needsReprice || attempt.status==='STALE'){待重新審核} @else if(attempt.completeAmount !== null && attempt.status==='COMPLETE'){完整估算 {{attempt.completeAmount}} USD（非 invoice）} @else {已知小計 {{attempt.knownSubtotal ?? '未知'}} USD · 完整金額未知}</td>
                  <td>{{attempt.sourceProjectionVersion}}</td><td><button type="button" (click)="store.selectAttempt(attempt?.id)" [attr.aria-label]="'檢視 '+attempt.provider+' '+attempt.model+' 費用修訂'">檢視修訂</button></td></tr>
              }</tbody>
            </table>
          </div>
        }
        @if(store.attemptPageInfo().hasNextPage){<button type="button" data-action="load-more-attempts" [disabled]="store.loadingMore()" (click)="store.loadMoreAttempts()">載入更多待確認費用</button>}
        <section aria-labelledby="attempt-lookup-heading">
          <h2 id="attempt-lookup-heading">重新開啟修訂歷史</h2>
          <p>輸入已知 attempt ID 讀取這個工作區的稽核歷史；此查詢不會加入或改動待確認清單。</p>
          <search aria-label="以 attempt ID 查詢修訂歷史">
            <form id="attempt-lookup-form" method="get" action="" aria-labelledby="attempt-lookup-heading" (submit)="submitLookup($event,lookupForm)" #lookupForm>
              <label for="attempt-lookup-id">Attempt ID</label>
              <p id="attempt-lookup-id-hint">只會顯示目前工作區且符合真實／模擬分區的 attempt；查詢會重新向伺服器驗權。</p>
              <input id="attempt-lookup-id" name="attemptId" type="search" required maxlength="200" autocomplete="off" autocapitalize="none" spellcheck="false" [disabled]="store.lookupLoading()" [attr.aria-describedby]="'attempt-lookup-id-hint'+(store.lookupError() ? ' attempt-lookup-error' : '')" [attr.aria-invalid]="store.lookupError() ? 'true' : 'false'" [value]="lookupValue()" (input)="lookupInput($event)">
              @if(store.lookupError()){<p id="attempt-lookup-error" #lookupErrorSummary role="alert" tabindex="-1">{{store.lookupError()}}</p>}
              <button type="submit" [disabled]="store.lookupLoading()">{{store.lookupLoading()?'查詢中…':'查詢修訂歷史'}}</button>
            </form>
          </search>
        </section>
        @if (store.selectedAttempt(); as attempt) {
          <section class="attempt-detail" aria-labelledby="revision-detail-heading">
            <h2 id="revision-detail-heading">{{attempt.provider}} / {{attempt.model}} 修訂明細 @if(store.detailSource()==='lookup'){<span>（ID 查詢唯讀）</span>}</h2>
            <button type="button" (click)="store.closeAttempt()">關閉明細</button>
            <p>Outcome：{{attempt.outcome}} · 費用覆蓋：{{attempt.attemptCoverage}} · 狀態：{{displayStatus(attempt)}} · @if(attempt.simulation){<strong>模擬資料</strong>} Effective snapshot：{{attempt.effectiveSnapshotId ?? '未提供'}}</p>
            <p>Provider request ID：{{attempt.providerRequestId ?? '未提供'}} · Provider response ID：{{attempt.providerResponseId ?? '未提供'}}</p>
            <p>目前 source projection version：<code>{{attempt.sourceProjectionVersion}}</code>。提交會將此字串原樣帶入 CAS；不轉成 JS 數值。</p>
            @if(attempt.needsReprice || attempt.status==='STALE'){<p>來源已更新；舊 current 金額與明細不顯示，請重新檢視來源並人工決定。</p>} @else {
              <p>已知小計：{{attempt.knownSubtotal ?? '未知'}} USD · 完整金額：{{attempt.completeAmount ?? '未知'}} USD · {{attempt.status}}</p>
            }
            @if(attempt.reasons.length){<h3>費用原因</h3><ul>@for(reason of attempt.reasons;track reason){<li>{{reason}}</li>}</ul>}
            @if(store.effectiveRawLoading()){<p role="status">依 effectiveSnapshotId 逐頁尋找有效原文…</p>}
            @if(store.effectiveRawError()){<p>{{store.effectiveRawError()}}</p>}
            @if(store.effectiveRawState()==='TEXT_FALLBACK'){<p>JSONB_UNSUPPORTED：保留的是合法 usage 子樹原文，不能作為可計價證據。</p>}
            @if(store.effectiveRawUsage() === null || store.effectiveRawState()==='UNAVAILABLE'){<p>無可用 effective 原始 usage；無法可信重對帳。</p>} @else {
              <h3>已保存的原始 usage</h3><pre>{{store.effectiveRawUsage()}}</pre>
              @if(store.effectiveRawUsage()==='null'){<p>原始 JSON 值為 JSON null。</p>}
            }
            <section aria-labelledby="historical-snapshots-heading">
              <h3 id="historical-snapshots-heading">已保存的 usage 快照（有效標記依 effectiveSnapshotId；其他為歷史資料）</h3>
              <p>JSONB 儲存狀態與 capture completeness 是分開欄位；僅帶 effectiveSnapshotId 標記的快照可作目前投影來源。</p>
              @if(store.snapshotsLoading()){<p role="status">載入已保存快照…</p>}
              @if(store.snapshotsError()){<p role="alert">{{store.snapshotsError()}}</p>}
              @for(snapshot of store.historicalSnapshots();track snapshot.id){
                <article class="snapshot-audit">
                  <h4>快照 {{snapshot.ordinal}} @if(snapshot.id===attempt.effectiveSnapshotId){<strong>effectiveSnapshotId 指向此筆</strong>} @else {<strong>歷史快照，非有效投影</strong>}</h4>
                  <p>Snapshot ID <code>{{snapshot.id}}</code> · 事件 {{snapshot.eventId}} · 擷取 {{snapshot.capturedAt}}</p>
                  <p>{{snapshot.semantic}} · 來源層 {{snapshot.sourceLayer}} · Capture {{snapshot.captureCompleteness}} · 儲存 {{snapshot.rawState}}</p>
                  <p>Schema {{snapshot.schemaVersion}} · Adapter {{snapshot.adapterVersion}} · Capture version {{snapshot.captureVersion}}</p>
                  @if(snapshot.captureReason){<p>擷取原因：{{snapshot.captureReason}}</p>}
                  @if(snapshot.sourceLayer==='SDK_NATIVE' && snapshot.captureCompleteness!=='COMPLETE'){<p>SDK_NATIVE capture 不完整；不可推定 wire/完整 provider usage。</p>}
                  @if(snapshot.rawState==='TEXT_FALLBACK'){<p>JSONB_UNSUPPORTED：保存的是完整取得的合法 usage 子樹原文，不能作為可計價來源。</p>}
                  @if(snapshot.rawUsage===null || snapshot.rawState==='UNAVAILABLE'){<p>此快照沒有可用 raw 原文。</p>} @else {<pre>{{snapshot.rawUsage}}</pre>@if(snapshot.rawUsage==='null'){<p>原始 JSON 值是 JSON null。</p>}<button type="button" data-action="download-snapshot-history" [attr.aria-label]="'重新驗權後下載快照 '+snapshot.id+' 的原始 JSON'" (click)="downloadHistoricalSnapshot(snapshot)">重新驗權並下載原始快照</button>}
                </article>
              }
              @if(!store.snapshotsLoading() && !store.snapshotsError() && !store.historicalSnapshots().length){<p>目前沒有已保存快照。</p>}
              @if(store.snapshotPageInfo().hasNextPage){<button type="button" data-action="load-more-snapshots" [disabled]="store.snapshotsLoading()" (click)="store.loadMoreSnapshots()">載入更多歷史快照</button>}
              @if(store.snapshotDownloadError()){<p role="alert">{{store.snapshotDownloadError()}}</p>}
            </section>
            @if(store.conflictCurrentVersion()){<p>發現新的 source version {{store.conflictCurrentVersion()}}。原提交版本與輸入仍保留；需由管理員人工重新審核。</p>}
            @if(store.revisions().filter(revision=>revision.attemptId===attempt?.id).length){
              <p>不可變修訂歷史；目前有效修訂指標：{{store.historyCurrentRevisionId() ?? '無'}}。此 ID 可能尚未出現在已載入頁面；金額狀態仍以目前 effective cost 為準。</p>
              <div class="table-scroll" role="region" tabindex="0" aria-label="不可變費用修訂歷史表格，可水平捲動">
                <table><caption>歷史修訂記錄，非當前有效費用</caption><thead><tr><th scope="col">記錄時間</th><th scope="col">種類／狀態</th><th scope="col">金額</th><th scope="col">Actor</th><th scope="col">來源／證據／管理員原因／計算原因</th><th scope="col">Source version</th></tr></thead><tbody>
                  @for(revision of store.revisions().filter(row=>row.attemptId===attempt?.id);track revision.id){<tr [attr.data-revision-id]="revision.id"><th scope="row">{{revision.recordedAt}}</th><td>{{revision.kind}} / {{revision.status}} @if(revision.candidate){自動候選，不作 current 費用} @if(store.historyCurrentRevisionId()===revision.id){稽核 current 指標}</td><td>@if(revision.status==='STALE'){已過期，金額隱藏} @else if(revision.kind==='MANUAL_ESTIMATE'){人工估算已知小計 {{revision.knownSubtotal ?? '未知'}} {{revision.currency}} · complete amount 不適用} @else {已知小計 {{revision.knownSubtotal ?? '未知'}} {{revision.currency}} · 完整 {{revision.completeAmount ?? '未知'}} {{revision.currency}}}</td><td>{{revision.actorId ?? 'system'}}</td><td>來源 {{revision.sourceType ?? '未提供'}} · 證據參照 {{revision.evidenceReference ?? '未提供'}} · 管理員原因 {{revision.reason ?? '未提供'}} @if(revision.reasons.length){· 計算原因 {{revision.reasons.join('、')}}} @if(revision.priceSelectionState){· 價格選擇 {{revision.priceSelectionState}}} @if(revision.priceSourceReference){· 價格來源 {{revision.priceSourceReference}}} @if(revision.priceEffectiveFrom || revision.priceEffectiveUntil){· 價格有效期 {{revision.priceEffectiveFrom ?? '未提供'}}–{{revision.priceEffectiveUntil ?? '未提供'}}} @if(revision.priceRatesJson){<pre aria-label="歷史精確費率 JSON 原文">{{revision.priceRatesJson}}</pre>}</td><td>{{revision.sourceProjectionVersion}}</td></tr>}
                </tbody></table>
              </div>
            }
            @if(store.historyPageInfo().hasNextPage){<button type="button" data-action="load-more-revisions" [disabled]="store.loadingMore()" (click)="store.loadMoreRevisions()">載入更多修訂</button>}
            @if(store.detailSource()==='review'){
            <section aria-labelledby="manual-heading"><h3 id="manual-heading">新增人工估算</h3><p>人工估算僅申報 known subtotal；complete amount 維持 null，人工估算不會解除未知費用，也不代表 provider actual 或 invoice。</p>
              <form id="manual-cost-form" aria-labelledby="manual-heading" novalidate (submit)="submitManual($event,manualForm)" #manualForm>
                <label for="manual-amount">人工估算 USD 金額（十進位，最多 12 位小數）</label><p id="manual-amount-hint">以精確字串輸入；0 也必須提供證據。</p>
                <input id="manual-amount" name="amount" type="text" inputmode="decimal" required pattern="{{amountPattern}}" maxlength="31" [attr.aria-describedby]="'manual-amount-hint'+(showManualError('amount') ? ' manual-amount-error' : '')" [attr.aria-invalid]="showManualError('amount') ? 'true' : 'false'" [value]="store.manualDraft()?.amount ?? ''" (input)="manualInput('amount',$event)" (blur)="blurManual('amount',$event)">
                @if(showManualError('amount')){<p id="manual-amount-error" >{{manualError('amount')}}</p>}
                <label for="manual-evidence">申報證據參考</label><p id="manual-evidence-hint">僅作稽核申報；不會抓取 URL 或視為已驗證。</p>
                <input id="manual-evidence" name="evidenceReference" type="text" required [attr.aria-describedby]="'manual-evidence-hint'+(showManualError('evidenceReference') ? ' manual-evidence-error' : '')" [attr.aria-invalid]="showManualError('evidenceReference') ? 'true' : 'false'" [value]="store.manualDraft()?.evidenceReference ?? ''" (input)="manualInput('evidenceReference',$event)" (blur)="blurManual('evidenceReference',$event)">
                @if(showManualError('evidenceReference')){<p id="manual-evidence-error" >{{manualError('evidenceReference')}}</p>}
                <label for="manual-reason">估算原因</label><p id="manual-reason-hint">說明此人工估算的背景。</p>
                <textarea id="manual-reason" name="reason" required [attr.aria-describedby]="'manual-reason-hint'+(showManualError('reason') ? ' manual-reason-error' : '')" [attr.aria-invalid]="showManualError('reason') ? 'true' : 'false'" [value]="store.manualDraft()?.reason ?? ''" (input)="manualInput('reason',$event)" (blur)="blurManual('reason',$event)"></textarea>
                @if(showManualError('reason')){<p id="manual-reason-error" >{{manualError('reason')}}</p>}
                <label for="manual-source-version">本次提交鎖定的 source version</label><input id="manual-source-version" name="expectedSourceProjectionVersion" type="text" readonly required [value]="store.manualDraft()?.expectedSourceProjectionVersion ?? ''" aria-describedby="manual-source-version-hint"><p id="manual-source-version-hint">expectedRevision：{{store.manualDraft()?.expectedRevision ?? 'null（預期沒有 current）'}}</p>
                <button type="submit" [disabled]="store.saving() || store.successOperation()==='manual'">{{store.saving() ? '提交中…' : store.successOperation()==='manual' ? '已記錄，請重新檢視後再開始' : '提交人工估算'}}</button>
              </form>
              @if(store.retryableManual()){<p>結果未知；不得假設失敗或重建操作。</p><button type="button" data-action="retry-manual" [disabled]="store.saving()" (click)="store.retryManual()">以同一完整 payload/key 重試</button>}
              @if(store.conflictCurrentVersion() && store.manualDraft()){<button type="button" data-action="adopt-current-version" (click)="store.adoptCurrentVersions()">檢視後採用目前版本，保留輸入並重新審核</button>}
            </section>
            <section aria-labelledby="reconcile-heading"><h3 id="reconcile-heading">可信來源重對帳</h3>
              <p>伺服器只會依已保存 raw 與指定已發布 catalog 計算；此證據參照是管理員申報，不抓取 URL，也不會自行證明來源可信。沒有可用 JSONB raw 或已發布 catalog 時不能提交。</p>
              @if(store.effectiveRawState()==='TEXT_FALLBACK'){<p>此 raw 是 JSONB 不支援時保存的原文，不作可計價來源。</p>}
              @if(store.effectiveRawState()==='UNAVAILABLE' || store.effectiveRawUsage()===null){<p>缺少可用 effective raw，無法重對帳。</p>}
              @else if(store.effectiveRawState()==='JSONB' && store.catalogs().length){
                  <form id="reconcile-cost-form" aria-labelledby="reconcile-heading" novalidate (submit)="submitReconcile($event,reconcileForm)" #reconcileForm>
                  <label for="reconcile-catalog">已發布價格目錄版本</label><p id="reconcile-catalog-hint">目錄 ID 會獨立保存，不是證據參照。</p>
                  <select id="reconcile-catalog" name="catalogRevisionId" required [attr.aria-describedby]="'reconcile-catalog-hint'+(showReconcileError('catalogRevisionId') ? ' reconcile-catalog-error' : '')" [attr.aria-invalid]="showReconcileError('catalogRevisionId') ? 'true' : 'false'" [value]="store.reconcileDraft()?.catalogRevisionId ?? ''" (change)="reconcileInput('catalogRevisionId',$event)" (blur)="blurReconcile('catalogRevisionId',$event)">
                    <option value="">選擇已發布目錄</option>@for(catalog of store.catalogs();track catalog.id){<option [value]="catalog.id">{{catalog.sourceReference}} · {{catalog.sourceRevision}} · generation {{catalog.generation}}</option>}
                  </select>
                  @if(showReconcileError('catalogRevisionId')){<p id="reconcile-catalog-error">{{reconcileError('catalogRevisionId')}}</p>}
                  @for(catalog of store.catalogs();track catalog.id){<details><summary>{{catalog.sourceReference}} · {{catalog.sourceRevision}}（generation {{catalog.generation}}）</summary><p>Catalog ID: <code>{{catalog.id}}</code> · 建立時間 {{catalog.createdAt}}</p>@for(entry of catalog.entries;track entry.id){<p>{{entry.provider}} / {{entry.endpoint}} / {{entry.model}} / {{entry.channel}} · price {{entry.priceRevision}} · {{entry.effectiveFrom}}–{{entry.effectiveUntil}}</p><pre aria-label="保存的精確費率 JSON 原文">{{entry.ratesJson}}</pre>}</details>}
                  @if(store.catalogPageInfo().hasNextPage){<button type="button" data-action="load-more-catalogs" [disabled]="store.loadingMore()" (click)="store.loadMoreCatalogs()">載入更多價格目錄</button>}
                  <label for="reconcile-evidence">管理員證據參照</label><p id="reconcile-evidence-hint">必填；只保存您的參照文字，不抓 URL、不宣稱已驗證。</p>
                  <input id="reconcile-evidence" name="evidenceReference" type="text" required maxlength="500" [attr.aria-describedby]="'reconcile-evidence-hint'+(showReconcileError('evidenceReference') ? ' reconcile-evidence-error' : '')" [attr.aria-invalid]="showReconcileError('evidenceReference') ? 'true' : 'false'" [value]="store.reconcileDraft()?.evidenceReference ?? ''" (input)="reconcileInput('evidenceReference',$event)" (blur)="blurReconcile('evidenceReference',$event)">
                  @if(showReconcileError('evidenceReference')){<p id="reconcile-evidence-error">{{reconcileError('evidenceReference')}}</p>}
                  <label for="reconcile-reason">重對帳原因</label><p id="reconcile-reason-hint">說明此次以保存來源與指定目錄重算的原因。</p>
                  <textarea id="reconcile-reason" name="reason" required maxlength="2000" [attr.aria-describedby]="'reconcile-reason-hint'+(showReconcileError('reason') ? ' reconcile-reason-error' : '')" [attr.aria-invalid]="showReconcileError('reason') ? 'true' : 'false'" [value]="store.reconcileDraft()?.reason ?? ''" (input)="reconcileInput('reason',$event)" (blur)="blurReconcile('reason',$event)"></textarea>
                  @if(showReconcileError('reason')){<p id="reconcile-reason-error">{{reconcileError('reason')}}</p>}
                  <p>CAS source version <code>{{store.reconcileDraft()?.expectedSourceProjectionVersion}}</code> · expected revision {{store.reconcileDraft()?.expectedRevision ?? 'null（預期沒有 current）'}}</p>
                  <button type="submit" [disabled]="store.saving() || store.successOperation()==='reconcile'">{{store.saving()?'重對帳中…':store.successOperation()==='reconcile'?'已記錄':'執行可信來源重對帳'}}</button>
                </form>
                @if(store.retryableReconcile()){<p>送出結果未知；保留同一 payload 與 idempotency key。</p><button type="button" data-action="retry-reconcile" [disabled]="store.saving()" (click)="store.retryReconcile()">以相同輸入與 key 明確重試</button>}
              } @else if(store.effectiveRawState()==='JSONB' && !store.catalogs().length){<p>目前沒有可用的已發布價格目錄。</p>@if(store.catalogPageInfo().hasNextPage){<button type="button" data-action="load-more-catalogs" [disabled]="store.loadingMore()" (click)="store.loadMoreCatalogs()">載入更多價格目錄</button>}}
            </section>
            }
          </section>
        }
        @if(store.mutationError()){<div #mutationErrorSummary tabindex="-1" role="alert"><p>{{store.mutationError()}}</p></div>}
        @if(store.success()){<p>{{store.success()}}</p>}
      } @else if(workspaceId && !manager) { <p role="alert">僅限工作區管理員檢視費用修訂。</p> }
      @else { <p>請先選擇可管理的工作區。</p> }
    </section>
  `,
  styles:[`:host{display:block;flex:1 1 auto;max-height:calc(100dvh - 9rem);min-height:0;min-width:0;overflow:auto}.revision-host{padding:1rem;min-width:0}.table-scroll{max-inline-size:100%;overflow:auto;margin-block:1rem}table{border-collapse:collapse}caption{text-align:start;font-weight:700;padding:.5rem}th,td{padding:.5rem;border:1px solid #777;text-align:start;vertical-align:top;white-space:nowrap}small,p{max-width:75ch}label{display:block;font-weight:600;margin-block:.75rem .25rem}.simulation-filter{display:flex;align-items:center;gap:.5rem}.simulation-filter input{display:inline-block}input,select,textarea{max-width:100%;padding:.5rem}textarea{min-width:min(30rem,100%);min-height:5rem}button{margin:.4rem;padding:.45rem .7rem}pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:24rem;overflow:auto}.fixture-notice{border-inline-start:.3rem solid #725b00;padding:.6rem;background:#fff8d6}input:focus-visible,select:focus-visible,textarea:focus-visible,button:focus-visible,.revision-host:focus-visible,.table-scroll:focus-visible{outline:3px solid #005fcc;outline-offset:2px}.visually-hidden{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}[aria-invalid="true"]{border:2px solid #b00020}`]
})
export class CostRevisionComponent implements OnChanges,OnDestroy {
  @Input() workspaceId:string|null=null;
  @Input() manager=false;
  @Input() transport:CostRevisionTransport=GRAPHQL_COST_REVISION_TRANSPORT;
  readonly store=new CostRevisionStore(GRAPHQL_COST_REVISION_TRANSPORT);
  @ViewChild('mutationErrorSummary') private mutationErrorSummary?:ElementRef<HTMLElement>;
  @ViewChild('lookupErrorSummary') private lookupErrorSummary?:ElementRef<HTMLElement>;
  readonly amountPattern=amountPattern;readonly fieldAnnouncement=signal('');readonly lookupValue=signal('');
  isControlledTransport():boolean{return !(this.transport instanceof GraphQLCostRevisionTransport);}
  private manualTouched=new Set<string>();
  private reconcileTouched=new Set<string>();
  lookupInput(event:Event):void{this.lookupValue.set((event.target as HTMLInputElement).value);this.store.lookupError.set(null);}
  async submitLookup(event:Event,form:HTMLFormElement):Promise<void>{event.preventDefault();const id=this.lookupValue().trim();if(!id){this.store.lookupError.set('請輸入 attempt ID。');form.querySelector<HTMLInputElement>('#attempt-lookup-id')?.focus();return;}const invalid=form.querySelector<HTMLElement>(':invalid');if(invalid){invalid.focus();form.reportValidity();return;}await this.store.lookupById(id);if(this.store.lookupError())queueMicrotask(()=>this.lookupErrorSummary?.nativeElement.focus());}
  ngOnChanges(changes:SimpleChanges):void{if(changes['transport'])this.store.setTransport(this.transport);if(changes['workspaceId']||changes['manager']||changes['transport'])void this.store.setScope(this.workspaceId,this.manager);}
  ngOnDestroy():void{this.store.dispose();}
  manualInput(field:'amount'|'evidenceReference'|'reason',event:Event):void{const input=event.target as HTMLInputElement|HTMLTextAreaElement;input.setCustomValidity('');this.fieldAnnouncement.set('');this.store.updateManual(field,input.value);}
  blurManual(field:string,event:Event):void{this.manualTouched.add(field);const input=event.target as HTMLInputElement|HTMLTextAreaElement;input.setCustomValidity('');const message=this.manualError(field);this.fieldAnnouncement.set(message?`錯誤：${message}`:'');}
  showManualError(field:string):boolean{return this.manualTouched.has(field)&&!!this.manualError(field);}
  manualError(field:string):string|null{const serverError=this.store.manualFieldErrors()[field];if(serverError)return serverError;const draft=this.store.manualDraft();if(!draft)return null;if(field==='amount'){if(!draft.amount.trim())return '請輸入人工估算金額。';if(!new RegExp(`^${amountPattern}$`).test(draft.amount.trim()))return '請輸入非負 USD 十進位，最多 18 位整數與 12 位小數。';}if(field==='evidenceReference'&&!draft.evidenceReference.trim())return '請填寫申報證據參考。';if(field==='reason'&&!draft.reason.trim())return '請填寫估算原因。';return null;}
  reconcileInput(field:'catalogRevisionId'|'evidenceReference'|'reason',event:Event):void{const input=event.target as HTMLInputElement|HTMLTextAreaElement|HTMLSelectElement;this.store.updateReconcile(field,input.value);this.fieldAnnouncement.set('');}
  blurReconcile(field:string,event:Event):void{this.reconcileTouched.add(field);const input=event.target as HTMLInputElement|HTMLTextAreaElement|HTMLSelectElement;input.setCustomValidity('');const message=this.reconcileError(field);this.fieldAnnouncement.set(message?`錯誤：${message}`:'');}
  showReconcileError(field:string):boolean{return this.reconcileTouched.has(field)&&!!this.reconcileError(field);}
  reconcileError(field:string):string|null{const serverError=this.store.reconcileFieldErrors()[field];if(serverError)return serverError;const draft=this.store.reconcileDraft();if(!draft)return null;if(field==='catalogRevisionId'&&!draft.catalogRevisionId)return '請選擇已發布價格目錄。';if(field==='evidenceReference'&&!draft.evidenceReference.trim())return '請填寫管理員證據參照。';if(field==='reason'&&!draft.reason.trim())return '請填寫重對帳原因。';return null;}
  displayStatus(attempt:{status:string;needsReprice:boolean}):string{return attempt.needsReprice||attempt.status==='STALE'?'STALE · 待重新估算':attempt.status;}
  private validSourceVersion(value:string):boolean{return isLongString(value);}
  async submitManual(event:Event,form:HTMLFormElement):Promise<void>{event.preventDefault();this.manualTouched=new Set(['amount','evidenceReference','reason']);const draft=this.store.manualDraft();const invalid=form.querySelector<HTMLElement>(':invalid');if(invalid){invalid.focus();form.reportValidity();return;}if(!draft||!this.validSourceVersion(draft.expectedSourceProjectionVersion)){this.store.mutationError.set('source version 無效，請重新載入並人工檢視。');return;}const custom=this.manualError('amount')||this.manualError('evidenceReference')||this.manualError('reason');if(custom){this.store.mutationError.set(custom);form.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus();return;}await this.store.submitManual();if(this.store.success())this.fieldAnnouncement.set(this.store.success()!);if(this.store.mutationError())queueMicrotask(()=>this.store.manualFieldErrors()['amount']?form.querySelector<HTMLElement>('#manual-amount')?.focus():this.mutationErrorSummary?.nativeElement.focus());}
  async submitReconcile(event:Event,form:HTMLFormElement):Promise<void>{event.preventDefault();this.reconcileTouched=new Set(['catalogRevisionId','evidenceReference','reason']);const draft=this.store.reconcileDraft();const invalid=form.querySelector<HTMLElement>(':invalid');if(invalid){invalid.focus();form.reportValidity();return;}if(!draft||!this.validSourceVersion(draft.expectedSourceProjectionVersion)){this.store.mutationError.set('source version 無效，請重新載入並人工檢視。');return;}const field=['catalogRevisionId','evidenceReference','reason'].find(name=>this.reconcileError(name));if(field){this.store.mutationError.set(this.reconcileError(field)!);form.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus();return;}await this.store.submitReconcile();if(this.store.success())this.fieldAnnouncement.set(this.store.success()!);if(this.store.mutationError())queueMicrotask(()=>this.mutationErrorSummary?.nativeElement.focus());}
  async downloadHistoricalSnapshot(snapshot:{id:string}):Promise<void>{const text=await this.store.authorizeSnapshotDownload(snapshot.id);if(text===null)return;const url=URL.createObjectURL(new Blob([text],{type:'application/json;charset=utf-8'}));const link=document.createElement('a');link.href=url;link.download=`cost-usage-${snapshot.id}.json`;link.click();URL.revokeObjectURL(url);}
}
