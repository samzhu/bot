import { signal } from '@angular/core';
import { UsageAttempt, UsageSnapshot } from './usage.store';

export interface UsageReportMetric { knownValue: string | null; completeValue: string | null; unknownCount: string; unit: string; meaning: string; }
export interface UsageReportSummary {
  status: 'EMPTY' | 'COMPLETE' | 'PARTIAL' | 'UNKNOWN'; currency: string; knownSubtotal: string | null; completeTotal: string | null;
  unknownCount: string; coverageUnknownCount: string; recordedAttemptCount: string; unassignedCount: string; sharePercent: string | null;
}
export interface UsageDailyBucket {
  day: string; knownSubtotal: string | null; completeTotal: string | null; unknownCount: string; recordedAttemptCount: string;
  visualRatioPercent: number | null;
}
export interface UsageReport {
  workspaceId: string; periodDays: number; from: string; to: string; asOf: string; timeZone: string; simulation: boolean;
  summary: UsageReportSummary;
  nativeGroups: Array<{provider:string;endpoint:string;model:string;schemaVersion:string;input:UsageReportMetric;output:UsageReportMetric;cacheRead:UsageReportMetric;cacheWrite:UsageReportMetric}>;
  daily: UsageDailyBucket[];
  attempts: {nodes: UsageAttempt[];pageInfo:{hasNextPage:boolean;endCursor:string|null}};
}
type ReportMode = 'mine' | 'workspace';
type FilterSnapshot = {periodDays:7|30;provider:string|null;model:string|null;simulation:boolean;memberId:string|null;unassignedOnly:boolean};

const attemptFields = `id workspaceId runId providerRequestId providerResponseId preparedAt startedAt finishedAt provider endpoint model channel executionOutcome usagePresence captureCompleteness projectionError projectionState projectionVersion effectiveSnapshotId attemptCoverage simulation input {value presence meaning} output {value presence meaning} cacheRead {value presence meaning} cacheWrite {value presence meaning} cost {status knownSubtotal completeAmount currency simulation needsReprice sourceProjectionVersion estimateSourceVersion currentRevision priceRevision priceSnapshotId priceSourceReference priceEffectiveFrom priceEffectiveUntil calculatorVersion sourceSnapshotId sourceLayer schemaVersion adapterVersion captureVersion captureCompleteness semantic ordinal reasons lineItems {category nativeQuantity unit meaning rate rateBasis exactAmount storedAmount reasons}}`;
const snapshotFields = `id eventId ordinal semantic sourceLayer schemaVersion adapterVersion captureVersion captureCompleteness rawState rawUsage captureReason capturedAt`;

export class UsageReportsStore {
  readonly workspaceId = signal<string|null>(null); readonly manager = signal(false); readonly mode = signal<ReportMode>('mine');
  readonly periodDays = signal<7|30>(7); readonly provider = signal(''); readonly model = signal(''); readonly simulation = signal(false);
  readonly memberId = signal(''); readonly unassignedOnly = signal(false); readonly dirty = signal(false);
  readonly report = signal<UsageReport|null>(null); readonly loading = signal(false); readonly error = signal<string|null>(null);
  readonly errorCode = signal<string|null>(null); readonly hasMore = signal(false); readonly selectedAttemptId = signal<string|null>(null);
  readonly detailAttempt = signal<UsageAttempt|null>(null); readonly snapshots = signal<UsageSnapshot[]>([]);
  readonly snapshotsLoading = signal(false); readonly snapshotsError = signal<string|null>(null); readonly snapshotsHasMore = signal(false);
  private generation = 0; private detailGeneration = 0; private snapshotGeneration = 0;
  private cursor:string|null = null; private snapshotCursor:string|null = null;

  constructor(private readonly request:typeof fetch=(...args)=>globalThis.fetch(...args)){}

  async open(workspaceId:string|null,manager:boolean):Promise<void>{
    this.manager.set(manager); this.workspaceId.set(workspaceId); this.mode.set('mine'); this.resetFilters();
    this.clearReport(); await this.loadFirstPage();
  }
  async changeScope(workspaceId:string|null,manager:boolean):Promise<void>{
    if(workspaceId===this.workspaceId() && manager===this.manager()) return;
    await this.open(workspaceId,manager);
  }
  async setMode(mode:ReportMode):Promise<void>{
    if(mode==='workspace' && !this.manager()) return;
    if(mode===this.mode()) return;
    this.mode.set(mode); this.memberId.set(''); this.unassignedOnly.set(false); this.dirty.set(false);
    this.clearReport(); await this.loadFirstPage();
  }
  setPeriodDays(value:7|30):void{this.periodDays.set(value);this.dirty.set(true);}
  setProvider(value:string):void{this.provider.set(value);this.dirty.set(true);}
  setModel(value:string):void{this.model.set(value);this.dirty.set(true);}
  setSimulation(value:boolean):void{this.simulation.set(value);this.dirty.set(true);}
  setMemberId(value:string):void{this.memberId.set(value);this.dirty.set(true);}
  setUnassignedOnly(value:boolean):void{this.unassignedOnly.set(value);this.dirty.set(true);}
  resetFilters():void{this.periodDays.set(7);this.provider.set('');this.model.set('');this.simulation.set(false);this.memberId.set('');this.unassignedOnly.set(false);this.dirty.set(false);}
  async applyFilters():Promise<void>{
    this.dirty.set(false); this.clearReport(); await this.loadFirstPage();
  }
  async retry():Promise<void>{this.clearReport();await this.loadFirstPage();}
  async loadMore():Promise<void>{if(!this.hasMore()||this.loading()||this.dirty())return;await this.fetchPage(this.cursor);}
  closeDetail():void{this.clearDetail();}
  dispose():void{this.clearReport();this.workspaceId.set(null);}

  private filterSnapshot():FilterSnapshot{return {periodDays:this.periodDays(),provider:this.provider().trim()||null,model:this.model().trim()||null,simulation:this.simulation(),memberId:this.mode()==='workspace'?(this.memberId().trim()||null):null,unassignedOnly:this.mode()==='workspace'&&this.unassignedOnly()};}
  private async loadFirstPage():Promise<void>{
    const workspaceId=this.workspaceId(); if(!workspaceId){this.error.set('目前沒有可讀取的工作區。');return;}
    this.cursor=null;this.hasMore.set(false);await this.fetchPage(null);
  }
  private clearReport():void{
    this.generation++;this.detailGeneration++;this.snapshotGeneration++;this.report.set(null);this.error.set(null);this.errorCode.set(null);
    this.loading.set(false);this.cursor=null;this.hasMore.set(false);this.selectedAttemptId.set(null);this.detailAttempt.set(null);
    this.snapshots.set([]);this.snapshotsLoading.set(false);this.snapshotsError.set(null);this.snapshotsHasMore.set(false);this.snapshotCursor=null;
  }
  private async graphql(query:string,variables:Record<string,unknown>):Promise<any>{
    const response=await this.request('/graphql',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({query,variables})});
    if(!response.ok)throw new Error('HTTP error');
    const payload=await response.json();
    if(payload.errors?.length){const issue=payload.errors[0];throw Object.assign(new Error(issue.message||'GraphQL request failed'),{code:issue.extensions?.code});}
    return payload.data;
  }
  private async fetchPage(after:string|null):Promise<void>{
    const workspaceId=this.workspaceId(),generation=this.generation,filters=this.filterSnapshot();if(!workspaceId)return;
    this.loading.set(true);this.error.set(null);this.errorCode.set(null);
    const personal=this.mode()==='mine';
    const query=personal
      ? `query MyUsageReport($workspaceId:ID!,$periodDays:Int,$provider:String,$model:String,$simulation:Boolean,$first:Int,$after:String){myUsageReport(workspaceId:$workspaceId,periodDays:$periodDays,provider:$provider,model:$model,simulation:$simulation,first:$first,after:$after){workspaceId periodDays from to asOf timeZone simulation summary{status currency knownSubtotal completeTotal unknownCount coverageUnknownCount recordedAttemptCount unassignedCount sharePercent} nativeGroups{provider endpoint model schemaVersion input{knownValue completeValue unknownCount unit meaning} output{knownValue completeValue unknownCount unit meaning} cacheRead{knownValue completeValue unknownCount unit meaning} cacheWrite{knownValue completeValue unknownCount unit meaning}} daily{day knownSubtotal completeTotal unknownCount recordedAttemptCount visualRatioPercent} attempts{nodes{${attemptFields}} pageInfo{hasNextPage endCursor}}}}`
      : `query WorkspaceUsageReport($workspaceId:ID!,$periodDays:Int,$provider:String,$model:String,$simulation:Boolean,$first:Int,$after:String,$memberId:ID,$unassignedOnly:Boolean){workspaceUsageReport(workspaceId:$workspaceId,periodDays:$periodDays,provider:$provider,model:$model,simulation:$simulation,first:$first,after:$after,memberId:$memberId,unassignedOnly:$unassignedOnly){workspaceId periodDays from to asOf timeZone simulation summary{status currency knownSubtotal completeTotal unknownCount coverageUnknownCount recordedAttemptCount unassignedCount sharePercent} nativeGroups{provider endpoint model schemaVersion input{knownValue completeValue unknownCount unit meaning} output{knownValue completeValue unknownCount unit meaning} cacheRead{knownValue completeValue unknownCount unit meaning} cacheWrite{knownValue completeValue unknownCount unit meaning}} daily{day knownSubtotal completeTotal unknownCount recordedAttemptCount visualRatioPercent} attempts{nodes{${attemptFields}} pageInfo{hasNextPage endCursor}}}}`;
    const variables={workspaceId,periodDays:filters.periodDays,provider:filters.provider,model:filters.model,simulation:filters.simulation,first:20,after,...(!personal?{memberId:filters.memberId,unassignedOnly:filters.unassignedOnly}:{})};
    try{
      const data=await this.graphql(query,variables);const result=(personal?data?.myUsageReport:data?.workspaceUsageReport) as UsageReport|undefined;
      if(generation!==this.generation)return;
      if(!result||!result.summary||!result.attempts?.pageInfo||!Array.isArray(result.attempts.nodes)||!Array.isArray(result.daily)||!Array.isArray(result.nativeGroups))throw new Error('Invalid usage report response');
      const existing=this.report();this.report.set(existing&&after?{...result,attempts:{...result.attempts,nodes:[...existing.attempts.nodes,...result.attempts.nodes]}}:result);
      this.cursor=result.attempts.pageInfo.endCursor;this.hasMore.set(result.attempts.pageInfo.hasNextPage);
    }catch(error){if(generation!==this.generation)return;if(this.isAccessDenied(error)){this.clearForAccessDenied(error);return;}this.report.set(null);this.hasMore.set(false);this.cursor=null;this.error.set(error instanceof Error?error.message:'Usage report failed');this.errorCode.set((error as {code?:string})?.code??null);}
    finally{if(generation===this.generation)this.loading.set(false);}
  }
  async selectAttempt(id:string):Promise<void>{
    const workspaceId=this.workspaceId();if(!workspaceId)return;
    const generation=++this.detailGeneration,scopeGeneration=this.generation;this.snapshotGeneration++;this.selectedAttemptId.set(id);this.detailAttempt.set(null);this.snapshots.set([]);this.snapshotsError.set(null);this.snapshotsHasMore.set(false);this.snapshotCursor=null;this.snapshotsLoading.set(true);
    try{
      const field=this.mode()==='mine'?'myUsageAttempt':'workspaceUsageAttempt';
      const data=await this.graphql(`query UsageReportAttempt($workspaceId:ID!,$id:ID!,$first:Int,$after:String){${field}(workspaceId:$workspaceId,id:$id){${attemptFields} snapshots(first:$first,after:$after){nodes{${snapshotFields}} pageInfo{hasNextPage endCursor}}}}`,{workspaceId,id,first:20,after:null});
      if(scopeGeneration!==this.generation||generation!==this.detailGeneration)return;
      const result=data?.[field];if(!result||result.id!==id||result.workspaceId!==workspaceId)throw new Error('Usage attempt unavailable');
      const page=result.snapshots;this.detailAttempt.set(result);this.snapshots.set(page.nodes);this.snapshotCursor=page.pageInfo.endCursor;this.snapshotsHasMore.set(page.pageInfo.hasNextPage);
    }catch(error){if(scopeGeneration!==this.generation||generation!==this.detailGeneration)return;if(this.isAccessDenied(error)){this.clearForAccessDenied(error);return;}this.clearDetail();this.error.set(error instanceof Error?error.message:'Usage attempt unavailable');this.errorCode.set((error as {code?:string})?.code??null);}
    finally{if(scopeGeneration===this.generation&&generation===this.detailGeneration)this.snapshotsLoading.set(false);}
  }
  async loadMoreSnapshots():Promise<void>{
    const workspaceId=this.workspaceId(),id=this.selectedAttemptId(),after=this.snapshotCursor;if(!workspaceId||!id||!after||!this.snapshotsHasMore()||this.snapshotsLoading())return;
    const generation=++this.snapshotGeneration,scopeGeneration=this.generation;this.snapshotsLoading.set(true);this.snapshotsError.set(null);
    try{
      const field=this.mode()==='mine'?'myUsageAttempt':'workspaceUsageAttempt';
      const data=await this.graphql(`query UsageReportSnapshotPage($workspaceId:ID!,$id:ID!,$first:Int,$after:String){${field}(workspaceId:$workspaceId,id:$id){id snapshots(first:$first,after:$after){nodes{${snapshotFields}} pageInfo{hasNextPage endCursor}}}}`,{workspaceId,id,first:20,after});
      if(scopeGeneration!==this.generation||generation!==this.snapshotGeneration||id!==this.selectedAttemptId())return;
      const result=data?.[field];if(result?.id!==id)throw new Error('Usage snapshot unavailable');const page=result.snapshots;
      this.snapshots.update(items=>[...items,...page.nodes]);this.snapshotCursor=page.pageInfo.endCursor;this.snapshotsHasMore.set(page.pageInfo.hasNextPage);
    }catch(error){if(scopeGeneration!==this.generation||generation!==this.snapshotGeneration)return;if(this.isAccessDenied(error)){this.clearForAccessDenied(error);return;}this.snapshotsError.set(error instanceof Error?error.message:'Usage snapshot unavailable');}
    finally{if(scopeGeneration===this.generation&&generation===this.snapshotGeneration)this.snapshotsLoading.set(false);}
  }
  async retrySnapshots():Promise<void>{const id=this.selectedAttemptId();if(id)await this.selectAttempt(id);}
  async authorizeSnapshot(snapshotId:string):Promise<boolean>{
    const workspaceId=this.workspaceId(),id=this.selectedAttemptId(),scopeGeneration=this.generation,snapshotGeneration=this.snapshotGeneration;
    if(!workspaceId||!id||!this.snapshots().some(row=>row.id===snapshotId))return false;
    try{const field=this.mode()==='mine'?'myUsageAttempt':'workspaceUsageAttempt';const data=await this.graphql(`query AuthorizeReportSnapshot($workspaceId:ID!,$id:ID!){${field}(workspaceId:$workspaceId,id:$id){id}}`,{workspaceId,id});
      if(scopeGeneration!==this.generation||snapshotGeneration!==this.snapshotGeneration)return false;
      if(data?.[field]?.id!==id){this.clearDetail();this.snapshotsError.set('此用量明細目前無法下載。');return false;}return true;
    }catch(error){if(scopeGeneration===this.generation){if(this.isAccessDenied(error))this.clearForAccessDenied(error);else this.snapshotsError.set(error instanceof Error?error.message:'Download authorization failed');}return false;}
  }

  private isAccessDenied(error:unknown):boolean{
    const code=(error as {code?:string})?.code;
    return code==='WORKSPACE_ACCESS_DENIED'||code==='WORKSPACE_MANAGER_REQUIRED'||code==='USAGE_ATTEMPT_ACCESS_DENIED';
  }
  private clearForAccessDenied(error:unknown):void{
    const message=error instanceof Error?error.message:'Workspace access unavailable';
    const code=(error as {code?:string})?.code??'WORKSPACE_ACCESS_DENIED';
    this.clearReport();this.error.set(message);this.errorCode.set(code);
  }
  private clearDetail():void{
    this.detailGeneration++;this.snapshotGeneration++;this.selectedAttemptId.set(null);this.detailAttempt.set(null);this.snapshots.set([]);
    this.snapshotsHasMore.set(false);this.snapshotsLoading.set(false);this.snapshotsError.set(null);this.snapshotCursor=null;
  }
}
