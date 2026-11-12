import { describe, expect, it, vi } from 'vitest';
import { UsageStore } from './usage.store';

const attempt = (id: string) => ({id,workspaceId:'A',preparedAt:'2026-01-01T00:00:00.123456Z',startedAt:null,provider:'gemini',model:'m',simulation:false,cost:{status:'UNPRICED',amount:null,currency:'USD'}});
describe('personal usage', () => {
  it('clears cached snapshots and refuses download when access is revoked', async () => {
    const snapshot = {id:'s',rawUsage:'null',rawState:'JSONB'};
    const request = vi.fn().mockResolvedValueOnce(Response.json({data:{myUsageAttempts:{nodes:[attempt('1')],pageInfo:{hasNextPage:false,endCursor:null}}}}))
      .mockResolvedValueOnce(Response.json({data:{myUsageAttempt:{id:'1',snapshots:{nodes:[snapshot],pageInfo:{hasNextPage:false,endCursor:null}}}}}))
      .mockResolvedValueOnce(Response.json({errors:[{message:'denied',extensions:{code:'WORKSPACE_ACCESS_DENIED'}}]}));
    const store = new UsageStore(request); await store.setWorkspace('A'); await store.selectAttempt('1');
    expect(await store.authorizeSnapshot('s')).toBe(false);
    expect(store.snapshots()).toEqual([]); expect(store.error()).toBeTruthy();
  });
  it('does not leave old workspace rows visible after access is revoked on a snapshot page', async () => {
    const request = vi.fn().mockResolvedValueOnce(Response.json({data:{myUsageAttempts:{nodes:[attempt('1')],pageInfo:{hasNextPage:false,endCursor:null}}}}))
      .mockResolvedValueOnce(Response.json({errors:[{message:'denied',extensions:{code:'WORKSPACE_ACCESS_DENIED'}}]}));
    const store = new UsageStore(request); await store.setWorkspace('A'); await store.selectAttempt('1');
    expect(store.attempts()).toEqual([]); expect(store.error()).toBeTruthy(); expect(store.selectedAttemptId()).toBeNull();
  });
  it('pages snapshots using the attempt-bound server cursor', async () => {
    const request = vi.fn().mockResolvedValueOnce(Response.json({data:{myUsageAttempts:{nodes:[attempt('1')],pageInfo:{hasNextPage:false,endCursor:null}}}}))
      .mockResolvedValueOnce(Response.json({data:{myUsageAttempt:{id:'1',snapshots:{nodes:[{id:'s1'}],pageInfo:{hasNextPage:true,endCursor:'snapshot-cursor'}}}}}))
      .mockResolvedValueOnce(Response.json({data:{myUsageAttempt:{id:'1',snapshots:{nodes:[{id:'s2'}],pageInfo:{hasNextPage:false,endCursor:null}}}}}));
    const store = new UsageStore(request); await store.setWorkspace('A'); await store.selectAttempt('1'); await store.loadMoreSnapshots();
    expect(store.snapshots().map(item => item.id)).toEqual(['s1','s2']);
    expect(JSON.parse(request.mock.calls[2][1].body).variables).toEqual({workspaceId:'A',id:'1',first:20,after:'snapshot-cursor'});
  });
  it.each(['list page','detail','snapshot page','download'])('ignores late %s responses after switching workspace', async (kind) => {
    const a = {id:'1',workspaceId:'A'};
    const snap = {id:'s',rawUsage:'{"value":900719925474099312345}',rawState:'JSONB'};
    const page = (nodes: unknown[], more = false, cursor: string | null = null) => ({nodes,pageInfo:{hasNextPage:more,endCursor:cursor}});
    let resolve!: (value: Response) => void; let calls = 0;
    const request = vi.fn(async (_url: string | URL | Request, init?: RequestInit) => {
      const body = JSON.parse(String(init?.body)); const query = body.query as string;
      if (body.variables.workspaceId === 'B') return Response.json({data:{myUsageAttempts:page([])}});
      if (query.includes('MyUsageAttempts')) {
        if (kind === 'list page' && body.variables.after) return new Promise<Response>(r => resolve = r);
        return Response.json({data:{myUsageAttempts:page([a],true,'a-cursor')}});
      }
      if (query.includes('AuthorizeUsageDownload')) return new Promise<Response>(r => resolve = r);
      calls++;
      if ((kind === 'detail' && calls === 1) || (kind === 'snapshot page' && calls === 2)) return new Promise<Response>(r => resolve = r);
      return Response.json({data:{myUsageAttempt:{id:'1',snapshots:page([snap],true,'s-cursor')}}});
    });
    const store = new UsageStore(request); await store.setWorkspace('A');
    let pending: Promise<unknown>;
    if (kind === 'list page') pending = store.loadMore();
    else if (kind === 'detail') pending = store.selectAttempt('1');
    else { await store.selectAttempt('1'); pending = kind === 'snapshot page' ? store.loadMoreSnapshots() : store.authorizeSnapshot('s'); }
    await store.setWorkspace('B');
    if (kind === 'list page') resolve(Response.json({data:{myUsageAttempts:page([a])}}));
    else if (kind === 'download') resolve(Response.json({data:{myUsageAttempt:{id:'1'}}}));
    else resolve(Response.json({data:{myUsageAttempt:{id:'1',snapshots:page([snap])}}}));
    const result = await pending;
    if (kind === 'download') expect(result).toBe(false);
    expect(store.workspaceId()).toBe('B'); expect(store.attempts()).toEqual([]); expect(store.snapshots()).toEqual([]); expect(store.selectedAttemptId()).toBeNull();
  });
  it('keeps the newer selected detail when an older detail response arrives late', async () => {
    let resolve!: (response: Response) => void;
    const page = {hasNextPage:false,endCursor:null};
    const request = vi.fn().mockResolvedValueOnce(Response.json({data:{myUsageAttempts:{nodes:[attempt('1'),attempt('2')],pageInfo:page}}}))
      .mockReturnValueOnce(new Promise<Response>(r => resolve = r))
      .mockResolvedValueOnce(Response.json({data:{myUsageAttempt:{...attempt('2'),projectionVersion:'2',snapshots:{nodes:[{id:'s2'}],pageInfo:page}}}}));
    const store = new UsageStore(request); await store.setWorkspace('A');
    const older = store.selectAttempt('1'); await store.selectAttempt('2');
    resolve(Response.json({data:{myUsageAttempt:{...attempt('1'),projectionVersion:'1',snapshots:{nodes:[{id:'s1'}],pageInfo:page}}}})); await older;
    expect(store.detailAttempt()?.id).toBe('2'); expect(store.detailAttempt()?.projectionVersion).toBe('2');
    expect(store.snapshots().map(row => row.id)).toEqual(['s2']);
  });
  it('loads scoped pages with the opaque server cursor without duplicating or converting values', async () => {
    const request = vi.fn().mockResolvedValueOnce(Response.json({data:{myUsageAttempts:{nodes:[attempt('1')],pageInfo:{hasNextPage:true,endCursor:'opaque'}}}})).mockResolvedValueOnce(Response.json({data:{myUsageAttempts:{nodes:[attempt('2')],pageInfo:{hasNextPage:false,endCursor:null}}}}));
    const store = new UsageStore(request);
    await store.setWorkspace('A'); await store.loadMore();
    expect(store.attempts().map(row => row.id)).toEqual(['1','2']);
    expect(JSON.parse(request.mock.calls[1][1].body).variables).toEqual({workspaceId:'A',first:20,after:'opaque'});
    expect(store.attempts()[0].cost).toEqual({status:'UNPRICED',amount:null,currency:'USD'});
  });
  it('clears previously visible usage on scope change and ignores late results', async () => {
    let resolve!: (value:Response)=>void;
    const request = vi.fn().mockReturnValueOnce(new Promise<Response>(r => resolve=r)).mockResolvedValueOnce(Response.json({data:{myUsageAttempts:{nodes:[],pageInfo:{hasNextPage:false,endCursor:null}}}}));
    const store = new UsageStore(request); const a = store.setWorkspace('A'); await store.setWorkspace('B');
    resolve(Response.json({data:{myUsageAttempts:{nodes:[attempt('a')],pageInfo:{hasNextPage:false,endCursor:null}}}})); await a;
    expect(store.attempts()).toEqual([]); expect(store.workspaceId()).toBe('B');
  });
});
