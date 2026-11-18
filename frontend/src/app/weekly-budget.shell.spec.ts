import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { UsageComponent } from './usage.component';
describe('weekly budget usage entry',()=>{
 afterEach(()=>vi.unstubAllGlobals());
 it('does not expose a member weekly budget entry in the MVP',async()=>{
  const request=vi.fn(async()=>Response.json({data:{myUsageAttempts:{nodes:[],pageInfo:{hasNextPage:false,endCursor:null}}}}));
  vi.stubGlobal('fetch',request);
  const f=TestBed.createComponent(UsageComponent);f.componentRef.setInput('workspaceId','w');f.componentRef.setInput('manager',false);f.detectChanges();
  await vi.waitFor(()=>expect(request).toHaveBeenCalled());
  expect(f.nativeElement.querySelector('nav')?.textContent).not.toContain('成員週額度');
  expect(f.nativeElement.querySelector('app-weekly-budget')).toBeNull();
  f.destroy();
 });
});
