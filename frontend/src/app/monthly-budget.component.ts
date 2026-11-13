import { Component, Input, OnChanges, OnDestroy, SimpleChanges, ElementRef, ViewChild, output } from '@angular/core';
import { BudgetTransport, MonthlyBudgetStore } from './monthly-budget.store';
@Component({selector:'app-monthly-budget',standalone:true,template:`
<section aria-labelledby="budget-title"><h2 id="budget-title">工作區月預算</h2>
<fieldset><legend>資料分區</legend><label><input type="radio" name="partition" [disabled]="store.saving()" [checked]="!store.simulation()" (change)="partition(false)">真實</label><label><input type="radio" name="partition" [disabled]="store.saving()" [checked]="store.simulation()" (change)="partition(true)">模擬</label></fieldset>
@if(store.loading()){<p>載入中…</p>} @if(store.error()){<p role="alert" #errorSummary tabindex="-1">{{store.error()}}</p>}
@if(!store.budget() && !store.loading()){<button type="button" (click)="store.refresh(true)">重新讀取</button>}
@if(store.budget(); as b){<p>工作區：{{b.workspaceId}}；{{b.simulation ? '模擬' : '真實'}}</p><p>台北月份：{{b.period}}（{{b.periodStart}} 至 {{b.periodEnd}}，不含結束時間）</p>
<dl><dt>預算</dt><dd>{{b.enabled ? '啟用' : '停用'}}；上限 {{b.capUsd ?? '未設定'}} USD</dd><dt>已知小計</dt><dd>{{b.knownSubtotalUsd === null ? '尚無可信已定價金額' : b.knownSubtotalUsd + ' USD'}}</dd><dt>終態未知費用</dt><dd>{{b.terminalUnknownCount}} 筆</dd><dt>狀態</dt><dd>{{stateText(b.state)}}（{{b.reason}}）</dd></dl>
@if(b.terminalUnknownCount !== '0'){<p>已知小計不代表完整費用。可查看費用修訂。 @if(manager && b.state==='BLOCKED_UNKNOWN'){<button type="button" (click)="reviewCosts.emit(b.simulation)">前往費用修訂</button>}</p>}
@if(b.state==='BLOCKED_CAP'){<p>已拒絕新工作，並要求取消此工作區同分區的進行中工作；供應商計費不保證立即停止。</p>}
@if(b.state==='BLOCKED_UNKNOWN'){<p>未知費用會阻止新工作，不因未知狀態取消進行中工作。</p>}
@if(b.state==='BLOCKED_RECOVERY'){<p>權威狀態尚未確認，新工作暫時受限。</p>} @if(b.state==='DISABLED'){<p>預算限制已停用。</p>}
@if(manager){<form aria-labelledby="budget-form-title" (submit)="submit($event)" novalidate><h3 id="budget-form-title">設定月預算</h3><label><input type="checkbox" name="enabled" [disabled]="store.saving()" [checked]="store.enabled()" (change)="changeEnabled($event)">啟用預算限制</label><label for="budget-cap">每月上限（USD）</label><p id="budget-cap-help">啟用時必填；非負數，最多 18 位整數、12 位小數。設定跨月持續。</p>
<input #capInput id="budget-cap" name="cap" [disabled]="store.saving()" type="text" inputmode="decimal" [required]="store.enabled()" pattern="[0-9]+([.][0-9]+)?" [value]="store.cap()" [attr.aria-invalid]="store.fieldError() ? 'true' : null" [attr.aria-describedby]="store.fieldError() ? 'budget-cap-help budget-cap-error' : 'budget-cap-help'" (input)="changeCap($event)" (blur)="store.validate()">
@if(store.fieldError()){<p id="budget-cap-error">{{store.fieldError()}}</p>} @if(store.conflict()){<p>伺服器目前版本：{{b.configVersion}}；草稿原版本：{{store.version()}}。</p><button type="button" (click)="store.adopt()">確認採用目前版本</button>}
<button type="submit" [disabled]="store.saving() || store.conflict() || store.retryable()">儲存</button><button type="button" [disabled]="store.saving()" (click)="store.cancel()">取消</button>@if(store.retryable()){<button type="button" [disabled]="store.saving()" (click)="retry()">以相同命令重試</button>}</form>} @if(store.success()){<p role="status">{{store.success()}}</p>}}
</section>`})
export class MonthlyBudgetComponent implements OnChanges,OnDestroy {
 @Input() workspaceId:string|null=null; @Input() manager=false; @Input() transport:BudgetTransport|null=null;
 @ViewChild('capInput') capInput?:ElementRef<HTMLInputElement>; @ViewChild('errorSummary') errorSummary?:ElementRef<HTMLElement>;
 readonly reviewCosts=output<boolean>(); readonly store=new MonthlyBudgetStore();
 ngOnChanges(changes:SimpleChanges){if(changes['transport']&&this.transport)this.store.setTransport(this.transport);void this.store.setScope(this.workspaceId,this.manager,this.store.simulation());}
 ngOnDestroy(){void this.store.setScope(null,false);}
 partition(v:boolean){void this.store.setScope(this.workspaceId,this.manager,v);}
 changeEnabled(e:Event){this.store.editEnabled((e.target as HTMLInputElement).checked);} changeCap(e:Event){this.store.editCap((e.target as HTMLInputElement).value);}
 async submit(e:Event){e.preventDefault();if(!this.store.validate()){this.capInput?.nativeElement.focus();return;}await this.store.submit();if(this.store.fieldError())this.capInput?.nativeElement.focus();else if(this.store.error())setTimeout(()=>this.errorSummary?.nativeElement.focus());}
 async retry(){await this.store.retry();if(this.store.error())setTimeout(()=>this.errorSummary?.nativeElement.focus());}
 stateText(s:string){return ({DISABLED:'已停用',OPEN:'可執行',BLOCKED_CAP:'已達上限',BLOCKED_UNKNOWN:'未知費用待確認',BLOCKED_RECOVERY:'等待狀態恢復'} as Record<string,string>)[s]??'狀態未知';}
}
