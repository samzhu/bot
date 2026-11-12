import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { UsageReportsComponent } from './usage-reports.component';

afterEach(() => vi.unstubAllGlobals());

const report = (overrides: Record<string, unknown> = {}) => ({
  workspaceId: 'A', periodDays: 7, from: '2026-11-07T00:00:00+08:00', to: '2026-11-13T12:34:56.123456+08:00',
  asOf: '2026-11-13T12:34:56.123456+08:00', timeZone: 'Asia/Taipei', simulation: false,
  summary: {status:'PARTIAL',currency:'USD',knownSubtotal:'0.001620000000',completeTotal:null,unknownCount:'2',coverageUnknownCount:'1',recordedAttemptCount:'3',unassignedCount:'0',sharePercent:null},
  nativeGroups: [{provider:'gemini',endpoint:'generateContent',model:'m',schemaVersion:'1',input:{knownValue:'900719925474099312345',completeValue:null,unknownCount:'1',unit:'provider tokens',meaning:'native prompt'},output:{knownValue:null,completeValue:null,unknownCount:'3',unit:'provider tokens',meaning:'candidate'},cacheRead:{knownValue:'0',completeValue:'0',unknownCount:'0',unit:'provider tokens',meaning:'cached'},cacheWrite:{knownValue:null,completeValue:null,unknownCount:'3',unit:'provider tokens',meaning:'write'}}],
  daily: [
    {day:'2026-11-12',knownSubtotal:'0.001620000000',completeTotal:null,unknownCount:'1',recordedAttemptCount:'2',visualRatioPercent:100},
    {day:'2026-11-13',knownSubtotal:null,completeTotal:null,unknownCount:'1',recordedAttemptCount:'1',visualRatioPercent:null}
  ],
  attempts: {nodes:[{id:'attempt-1',workspaceId:'A',preparedAt:'2026-11-13T12:30:00.123456Z',startedAt:'2026-11-13T12:30:01.123456Z',provider:'gemini',endpoint:'generateContent',model:'m',channel:'direct',executionOutcome:'COMPLETED',attemptCoverage:'UNKNOWN',simulation:false,cost:{status:'PARTIAL',amount:null,knownSubtotal:'0.001620000000',completeAmount:null,currency:'USD',simulation:false,needsReprice:false,sourceProjectionVersion:'1',estimateSourceVersion:null,currentRevision:null,priceRevision:null,priceSnapshotId:null,priceSourceReference:null,priceEffectiveFrom:null,priceEffectiveUntil:null,calculatorVersion:null,sourceSnapshotId:null,sourceLayer:null,schemaVersion:null,adapterVersion:null,captureVersion:null,captureCompleteness:null,semantic:null,ordinal:null,reasons:[],lineItems:[]}}],pageInfo:{hasNextPage:false,endCursor:null}},
  ...overrides
});
const denied = () => Response.json({errors:[{message:'Workspace access denied',extensions:{code:'WORKSPACE_ACCESS_DENIED'}}]});
const snapshot = (id:string,rawUsage:string) => ({id,eventId:id,ordinal:'1',semantic:'FINAL',sourceLayer:'SDK_NATIVE',schemaVersion:'1',adapterVersion:'1',captureVersion:'1',captureCompleteness:'COMPLETE',rawState:'JSONB',rawUsage,captureReason:null,capturedAt:'2026-11-13T12:00:00Z'});
const detail = (snapshots:{nodes:unknown[];pageInfo:{hasNextPage:boolean;endCursor:string|null}}) => ({...report().attempts.nodes[0],snapshots});

describe('usage reports', () => {
  it('shows personal report values as exact strings and does not plot unknown as zero', async () => {
    const requests: Array<{query:string;variables:Record<string,unknown>}> = [];
    vi.stubGlobal('fetch', vi.fn(async (_url:string, init:RequestInit) => {
      const request = JSON.parse(String(init.body)) as {query:string;variables:Record<string,unknown>}; requests.push(request);
      return Response.json({data:{myUsageReport:report()}});
    }));
    const fixture = TestBed.createComponent(UsageReportsComponent); fixture.componentRef.setInput('workspaceId','A'); fixture.detectChanges();
    await vi.waitFor(() => expect(fixture.componentInstance.store.report()).not.toBeNull()); fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(requests[0].query).toContain('myUsageReport');
    expect(requests[0].variables).toMatchObject({workspaceId:'A',periodDays:7,first:20,simulation:false});
    expect(el.textContent).toContain('USD 0.001620000000'); expect(el.textContent).toContain('完整總額未知');
    expect(el.textContent).toContain('已記錄派送嘗試：3'); expect(el.textContent).toContain('Asia/Taipei');
    expect(el.textContent).toContain('900719925474099312345'); expect(el.textContent).toContain('native prompt');
    expect(el.querySelector('table caption')?.textContent).toContain('每日費用趨勢資料');
    expect(el.querySelectorAll('th[scope="col"]').length).toBeGreaterThan(0);
    const bars = el.querySelectorAll('[data-testid="known-usd-bar"]');
    expect(bars).toHaveLength(1); expect((bars[0] as HTMLElement).style.width).toBe('100%');
    expect(el.textContent).toContain('無法計算的費用不會顯示為零');
    expect(el.textContent).not.toContain('目前篩選範圍占比');
  });

  it('sends active filters and hides workspace share for a member-filtered report', async () => {
    const requests: Array<{query:string;variables:Record<string,unknown>}> = [];
    vi.stubGlobal('fetch', vi.fn(async (_url:string, init:RequestInit) => {
      const request = JSON.parse(String(init.body)) as {query:string;variables:Record<string,unknown>}; requests.push(request);
      const isWorkspace=request.query.includes('workspaceUsageReport');
      const result=report({periodDays:request.variables['periodDays'],summary:{...report().summary,status:isWorkspace?'COMPLETE':'PARTIAL',completeTotal:isWorkspace?'0.001620000000':null,unknownCount:isWorkspace?'0':'2',coverageUnknownCount:isWorkspace?'0':'1',sharePercent:'100.00'}});
      return Response.json({data:request.query.includes('workspaceUsageReport')?{workspaceUsageReport:result}:{myUsageReport:result}});
    }));
    const fixture = TestBed.createComponent(UsageReportsComponent); fixture.componentRef.setInput('workspaceId','A'); fixture.componentRef.setInput('manager',true); fixture.detectChanges();
    expect(fixture.componentInstance.manager).toBe(true);
    await vi.waitFor(() => expect(fixture.componentInstance.store.report()).not.toBeNull()); fixture.detectChanges();
    (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('.report-scopes button')[1].click();
    await vi.waitFor(() => expect(requests.length).toBe(2));
    await vi.waitFor(() => expect(fixture.componentInstance.store.mode()).toBe('workspace'));
    await vi.waitFor(() => expect(fixture.componentInstance.store.loading()).toBe(false));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('目前篩選範圍占比');
    const member = el.querySelector<HTMLInputElement>('#report-member-id')!;
    member.value = 'member-1'; member.dispatchEvent(new Event('input',{bubbles:true}));
    const period = el.querySelector<HTMLSelectElement>('#report-period')!; period.value = '30'; period.dispatchEvent(new Event('change',{bubbles:true}));
    expect(fixture.componentInstance.store.periodDays()).toBe(30);
    el.querySelector<HTMLButtonElement>('form button[type="submit"]')!.click();
    await vi.waitFor(() => expect(requests.length).toBe(3)); fixture.detectChanges();
    await vi.waitFor(() => expect(fixture.componentInstance.store.report()?.periodDays).toBe(30));
    await vi.waitFor(() => expect(fixture.componentInstance.store.loading()).toBe(false)); fixture.detectChanges();
    expect(requests[2].query).toContain('workspaceUsageReport');
    expect(requests[2].variables).toMatchObject({workspaceId:'A',periodDays:30,memberId:'member-1',unassignedOnly:false});
    expect(el.textContent).not.toContain('100.00%'); expect(el.textContent).not.toContain('目前篩選範圍占比');
    expect(el.textContent).toContain('成員篩選結果只顯示絕對值');
  });

  it('clears old report on scope change and ignores a late response', async () => {
    let resolveA!: (response:Response)=>void;
    const request = vi.fn(async (_url:string, init:RequestInit) => {
      const body = JSON.parse(String(init.body));
      if (body.variables.workspaceId === 'A') return new Promise<Response>(resolve => resolveA = resolve);
      return Response.json({data:{myUsageReport:report({workspaceId:'B',summary:{...report().summary,knownSubtotal:null,completeTotal:null}})}});
    });
    vi.stubGlobal('fetch',request);
    const fixture = TestBed.createComponent(UsageReportsComponent); fixture.componentRef.setInput('workspaceId','A'); fixture.detectChanges();
    fixture.componentRef.setInput('workspaceId','B'); fixture.detectChanges();
    await vi.waitFor(() => expect(fixture.componentInstance.store.report()?.workspaceId).toBe('B'));
    resolveA(Response.json({data:{myUsageReport:report({summary:{...report().summary,knownSubtotal:'99.00'}})}}));
    await vi.waitFor(() => expect(fixture.componentInstance.store.report()?.workspaceId).toBe('B')); fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('99.00');
  });

  it('clears prior data and shows a scoped server filter error instead of an empty result', async () => {
    let workspaceRequests=0;
    vi.stubGlobal('fetch',vi.fn(async (_url:string,init:RequestInit)=>{
      const request=JSON.parse(String(init.body)) as {query:string};
      if(request.query.includes('workspaceUsageReport')){
        workspaceRequests++;
        if(workspaceRequests>1)return Response.json({errors:[{message:'Invalid report filter',extensions:{code:'INVALID_REPORT_FILTER'}}]});
        return Response.json({data:{workspaceUsageReport:report({summary:{...report().summary,status:'COMPLETE',completeTotal:'0.001620000000',unknownCount:'0',coverageUnknownCount:'0',sharePercent:'100.00'}})}});
      }
      return Response.json({data:{myUsageReport:report()}});
    }));
    const fixture=TestBed.createComponent(UsageReportsComponent);fixture.componentRef.setInput('workspaceId','A');fixture.componentRef.setInput('manager',true);fixture.detectChanges();
    await vi.waitFor(()=>expect(fixture.componentInstance.store.report()).not.toBeNull());
    (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('.report-scopes button')[1].click();
    await vi.waitFor(()=>expect(workspaceRequests).toBe(1));await vi.waitFor(()=>expect(fixture.componentInstance.store.loading()).toBe(false));
    const el=fixture.nativeElement as HTMLElement;el.querySelector<HTMLInputElement>('#report-member-id')!.value='member-1';
    el.querySelector<HTMLInputElement>('#report-member-id')!.dispatchEvent(new Event('input',{bubbles:true}));
    el.querySelector<HTMLInputElement>('#report-member-id')!.value='member-1';
    el.querySelector<HTMLInputElement>('#report-member-id')!.dispatchEvent(new Event('input',{bubbles:true}));
    el.querySelector<HTMLInputElement>('input[type="checkbox"]')!.click();
    el.querySelector<HTMLButtonElement>('form button[type="submit"]')!.click();
    await vi.waitFor(()=>expect(fixture.componentInstance.store.errorCode()).toBe('INVALID_REPORT_FILTER'));fixture.detectChanges();
    expect(fixture.componentInstance.store.report()).toBeNull();expect(el.querySelector('[role="alert"]')?.textContent).toContain('INVALID_REPORT_FILTER');
  });

  it('clears report state when the initial scoped list is forbidden', async () => {
    vi.stubGlobal('fetch',vi.fn(async()=>denied()));
    const fixture=TestBed.createComponent(UsageReportsComponent);fixture.componentRef.setInput('workspaceId','A');fixture.detectChanges();
    await vi.waitFor(()=>expect(fixture.componentInstance.store.errorCode()).toBe('WORKSPACE_ACCESS_DENIED'));
    expect(fixture.componentInstance.store.report()).toBeNull();
    expect((fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')?.textContent).toContain('WORKSPACE_ACCESS_DENIED');
  });

  it('clears an already visible raw detail when report paging is forbidden', async () => {
    const raw='{"large":900719925474099312345,"nested":null}';let workspaceCalls=0;
    vi.stubGlobal('fetch',vi.fn(async(_url:string,init:RequestInit)=>{
      const request=JSON.parse(String(init.body)) as {query:string;variables:Record<string,unknown>};
      if(request.query.includes('WorkspaceUsageReport')){workspaceCalls++;if(request.variables['after'])return denied();return Response.json({data:{workspaceUsageReport:report({attempts:{...report().attempts,pageInfo:{hasNextPage:true,endCursor:'report-cursor'}}})}});}
      if(request.query.includes('UsageReportAttempt'))return Response.json({data:{workspaceUsageAttempt:detail({nodes:[snapshot('snap-1',raw)],pageInfo:{hasNextPage:false,endCursor:null}})}});
      return Response.json({data:{myUsageReport:report()}});
    }));
    const fixture=TestBed.createComponent(UsageReportsComponent);fixture.componentRef.setInput('workspaceId','A');fixture.componentRef.setInput('manager',true);fixture.detectChanges();
    await vi.waitFor(()=>expect(fixture.componentInstance.store.report()).not.toBeNull());
    (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('.report-scopes button')[1].click();
    await vi.waitFor(()=>expect(workspaceCalls).toBe(1));await vi.waitFor(()=>expect(fixture.componentInstance.store.loading()).toBe(false));fixture.detectChanges();
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('tbody button')!.click();
    await vi.waitFor(()=>expect((fixture.nativeElement as HTMLElement).textContent).toContain(raw));fixture.detectChanges();
    const more=[...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('button')].find(button=>button.textContent?.includes('載入更多派送嘗試'))!;
    more.click();
    await vi.waitFor(()=>expect(fixture.componentInstance.store.errorCode()).toBe('WORKSPACE_ACCESS_DENIED'));fixture.detectChanges();
    expect(fixture.componentInstance.store.report()).toBeNull();expect(fixture.componentInstance.store.detailAttempt()).toBeNull();expect(fixture.componentInstance.store.snapshots()).toEqual([]);
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain(raw);
  });

  it('invalidates a pending detail response after a forbidden report page', async () => {
    let resolveDetail!:(response:Response)=>void;let workspaceCalls=0;
    vi.stubGlobal('fetch',vi.fn(async(_url:string,init:RequestInit)=>{
      const request=JSON.parse(String(init.body)) as {query:string;variables:Record<string,unknown>};
      if(request.query.includes('WorkspaceUsageReport')){workspaceCalls++;if(request.variables['after'])return denied();return Response.json({data:{workspaceUsageReport:report({attempts:{...report().attempts,pageInfo:{hasNextPage:true,endCursor:'report-cursor'}}})}});}
      if(request.query.includes('UsageReportAttempt'))return new Promise<Response>(resolve=>resolveDetail=resolve);
      return Response.json({data:{myUsageReport:report()}});
    }));
    const fixture=TestBed.createComponent(UsageReportsComponent);fixture.componentRef.setInput('workspaceId','A');fixture.componentRef.setInput('manager',true);fixture.detectChanges();
    await vi.waitFor(()=>expect(fixture.componentInstance.store.report()).not.toBeNull());
    (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('.report-scopes button')[1].click();
    await vi.waitFor(()=>expect(workspaceCalls).toBe(1));await vi.waitFor(()=>expect(fixture.componentInstance.store.loading()).toBe(false));fixture.detectChanges();
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('tbody button')!.click();
    await vi.waitFor(()=>expect(resolveDetail).toBeDefined());
    [...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('button')].find(button=>button.textContent?.includes('載入更多派送嘗試'))!.click();
    await vi.waitFor(()=>expect(fixture.componentInstance.store.errorCode()).toBe('WORKSPACE_ACCESS_DENIED'));
    resolveDetail(Response.json({data:{workspaceUsageAttempt:detail({nodes:[snapshot('late','{"late":true}')],pageInfo:{hasNextPage:false,endCursor:null}})}}));
    await vi.waitFor(()=>expect(fixture.componentInstance.store.report()).toBeNull());fixture.detectChanges();
    expect(fixture.componentInstance.store.detailAttempt()).toBeNull();expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('late');
  });

  it('clears the report and raw state on forbidden detail, snapshot-page, and download authorization', async () => {
    const raw='{"private":12345678901234567890}';
    for(const deniedOperation of ['detail','snapshot','download'] as const){
      vi.stubGlobal('fetch',vi.fn(async(_url:string,init:RequestInit)=>{
        const request=JSON.parse(String(init.body)) as {query:string};
        if(request.query.includes('MyUsageReport'))return Response.json({data:{myUsageReport:report()}});
        if(request.query.includes('AuthorizeReportSnapshot'))return denied();
        if(request.query.includes('UsageReportSnapshotPage'))return denied();
        if(request.query.includes('UsageReportAttempt')){
          if(deniedOperation==='detail')return denied();
          const snapshots={nodes:[snapshot('snap-1',raw)],pageInfo:{hasNextPage:deniedOperation==='snapshot',endCursor:deniedOperation==='snapshot'?'snapshot-cursor':null}};
          return Response.json({data:{myUsageAttempt:detail(snapshots)}});
        }
        return Response.json({data:{myUsageReport:report()}});
      }));
      const fixture=TestBed.createComponent(UsageReportsComponent);fixture.componentRef.setInput('workspaceId','A');fixture.detectChanges();
      await vi.waitFor(()=>expect(fixture.componentInstance.store.report()).not.toBeNull());
      (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('tbody button')!.click();
      if(deniedOperation==='detail'){
        await vi.waitFor(()=>expect(fixture.componentInstance.store.errorCode()).toBe('WORKSPACE_ACCESS_DENIED'));
      }else{
        await vi.waitFor(()=>expect((fixture.nativeElement as HTMLElement).textContent).toContain(raw));
        fixture.detectChanges();
        if(deniedOperation==='snapshot'){
          [...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('button')].find(button=>button.textContent?.includes('載入更多快照'))!.click();
        }else{
          const create=vi.fn(()=> 'blob:test');vi.stubGlobal('URL',{createObjectURL:create,revokeObjectURL:vi.fn()});
          vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(()=>{});
          [...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('article button')][0].click();
          await vi.waitFor(()=>expect(fixture.componentInstance.store.errorCode()).toBe('WORKSPACE_ACCESS_DENIED'));
          expect(create).not.toHaveBeenCalled();
        }
        await vi.waitFor(()=>expect(fixture.componentInstance.store.errorCode()).toBe('WORKSPACE_ACCESS_DENIED'));
      }
      fixture.detectChanges();expect(fixture.componentInstance.store.report()).toBeNull();expect(fixture.componentInstance.store.detailAttempt()).toBeNull();
      expect((fixture.nativeElement as HTMLElement).textContent).not.toContain(raw);
      vi.restoreAllMocks();vi.unstubAllGlobals();
    }
  });
});
