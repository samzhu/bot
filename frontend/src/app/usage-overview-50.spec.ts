import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { UsageComponent } from './usage.component';

describe('#50 usage overview', () => {
  afterEach(() => vi.unstubAllGlobals());
  it('opens with the monthly known amount, budget status, and a truthful seven-day trend', async () => {
    const requests:{query:string;variables:any}[]=[];
    vi.stubGlobal('fetch',vi.fn(async (_url:RequestInfo|URL,init?:RequestInit)=>{
      const req=JSON.parse(String(init?.body));requests.push(req);
      if(req.query.includes('MonthlyBudget'))return Response.json({data:{workspaceMonthlyBudget:{workspaceId:'workspace',simulation:false,period:'2026-11',periodStart:'2026-11-01',periodEnd:'2026-12-01',enabled:false,capUsd:'10',knownSubtotalUsd:'0.000311250000',terminalUnknownCount:'1',state:'DISABLED',reason:'DISABLED',configVersion:'0',generation:'1'}}});
      if(req.query.includes('UsageReport'))return Response.json({data:{myUsageReport:{workspaceId:'workspace',periodDays:7,from:'2026-11-09',to:'2026-11-16',asOf:'2026-11-16',timeZone:'Asia/Taipei',simulation:false,summary:{status:'PARTIAL',currency:'USD',knownSubtotal:'0.000311250000',completeTotal:null,unknownCount:'1',coverageUnknownCount:'1',recordedAttemptCount:'1',unassignedCount:'0',sharePercent:null},nativeGroups:[],daily:[{day:'2026-11-15',knownSubtotal:'0.000311250000',completeTotal:null,unknownCount:'1',recordedAttemptCount:'1',visualRatioPercent:100},{day:'2026-11-16',knownSubtotal:null,completeTotal:null,unknownCount:'1',recordedAttemptCount:'1',visualRatioPercent:null}],attempts:{nodes:[],pageInfo:{hasNextPage:false,endCursor:null}}}}});
      if(req.query.includes('MyUsageAttempts'))return Response.json({data:{myUsageAttempts:{nodes:[],pageInfo:{hasNextPage:false,endCursor:null}}}});
      throw new Error(`Unexpected request ${req.query}`);
    }));
    const fixture=TestBed.createComponent(UsageComponent);fixture.componentRef.setInput('workspaceId','workspace');fixture.componentRef.setInput('manager',true);fixture.detectChanges();
    const root=fixture.nativeElement as HTMLElement;
    await vi.waitFor(()=>{fixture.detectChanges();expect(root.textContent).toContain('0.000311250000');});
    expect(root.textContent).toContain('完整費用尚未確認');expect(root.textContent).toContain('10');expect(root.textContent).toContain('停用');
    expect(root.querySelectorAll('[data-testid="known-usd-bar"]')).toHaveLength(1);
    expect(requests.find(r=>r.query.includes('MonthlyBudget'))?.variables).toMatchObject({workspaceId:'workspace',simulation:false});
    expect(requests.find(r=>r.query.includes('UsageReport'))?.variables).toMatchObject({workspaceId:'workspace',simulation:false,periodDays:7});
    [...root.querySelectorAll('button')].find(b=>b.textContent?.trim()==='查看紀錄')!.click();await vi.waitFor(()=>{fixture.detectChanges();expect(root.textContent).toContain('目前沒有用量紀錄');});
    const overview=[...root.querySelectorAll('button')].find(b=>b.textContent?.trim()==='概況');expect(overview).toBeTruthy();overview!.click();
    await vi.waitFor(()=>{fixture.detectChanges();expect(root.textContent).toContain('0.000311250000');});
  });
  it('switches the known USD trend to 30 days without changing the monthly summary', async () => {
    const periods:number[]=[];
    vi.stubGlobal('fetch',vi.fn(async (_url:RequestInfo|URL,init?:RequestInit)=>{
      const req=JSON.parse(String(init?.body));
      if(req.query.includes('MonthlyBudget'))return Response.json({data:{workspaceMonthlyBudget:{workspaceId:'workspace',simulation:false,period:'2026-11',periodStart:'2026-11-01',periodEnd:'2026-12-01',enabled:false,capUsd:'10',knownSubtotalUsd:'1.000000000001',terminalUnknownCount:'0',state:'DISABLED',reason:'DISABLED',configVersion:'0',generation:'1'}}});
      if(req.query.includes('UsageReport')){periods.push(req.variables.periodDays);return Response.json({data:{myUsageReport:{workspaceId:'workspace',periodDays:req.variables.periodDays,from:'a',to:'b',asOf:'c',timeZone:'Asia/Taipei',simulation:false,summary:{status:'PARTIAL',currency:'USD',knownSubtotal:'2',completeTotal:null,unknownCount:'1',coverageUnknownCount:'1',recordedAttemptCount:'1',unassignedCount:'0',sharePercent:null},nativeGroups:[],daily:[],attempts:{nodes:[],pageInfo:{hasNextPage:false,endCursor:null}}}}});}
      if(req.query.includes('MyUsageAttempts'))return Response.json({data:{myUsageAttempts:{nodes:[],pageInfo:{hasNextPage:false,endCursor:null}}}});
      throw new Error('unexpected request');
    }));
    const f=TestBed.createComponent(UsageComponent);f.componentRef.setInput('workspaceId','workspace');f.detectChanges();const root=f.nativeElement as HTMLElement;
    await vi.waitFor(()=>{f.detectChanges();expect(root.textContent).toContain('1.000000000001');});
    const change=[...root.querySelectorAll('button')].find(b=>b.textContent?.includes('30 日'));expect(change).toBeTruthy();change!.click();
    await vi.waitFor(()=>expect(periods).toEqual([7,30]));f.detectChanges();expect(root.textContent).toContain('1.000000000001');expect(root.textContent).toContain('完整費用尚未確認');
  });
  it('keeps the trend available when the monthly summary fails and retries it', async () => {
    let budgetReads=0;
    vi.stubGlobal('fetch',vi.fn(async (_url:RequestInfo|URL,init?:RequestInit)=>{
      const req=JSON.parse(String(init?.body));
      if(req.query.includes('MonthlyBudget')){if(++budgetReads===1)return new Response('',{status:503});return Response.json({data:{workspaceMonthlyBudget:{workspaceId:'workspace',simulation:false,period:'2026-11',periodStart:'a',periodEnd:'b',enabled:false,capUsd:'10',knownSubtotalUsd:'1',terminalUnknownCount:'0',state:'DISABLED',reason:'DISABLED',configVersion:'0',generation:'1'}}});}
      if(req.query.includes('UsageReport'))return Response.json({data:{myUsageReport:{workspaceId:'workspace',periodDays:7,from:'a',to:'b',asOf:'c',timeZone:'Asia/Taipei',simulation:false,summary:{status:'EMPTY',currency:'USD',knownSubtotal:null,completeTotal:null,unknownCount:'0',coverageUnknownCount:'0',recordedAttemptCount:'0',unassignedCount:'0',sharePercent:null},nativeGroups:[],daily:[],attempts:{nodes:[],pageInfo:{hasNextPage:false,endCursor:null}}}}});
      return Response.json({data:{myUsageAttempts:{nodes:[],pageInfo:{hasNextPage:false,endCursor:null}}}});
    }));
    const f=TestBed.createComponent(UsageComponent);f.componentRef.setInput('workspaceId','workspace');f.detectChanges();const root=f.nativeElement as HTMLElement;
    await vi.waitFor(()=>{f.detectChanges();expect(root.textContent).toContain('無法讀取本月摘要');expect(root.textContent).toContain('未知費用不當作零');});
    root.querySelector<HTMLButtonElement>('[role=alert] button')!.click();await vi.waitFor(()=>{f.detectChanges();expect(root.textContent).toContain('USD 1');});expect(budgetReads).toBe(2);
  });
  it('does not show a late previous workspace summary after switching', async () => {
    let finish!:(response:Response)=>void; let finishReport!:(response:Response)=>void;
    vi.stubGlobal('fetch',vi.fn(async (_url:RequestInfo|URL,init?:RequestInit)=>{
      const req=JSON.parse(String(init?.body));
      if(req.query.includes('MonthlyBudget')){if(req.variables.workspaceId==='A')return new Promise<Response>(resolve=>finish=resolve);return Response.json({data:{workspaceMonthlyBudget:{workspaceId:'B',simulation:false,period:'2026-11',periodStart:'a',periodEnd:'b',enabled:false,capUsd:'10',knownSubtotalUsd:'2',terminalUnknownCount:'0',state:'DISABLED',reason:'DISABLED',configVersion:'0',generation:'1'}}});}
      if(req.query.includes('UsageReport')){if(req.variables.workspaceId==='A')return new Promise<Response>(resolve=>finishReport=resolve);return Response.json({data:{myUsageReport:{workspaceId:'B',periodDays:7,from:'a',to:'b',asOf:'c',timeZone:'Asia/Taipei',simulation:false,summary:{status:'EMPTY',currency:'USD',knownSubtotal:null,completeTotal:null,unknownCount:'0',coverageUnknownCount:'0',recordedAttemptCount:'0',unassignedCount:'0',sharePercent:null},nativeGroups:[],daily:[],attempts:{nodes:[],pageInfo:{hasNextPage:false,endCursor:null}}}}});}
      return Response.json({data:{myUsageAttempts:{nodes:[],pageInfo:{hasNextPage:false,endCursor:null}}}});
    }));
    const f=TestBed.createComponent(UsageComponent);f.componentRef.setInput('workspaceId','A');f.detectChanges();await vi.waitFor(()=>{expect(finish).toBeDefined();expect(finishReport).toBeDefined();});f.componentRef.setInput('workspaceId','B');f.detectChanges();const root=f.nativeElement as HTMLElement;
    await vi.waitFor(()=>{f.detectChanges();expect(root.textContent).toContain('USD 2');});
    finish(Response.json({data:{workspaceMonthlyBudget:{workspaceId:'A',simulation:false,period:'2026-11',periodStart:'a',periodEnd:'b',enabled:false,capUsd:'10',knownSubtotalUsd:'999',terminalUnknownCount:'0',state:'DISABLED',reason:'DISABLED',configVersion:'0',generation:'1'}}}));finishReport(Response.json({data:{myUsageReport:{workspaceId:'A',periodDays:7,from:'a',to:'b',asOf:'c',timeZone:'Asia/Taipei',simulation:false,summary:{status:'PARTIAL',currency:'USD',knownSubtotal:'999',completeTotal:null,unknownCount:'1',coverageUnknownCount:'1',recordedAttemptCount:'1',unassignedCount:'0',sharePercent:null},nativeGroups:[],daily:[{day:'old-private',knownSubtotal:'999',completeTotal:null,unknownCount:'1',recordedAttemptCount:'1',visualRatioPercent:100}],attempts:{nodes:[],pageInfo:{hasNextPage:false,endCursor:null}}}}}));await Promise.resolve();await Promise.resolve();f.detectChanges();expect(root.textContent).toContain('USD 2');expect(root.textContent).not.toContain('999');
  });
});
