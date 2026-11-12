import { Component, input } from '@angular/core';
import { UsageCost, UsageCostStatus } from './usage.store';

@Component({
  selector: 'app-cost-explanation',
  standalone: true,
  template: `
    <div class="cost-explanation">
      <p class="status"><strong>{{ statusLabel(cost().status, cost().needsReprice) }}</strong>
        @if (cost().simulation) { <strong class="simulation">模擬估算，不計入真實費用</strong> }
      </p>
      @if (cost().status === 'NOT_DISPATCHED') {
        <p>尚未派送，沒有供應商費用。</p>
      } @else if (cost().needsReprice) {
        <p class="stale">待以最新用量重新估算。估算來源版本 {{ cost().estimateSourceVersion ?? '未提供' }}；目前用量版本 {{ cost().sourceProjectionVersion }}。不顯示舊估算金額。</p>
      } @else if (cost().status === 'COMPLETE' && cost().completeAmount !== null) {
        <p>完整估算金額：{{ cost().currency }} {{ cost().completeAmount }}</p>
      } @else if ((cost().status === 'PARTIAL' || cost().status === 'UNKNOWN') && cost().knownSubtotal !== null) {
        <p>已知小計：{{ cost().currency }} {{ cost().knownSubtotal }}</p>
        <p>完整估算總額未知。</p>
      } @else if (cost().status === 'UNPRICED') {
        <p>金額幣別：{{ cost().currency }}。目前尚無可用費率，未提供金額。</p>
      } @else if (cost().status === 'COMPLETE') {
        <p>完整估算金額尚未提供。</p>
      } @else {
        <p>費用未知，未提供完整金額。</p>
      }

      @if (compact() && cost().reasons.length) {
        <p>原因：@for (reason of cost().reasons; track $index) { {{ reason }}{{ !$last ? '、' : '' }} }</p>
      }

      @if (!compact()) {
        <section class="details" aria-labelledby="cost-estimate-title">
          <h3 id="cost-estimate-title">估算說明</h3>
          <p>本估算只涵蓋已列明的計價範圍，不是供應商帳單。</p>
          @if (cost().reasons.length) {
            <p>未知或限制原因：</p>
            <ul>@for (reason of cost().reasons; track $index) { <li>{{ reason }}</li> }</ul>
          }
          <dl>
            <dt>估算 revision</dt><dd>{{ cost().currentRevision ?? '未提供' }}</dd>
            <dt>價格 revision</dt><dd>{{ cost().priceRevision ?? '未提供' }}</dd>
            <dt>價格快照</dt><dd>{{ cost().priceSnapshotId ?? '未提供' }}</dd>
            <dt>價格來源</dt><dd>{{ cost().priceSourceReference ?? '未提供' }}</dd>
            <dt>價格有效期間</dt><dd>{{ cost().priceEffectiveFrom ?? '未提供' }} – {{ cost().priceEffectiveUntil ?? '未提供' }}</dd>
            <dt>計算器版本</dt><dd>{{ cost().calculatorVersion ?? '未提供' }}</dd>
            <dt>估算來源版本</dt><dd>{{ cost().estimateSourceVersion ?? '未提供' }}</dd>
            <dt>目前用量版本</dt><dd>{{ cost().sourceProjectionVersion }}</dd>
            <dt>來源快照</dt><dd>{{ cost().sourceSnapshotId ?? '未提供' }}</dd>
            <dt>來源／Schema</dt><dd>{{ cost().sourceLayer ?? '未提供' }} · {{ cost().schemaVersion ?? '未提供' }}</dd>
            <dt>Adapter／Capture</dt><dd>{{ cost().adapterVersion ?? '未提供' }} · {{ cost().captureVersion ?? '未提供' }}</dd>
            <dt>擷取完整度</dt><dd>{{ cost().captureCompleteness ?? '未知' }} · {{ cost().semantic ?? '未知' }} · ordinal {{ cost().ordinal ?? '未提供' }}</dd>
          </dl>

          @if (cost().needsReprice) {
            <p>舊來源的費率與分項金額不代表目前用量，已隱藏，待重新估算後再顯示。</p>
          } @else if (cost().lineItems.length) {
            <div class="line-items-scroll" role="region" tabindex="0" aria-label="費用估算分項，可水平捲動">
              <table>
                <caption>費用估算分項（{{ cost().currency }}）</caption>
                <thead><tr><th scope="col">分項</th><th scope="col">供應商原生用量</th><th scope="col">費率</th><th scope="col">分項金額</th><th scope="col">未知原因</th></tr></thead>
                <tbody>@for (item of cost().lineItems; track $index) {
                  <tr>
                    <th scope="row">{{ item.category }}</th>
                    <td>{{ item.nativeQuantity ?? '未知' }} {{ item.unit }}<small>{{ item.meaning }}</small></td>
                    <td>{{ item.rate ?? '未知' }}<small>{{ item.rateBasis }}</small></td>
                    <td>
                      @if (item.storedAmount !== null) { {{ cost().currency }} {{ item.storedAmount }} }
                      @else if (item.exactAmount !== null) { 精確值 {{ cost().currency }} {{ item.exactAmount }}（未納入可用金額） }
                      @else { 未知 }
                    </td>
                    <td>@if (item.reasons.length) { <ul>@for (reason of item.reasons; track $index) { <li>{{ reason }}</li> }</ul> } @else { — }</td>
                  </tr>
                }</tbody>
              </table>
            </div>
          } @else {
            <p>沒有可顯示的費用分項。</p>
          }
        </section>
      }
    </div>
  `,
  styles: [`
    :host { display: block; min-width: 0; }
    .status { display: flex; flex-wrap: wrap; gap: .5rem; margin-block: .25rem; }
    .simulation { border: 2px solid currentColor; padding: .15rem .35rem; }
    .stale { font-weight: 700; }
    .details { margin-block-start: 1rem; }
    dl { display: grid; grid-template-columns: minmax(9rem, max-content) minmax(0, 1fr); gap: .35rem .75rem; }
    dt { font-weight: 700; }
    dd { margin: 0; overflow-wrap: anywhere; }
    .line-items-scroll { max-inline-size: 100%; overflow: auto; }
    table { border-collapse: separate; border-spacing: 0; }
    th, td { padding: .5rem; text-align: start; vertical-align: top; white-space: nowrap; }
    small { display: block; white-space: normal; }
    ul { margin: 0; padding-inline-start: 1.25rem; white-space: normal; }
  `]
})
export class CostExplanationComponent {
  readonly cost = input.required<UsageCost>();
  readonly compact = input(false);

  statusLabel(status: UsageCostStatus, needsReprice = false): string {
    if (needsReprice) return '待重新估算';
    switch (status) {
      case 'COMPLETE': return '完整估算';
      case 'PARTIAL': return '部分估算';
      case 'UNPRICED': return '尚未定價';
      case 'NOT_DISPATCHED': return '尚未派送';
      case 'UNKNOWN': return '費用未知';
    }
  }
}
