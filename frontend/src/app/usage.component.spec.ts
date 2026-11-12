import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi, afterEach } from 'vitest';
import { UsageComponent } from './usage.component';

afterEach(() => vi.unstubAllGlobals());
it('shows API unpriced status and exact native values in an accessible table', async () => {
  const native = {value:'900719925474099312345',presence:'PRESENT',meaning:'provider raw input'};
  const missing = {value:null,presence:'UNKNOWN',meaning:'capture incomplete'};
  vi.stubGlobal('fetch',vi.fn(async () => Response.json({data:{myUsageAttempts:{nodes:[{id:'a',workspaceId:'A',preparedAt:'2026-01-01T00:00:00.123456Z',startedAt:null,provider:'gemini',model:'m',executionOutcome:'COMPLETED',attemptCoverage:'UNKNOWN',simulation:true,input:native,output:missing,cacheRead:missing,cacheWrite:missing,cost:{status:'UNPRICED',amount:null,currency:'USD'}}],pageInfo:{hasNextPage:false,endCursor:null}}}})));
  const fixture = TestBed.createComponent(UsageComponent); fixture.componentRef.setInput('workspaceId','A'); fixture.detectChanges();
  await vi.waitFor(() => expect(fixture.componentInstance.store.attempts()).toHaveLength(1)); fixture.detectChanges();
  const el = fixture.nativeElement as HTMLElement;
  expect(el.querySelector('table caption')?.textContent).toContain('個人用量');
  expect(el.querySelector('th[scope="col"]')).toBeTruthy(); expect(el.querySelector('[role="region"][tabindex="0"]')).toBeTruthy();
  expect(el.textContent).toContain('900719925474099312345'); expect(el.textContent).toContain('provider raw input');
  expect(el.textContent).toContain('逐次呼叫涵蓋：未知'); expect(el.textContent).toContain('UNPRICED'); expect(el.textContent).toContain('USD'); expect(el.textContent).toContain('模擬');
});
it('uses fresh attempt metadata together with its new snapshot in detail', async () => {
  const native = {value:null,presence:'UNKNOWN',meaning:'incomplete'};
  const a = {id:'a',workspaceId:'A',preparedAt:'2026-01-01',startedAt:null,provider:'p',model:'m',endpoint:'e',channel:'c',executionOutcome:'IN_PROGRESS',usagePresence:'PENDING',captureCompleteness:'UNKNOWN',projectionState:'UNKNOWN',projectionVersion:'1',effectiveSnapshotId:null,attemptCoverage:'UNKNOWN',simulation:false,input:native,output:native,cacheRead:native,cacheWrite:native,cost:{status:'UNPRICED',amount:null,currency:'USD'}};
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
  const a = {id:'a',workspaceId:'A',preparedAt:'2026-01-01T01:02:03.123456Z',startedAt:null,provider:'p',model:'m',endpoint:'e',channel:'c',executionOutcome:'IN_PROGRESS',usagePresence:'PENDING',captureCompleteness:'UNKNOWN',projectionState:'UNKNOWN',projectionVersion:'1',effectiveSnapshotId:null,attemptCoverage:'UNKNOWN',simulation:false,input:native,output:native,cacheRead:native,cacheWrite:native,cost:{status:'UNPRICED',amount:null,currency:'USD'}};
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
  const attempt = {id:'a',workspaceId:'A',preparedAt:'2026-01-01T00:00:00Z',startedAt:null,provider:'p',model:'m',simulation:false,input:native,output:native,cacheRead:native,cacheWrite:native,cost:{status:'UNPRICED',amount:null,currency:'USD'}};
  const snapshot = (id:string,rawUsage:string|null,rawState:string) => ({id,eventId:id,ordinal:id,semantic:'CUMULATIVE',sourceLayer:'SDK_NATIVE',schemaVersion:'1',adapterVersion:'1',captureVersion:'1',captureCompleteness:'INCOMPLETE',rawState,rawUsage,captureReason:null,capturedAt:''});
  vi.stubGlobal('fetch',vi.fn(async (_url:string, init:RequestInit) => {
    const query = JSON.parse(String(init.body)).query as string;
    if (query.includes('MyUsageAttempts')) return Response.json({data:{myUsageAttempts:{nodes:[attempt],pageInfo:{hasNextPage:false,endCursor:null}}}});
    if (query.includes('AuthorizeUsageDownload')) return Response.json({data:{myUsageAttempt:{id:'a'}}});
    return Response.json({data:{myUsageAttempt:{id:'a',snapshots:{nodes:[snapshot('1',raw,'TEXT_FALLBACK'),snapshot('2','null','JSONB'),snapshot('3',null,'UNAVAILABLE')],pageInfo:{hasNextPage:false,endCursor:null}}}}});
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
  const a = {id:'a',workspaceId:'A',preparedAt:'2026-01-01',startedAt:null,provider:'p',model:'m',simulation:false,input:native,output:native,cacheRead:native,cacheWrite:native,cost:{status:'UNPRICED',amount:null,currency:'USD'}};
  const snapshot = {id:'s',eventId:'s',ordinal:'1',semantic:'CUMULATIVE',sourceLayer:'SDK_NATIVE',schemaVersion:'1',adapterVersion:'1',captureVersion:'1',captureCompleteness:'COMPLETE',rawState:'JSONB',rawUsage:'{"secret":12345678901234567890}',captureReason:null,capturedAt:''};
  vi.stubGlobal('fetch',vi.fn(async (_url:string, init:RequestInit) => {
    const query = JSON.parse(String(init.body)).query as string;
    if (query.includes('MyUsageAttempts')) return Response.json({data:{myUsageAttempts:{nodes:[a],pageInfo:{hasNextPage:false,endCursor:null}}}});
    if (query.includes('AuthorizeUsageDownload')) return Response.json({errors:[{message:'denied',extensions:{code:'WORKSPACE_ACCESS_DENIED'}}]});
    return Response.json({data:{myUsageAttempt:{id:'a',snapshots:{nodes:[snapshot],pageInfo:{hasNextPage:false,endCursor:null}}}}});
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
  const row = {id:'a',workspaceId:'A',preparedAt:'2026-01-01',startedAt:null,provider:'p',model:'m',simulation:false,input:native,output:native,cacheRead:native,cacheWrite:native,cost:{status:'UNPRICED',amount:null,currency:'USD'}};
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
