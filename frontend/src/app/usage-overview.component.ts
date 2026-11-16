import { Component, Input, OnChanges, OnDestroy, output } from '@angular/core';
import { MonthlyBudgetStore } from './monthly-budget.store';
import { UsageReportsStore } from './usage-reports.store';

@Component({
  selector:'app-usage-overview',standalone:true,
  template:`
    <section class="overview" aria-labelledby="overview-title">
      <header class="overview-header"><div><p class="eyebrow">用量與預算</p><h2 id="overview-title">用量概況</h2></div><button type="button" class="secondary" (click)="openRecords.emit()">查看紀錄</button></header>
      <section class="summary" aria-label="本月摘要">
      @if (budget.loading()) { <p role="status">載入本月摘要中…</p> }
      @if (budget.error()) { <p role="alert">無法讀取本月摘要。<button type="button" (click)="budget.refresh(true)">重試</button></p> }
      @if (budget.budget(); as b) {
        <p class="summary-label">本月已知費用 · {{b.period}}</p>
        <p class="amount">{{b.knownSubtotalUsd === null ? '尚無已知費用' : 'USD ' + b.knownSubtotalUsd}}</p>
        <p class="muted">完整費用尚未確認</p>
        <p>每月預算：{{b.capUsd === null ? '未設定' : 'USD ' + b.capUsd}} · {{b.enabled ? '已啟用' : '已停用'}}</p>
      }
      <button type="button" class="secondary" (click)="openBudget.emit()">預算設定</button>
      </section>
      <section class="trend" aria-labelledby="overview-trend-title">
        <h3 id="overview-trend-title">最近 {{report.periodDays()}} 日已知 USD</h3>
        <button type="button" [attr.aria-pressed]="report.periodDays() === 7" (click)="setPeriod(7)">7 日</button>
        <button type="button" [attr.aria-pressed]="report.periodDays() === 30" (click)="setPeriod(30)">30 日</button>
        @if (report.loading()) { <p role="status">載入趨勢中…</p> }
        @if (report.error()) { <p role="alert">無法讀取趨勢。<button type="button" (click)="report.retry()">重試</button></p> }
        @if (report.report(); as r) {
          <p>僅顯示已知小計；未知費用不當作零。</p>
          <div class="bars" aria-hidden="true">@for (day of r.daily; track day.day) { @if(day.knownSubtotal !== null && day.visualRatioPercent !== null && day.visualRatioPercent >= 0 && day.visualRatioPercent <= 100) { <div class="bar-row"><span>{{day.day}}</span><span class="bar-track"><span data-testid="known-usd-bar" [style.width.%]="day.visualRatioPercent"></span></span></div> } }</div>
          @if (!r.daily.length) { <p>這段期間沒有用量紀錄。</p> }
          <details><summary>查看每日數值</summary><ul>@for (day of r.daily; track day.day) { <li>{{day.day}}：{{day.knownSubtotal === null ? '未知' : 'USD ' + day.knownSubtotal}}</li> }</ul></details>
        }
      </section>
    </section>
  `,
  styles:[`
    :host{display:block;min-inline-size:0}.overview{display:grid;gap:1.25rem;max-inline-size:64rem;color:#172b38}.overview h2,.overview h3,.overview p{margin:0}.overview-header{display:flex;justify-content:space-between;align-items:end;gap:1rem;flex-wrap:wrap}.overview-header h2{font-size:clamp(1.5rem,1.2rem + 1vw,2rem)}.eyebrow,.summary-label{font-size:.875rem;font-weight:650;color:#526a76}.summary,.trend{display:grid;justify-items:start;gap:.75rem;padding:clamp(1rem,1rem + 1vw,1.5rem);border:1px solid #d5e0e5;border-radius:.8rem;background:#fff;min-inline-size:0}.summary{background:#f3f8f8}.amount{font-size:clamp(1.35rem,1.1rem + 1vw,1.85rem);font-weight:700;overflow-wrap:anywhere;letter-spacing:-.02em}.muted{color:#526a76}.trend{grid-template-columns:1fr auto auto;align-items:center}.trend h3{grid-column:1}.trend p,.trend .bars,.trend details{grid-column:1 / -1}.trend button{min-width:3.5rem}.trend button[aria-pressed=true]{background:#174e57;color:white}.bars{display:grid;gap:.35rem;width:100%;max-inline-size:40rem}.bar-row{display:grid;grid-template-columns:6.5rem minmax(0,1fr);gap:.5rem;align-items:center;font-size:.85rem}.bar-track{display:block;min-width:0;background:#e7eeee;border-radius:999px;overflow:hidden}[data-testid=known-usd-bar]{display:block;max-inline-size:100%;height:.6rem;background:#24727a}.overview button{font:inherit;min-height:2.75rem;padding:.5rem .9rem;border:1px solid #a8bdc1;border-radius:.5rem;background:#fff;color:#174e57;cursor:pointer}.overview button:focus-visible,.overview summary:focus-visible{outline:3px solid #1769aa;outline-offset:2px}.overview [role=alert]{color:#9a1d1d}.overview details{max-inline-size:100%}.overview li{overflow-wrap:anywhere}
  `]
})
export class UsageOverviewComponent implements OnChanges, OnDestroy {
  @Input() workspaceId:string|null=null;
  @Input() manager=false;
  readonly openBudget=output<void>(); readonly openRecords=output<void>();
  readonly budget=new MonthlyBudgetStore(); readonly report=new UsageReportsStore();
  ngOnChanges():void { void this.budget.setScope(this.workspaceId,this.manager,false); void this.report.changeScope(this.workspaceId,this.manager); }
  setPeriod(days:7|30):void { if (days === this.report.periodDays()) return; this.report.setPeriodDays(days); void this.report.applyFilters(); }
  ngOnDestroy():void { void this.budget.setScope(null,false); this.report.dispose(); }
}
