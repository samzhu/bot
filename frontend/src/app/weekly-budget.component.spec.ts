import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { WeeklyBudgetComponent } from './weekly-budget.component';
import { WeeklyBudget, WeeklyBudgetTransport } from './weekly-budget.store';
const budget:WeeklyBudget={workspaceId:'w',memberUserId:'self',simulation:false,periodStart:'start',periodEnd:'end',timezone:'Asia/Taipei',enabled:true,capUsd:'5.000000000001',knownSubtotalUsd:null,terminalUnknownCount:'1',state:'BLOCKED_UNKNOWN',reason:'TERMINAL_COST_UNKNOWN',configVersion:'1'};
describe('weekly budget component',()=>{
  it('shows self unknown reason and recovery guidance without an editor',async()=>{
    const transport:WeeklyBudgetTransport={read:vi.fn(async()=>budget),configure:vi.fn()};
    const f=TestBed.createComponent(WeeklyBudgetComponent);
    f.componentRef.setInput('workspaceId','w'); f.componentRef.setInput('manager',false); f.componentRef.setInput('transport',transport);
    f.detectChanges(); await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true)); f.detectChanges();
    expect(f.nativeElement.textContent).toContain('5.000000000001');
    expect(f.nativeElement.textContent).toContain('管理者');
    expect(f.nativeElement.querySelector('form')).toBeNull();
    expect(transport.read).toHaveBeenCalledWith('w',null,false);
  });
  it('allows a manager to edit the selected user with accessible validation',async()=>{
    const transport:WeeklyBudgetTransport={read:vi.fn(async()=>budget),configure:vi.fn()};
    const f=TestBed.createComponent(WeeklyBudgetComponent);
    f.componentRef.setInput('workspaceId','w'); f.componentRef.setInput('manager',true); f.componentRef.setInput('memberUserId','self'); f.componentRef.setInput('transport',transport);
    f.detectChanges(); await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true)); f.detectChanges();
    const input=f.nativeElement.querySelector('#weekly-cap') as HTMLInputElement;
    expect(input.labels?.[0]?.textContent).toContain('上限');
    input.value='-1'; input.dispatchEvent(new Event('input')); input.dispatchEvent(new Event('blur')); f.detectChanges();
    expect(input.getAttribute('aria-invalid')).toBe('true');
    f.nativeElement.querySelector('form').dispatchEvent(new Event('submit',{cancelable:true}));
    expect(document.activeElement).toBe(input);
    expect(transport.configure).not.toHaveBeenCalled();
  });

  it('prevents repeated valid form saves while the first is pending',async()=>{
    let finish!:(value:{outcome:'APPLIED';budget:WeeklyBudget})=>void;
    const configure=vi.fn(()=>new Promise<{outcome:'APPLIED';budget:WeeklyBudget}>(r=>finish=r));
    const transport:WeeklyBudgetTransport={read:vi.fn(async()=>budget),configure};
    const f=TestBed.createComponent(WeeklyBudgetComponent);
    f.componentRef.setInput('workspaceId','w');f.componentRef.setInput('manager',true);f.componentRef.setInput('memberUserId','self');f.componentRef.setInput('transport',transport);
    f.detectChanges();await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true));f.detectChanges();
    const form=f.nativeElement.querySelector('form');
    form.dispatchEvent(new Event('submit',{cancelable:true}));f.detectChanges();
    expect((form.querySelector('button[type=submit]') as HTMLButtonElement).disabled).toBe(true);
    expect((form.querySelector('#weekly-cap') as HTMLInputElement).disabled).toBe(true);
    expect((form.querySelector('input[type=checkbox]') as HTMLInputElement).disabled).toBe(true);
    form.dispatchEvent(new Event('submit',{cancelable:true}));
    expect(configure).toHaveBeenCalledOnce();finish({outcome:'APPLIED',budget});await f.whenStable();
  });

  it('switches to simulated data and passes that partition to cost review',async()=>{
    const transport:WeeklyBudgetTransport={read:vi.fn(async (_w,_u,simulation)=>({...budget,simulation})),configure:vi.fn()};
    const f=TestBed.createComponent(WeeklyBudgetComponent); const review=vi.fn(); f.componentInstance.reviewCosts.subscribe(review);
    f.componentRef.setInput('workspaceId','w');f.componentRef.setInput('manager',true);f.componentRef.setInput('memberUserId','self');f.componentRef.setInput('transport',transport);
    f.detectChanges();await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true));f.detectChanges();
    (f.nativeElement.querySelector('input[value=simulation]') as HTMLInputElement).click();await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true));f.detectChanges();
    expect(transport.read).toHaveBeenLastCalledWith('w','self',true);
    [...f.nativeElement.querySelectorAll('button')].find((x:any)=>x.textContent.includes('費用修訂'))!.click();
    expect(review).toHaveBeenCalledWith(true);
  });

  it('retains the draft through conflict and requires adopting the server version',async()=>{
    let reads=0;const configure=vi.fn(async()=>{throw Object.assign(new Error('conflict'),{code:'WEEKLY_BUDGET_CONFIGURATION_CONFLICT'});});
    const transport:WeeklyBudgetTransport={read:vi.fn(async()=>({...budget,configVersion:++reads===1?'1':'2'})),configure};
    const f=TestBed.createComponent(WeeklyBudgetComponent);f.componentRef.setInput('workspaceId','w');f.componentRef.setInput('manager',true);f.componentRef.setInput('memberUserId','self');f.componentRef.setInput('transport',transport);
    f.detectChanges();await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true));f.detectChanges();
    const input=f.nativeElement.querySelector('#weekly-cap') as HTMLInputElement;input.value='42';input.dispatchEvent(new Event('input'));f.detectChanges();expect(f.componentInstance.cap()).toBe('42');
    f.nativeElement.querySelector('form').dispatchEvent(new Event('submit',{cancelable:true}));await vi.waitFor(()=>expect(f.componentInstance.store?.saving()).toBe(false));f.detectChanges();
    expect((f.nativeElement.querySelector('#weekly-cap') as HTMLInputElement).value).toBe('42');expect(f.nativeElement.textContent).toContain('目前版本');
    expect((f.nativeElement.querySelector('button[type=submit]') as HTMLButtonElement).disabled).toBe(true);
    [...f.nativeElement.querySelectorAll('button')].find((b:any)=>b.textContent.includes('採用'))!.click();f.detectChanges();
    expect((f.nativeElement.querySelector('#weekly-cap') as HTMLInputElement).value).toBe('42');expect((f.nativeElement.querySelector('button[type=submit]') as HTMLButtonElement).disabled).toBe(false);
  });

  it('offers a same-command retry and displays the replayed current value',async()=>{
    let calls=0;const configure=vi.fn(async()=>{if(++calls===1)throw new Error('lost');return {outcome:'REPLAYED' as const,budget:{...budget,capUsd:'30'}};});
    const transport:WeeklyBudgetTransport={read:vi.fn(async()=>budget),configure};
    const f=TestBed.createComponent(WeeklyBudgetComponent);f.componentRef.setInput('workspaceId','w');f.componentRef.setInput('manager',true);f.componentRef.setInput('memberUserId','self');f.componentRef.setInput('transport',transport);
    f.detectChanges();await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true));f.detectChanges();
    f.nativeElement.querySelector('form').dispatchEvent(new Event('submit',{cancelable:true}));await vi.waitFor(()=>expect(f.componentInstance.store?.retryable()).toBe(true));f.detectChanges();
    [...f.nativeElement.querySelectorAll('button')].find((b:any)=>b.textContent.includes('相同命令'))!.click();
    await vi.waitFor(()=>expect(f.componentInstance.store?.budget()?.capUsd).toBe('30'));f.detectChanges();
    expect((f.nativeElement.querySelector('#weekly-cap') as HTMLInputElement).value).toBe('30');
  });

  it('loads additional active members and selects by userId',async()=>{
    const members=vi.fn(async (_w:string,after:string|null)=>after===null?{nodes:[{userId:'u1',role:'MANAGER'}],pageInfo:{hasNextPage:true,endCursor:'c1'}}:{nodes:[{userId:'u2',role:'MEMBER'}],pageInfo:{hasNextPage:false,endCursor:null}});
    const transport:WeeklyBudgetTransport={members,read:vi.fn(async (_w,u)=>({...budget,memberUserId:u!})),configure:vi.fn()};
    const f=TestBed.createComponent(WeeklyBudgetComponent);f.componentRef.setInput('workspaceId','w');f.componentRef.setInput('manager',true);f.componentRef.setInput('transport',transport);
    f.detectChanges();await vi.waitFor(()=>expect(f.nativeElement.textContent).toContain('u1'));
    [...f.nativeElement.querySelectorAll('button')].find((b:any)=>b.textContent.includes('更多成員'))!.click();
    await vi.waitFor(()=>expect(f.nativeElement.textContent).toContain('u2'));
    const select=f.nativeElement.querySelector('select') as HTMLSelectElement;select.value='u2';select.dispatchEvent(new Event('change'));
    await vi.waitFor(()=>expect(transport.read).toHaveBeenLastCalledWith('w','u2',false));
    expect(members).toHaveBeenNthCalledWith(2,'w','c1');
  });

  it('ignores an old directory page and loads the new workspace after switching',async()=>{
    let finish!:(v:import('./weekly-budget.store').MemberPage)=>void;
    const members=vi.fn((w:string)=>w==='old'?new Promise<import('./weekly-budget.store').MemberPage>(r=>finish=r):Promise.resolve({nodes:[{userId:'new-user',role:'MEMBER'}],pageInfo:{hasNextPage:false,endCursor:null}}));
    const f=TestBed.createComponent(WeeklyBudgetComponent);f.componentRef.setInput('workspaceId','old');f.componentRef.setInput('manager',true);f.componentRef.setInput('transport',{members,read:vi.fn(),configure:vi.fn()});f.detectChanges();
    f.componentRef.setInput('workspaceId','new');f.detectChanges();
    await vi.waitFor(()=>expect(f.nativeElement.textContent).toContain('new-user'));
    finish({nodes:[{userId:'old-user',role:'MEMBER'}],pageInfo:{hasNextPage:false,endCursor:null}});await Promise.resolve();f.detectChanges();
    expect(f.nativeElement.textContent).not.toContain('old-user');
  });

  it('clears member options when directory access is revoked on a later page',async()=>{
    const members=vi.fn(async (_w:string,after:string|null)=>{if(after)throw Object.assign(new Error('denied'),{code:'WORKSPACE_MANAGER_REQUIRED'});return {nodes:[{userId:'private-user',role:'MEMBER'}],pageInfo:{hasNextPage:true,endCursor:'c'}};});
    const f=TestBed.createComponent(WeeklyBudgetComponent);f.componentRef.setInput('workspaceId','w');f.componentRef.setInput('manager',true);f.componentRef.setInput('transport',{members,read:vi.fn(),configure:vi.fn()});f.detectChanges();
    await vi.waitFor(()=>expect(f.nativeElement.textContent).toContain('private-user'));
    [...f.nativeElement.querySelectorAll('button')].find((b:any)=>b.textContent.includes('更多成員'))!.click();
    await vi.waitFor(()=>expect(f.nativeElement.textContent).toContain('存取權'));
    expect(f.nativeElement.textContent).not.toContain('private-user');
  });

  it('focuses the server validation error after a failed submit',async()=>{
    const transport:WeeklyBudgetTransport={read:vi.fn(async()=>budget),configure:vi.fn(async()=>{throw Object.assign(new Error('invalid'),{code:'INVALID_WEEKLY_BUDGET_CONFIGURATION'});})};
    const f=TestBed.createComponent(WeeklyBudgetComponent);f.componentRef.setInput('workspaceId','w');f.componentRef.setInput('manager',true);f.componentRef.setInput('memberUserId','self');f.componentRef.setInput('transport',transport);f.detectChanges();
    await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true));f.detectChanges();
    f.nativeElement.querySelector('form').dispatchEvent(new Event('submit',{cancelable:true}));
    await vi.waitFor(()=>expect(f.componentInstance.store?.error()).toContain('伺服器'));f.detectChanges();
    await vi.waitFor(()=>expect(document.activeElement?.getAttribute('role')).toBe('alert'));
  });

  it('explains a reached cap without claiming running work was cancelled',async()=>{
    const f=TestBed.createComponent(WeeklyBudgetComponent);f.componentRef.setInput('workspaceId','w');f.componentRef.setInput('transport',{read:vi.fn(async()=>({...budget,state:'BLOCKED_CAP',reason:'CAP_REACHED'})),configure:vi.fn()});f.detectChanges();
    await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true));f.detectChanges();
    expect(f.nativeElement.textContent).toContain('已達上限');expect(f.nativeElement.textContent).toContain('不額外取消');
  });

  it('does not overwrite a new member draft when an old save resolves',async()=>{
    let finish!:(v:{outcome:'APPLIED';budget:WeeklyBudget})=>void;
    const transport:WeeklyBudgetTransport={read:vi.fn(async (_w,u)=>({...budget,memberUserId:u!})),configure:vi.fn(()=>new Promise<{outcome:'APPLIED';budget:WeeklyBudget}>(r=>finish=r))};
    const f=TestBed.createComponent(WeeklyBudgetComponent);f.componentRef.setInput('workspaceId','w');f.componentRef.setInput('manager',true);f.componentRef.setInput('memberUserId','self');f.componentRef.setInput('transport',transport);f.detectChanges();
    await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true));f.detectChanges();
    f.nativeElement.querySelector('form').dispatchEvent(new Event('submit',{cancelable:true}));
    f.componentRef.setInput('memberUserId','other');f.detectChanges();await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true));f.detectChanges();
    const input=f.nativeElement.querySelector('#weekly-cap') as HTMLInputElement;input.value='44';input.dispatchEvent(new Event('input'));f.detectChanges();
    finish({outcome:'APPLIED',budget});await new Promise(r=>setTimeout(r,0));f.detectChanges();
    expect((f.nativeElement.querySelector('#weekly-cap') as HTMLInputElement).value).toBe('44');
  });

  it('lets a member retry a failed summary read',async()=>{
    let n=0;const read=vi.fn(async()=>{if(++n===1)throw new Error('offline');return budget;});
    const f=TestBed.createComponent(WeeklyBudgetComponent);f.componentRef.setInput('workspaceId','w');f.componentRef.setInput('transport',{read,configure:vi.fn()});f.detectChanges();
    await vi.waitFor(()=>expect(f.nativeElement.textContent).toContain('無法讀取週額度'));
    [...f.nativeElement.querySelectorAll('button')].find((b:any)=>b.textContent.includes('重新讀取週額度'))!.click();
    await vi.waitFor(()=>expect(f.nativeElement.textContent).toContain('5.000000000001'));
  });

  it('retains an unknown command across member and partition switches and retries the same key',async()=>{
    const commands:import('./weekly-budget.store').WeeklyBudgetCommand[]=[];
    const transport:WeeklyBudgetTransport={read:vi.fn(async (w,u,simulation)=>({...budget,workspaceId:w,memberUserId:u!,simulation})),configure:vi.fn(async c=>{commands.push(c);if(commands.length===1)throw new Error('lost');return {outcome:'REPLAYED' as const,budget:{...budget,workspaceId:c.workspaceId,memberUserId:c.memberUserId,simulation:c.simulation,capUsd:'21'}};})};
    const f=TestBed.createComponent(WeeklyBudgetComponent);f.componentRef.setInput('workspaceId','w');f.componentRef.setInput('manager',true);f.componentRef.setInput('memberUserId','self');f.componentRef.setInput('transport',transport);f.detectChanges();
    await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true));f.detectChanges();
    const input=f.nativeElement.querySelector('#weekly-cap') as HTMLInputElement;input.value='21';input.dispatchEvent(new Event('input'));
    f.nativeElement.querySelector('form').dispatchEvent(new Event('submit',{cancelable:true}));await vi.waitFor(()=>expect(f.componentInstance.store?.retryable()).toBe(true));
    f.componentRef.setInput('memberUserId','other');f.detectChanges();await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true));
    f.componentInstance.partition(true);await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true));
    f.componentRef.setInput('memberUserId','self');f.detectChanges();await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true));
    f.componentInstance.partition(false);await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true));f.detectChanges();
    expect((f.nativeElement.querySelector('#weekly-cap') as HTMLInputElement).value).toBe('21');
    const retry=[...f.nativeElement.querySelectorAll('button')].find((b:any)=>b.textContent.includes('相同命令')) as HTMLButtonElement;expect(retry).toBeTruthy();retry.click();
    await vi.waitFor(()=>expect(commands).toHaveLength(2));expect(commands[1]).toEqual(commands[0]);
  });

  it('preserves an in-flight command across workspace change and component recreation',async()=>{
    let finish!:(v:never)=>void;const commands:import('./weekly-budget.store').WeeklyBudgetCommand[]=[];
    const transport:WeeklyBudgetTransport={read:vi.fn(async(w,u,simulation)=>({...budget,workspaceId:w,memberUserId:u!,simulation})),configure:vi.fn(c=>{commands.push(c);if(commands.length===1)return new Promise<{outcome:'APPLIED'|'REPLAYED';budget:WeeklyBudget}>((_r,reject)=>finish=reject);return Promise.resolve({outcome:'REPLAYED' as const,budget:{...budget,workspaceId:c.workspaceId,memberUserId:c.memberUserId,simulation:c.simulation,capUsd:'33'}});})};
    const make=()=>{const f=TestBed.createComponent(WeeklyBudgetComponent);f.componentRef.setInput('workspaceId','w');f.componentRef.setInput('manager',true);f.componentRef.setInput('memberUserId','self');f.componentRef.setInput('transport',transport);f.detectChanges();return f;};
    const f=make();await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true));f.detectChanges();const input=f.nativeElement.querySelector('#weekly-cap') as HTMLInputElement;input.value='33';input.dispatchEvent(new Event('input'));
    f.nativeElement.querySelector('form').dispatchEvent(new Event('submit',{cancelable:true}));await vi.waitFor(()=>expect(commands).toHaveLength(1));
    f.componentRef.setInput('workspaceId','other');f.detectChanges();await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true));f.destroy();
    finish(new Error('lost') as never);await new Promise(r=>setTimeout(r,0));
    const again=make();await vi.waitFor(()=>expect(again.componentInstance.ready()).toBe(true));again.detectChanges();
    expect((again.nativeElement.querySelector('#weekly-cap') as HTMLInputElement).value).toBe('33');
    const retry=[...again.nativeElement.querySelectorAll('button')].find((b:any)=>b.textContent.includes('相同命令')) as HTMLButtonElement;expect(retry).toBeTruthy();retry.click();await vi.waitFor(()=>expect(commands).toHaveLength(2));expect(commands[1]).toEqual(commands[0]);again.destroy();
  });

  it('rechecks access before displaying a retained unknown command after returning',async()=>{
    let reads=0;const transport:WeeklyBudgetTransport={read:vi.fn(async(w,u,simulation)=>{if(++reads===2)throw Object.assign(new Error('denied'),{code:'WORKSPACE_MANAGER_REQUIRED'});return {...budget,workspaceId:w,memberUserId:u!,simulation};}),configure:vi.fn(async()=>{throw new Error('lost');})};
    const f=TestBed.createComponent(WeeklyBudgetComponent);f.componentRef.setInput('workspaceId','w');f.componentRef.setInput('manager',true);f.componentRef.setInput('memberUserId','self');f.componentRef.setInput('transport',transport);f.detectChanges();await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true));f.detectChanges();
    f.nativeElement.querySelector('form').dispatchEvent(new Event('submit',{cancelable:true}));await vi.waitFor(()=>expect(f.componentInstance.store?.retryable()).toBe(true));f.destroy();
    const next=TestBed.createComponent(WeeklyBudgetComponent);next.componentRef.setInput('workspaceId','w');next.componentRef.setInput('manager',true);next.componentRef.setInput('memberUserId','self');next.componentRef.setInput('transport',transport);next.detectChanges();await vi.waitFor(()=>expect(next.componentInstance.ready()).toBe(true));next.detectChanges();
    expect(next.nativeElement.querySelector('form')).toBeNull();expect(next.nativeElement.textContent).toContain('存取');next.destroy();
  });

  it('retains the unresolved command if the directory later revokes access',async()=>{
    let directoryCalls=0;const commands:import('./weekly-budget.store').WeeklyBudgetCommand[]=[];
    const transport:WeeklyBudgetTransport={members:vi.fn(async()=>{if(++directoryCalls===2)throw Object.assign(new Error('denied'),{code:'WORKSPACE_MANAGER_REQUIRED'});return {nodes:[{userId:'self',role:'MEMBER'}],pageInfo:{hasNextPage:true,endCursor:'c'}};}),read:vi.fn(async(w,u,simulation)=>({...budget,workspaceId:w,memberUserId:u!,simulation})),configure:vi.fn(async c=>{commands.push(c);if(commands.length===1)throw new Error('lost');return {outcome:'REPLAYED' as const,budget:{...budget,capUsd:'24'}};})};
    const f=TestBed.createComponent(WeeklyBudgetComponent);f.componentRef.setInput('workspaceId','w');f.componentRef.setInput('manager',true);f.componentRef.setInput('transport',transport);f.detectChanges();await vi.waitFor(()=>expect(f.componentInstance.members().length).toBe(1));f.componentInstance.selectMember('self');await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true));f.detectChanges();
    const input=f.nativeElement.querySelector('#weekly-cap') as HTMLInputElement;input.value='24';input.dispatchEvent(new Event('input'));f.nativeElement.querySelector('form').dispatchEvent(new Event('submit',{cancelable:true}));await vi.waitFor(()=>expect(f.componentInstance.store?.retryable()).toBe(true));
    await f.componentInstance.loadMembers(true);await f.componentInstance.loadMembers(false);f.componentInstance.selectMember('self');await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true));f.detectChanges();
    expect((f.nativeElement.querySelector('#weekly-cap') as HTMLInputElement).value).toBe('24');const retry=[...f.nativeElement.querySelectorAll('button')].find((b:any)=>b.textContent.includes('相同命令')) as HTMLButtonElement;expect(retry).toBeTruthy();retry.click();await vi.waitFor(()=>expect(commands).toHaveLength(2));expect(commands[1]).toEqual(commands[0]);
  });

  it('ignores a stale authorization failure when returning twice to a pending scope',async()=>{
    let aReads=0;let rejectOld!:(e:Error)=>void;
    const transport:WeeklyBudgetTransport={read:vi.fn(async(w,u,simulation)=>{if(u==='self' && ++aReads===2)return new Promise<WeeklyBudget>((_r,reject)=>rejectOld=reject);return {...budget,workspaceId:w,memberUserId:u!,simulation};}),configure:vi.fn(async()=>{throw new Error('lost');})};
    const f=TestBed.createComponent(WeeklyBudgetComponent);f.componentRef.setInput('workspaceId','w');f.componentRef.setInput('manager',true);f.componentRef.setInput('memberUserId','self');f.componentRef.setInput('transport',transport);f.detectChanges();await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true));f.detectChanges();
    f.nativeElement.querySelector('form').dispatchEvent(new Event('submit',{cancelable:true}));await vi.waitFor(()=>expect(f.componentInstance.store?.retryable()).toBe(true));
    f.componentRef.setInput('memberUserId','other');f.detectChanges();await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true));
    f.componentRef.setInput('memberUserId','self');f.detectChanges();await vi.waitFor(()=>expect(rejectOld).toBeDefined());
    f.componentRef.setInput('memberUserId','other');f.detectChanges();await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true));
    f.componentRef.setInput('memberUserId','self');f.detectChanges();await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true));
    rejectOld(Object.assign(new Error('denied'),{code:'WORKSPACE_MANAGER_REQUIRED'}));await new Promise(r=>setTimeout(r,0));f.detectChanges();
    expect(f.nativeElement.querySelector('form')).not.toBeNull();expect(f.nativeElement.textContent).not.toContain('存取權已變更');
  });

  it('keeps an unknown command when retry is denied and uses it after access returns',async()=>{
    const commands:import('./weekly-budget.store').WeeklyBudgetCommand[]=[];
    const transport:WeeklyBudgetTransport={read:vi.fn(async(w,u,simulation)=>({...budget,workspaceId:w,memberUserId:u!,simulation})),configure:vi.fn(async c=>{commands.push(c);if(commands.length===1)throw new Error('lost reply');if(commands.length===2)throw Object.assign(new Error('denied'),{code:'WORKSPACE_MANAGER_REQUIRED'});return {outcome:'REPLAYED' as const,budget:{...budget,capUsd:'17'}};})};
    const f=TestBed.createComponent(WeeklyBudgetComponent);f.componentRef.setInput('workspaceId','w');f.componentRef.setInput('manager',true);f.componentRef.setInput('memberUserId','self');f.componentRef.setInput('transport',transport);f.detectChanges();await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true));f.detectChanges();
    const input=f.nativeElement.querySelector('#weekly-cap') as HTMLInputElement;input.value='17';input.dispatchEvent(new Event('input'));f.nativeElement.querySelector('form').dispatchEvent(new Event('submit',{cancelable:true}));await vi.waitFor(()=>expect(f.componentInstance.store?.retryable()).toBe(true));f.detectChanges();
    const retry=()=>[...f.nativeElement.querySelectorAll('button')].find((b:any)=>b.textContent.includes('相同命令')) as HTMLButtonElement;
    retry().click();await vi.waitFor(()=>expect(commands).toHaveLength(2));await vi.waitFor(()=>expect(f.componentInstance.store?.saving()).toBe(false));f.detectChanges();
    expect(f.nativeElement.querySelector('form')).toBeNull();expect(f.nativeElement.textContent).toContain('存取權');
    f.componentRef.setInput('workspaceId','other');f.detectChanges();await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true));
    f.componentRef.setInput('workspaceId','w');f.detectChanges();await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true));f.detectChanges();
    expect((f.nativeElement.querySelector('#weekly-cap') as HTMLInputElement).value).toBe('17');expect(retry()).toBeTruthy();retry().click();await vi.waitFor(()=>expect(commands).toHaveLength(3));expect(commands[1]).toEqual(commands[0]);expect(commands[2]).toEqual(commands[0]);
  });

  it('does not reveal a pending scope when an older authorized read arrives after retry denial',async()=>{
    let reads=0;let releaseRead!:(b:WeeklyBudget)=>void;let denyRetry!:(e:Error)=>void;const commands:import('./weekly-budget.store').WeeklyBudgetCommand[]=[];
    const transport:WeeklyBudgetTransport={read:vi.fn(async(w,u,simulation)=>{const b={...budget,workspaceId:w,memberUserId:u!,simulation};if(++reads===2)return new Promise<WeeklyBudget>(r=>releaseRead=r);return b;}),configure:vi.fn(c=>{commands.push(c);if(commands.length===1)return Promise.reject(new Error('lost'));if(commands.length===2)return new Promise<{outcome:'APPLIED'|'REPLAYED';budget:WeeklyBudget}>((_r,reject)=>denyRetry=reject);return Promise.resolve({outcome:'REPLAYED' as const,budget:{...budget,capUsd:'19'}});})};
    const make=()=>{const f=TestBed.createComponent(WeeklyBudgetComponent);f.componentRef.setInput('workspaceId','w');f.componentRef.setInput('manager',true);f.componentRef.setInput('memberUserId','self');f.componentRef.setInput('transport',transport);f.detectChanges();return f;};
    const f=make();await vi.waitFor(()=>expect(f.componentInstance.ready()).toBe(true));f.detectChanges();const input=f.nativeElement.querySelector('#weekly-cap') as HTMLInputElement;input.value='19';input.dispatchEvent(new Event('input'));f.nativeElement.querySelector('form').dispatchEvent(new Event('submit',{cancelable:true}));await vi.waitFor(()=>expect(f.componentInstance.store?.retryable()).toBe(true));f.detectChanges();
    ([...f.nativeElement.querySelectorAll('button')].find((b:any)=>b.textContent.includes('相同命令')) as HTMLButtonElement).click();await vi.waitFor(()=>expect(denyRetry).toBeDefined());f.destroy();
    const next=make();await vi.waitFor(()=>expect(releaseRead).toBeDefined());denyRetry(Object.assign(new Error('denied'),{code:'WORKSPACE_MANAGER_REQUIRED'}));await new Promise(r=>setTimeout(r,0));releaseRead(budget);await vi.waitFor(()=>expect(next.componentInstance.ready()).toBe(true));next.detectChanges();
    expect(next.nativeElement.querySelector('form')).toBeNull();expect(next.nativeElement.textContent).not.toContain('5.000000000001');
    ([...next.nativeElement.querySelectorAll('button')].find((b:any)=>b.textContent.includes('重新讀取週額度')) as HTMLButtonElement).click();await vi.waitFor(()=>expect(next.componentInstance.ready()).toBe(true));next.detectChanges();
    expect((next.nativeElement.querySelector('#weekly-cap') as HTMLInputElement).value).toBe('19');([ ...next.nativeElement.querySelectorAll('button')].find((b:any)=>b.textContent.includes('相同命令')) as HTMLButtonElement).click();await vi.waitFor(()=>expect(commands).toHaveLength(3));expect(commands[1]).toEqual(commands[0]);expect(commands[2]).toEqual(commands[0]);next.destroy();
  });

});
