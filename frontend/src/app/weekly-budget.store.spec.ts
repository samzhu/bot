import { describe, expect, it, vi } from 'vitest';
import { WeeklyBudget, WeeklyBudgetStore, WeeklyBudgetTransport } from './weekly-budget.store';
const budget:WeeklyBudget = {workspaceId:'w', memberUserId:'u', simulation:false, periodStart:'2026-11-15T16:00:00Z',periodEnd:'2026-11-22T16:00:00Z',timezone:'Asia/Taipei', enabled:true,capUsd:'12.000000000001',knownSubtotalUsd:null,terminalUnknownCount:'1',state:'BLOCKED_UNKNOWN',reason:'TERMINAL_COST_UNKNOWN',configVersion:'1'};
describe('weekly budget store', () => {
  it('loads a manager selected member summary without converting its amount', async () => {
    const read = vi.fn(async () => budget);
    const store = new WeeklyBudgetStore({read,configure:vi.fn()} as WeeklyBudgetTransport);
    await store.setScope('w','u',false);
    expect(read).toHaveBeenCalledWith('w','u',false);
    expect(store.budget()).toEqual(budget);
  });
  it('ignores an earlier member response after selection changes', async () => {
    let finish!:(b:WeeklyBudget)=>void;
    const read = vi.fn((_w:string, user:string|null) => user === 'u' ? new Promise<WeeklyBudget>(r=>finish=r) : Promise.resolve({...budget,memberUserId:'v'}));
    const store = new WeeklyBudgetStore({read,configure:vi.fn()} as WeeklyBudgetTransport);
    const first = store.setScope('w','u',false);
    await store.setScope('w','v',false);
    finish(budget); await first;
    expect(store.budget()?.memberUserId).toBe('v');
  });

  it('submits a precise cap and prevents a second pending submission', async () => {
    let finish!:(value:{outcome:'APPLIED';budget:WeeklyBudget})=>void;
    const configure = vi.fn((_command: import("./weekly-budget.store").WeeklyBudgetCommand) => new Promise<{outcome:'APPLIED';budget:WeeklyBudget}>(r=>finish=r));
    const store = new WeeklyBudgetStore({read:vi.fn(async()=>budget),configure});
    await store.setScope('w','u',false);
    const first=store.submit(true,'0.000000000001');
    await store.submit(true,'0.000000000001');
    expect(configure).toHaveBeenCalledOnce();
    expect(configure.mock.calls[0][0]).toMatchObject({workspaceId:'w',memberUserId:'u',capUsd:'0.000000000001',expectedConfigVersion:'1'});
    finish({outcome:'APPLIED',budget}); await first;
  });

  it('retries an unknown result with the same command and shows current replay', async () => {
    const configure = vi.fn(async (_command:import('./weekly-budget.store').WeeklyBudgetCommand):Promise<{outcome:'REPLAYED';budget:WeeklyBudget}> => { if(configure.mock.calls.length===1) throw new Error('lost'); return {outcome:'REPLAYED',budget:{...budget,capUsd:'30',configVersion:'3'}}; });
    const store=new WeeklyBudgetStore({read:vi.fn(async()=>budget),configure});
    await store.setScope('w','u',false);
    await store.submit(true,'20'); await store.retry();
    expect(configure).toHaveBeenCalledTimes(2);
    expect(configure.mock.calls[1][0]).toEqual(configure.mock.calls[0][0]);
    expect(store.budget()?.capUsd).toBe('30');
  });

  it('rejects invalid caps before submitting and permits zero', async () => {
    const configure=vi.fn(async (_c:import('./weekly-budget.store').WeeklyBudgetCommand)=>({outcome:'APPLIED' as const,budget}));
    const store=new WeeklyBudgetStore({read:vi.fn(async()=>budget),configure});
    await store.setScope('w','u',false);
    for(const cap of [null,'-1','1000000000000000000','0.0000000000001']) await store.submit(true,cap);
    expect(configure).not.toHaveBeenCalled();
    await store.submit(true,'0');
    expect(configure).toHaveBeenCalledOnce();
  });

  it('ignores a pending save when scope changes', async () => {
    let finish!:(v:{outcome:'APPLIED';budget:WeeklyBudget})=>void;
    const configure=vi.fn(()=>new Promise<{outcome:'APPLIED';budget:WeeklyBudget}>(r=>finish=r));
    const store=new WeeklyBudgetStore({read:vi.fn(async (_w:string,u:string|null,simulation:boolean)=>({...budget,memberUserId:u!,simulation})),configure});
    await store.setScope('w','u',false); const pending=store.submit(true,'10');
    await store.setScope('w','v',true);
    finish({outcome:'APPLIED',budget:{...budget,capUsd:'10'}}); await pending;
    expect(store.budget()).toMatchObject({memberUserId:'v',simulation:true});
  });

  it('clears a stale summary when a read loses workspace access', async () => {
    let count=0;
    const read=vi.fn(async()=>{if(++count===1)return budget; throw Object.assign(new Error('denied'),{code:'WORKSPACE_ACCESS_DENIED'});});
    const store=new WeeklyBudgetStore({read,configure:vi.fn()});
    await store.setScope('w','u',false);
    await store.setScope('w','v',false);
    expect(store.budget()).toBeNull();
    expect(store.error()).toContain('存取');
  });

  it('does not display a response for a different member or partition', async()=>{
    const store=new WeeklyBudgetStore({read:vi.fn(async()=>({...budget,memberUserId:'other'})),configure:vi.fn()});
    await store.setScope('w','u',false);
    expect(store.budget()).toBeNull();
    expect(store.error()).toContain('無法讀取');
  });

  it('requires explicit adoption after a configuration conflict', async()=>{
    let reads=0; const commands:import('./weekly-budget.store').WeeklyBudgetCommand[]=[];
    const store=new WeeklyBudgetStore({read:vi.fn(async()=>({...budget,configVersion:++reads===1?'1':'2'})),configure:vi.fn(async c=>{commands.push(c);if(commands.length===1)throw Object.assign(new Error('conflict'),{code:'WEEKLY_BUDGET_CONFIGURATION_CONFLICT'});return {outcome:'APPLIED' as const,budget:{...budget,configVersion:'3'}};})});
    await store.setScope('w','u',false);await store.submit(true,'42');
    expect(store.conflict()).toBe(true); expect(store.budget()?.configVersion).toBe('2');
    await store.submit(true,'42'); expect(commands).toHaveLength(1);
    store.adopt();await store.submit(true,'42');
    expect(commands[1].expectedConfigVersion).toBe('2');expect(commands[1].idempotencyKey).not.toBe(commands[0].idempotencyKey);
  });

  it('clears the summary rather than retrying after manager access is revoked',async()=>{
    const store=new WeeklyBudgetStore({read:vi.fn(async()=>budget),configure:vi.fn(async()=>{throw Object.assign(new Error('denied'),{code:'WORKSPACE_MANAGER_REQUIRED'});})});
    await store.setScope('w','u',false);await store.submit(true,'12');
    expect(store.budget()).toBeNull();expect(store.retryable()).toBe(false);expect(store.error()).toContain('存取');
  });

  it('rejects a save response belonging to another member',async()=>{
    const store=new WeeklyBudgetStore({read:vi.fn(async()=>budget),configure:vi.fn(async()=>({outcome:'APPLIED' as const,budget:{...budget,memberUserId:'other'}}))});
    await store.setScope('w','u',false);await store.submit(true,'5');
    expect(store.budget()?.memberUserId).toBe('u');expect(store.retryable()).toBe(true);
  });

  it('cannot adopt a version when the conflict refresh failed',async()=>{
    let reads=0;const configure=vi.fn(async()=>{throw Object.assign(new Error('conflict'),{code:'WEEKLY_BUDGET_CONFIGURATION_CONFLICT'});});
    const store=new WeeklyBudgetStore({read:vi.fn(async()=>{if(++reads===1)return budget;throw new Error('offline');}),configure});
    await store.setScope('w','u',false);await store.submit(true,'5');store.adopt();await store.submit(true,'5');
    expect(store.conflict()).toBe(true);expect(configure).toHaveBeenCalledOnce();
  });

  it('allows retrying a failed conflict refresh without discarding the draft command',async()=>{
    let reads=0;const store=new WeeklyBudgetStore({read:vi.fn(async()=>{if(++reads===2)throw new Error('offline');return {...budget,configVersion:reads===1?'1':'2'};}),configure:vi.fn(async()=>{throw Object.assign(new Error('conflict'),{code:'WEEKLY_BUDGET_CONFIGURATION_CONFLICT'});})});
    await store.setScope('w','u',false);await store.submit(true,'42');await store.refreshConflict();store.adopt();
    expect(store.conflict()).toBe(false);expect(store.budget()?.configVersion).toBe('2');
  });

  it('clears private summary if conflict refresh loses authorization',async()=>{
    let n=0;const store=new WeeklyBudgetStore({read:vi.fn(async()=>{if(++n===1)return budget;throw Object.assign(new Error('denied'),{code:'WORKSPACE_MANAGER_REQUIRED'});}),configure:vi.fn(async()=>{throw Object.assign(new Error('conflict'),{code:'WEEKLY_BUDGET_CONFIGURATION_CONFLICT'});})});
    await store.setScope('w','u',false);await store.submit(true,'9');
    expect(store.budget()).toBeNull();expect(store.error()).toContain('存取');
  });

});
