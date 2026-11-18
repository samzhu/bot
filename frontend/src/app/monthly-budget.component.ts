import { Component, Input, OnChanges, OnDestroy, SimpleChanges, ElementRef, ViewChild, output } from '@angular/core';
import { BudgetTransport, MonthlyBudgetStore } from './monthly-budget.store';
import { BudgetAlertsComponent } from './budget-alerts.component';
@Component({selector:'app-monthly-budget',standalone:true,imports:[BudgetAlertsComponent],template:`
<section class="budget" aria-labelledby="budget-title"><header><p class="eyebrow">用量與預算</p><h2 id="budget-title">工作區月預算</h2><p>Sam 本機使用 · 掌握本月用量，設定每月支出上限。</p></header>
@if(store.loading()){<p role="status">載入中…</p>} @if(store.error()){<p role="alert" #errorSummary tabindex="-1">{{store.error()}}</p>}
@if(!store.budget() && !store.loading()){<button type="button" (click)="store.refresh(true)">重新讀取</button>}
@if(store.budget(); as b){<div class="period"><h3>{{b.period}} 用量</h3><p>台北時間計算</p></div>
<dl class="summary"><div><dt>本月已知費用</dt><dd>{{b.knownSubtotalUsd === null ? '尚無可信已定價金額' : b.knownSubtotalUsd + ' USD'}}</dd></div><div><dt>每月上限</dt><dd>{{b.capUsd === null ? '未設定' : b.capUsd + ' USD'}}</dd><small>{{b.enabled ? '限制已啟用' : '限制已停用'}}</small></div><div><dt>預算狀態</dt><dd>{{stateText(b.state)}}</dd><small>終態未知費用 {{b.terminalUnknownCount}} 筆</small></div></dl>
@if(b.terminalUnknownCount !== '0'){<p>已知小計不代表完整費用。可查看費用修訂。 @if(manager && b.state==='BLOCKED_UNKNOWN'){<button type="button" (click)="reviewCosts.emit(b.simulation)">前往費用修訂</button>}</p>}
@if(b.state==='BLOCKED_CAP'){<p>已拒絕新工作，並要求取消此工作區同分區的進行中工作；供應商計費不保證立即停止。</p>}
@if(b.state==='BLOCKED_UNKNOWN'){<p>未知費用會阻止新工作，不因未知狀態取消進行中工作。</p>}
@if(b.state==='BLOCKED_RECOVERY'){<p>權威狀態尚未確認，新工作暫時受限。</p>} @if(b.state==='DISABLED'){<p>預算限制已停用。</p>}
@if(manager){<form class="budget-form" aria-labelledby="budget-form-title" (submit)="submit($event)" novalidate><h3 id="budget-form-title">設定月預算</h3><label class="toggle"><input type="checkbox" name="enabled" [disabled]="store.saving()" [checked]="store.enabled()" (change)="changeEnabled($event)">啟用預算限制</label><label for="budget-cap">每月上限（USD）</label><p id="budget-cap-help">啟用時必填；非負數，最多 18 位整數、12 位小數。設定跨月持續。</p>
<input #capInput id="budget-cap" name="cap" [disabled]="store.saving()" type="text" inputmode="decimal" [required]="store.enabled()" pattern="[0-9]+([.][0-9]+)?" [value]="store.cap()" [attr.aria-invalid]="store.fieldError() ? 'true' : null" [attr.aria-describedby]="store.fieldError() ? 'budget-cap-help budget-cap-error' : 'budget-cap-help'" (input)="changeCap($event)" (blur)="store.validate()">
@if(store.fieldError()){<p id="budget-cap-error">{{store.fieldError()}}</p>} @if(store.conflict()){<p>伺服器目前版本：{{b.configVersion}}；草稿原版本：{{store.version()}}。</p><button type="button" (click)="store.adopt()">確認採用目前版本</button>}
<button type="submit" [disabled]="store.saving() || store.conflict() || store.retryable()">儲存</button><button type="button" [disabled]="store.saving()" (click)="store.cancel()">取消</button>@if(store.retryable()){<button type="button" [disabled]="store.saving()" (click)="retry()">以相同命令重試</button>}</form>} @if(store.success()){<p role="status">{{store.success()}}</p>}}
@if(manager && workspaceId){<app-budget-alerts [workspaceId]="workspaceId" [simulation]="false" />}
</section>`, styles: [`
:host{display:block;max-width:70rem}.budget{display:grid;gap:1.25rem}.budget h2,.budget h3,.budget p{margin:0}.budget header{display:grid;gap:.35rem}.eyebrow{font-size:.8rem;font-weight:700;letter-spacing:.06em;color:#526878}.budget header>p:last-child,.period p{color:#52606a}.toggle{display:flex;align-items:center;gap:.45rem}.period{display:flex;justify-content:space-between;align-items:baseline;gap:.5rem;flex-wrap:wrap}.summary{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,13rem),1fr));gap:.75rem;margin:0}.summary>div{border:1px solid #ccd6dc;border-radius:.7rem;padding:1rem;min-width:0;background:#f8fafb}.summary dt{color:#52606a;font-size:.9rem}.summary dd{font-size:1.2rem;font-weight:700;margin:.4rem 0;overflow-wrap:anywhere}.summary small{color:#52606a}.budget-form{display:grid;justify-items:start;gap:.65rem;border:1px solid #ccd6dc;border-radius:.7rem;padding:1.25rem}.budget-form input[type=text]{box-sizing:border-box;width:min(100%,24rem);min-height:3rem;padding:.6rem;font:inherit;border:1px solid #667782;border-radius:.35rem}.budget-form button,.budget button{min-height:2.75rem;padding:.5rem .85rem;font:inherit}.budget-form p{max-width:45rem}.budget [role=alert],#budget-cap-error{color:#a12622}input:focus-visible,button:focus-visible{outline:3px solid #1769aa;outline-offset:2px}
`]})
export class MonthlyBudgetComponent implements OnChanges,OnDestroy {
 @Input() workspaceId:string|null=null; @Input() manager=false; @Input() transport:BudgetTransport|null=null;
 @ViewChild('capInput') capInput?:ElementRef<HTMLInputElement>; @ViewChild('errorSummary') errorSummary?:ElementRef<HTMLElement>;
 readonly reviewCosts=output<boolean>(); readonly store=new MonthlyBudgetStore();
 ngOnChanges(changes:SimpleChanges){if(changes['transport']&&this.transport)this.store.setTransport(this.transport);void this.store.setScope(this.workspaceId,this.manager,false);}
 ngOnDestroy(){void this.store.setScope(null,false);}
 changeEnabled(e:Event){this.store.editEnabled((e.target as HTMLInputElement).checked);} changeCap(e:Event){this.store.editCap((e.target as HTMLInputElement).value);}
 async submit(e:Event){e.preventDefault();if(!this.store.validate()){this.capInput?.nativeElement.focus();return;}await this.store.submit();if(this.store.fieldError())this.capInput?.nativeElement.focus();else if(this.store.error())setTimeout(()=>this.errorSummary?.nativeElement.focus());}
 async retry(){await this.store.retry();if(this.store.error())setTimeout(()=>this.errorSummary?.nativeElement.focus());}
 stateText(s:string){return ({DISABLED:'已停用',OPEN:'可執行',BLOCKED_CAP:'已達上限',BLOCKED_UNKNOWN:'未知費用待確認',BLOCKED_RECOVERY:'等待狀態恢復'} as Record<string,string>)[s]??'狀態未知';}
}
