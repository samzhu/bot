import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi, afterEach } from 'vitest';
import { UsageComponent } from './usage.component';
import { UsageCost } from './usage.store';

const unpricedCost = (simulation = false): UsageCost => ({status:'UNPRICED',amount:null,knownSubtotal:null,completeAmount:null,currency:'USD',simulation,needsReprice:false,sourceProjectionVersion:'1',estimateSourceVersion:null,currentRevision:null,priceRevision:null,priceSnapshotId:null,priceSourceReference:null,priceEffectiveFrom:null,priceEffectiveUntil:null,calculatorVersion:null,sourceSnapshotId:null,sourceLayer:null,schemaVersion:null,adapterVersion:null,captureVersion:null,captureCompleteness:null,semantic:null,ordinal:null,reasons:[],lineItems:[]});


afterEach(() => vi.unstubAllGlobals());
it('shows API unpriced status and exact native values in an accessible table', async () => {
  const native = {value:'900719925474099312345',presence:'PRESENT',meaning:'provider raw input'};
  const missing = {value:null,presence:'UNKNOWN',meaning:'capture incomplete'};
  vi.stubGlobal('fetch',vi.fn(async () => Response.json({data:{myUsageAttempts:{nodes:[{id:'a',workspaceId:'A',preparedAt:'2026-01-01T00:00:00.123456Z',startedAt:null,provider:'gemini',model:'m',executionOutcome:'COMPLETED',attemptCoverage:'UNKNOWN',simulation:true,input:native,output:missing,cacheRead:missing,cacheWrite:missing,cost:unpricedCost(true)}],pageInfo:{hasNextPage:false,endCursor:null}}}})));
  const fixture = TestBed.createComponent(UsageComponent); fixture.componentRef.setInput('workspaceId','A'); fixture.detectChanges();
  await vi.waitFor(() => expect(fixture.componentInstance.store.attempts()).toHaveLength(1)); fixture.detectChanges();
  const el = fixture.nativeElement as HTMLElement;
  expect(el.querySelector('table caption')?.textContent).toContain('個人用量');
  expect(el.querySelector('th[scope="col"]')).toBeTruthy(); expect(el.querySelector('[role="region"][tabindex="0"]')).toBeTruthy();
  expect(el.textContent).toContain('900719925474099312345'); expect(el.textContent).toContain('provider raw input');
  expect(el.textContent).toContain('逐次呼叫涵蓋：未知'); expect(el.textContent).toContain('尚未定價'); expect(el.textContent).toContain('USD'); expect(el.textContent).toContain('模擬');
});
it('uses fresh attempt metadata together with its new snapshot in detail', async () => {
  const native = {value:null,presence:'UNKNOWN',meaning:'incomplete'};
  const a = {id:'a',workspaceId:'A',preparedAt:'2026-01-01',startedAt:null,provider:'p',model:'m',endpoint:'e',channel:'c',executionOutcome:'IN_PROGRESS',usagePresence:'PENDING',captureCompleteness:'UNKNOWN',projectionState:'UNKNOWN',projectionVersion:'1',effectiveSnapshotId:null,attemptCoverage:'UNKNOWN',simulation:false,input:native,output:native,cacheRead:native,cacheWrite:native,cost:unpricedCost()};
  const snapshot = {id:'s2',eventId:'e',ordinal:'2',semantic:'CUMULATIVE',sourceLayer:'SDK_NATIVE',schemaVersion:'1',adapterVersion:'1',captureVersion:'1',captureCompleteness:'COMPLETE',rawState:'JSONB',rawUsage:'{"fresh":2}',captureReason:null,capturedAt:''};
  vi.stubGlobal('fetch',vi.fn(async (_url:string, init:RequestInit) => {
    const query = JSON.parse(String(init.body)).query as string;
    if (query.includes('MyUsageAttempts')) return Response.json({data:{myUsageAttempts:{nodes:[a],pageInfo:{hasNextPage:false,endCursor:null}}}});
    return Response.json({data:{myUsageAttempt:{...a,executionOutcome:'COMPLETED',projectionVersion:'2',effectiveSnapshotId:'s2',snapshots:{nodes:[snapshot],pageInfo:{hasNextPage:false,endCursor:null}}}}});
  }));
  const fixture = TestBed.createComponent(UsageComponent); fixture.componentRef.setInput('workspaceId','A'); fixture.detectChanges();
  await vi.waitFor(() => expect(fixture.componentInstance.store.attempts()).toHaveLength(1)); fixture.detectChanges();
  (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('tbody button')!.click();
  await vi.waitFor(() => expect(fixture.componentInstance.store.snapshots()).toHaveLength(1)); fixture.detectChanges();
  const details = (fixture.nativeElement as HTMLElement).querySelector('section section')!;
  expect(details.textContent).toContain('COMPLETED'); expect(details.textContent).toContain('版本 2');
  expect(details.textContent).toContain('s2'); expect(details.textContent).toContain('{"fresh":2}');
});
it('shows ledger registration time separately from a not-yet-dispatched attempt', async () => {
  const native = {value:null,presence:'UNKNOWN',meaning:'incomplete'};
  const a = {id:'a',workspaceId:'A',preparedAt:'2026-01-01T01:02:03.123456Z',startedAt:null,provider:'p',model:'m',endpoint:'e',channel:'c',executionOutcome:'IN_PROGRESS',usagePresence:'PENDING',captureCompleteness:'UNKNOWN',projectionState:'UNKNOWN',projectionVersion:'1',effectiveSnapshotId:null,attemptCoverage:'UNKNOWN',simulation:false,input:native,output:native,cacheRead:native,cacheWrite:native,cost:unpricedCost()};
  vi.stubGlobal('fetch',vi.fn(async (_url:string, init:RequestInit) => {
    const query = JSON.parse(String(init.body)).query as string;
    if (query.includes('MyUsageAttempts')) return Response.json({data:{myUsageAttempts:{nodes:[a],pageInfo:{hasNextPage:false,endCursor:null}}}});
    return Response.json({data:{myUsageAttempt:{...a,snapshots:{nodes:[],pageInfo:{hasNextPage:false,endCursor:null}}}}});
  }));
  const fixture = TestBed.createComponent(UsageComponent); fixture.componentRef.setInput('workspaceId','A'); fixture.detectChanges();
  await vi.waitFor(() => expect(fixture.componentInstance.store.attempts()).toHaveLength(1)); fixture.detectChanges();
  const el = fixture.nativeElement as HTMLElement;
  expect(el.textContent).toContain('登記時間'); expect(el.textContent).toContain(a.preparedAt);
  el.querySelector<HTMLButtonElement>('tbody button')!.click();
  await vi.waitFor(() => expect(fixture.componentInstance.store.detailAttempt()).not.toBeNull()); fixture.detectChanges();
  expect(el.textContent).toContain('派送時間：尚未派送');
});
it('shows JSON null separately from unavailable and downloads exact text after authorization', async () => {
  const raw = '{"large":900719925474099312345,"nested":null,"text":"中文"}';
  const native = {value:null,presence:'UNKNOWN',meaning:'capture incomplete'};
  const attempt = {id:'a',workspaceId:'A',preparedAt:'2026-01-01T00:00:00Z',startedAt:null,provider:'p',model:'m',simulation:false,input:native,output:native,cacheRead:native,cacheWrite:native,cost:unpricedCost()};
  const snapshot = (id:string,rawUsage:string|null,rawState:string) => ({id,eventId:id,ordinal:id,semantic:'CUMULATIVE',sourceLayer:'SDK_NATIVE',schemaVersion:'1',adapterVersion:'1',captureVersion:'1',captureCompleteness:'INCOMPLETE',rawState,rawUsage,captureReason:null,capturedAt:''});
  vi.stubGlobal('fetch',vi.fn(async (_url:string, init:RequestInit) => {
    const query = JSON.parse(String(init.body)).query as string;
    if (query.includes('MyUsageAttempts')) return Response.json({data:{myUsageAttempts:{nodes:[attempt],pageInfo:{hasNextPage:false,endCursor:null}}}});
    if (query.includes('AuthorizeUsageDownload')) return Response.json({data:{myUsageAttempt:{id:'a'}}});
    return Response.json({data:{myUsageAttempt:{...attempt,snapshots:{nodes:[snapshot('1',raw,'TEXT_FALLBACK'),snapshot('2','null','JSONB'),snapshot('3',null,'UNAVAILABLE')],pageInfo:{hasNextPage:false,endCursor:null}}}}});
  }));
  const fixture = TestBed.createComponent(UsageComponent); fixture.componentRef.setInput('workspaceId','A'); fixture.detectChanges();
  await vi.waitFor(() => expect(fixture.componentInstance.store.attempts()).toHaveLength(1)); fixture.detectChanges();
  (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('tbody button')!.click();
  await vi.waitFor(() => expect(fixture.componentInstance.store.snapshots()).toHaveLength(3)); fixture.detectChanges();
  const el = fixture.nativeElement as HTMLElement;
  expect([...el.querySelectorAll('pre')].map(node => node.textContent)).toEqual([raw,'null']);
  expect(el.textContent).toContain('未取得原生用量'); expect(el.textContent).toContain('JSONB_UNSUPPORTED');
  const create = vi.fn(() => 'blob:test'); vi.stubGlobal('URL',{createObjectURL:create,revokeObjectURL:vi.fn()});
  const click = vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(() => {});
  const blob = vi.spyOn(globalThis,'Blob');
  (el.querySelectorAll('article button')[0] as HTMLButtonElement).click();
  await vi.waitFor(() => expect(create).toHaveBeenCalledOnce());
  expect(blob.mock.calls[0][0]).toEqual([raw]); expect(blob.mock.calls[0][1]?.type).toBe('application/json;charset=utf-8');
  click.mockRestore(); blob.mockRestore();
});
it('does not create a blob when download reauthorization is denied', async () => {
  const native = {value:null,presence:'UNKNOWN',meaning:'incomplete'};
  const a = {id:'a',workspaceId:'A',preparedAt:'2026-01-01',startedAt:null,provider:'p',model:'m',simulation:false,input:native,output:native,cacheRead:native,cacheWrite:native,cost:unpricedCost()};
  const snapshot = {id:'s',eventId:'s',ordinal:'1',semantic:'CUMULATIVE',sourceLayer:'SDK_NATIVE',schemaVersion:'1',adapterVersion:'1',captureVersion:'1',captureCompleteness:'COMPLETE',rawState:'JSONB',rawUsage:'{"secret":12345678901234567890}',captureReason:null,capturedAt:''};
  vi.stubGlobal('fetch',vi.fn(async (_url:string, init:RequestInit) => {
    const query = JSON.parse(String(init.body)).query as string;
    if (query.includes('MyUsageAttempts')) return Response.json({data:{myUsageAttempts:{nodes:[a],pageInfo:{hasNextPage:false,endCursor:null}}}});
    if (query.includes('AuthorizeUsageDownload')) return Response.json({errors:[{message:'denied',extensions:{code:'WORKSPACE_ACCESS_DENIED'}}]});
    return Response.json({data:{myUsageAttempt:{...a,snapshots:{nodes:[snapshot],pageInfo:{hasNextPage:false,endCursor:null}}}}});
  }));
  const create = vi.fn(() => 'blob:test'); vi.stubGlobal('URL',{createObjectURL:create,revokeObjectURL:vi.fn()});
  const fixture = TestBed.createComponent(UsageComponent); fixture.componentRef.setInput('workspaceId','A'); fixture.detectChanges();
  await vi.waitFor(() => expect(fixture.componentInstance.store.attempts()).toHaveLength(1)); fixture.detectChanges();
  (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('tbody button')!.click();
  await vi.waitFor(() => expect(fixture.componentInstance.store.snapshots()).toHaveLength(1)); fixture.detectChanges();
  (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('article button')!.click();
  await vi.waitFor(() => expect(fixture.componentInstance.store.error()).toBeTruthy()); fixture.detectChanges();
  expect(create).not.toHaveBeenCalled(); expect((fixture.nativeElement as HTMLElement).querySelector('article')).toBeNull();
});

it('bounds and names the vertically scrollable usage content for wheel and keyboard access', async () => {
  const native = {value:null,presence:'UNKNOWN',meaning:'incomplete'};
  const row = {id:'a',workspaceId:'A',preparedAt:'2026-01-01',startedAt:null,provider:'p',model:'m',simulation:false,input:native,output:native,cacheRead:native,cacheWrite:native,cost:unpricedCost()};
  vi.stubGlobal('fetch',vi.fn(async () => Response.json({data:{myUsageAttempts:{nodes:[row],pageInfo:{hasNextPage:false,endCursor:null}}}})));
  const fixture = TestBed.createComponent(UsageComponent); fixture.componentRef.setInput('workspaceId','A'); fixture.detectChanges();
  await vi.waitFor(() => expect(fixture.componentInstance.store.attempts()).toHaveLength(1)); fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const style = getComputedStyle(host);
  expect(style.display).toBe('flex'); expect(style.flex).toContain('1'); expect(style.minHeight).toBe('0px'); expect(style.minWidth).toBe('0px'); expect(style.overflow).toBe('hidden');
  const scroll = host.querySelector('[role="region"][tabindex="0"][aria-label="個人用量內容，可垂直捲動"]');
  expect(scroll).toBeTruthy(); expect(getComputedStyle(scroll!).overflowY).toBe('auto');
  expect(host.querySelector('.table-scroll[role="region"][tabindex="0"]')).toBeTruthy();
});

it('explains current cost without parsing decimals or showing stale amounts', async () => {
  const native = {value:null,presence:'UNKNOWN',meaning:'incomplete'};
  const cost = (overrides: Record<string,unknown> = {}) => ({status:'COMPLETE',amount:'9007199254740993.123456789012',knownSubtotal:'9007199254740993.123456789012',completeAmount:'9007199254740993.123456789012',currency:'USD',simulation:true,needsReprice:false,sourceProjectionVersion:'v2',estimateSourceVersion:'v2',currentRevision:'r2',priceRevision:'price-v1',priceSnapshotId:'price-snapshot-1',priceSourceReference:'Fixture price list §3',priceEffectiveFrom:'2000-01-01T00:00:00.123456Z',priceEffectiveUntil:'2100-01-01T00:00:00.654321Z',calculatorVersion:'calc-1',sourceSnapshotId:'s2',sourceLayer:'SDK_NATIVE',schemaVersion:'schema-1',adapterVersion:'adapter-1',captureVersion:'capture-1',captureCompleteness:'COMPLETE',semantic:'FINAL',ordinal:'9007199254740993',reasons:[],lineItems:[{category:'input',nativeQuantity:'900719925474099312345',unit:'provider tokens',meaning:'native prompt units',rate:'0.123456789012',rateBasis:'USD per million provider tokens',exactAmount:'1524157875323883.4432',storedAmount:'1524157875323883.443200000000',reasons:[]}],...overrides});
  const rows = [
    {id:'complete',workspaceId:'A',preparedAt:'2026-01-01',startedAt:'2026-01-01',provider:'gemini',model:'large',endpoint:'generateContent',channel:'online',executionOutcome:'COMPLETED',simulation:true,cost:cost(),input:native,output:native,cacheRead:native,cacheWrite:native},
    {id:'stale',workspaceId:'A',preparedAt:'2026-01-02',startedAt:'2026-01-02',provider:'openai',model:'m',endpoint:'responses',channel:'online',executionOutcome:'COMPLETED',simulation:false,cost:cost({amount:'0.500000000000',knownSubtotal:'0.400000000000',completeAmount:'0.500000000000',needsReprice:true,sourceProjectionVersion:'v4',estimateSourceVersion:'v3',simulation:false,lineItems:[{category:'old-price-line',nativeQuantity:'1000',unit:'tokens',meaning:'old projection',rate:'999.999',rateBasis:'USD per million',exactAmount:'0.500000000000',storedAmount:'0.500000000000',reasons:[]}]}),input:native,output:native,cacheRead:native,cacheWrite:native},
    {id:'partial',workspaceId:'A',preparedAt:'2026-01-03',startedAt:'2026-01-03',provider:'anthropic',model:'m',endpoint:'messages',channel:'online',executionOutcome:'COMPLETED',simulation:false,cost:cost({status:'PARTIAL',amount:null,knownSubtotal:'0.000000000000',completeAmount:null,simulation:false,reasons:['NOT_FINAL'],lineItems:[{category:'output',nativeQuantity:null,unit:'provider tokens',meaning:'not captured',rate:null,rateBasis:'USD per million provider tokens',exactAmount:'0.0000000000001',storedAmount:null,reasons:['PRECISION_UNSUPPORTED']}]}),input:native,output:native,cacheRead:native,cacheWrite:native},
    {id:'unknown',workspaceId:'A',preparedAt:'2026-01-04',startedAt:'2026-01-04',provider:'gemini',model:'m',endpoint:'generateContent',channel:'online',executionOutcome:'COMPLETED',simulation:false,cost:cost({status:'UNKNOWN',amount:null,knownSubtotal:null,completeAmount:null,simulation:false}),input:native,output:native,cacheRead:native,cacheWrite:native},
    {id:'not-dispatched',workspaceId:'A',preparedAt:'2026-01-05',startedAt:null,provider:'openai',model:'m',endpoint:'responses',channel:'online',executionOutcome:'NOT_DISPATCHED',simulation:false,cost:cost({status:'NOT_DISPATCHED',amount:null,knownSubtotal:null,completeAmount:null,simulation:false}),input:native,output:native,cacheRead:native,cacheWrite:native}
  ];
  const requests: Array<{query:string;variables:Record<string,unknown>}> = [];
  vi.stubGlobal('fetch',vi.fn(async (_url:string, init:RequestInit) => {
    const request = JSON.parse(String(init.body)) as {query:string;variables:Record<string,unknown>}; requests.push(request);
    if (request.query.includes('MyUsageAttempts')) return Response.json({data:{myUsageAttempts:{nodes:rows,pageInfo:{hasNextPage:false,endCursor:null}}}});
    const selected = rows.find(row => row.id === request.variables['id'])!;
    return Response.json({data:{myUsageAttempt:{...selected,snapshots:{nodes:[],pageInfo:{hasNextPage:false,endCursor:null}}}}});
  }));
  const fixture = TestBed.createComponent(UsageComponent); fixture.componentRef.setInput('workspaceId','A'); fixture.detectChanges();
  await vi.waitFor(() => expect(fixture.componentInstance.store.attempts()).toHaveLength(5)); fixture.detectChanges();
  const el = fixture.nativeElement as HTMLElement;
  const table = el.querySelector('table')!;
  expect(table.textContent).toContain('9007199254740993.123456789012');
  expect(table.textContent).toContain('模擬估算');
  expect(table.textContent).toContain('待以最新用量重新估算');
  expect(table.textContent).not.toContain('0.500000000000');
  expect(table.textContent).toContain('已知小計：USD 0.000000000000');
  expect(table.textContent).toContain('費用未知'); expect(table.textContent).toContain('尚未派送，沒有供應商費用');
  const unknownRow = [...table.querySelectorAll('tbody tr')].find(row => row.textContent?.includes('2026-01-04'))!;
  expect(unknownRow.textContent).not.toContain('USD 0.00');
  const staleRow = [...table.querySelectorAll('tbody tr')].find(row => row.textContent?.includes('2026-01-02'))!;
  expect(staleRow.textContent).toContain('待重新估算');
  expect(staleRow.textContent).not.toContain('0.500000000000');
  expect(staleRow.textContent).not.toContain('0.400000000000');
  expect(staleRow.textContent).not.toContain('999.999');
  expect(requests[0].query).toContain('completeAmount'); expect(requests[0].query).toContain('estimateSourceVersion'); expect(requests[0].query).toContain('lineItems');
  expect(requests[0].query).toContain('priceSnapshotId'); expect(requests[0].query).toContain('priceSourceReference');
  el.querySelector<HTMLButtonElement>('tbody button')!.click();
  await vi.waitFor(() => expect(fixture.componentInstance.store.detailAttempt()).not.toBeNull()); fixture.detectChanges();
  const explanation = [...el.querySelectorAll('app-cost-explanation')].find(node => node.querySelector('.details'))!;
  expect(explanation.textContent).toContain('不是供應商帳單');
  expect(explanation.textContent).toContain('price-v1'); expect(explanation.textContent).toContain('v2');
  expect(explanation.textContent).toContain('price-snapshot-1'); expect(explanation.textContent).toContain('Fixture price list §3');
  expect(explanation.textContent).toContain('2000-01-01T00:00:00.123456Z'); expect(explanation.textContent).toContain('2100-01-01T00:00:00.654321Z');
  expect(explanation.textContent).toContain('900719925474099312345');
  expect(explanation.querySelector('table caption')?.textContent).toContain('估算分項');
  expect(explanation.querySelector('th[scope="col"]')).toBeTruthy();
  el.querySelectorAll<HTMLButtonElement>('tbody button')[1].click();
  await vi.waitFor(() => expect(fixture.componentInstance.store.detailAttempt()?.id).toBe('stale')); fixture.detectChanges();
  const staleExplanation = [...el.querySelectorAll('app-cost-explanation')].find(node => node.querySelector('.details'))!;
  expect(staleExplanation.textContent).toContain('不顯示舊估算金額');
  expect(staleExplanation.textContent).toContain('目前用量版本 v4');
  expect(staleExplanation.textContent).toContain('估算來源版本 v3');
  expect(staleExplanation.textContent).toContain('price-v1');
  expect(staleExplanation.textContent).not.toContain('完整估算');
  expect(staleExplanation.textContent).not.toContain('USD');
  expect(staleExplanation.textContent).not.toContain('999.999');
  expect(staleExplanation.textContent).not.toContain('0.500000000000');
  expect(staleExplanation.textContent).not.toContain('0.400000000000');
  expect(staleExplanation.querySelector('table')).toBeNull();
  el.querySelectorAll<HTMLButtonElement>('tbody button')[2].click();
  await vi.waitFor(() => expect(fixture.componentInstance.store.detailAttempt()?.id).toBe('partial')); fixture.detectChanges();
  expect(el.textContent).toContain('精確值 USD 0.0000000000001（未納入可用金額）');
  expect(el.textContent).toContain('PRECISION_UNSUPPORTED');
});
