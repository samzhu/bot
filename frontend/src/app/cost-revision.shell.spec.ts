import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { UsageComponent } from './usage.component';

describe('manager cost revision entry in the usage shell',()=>{
  afterEach(()=>vi.unstubAllGlobals());

  it('exposes the manager-only entry and uses the real GraphQL transport',async()=>{
    const queries:string[]=[];
    vi.stubGlobal('fetch',vi.fn(async(_input:RequestInfo|URL,init?:RequestInit)=>{
      const body=JSON.parse(String(init?.body??'{}')) as {query:string};queries.push(body.query);
      let data:Record<string,unknown>={};
      if(body.query.includes('myUsageAttempts'))data={myUsageAttempts:{nodes:[],pageInfo:{hasNextPage:false,endCursor:null}}};
      if(body.query.includes('costReviewAttempts'))data={costReviewAttempts:{nodes:[],pageInfo:{hasNextPage:false,endCursor:null}}};
      if(body.query.includes('publishedPricingCatalogs'))data={publishedPricingCatalogs:{nodes:[],pageInfo:{hasNextPage:false,endCursor:null}}};
      return new Response(JSON.stringify({data}),{status:200,headers:{'Content-Type':'application/json'}});
    }));
    TestBed.configureTestingModule({imports:[UsageComponent]});
    const fixture=TestBed.createComponent(UsageComponent);fixture.componentRef.setInput('workspaceId','workspace-a');fixture.componentRef.setInput('manager',true);fixture.detectChanges();
    const el=fixture.nativeElement as HTMLElement;const entry=[...el.querySelectorAll('button')].find(button=>button.textContent?.includes('費用修訂管理'))!;
    expect(entry).toBeTruthy();entry.click();fixture.detectChanges();expect(el.querySelector('app-cost-revision')).toBeTruthy();
    await vi.waitFor(()=>expect(queries.some(query=>query.includes('costReviewAttempts'))).toBe(true));
    expect(queries.some(query=>query.includes('publishedPricingCatalogs'))).toBe(true);expect(el.textContent).not.toContain('受控假資料預覽');
    fixture.destroy();
  });

  it('does not expose manager revision controls to a non-manager',()=>{
    TestBed.configureTestingModule({imports:[UsageComponent]});
    const fixture=TestBed.createComponent(UsageComponent);fixture.componentRef.setInput('workspaceId','workspace-a');fixture.componentRef.setInput('manager',false);fixture.detectChanges();
    const el=fixture.nativeElement as HTMLElement;expect([...el.querySelectorAll('button')].some(button=>button.textContent?.includes('費用修訂管理'))).toBe(false);fixture.destroy();
  });
});
