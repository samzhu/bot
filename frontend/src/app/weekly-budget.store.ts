import { Injectable, signal } from '@angular/core';

const fields = 'workspaceId memberUserId simulation periodStart periodEnd timezone enabled capUsd knownSubtotalUsd terminalUnknownCount state reason configVersion';
export class GraphQLWeeklyBudgetTransport {
  constructor(private request: typeof fetch = (...args) => globalThis.fetch(...args)) {}
  async members(workspaceId:string, after:string|null):Promise<MemberPage> {
    const response=await this.request('/graphql',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({query:'query WorkspaceActiveMembers($workspaceId:ID!,$first:Int,$after:String){workspaceActiveMembers(workspaceId:$workspaceId,first:$first,after:$after){nodes{userId role} pageInfo{hasNextPage endCursor}}}',variables:{workspaceId,first:20,after}})});
    if(!response.ok)throw new Error('HTTP error'); const payload=await response.json();
    if(payload.errors?.length)throw Object.assign(new Error(payload.errors[0].message),{code:payload.errors[0].extensions?.code});
    return payload.data.workspaceActiveMembers;
  }
  async configure(input: object): Promise<{outcome:'APPLIED'|'REPLAYED'; budget:WeeklyBudget}> {
    const response = await this.request('/graphql', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({query:`mutation ConfigureMemberWeeklyBudget($input:ConfigureMemberWeeklyBudgetInput!){configureMemberWeeklyBudget(input:$input){outcome budget{${fields}}}}`, variables:{input}})});
    if (!response.ok) throw new Error('HTTP error');
    const payload = await response.json();
    if (payload.errors?.length) throw Object.assign(new Error(payload.errors[0].message), {code:payload.errors[0].extensions?.code});
    return payload.data.configureMemberWeeklyBudget;
  }
  async read(workspaceId: string, memberUserId: string | null, simulation: boolean): Promise<WeeklyBudget> {
    const query = memberUserId === null
      ? `query MyMemberWeeklyBudget($workspaceId:ID!,$simulation:Boolean!){myMemberWeeklyBudget(workspaceId:$workspaceId,simulation:$simulation){${fields}}}`
      : `query MemberWeeklyBudget($workspaceId:ID!,$memberUserId:ID!,$simulation:Boolean!){memberWeeklyBudget(workspaceId:$workspaceId,memberUserId:$memberUserId,simulation:$simulation){${fields}}}`;
    const variables = memberUserId === null ? {workspaceId, simulation} : {workspaceId, memberUserId, simulation};
    const response = await this.request('/graphql', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({query, variables})});
    if (!response.ok) throw new Error("HTTP error");
    const payload = await response.json();
    if (payload.errors?.length) throw Object.assign(new Error(payload.errors[0].message), {code: payload.errors[0].extensions?.code});
    return memberUserId === null ? payload.data.myMemberWeeklyBudget : payload.data.memberWeeklyBudget;
  }
}

export interface MemberPage { nodes:{userId:string;role:string}[]; pageInfo:{hasNextPage:boolean;endCursor:string|null}; }
export interface WeeklyBudget { workspaceId:string; memberUserId:string; simulation:boolean; periodStart:string; periodEnd:string; timezone:string; enabled:boolean; capUsd:string|null; knownSubtotalUsd:string|null; terminalUnknownCount:string; state:string; reason:string; configVersion:string; }
export interface WeeklyBudgetCommand { workspaceId:string; memberUserId:string; simulation:boolean; enabled:boolean; capUsd:string|null; expectedConfigVersion:string; idempotencyKey:string; }
export interface WeeklyBudgetTransport { members?(workspaceId:string, after:string|null):Promise<MemberPage>; read(workspaceId:string, memberUserId:string|null, simulation:boolean):Promise<WeeklyBudget>; configure(input:WeeklyBudgetCommand):Promise<{outcome:'APPLIED'|'REPLAYED'; budget:WeeklyBudget}>; }

export class WeeklyBudgetStore {
  readonly budget = signal<WeeklyBudget|null>(null);
  readonly error=signal(''); readonly conflict=signal(false); private accessGeneration=0;
  async refreshConflict():Promise<void> { const current=this.budget(); if(!this.conflict() || !current || this.saving())return;const generation=this.generation;try {const b=await this.api.read(current.workspaceId,current.memberUserId,current.simulation);if(generation!==this.generation)return;if(b.workspaceId!==current.workspaceId||b.memberUserId!==current.memberUserId||b.simulation!==current.simulation)throw new Error('scope mismatch');this.budget.set(b);this.conflictRefreshReady=true;this.error.set('設定已變更；草稿已保留。');}catch (e) {if(generation===this.generation)this.handleRefreshError(e);}}
  private handleRefreshError(error:unknown):void { const code=(error as {code?:string})?.code; if(['WORKSPACE_ACCESS_DENIED','WORKSPACE_MANAGER_REQUIRED','MEMBER_ACCESS_DENIED'].includes(code??'')){this.accessGeneration++;this.budget.set(null);this.conflict.set(false);this.error.set('存取權已變更，資料已清除。');}else this.error.set('無法取得目前版本，請重新讀取。'); }
  private conflictRefreshReady=false;
  adopt():void { if(!this.conflictRefreshReady)return; this.conflict.set(false); this.error.set('已採用目前版本，請重新審核。'); }
  readonly saving=signal(false); readonly retryable=signal(false);
  private pending:WeeklyBudgetCommand|null=null;
  pendingCommand():WeeklyBudgetCommand|null { return this.pending; }
  async verifyScope(workspaceId:string,memberUserId:string|null,simulation:boolean):Promise<{budget:WeeklyBudget;accessGeneration:number}> { const accessGeneration=this.accessGeneration; const b=await this.api.read(workspaceId,memberUserId,simulation);if(!b || b.workspaceId!==workspaceId || b.simulation!==simulation || (memberUserId!==null && b.memberUserId!==memberUserId))throw new Error('scope mismatch'); return {budget:b,accessGeneration}; }
  restoreVerifiedBudget(verified:{budget:WeeklyBudget;accessGeneration:number}):boolean { if(verified.accessGeneration!==this.accessGeneration)return false; if(this.pending && !this.budget()) {this.budget.set(verified.budget);this.error.set('');} return true; }
  async retry():Promise<void> { if(this.pending && this.retryable() && !this.saving()) await this.send(this.pending,true); }
  async submit(enabled:boolean, capUsd:string|null):Promise<void> {
    const b=this.budget(); if(!b || this.saving() || this.retryable() || this.conflict())return;
    if ((enabled && !capUsd) || (capUsd !== null && (!/^\d+(?:\.\d+)?$/.test(capUsd) || capUsd.split('.')[0].replace(/^0+/, '').length > 18 || (capUsd.split('.')[1]?.length ?? 0) > 12))) return;
    this.pending={workspaceId:b.workspaceId,memberUserId:b.memberUserId,simulation:b.simulation,enabled,capUsd,expectedConfigVersion:b.configVersion,idempotencyKey:crypto.randomUUID()};
    await this.send(this.pending);
  }
  private async send(command:WeeklyBudgetCommand, wasUnknown=false):Promise<void> {
    const generation=this.generation; this.saving.set(true); this.retryable.set(false); this.error.set('');
    try { const result=await this.api.configure(command); if(generation!==this.generation)return; if(!result?.budget || result.budget.workspaceId!==command.workspaceId || result.budget.memberUserId!==command.memberUserId || result.budget.simulation!==command.simulation)throw new Error('scope mismatch'); this.budget.set(result.budget); this.pending=null; }
    catch (e) { if(generation===this.generation) { const code=(e as {code?:string})?.code; if(['WORKSPACE_ACCESS_DENIED','WORKSPACE_MANAGER_REQUIRED','MEMBER_ACCESS_DENIED'].includes(code ?? '')) { this.accessGeneration++; if(wasUnknown)this.retryable.set(true);else this.pending=null; this.budget.set(null); this.error.set('存取權已變更，資料已清除。'); } else if(code==='INVALID_WEEKLY_BUDGET_CONFIGURATION') {this.pending=null;this.error.set('伺服器拒絕設定，請檢查金額。');} else if(code==='WEEKLY_BUDGET_CONFIGURATION_CONFLICT') { this.pending=null; this.conflictRefreshReady=false; this.conflict.set(true); this.error.set('設定已變更；草稿已保留。'); try { const b=await this.api.read(command.workspaceId,command.memberUserId,command.simulation); if(generation===this.generation && b.workspaceId===command.workspaceId && b.memberUserId===command.memberUserId && b.simulation===command.simulation){this.budget.set(b);this.conflictRefreshReady=true;} } catch (refreshError) { if(generation===this.generation)this.handleRefreshError(refreshError); } } else this.retryable.set(true); } }
    finally {if(generation===this.generation)this.saving.set(false);}
  }
  private generation = 0;
  constructor(private api:WeeklyBudgetTransport) {}
  async setScope(workspaceId:string|null, memberUserId:string|null, simulation:boolean):Promise<void> { const generation=++this.generation; this.accessGeneration++; this.budget.set(null); this.pending=null; this.retryable.set(false); this.saving.set(false); this.conflict.set(false); this.error.set(''); if (workspaceId) { try { const b=await this.api.read(workspaceId, memberUserId, simulation); if (generation===this.generation) { if(b.workspaceId!==workspaceId || b.simulation!==simulation || (memberUserId!==null && b.memberUserId!==memberUserId)) throw new Error('scope mismatch'); this.budget.set(b); } } catch (e) { if(generation===this.generation) this.error.set((e as {code?:string})?.code === 'WORKSPACE_ACCESS_DENIED' ? '存取權已變更。' : '無法讀取週額度，請重試。'); } } }
}

// Keep unresolved idempotent commands for the app lifetime, scoped by their trusted selection.
@Injectable({providedIn:'root'})
export class WeeklyBudgetPendingOwner {
  private readonly defaultTransport = new GraphQLWeeklyBudgetTransport();
  private readonly stores = new WeakMap<WeeklyBudgetTransport, Map<string, WeeklyBudgetStore>>();
  acquire(transport:WeeklyBudgetTransport|null, workspaceId:string, memberUserId:string|null, simulation:boolean):{store:WeeklyBudgetStore; retained:boolean} {
    const api=transport ?? this.defaultTransport;
    let scoped=this.stores.get(api); if(!scoped){scoped=new Map();this.stores.set(api,scoped);}
    const key=JSON.stringify([workspaceId,memberUserId,simulation]); const existing=scoped.get(key);
    if(existing && (existing.saving() || existing.retryable())) return {store:existing,retained:true};
    const store=new WeeklyBudgetStore(api);scoped.set(key,store);return {store,retained:false};
  }
}
