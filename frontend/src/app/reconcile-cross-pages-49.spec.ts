import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { UsageComponent } from './usage.component';

describe('#49 reconciliation across usage pages', () => {
  afterEach(() => vi.unstubAllGlobals());
  it('re-reads one REAL workspace after a partial reconciliation', async () => {
    // Synthetic fetch-boundary fixture; this is not PG or live provider evidence.
    const requests:{query:string;variables:any}[]=[];const pageInfo={hasNextPage:false,endCursor:null};const native={value:null,presence:'MISSING',meaning:'unknown'};const subtotal='0.000311250000';let applied=false;
    const cost=()=>({status:applied?'PARTIAL':'UNPRICED',knownSubtotal:applied?subtotal:null,completeAmount:null,currency:'USD',simulation:false,needsReprice:false,sourceProjectionVersion:'7',estimateSourceVersion:'7',currentRevision:applied?'revision-new':null,priceRevision:null,priceSnapshotId:null,priceSourceReference:null,priceEffectiveFrom:null,priceEffectiveUntil:null,calculatorVersion:null,sourceSnapshotId:'snapshot',sourceLayer:'SDK_NATIVE',schemaVersion:'1',adapterVersion:'1',captureVersion:'1',captureCompleteness:'INCOMPLETE',semantic:'FINAL',ordinal:'1',reasons:['COVERAGE_UNKNOWN'],lineItems:[]});
    const attempt=()=>({id:'attempt',workspaceId:'workspace',runId:null,providerRequestId:null,providerResponseId:null,preparedAt:'2026-11-16T00:00:00Z',startedAt:'2026-11-16T00:00:00Z',finishedAt:null,provider:'GOOGLE',endpoint:'generateContent',model:'model',channel:'GEMINI_API',executionOutcome:'SUCCEEDED',usagePresence:'PRESENT',captureCompleteness:'INCOMPLETE',projectionError:null,projectionState:'VALID',projectionVersion:'7',effectiveSnapshotId:'snapshot',attemptCoverage:'UNKNOWN',simulation:false,input:native,output:native,cacheRead:native,cacheWrite:native,cost:cost()});
    const snapshot={id:'snapshot',eventId:'event',ordinal:'1',semantic:'FINAL',sourceLayer:'SDK_NATIVE',schemaVersion:'1',adapterVersion:'1',captureVersion:'1',captureCompleteness:'INCOMPLETE',rawState:'JSONB',rawUsage:'{"promptTokenCount":8}',captureReason:null,capturedAt:'2026-11-16T00:00:00Z'};
    vi.stubGlobal('fetch',vi.fn(async (_url:RequestInfo|URL,init?:RequestInit)=>{
      const req=JSON.parse(String(init?.body)) as {query:string;variables:any};requests.push(req);let data:Record<string,unknown>;
      if(req.query.includes('query MyUsageAttempts'))data={myUsageAttempts:{nodes:[attempt()],pageInfo}};
      else if(req.query.includes('query CostReviewAttempts'))data={costReviewAttempts:{nodes:[attempt()],pageInfo}};
      else if(req.query.includes('query PublishedPricingCatalogs'))data={publishedPricingCatalogs:{nodes:[{id:'catalog',generation:'1',sourceReference:'official',sourceRevision:'v1',createdAt:'2026-11-16',entries:[]}],pageInfo}};
      else if(req.query.includes('query CostRevisions'))data={costRevisions:{nodes:[],currentRevisionId:null,pageInfo}};
      else if(req.query.includes('query CostReviewAttemptSnapshots'))data={workspaceUsageAttempt:{...attempt(),snapshots:{nodes:[snapshot],pageInfo}}};
      else if(req.query.includes('mutation ReconcileCost')){applied=true;data={reconcileCost:{outcome:'APPLIED',revision:{id:'revision-new',attemptId:'attempt',kind:'TRUSTED_RECONCILIATION',status:'PARTIAL',knownSubtotal:subtotal,completeAmount:null,currency:'USD',sourceProjectionVersion:'7',sourceSnapshotId:'snapshot',priceSnapshotId:'price',priceRevision:'v1',catalogRevisionId:'catalog',calculatorVersion:'1',sourceType:'SERVER_RAW_CATALOG',evidenceReference:'ticket',reason:'reprice',actorId:'manager',recordedAt:'2026-11-16',reasons:['COVERAGE_UNKNOWN']},effectiveCost:cost()}};}
      else if(req.query.includes('query MyUsageReport'))data={myUsageReport:{workspaceId:'workspace',periodDays:7,from:'2026-11-09',to:'2026-11-16',asOf:'2026-11-16',timeZone:'Asia/Taipei',simulation:false,summary:{status:'PARTIAL',currency:'USD',knownSubtotal:subtotal,completeTotal:null,unknownCount:'1',coverageUnknownCount:'1',recordedAttemptCount:'1',unassignedCount:'0',sharePercent:null},nativeGroups:[],daily:[],attempts:{nodes:[attempt()],pageInfo}}};
      else if(req.query.includes('query MonthlyBudget'))data={workspaceMonthlyBudget:{workspaceId:'workspace',simulation:false,period:'2026-11',periodStart:'2026-11-01',periodEnd:'2026-12-01',enabled:false,capUsd:'10',knownSubtotalUsd:subtotal,terminalUnknownCount:'1',state:'DISABLED',reason:'DISABLED',configVersion:'0',generation:'1'}};
      else if(req.query.includes('BudgetAlerts'))data={workspaceBudgetAlerts:{workspaceId:'workspace',simulation:false,thresholds:[],notices:[]}};
      else throw new Error(`Unexpected request ${req.query}`);return Response.json({data});
    }));
    const fixture=TestBed.createComponent(UsageComponent);fixture.componentRef.setInput('workspaceId','workspace');fixture.componentRef.setInput('manager',true);fixture.detectChanges();const root=fixture.nativeElement as HTMLElement;
    [...root.querySelectorAll<HTMLButtonElement>('nav button')].find(b=>b.textContent?.trim()==='逐次用量')!.click(); fixture.detectChanges();
    const button=(label:string)=>[...root.querySelectorAll('button')].find(b=>b.textContent?.trim()===label)!;
    await vi.waitFor(()=>{fixture.detectChanges();expect(root.textContent).toContain('尚未定價');});const more=root.querySelector<HTMLDetailsElement>('nav details')!;expect(more.open).toBe(false);more.querySelector<HTMLElement>('summary')!.click();expect(more.open).toBe(true);button('費用修訂管理').click();
    await vi.waitFor(()=>{fixture.detectChanges();expect(root.querySelector('app-cost-revision tbody button')).toBeTruthy();});root.querySelector<HTMLButtonElement>('app-cost-revision tbody button')!.click();
    await vi.waitFor(()=>{fixture.detectChanges();expect(root.querySelector('#reconcile-cost-form')).toBeTruthy();});
    const select=root.querySelector<HTMLSelectElement>('#reconcile-catalog')!;select.value='catalog';select.dispatchEvent(new Event('change',{bubbles:true}));
    for(const [selector,value] of [['#reconcile-evidence','ticket'],['#reconcile-reason','reprice']]){const input=root.querySelector<HTMLInputElement>(selector)!;input.value=value;input.dispatchEvent(new Event('input',{bubbles:true}));}
    root.querySelector<HTMLFormElement>('#reconcile-cost-form')!.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));
    await vi.waitFor(()=>{fixture.detectChanges();expect(root.textContent).toContain('APPLIED');});const mutation=requests.find(r=>r.query.includes('mutation ReconcileCost'))!;
    expect(mutation.variables.input).toMatchObject({workspaceId:'workspace',attemptId:'attempt',catalogRevisionId:'catalog',expectedRevision:null,expectedSourceProjectionVersion:'7',evidenceReference:'ticket',reason:'reprice',idempotencyKey:expect.any(String)});
    button('逐次用量').click();fixture.detectChanges();await vi.waitFor(()=>{fixture.detectChanges();expect(root.querySelector('app-cost-explanation')?.textContent).toContain(subtotal);});expect(root.textContent).toContain('完整估算總額未知');
    button('報表').click();await vi.waitFor(()=>{fixture.detectChanges();expect(root.querySelector('app-usage-reports')?.textContent).toContain(subtotal);});expect(root.querySelector('app-usage-reports')?.textContent).toContain('完整總額未知');
    button('工作區月預算').click();await vi.waitFor(()=>{fixture.detectChanges();expect(root.querySelector('app-monthly-budget')?.textContent).toContain(subtotal);});
    expect(root.querySelector('app-monthly-budget')?.textContent).toMatch(/終態未知費用\s*1\s*筆/);
    expect(requests.find(r=>r.query.includes('query CostReviewAttempts'))?.variables).toMatchObject({workspaceId:'workspace',simulation:false});
    expect(requests.find(r=>r.query.includes('query PublishedPricingCatalogs'))?.variables).toMatchObject({workspaceId:'workspace'});
    expect(requests.find(r=>r.query.includes('query CostReviewAttemptSnapshots'))?.variables).toMatchObject({workspaceId:'workspace',id:'attempt'});
    expect(requests.find(r=>r.query.includes('query CostRevisions'))?.variables).toMatchObject({workspaceId:'workspace',attemptId:'attempt'});
    expect(requests.filter(r=>r.query.includes('query MyUsageAttempts')).every(r=>r.variables.workspaceId==='workspace')).toBe(true);
    expect(requests.filter(r=>r.query.includes('query MyUsageAttempts'))).toHaveLength(3);expect(requests.find(r=>r.query.includes('query MyUsageReport'))?.variables).toMatchObject({workspaceId:'workspace',simulation:false});expect(requests.find(r=>r.query.includes('query MonthlyBudget'))?.variables).toMatchObject({workspaceId:'workspace',simulation:false});
  });
  it('offers retry when re-entering the usage list fails', async () => {
    let reads=0;
    vi.stubGlobal('fetch',vi.fn(async (_url:RequestInfo|URL,init?:RequestInit)=>{
      const req=JSON.parse(String(init?.body));
      if(req.query.includes('query MyUsageAttempts')) return ++reads===3 ? new Response('',{status:503}) : Response.json({data:{myUsageAttempts:{nodes:[],pageInfo:{hasNextPage:false,endCursor:null}}}});
      if(req.query.includes('query MonthlyBudget')) return Response.json({data:{workspaceMonthlyBudget:{workspaceId:'workspace',simulation:false,period:'2026-11',periodStart:'2026-11-01',periodEnd:'2026-12-01',enabled:false,capUsd:'10',knownSubtotalUsd:null,terminalUnknownCount:'0',state:'DISABLED',reason:'DISABLED',configVersion:'0',generation:'1'}}});
      return Response.json({data:{}});
    }));
    const f=TestBed.createComponent(UsageComponent);f.componentRef.setInput('workspaceId','workspace');f.detectChanges();const root=f.nativeElement as HTMLElement;
    [...root.querySelectorAll<HTMLButtonElement>('nav button')].find(b=>b.textContent?.trim()==='逐次用量')!.click(); f.detectChanges();
    const button=(label:string)=>[...root.querySelectorAll('button')].find(b=>b.textContent?.trim()===label)!;
    await vi.waitFor(()=>{f.detectChanges();expect(root.textContent).toContain('目前沒有用量紀錄');});button('工作區月預算').click();f.detectChanges();button('逐次用量').click();
    await vi.waitFor(()=>{f.detectChanges();expect(root.querySelector('[role=alert]')?.textContent).toContain('用量載入失敗');});
    root.querySelector<HTMLButtonElement>('[role=alert] button')!.click();await vi.waitFor(()=>{f.detectChanges();expect(root.textContent).toContain('目前沒有用量紀錄');});expect(reads).toBe(4);
  });
  it('does not display a delayed re-entry response after switching workspace', async () => {
    let aReads=0;let finish!:(response:Response)=>void;
    vi.stubGlobal('fetch',vi.fn(async (_url:RequestInfo|URL,init?:RequestInit)=>{
      const req=JSON.parse(String(init?.body));
      if(req.query.includes('query MyUsageAttempts')) {
        if(req.variables.workspaceId==='A' && ++aReads===3)return new Promise<Response>(resolve=>finish=resolve);
        return Response.json({data:{myUsageAttempts:{nodes:[],pageInfo:{hasNextPage:false,endCursor:null}}}});
      }
      if(req.query.includes('query MonthlyBudget'))return Response.json({data:{workspaceMonthlyBudget:{workspaceId:req.variables.workspaceId,simulation:false,period:'2026-11',periodStart:'2026-11-01',periodEnd:'2026-12-01',enabled:false,capUsd:'10',knownSubtotalUsd:null,terminalUnknownCount:'0',state:'DISABLED',reason:'DISABLED',configVersion:'0',generation:'1'}}});
      return Response.json({data:{}});
    }));
    const f=TestBed.createComponent(UsageComponent);f.componentRef.setInput('workspaceId','A');f.detectChanges();const root=f.nativeElement as HTMLElement;
    [...root.querySelectorAll<HTMLButtonElement>('nav button')].find(b=>b.textContent?.trim()==='逐次用量')!.click(); f.detectChanges();
    const button=(label:string)=>[...root.querySelectorAll('button')].find(b=>b.textContent?.trim()===label)!;
    await vi.waitFor(()=>{f.detectChanges();expect(root.textContent).toContain('目前沒有用量紀錄');});button('工作區月預算').click();f.detectChanges();button('逐次用量').click();
    await vi.waitFor(()=>expect(finish).toBeDefined());f.componentRef.setInput('workspaceId','B');f.detectChanges();
    await vi.waitFor(()=>{f.detectChanges();expect(root.textContent).toContain('目前沒有用量紀錄');});
    finish(Response.json({data:{myUsageAttempts:{nodes:[{id:'old',workspaceId:'A',preparedAt:'old',provider:'PRIVATE_A',model:'old'}],pageInfo:{hasNextPage:false,endCursor:null}}}}));await Promise.resolve();await Promise.resolve();f.detectChanges();
    expect(root.textContent).not.toContain('PRIVATE_A');expect(root.textContent).toContain('目前沒有用量紀錄');
  });
});
