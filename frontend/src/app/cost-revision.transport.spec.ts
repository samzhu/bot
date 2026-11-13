import { describe, expect, it, vi } from 'vitest';
import { GraphQLCostRevisionTransport } from './cost-revision.store';

describe('GraphQL cost revision transport',()=>{
  const response=(data:unknown,status=200)=>new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/json'}});

  it('maps manager attempts without converting decimal strings and sends opaque cursor variables',async()=>{
    const request=vi.fn(async(_input:RequestInfo|URL,init?:RequestInit)=>response({data:{costReviewAttempts:{nodes:[{id:'attempt-a',workspaceId:'workspace-a',simulation:false,provider:'fake',providerRequestId:null,providerResponseId:'response-1',attemptCoverage:'UNKNOWN',endpoint:'chat',model:'fixture',executionOutcome:'COMPLETED',projectionVersion:'9007199254740993',effectiveSnapshotId:'snapshot-a',cost:{status:'UNKNOWN',knownSubtotal:'9007199254740993.000000000001',completeAmount:null,currency:'USD',simulation:false,needsReprice:false,sourceProjectionVersion:'9007199254740993',estimateSourceVersion:null,currentRevision:null,priceRevision:null,reasons:['COVERAGE_UNKNOWN']}}],pageInfo:{hasNextPage:true,endCursor:'signed-cursor'}}}}));
    const transport=new GraphQLCostRevisionTransport(request as typeof fetch);const page=await transport.costReviewAttempts('workspace-a',false,20,'opaque-prior-cursor');
    const body=JSON.parse(String(request.mock.calls[0][1]?.body)) as {query:string;variables:Record<string,unknown>};
    expect(body.query).toContain('costReviewAttempts');expect(body.query).toContain('providerRequestId');expect(body.query).toContain('providerResponseId');expect(body.query).toContain('attemptCoverage');expect(body.variables).toEqual({workspaceId:'workspace-a',simulation:false,first:20,after:'opaque-prior-cursor'});
    expect(page.nodes[0].sourceProjectionVersion).toBe('9007199254740993');expect(page.nodes[0].knownSubtotal).toBe('9007199254740993.000000000001');expect(page.nodes[0].effectiveSnapshotId).toBe('snapshot-a');expect(page.nodes[0].providerRequestId).toBeNull();expect(page.nodes[0].providerResponseId).toBe('response-1');expect(page.nodes[0].attemptCoverage).toBe('UNKNOWN');expect(page.nodes[0].rawUsage).toBeNull();expect(page.pageInfo.endCursor).toBe('signed-cursor');
  });

  it('preserves raw JSON text and the server permission error code',async()=>{
    const raw='{"large":900719925474099312345,"value":null}';const request=vi.fn(async()=>response({data:{workspaceUsageAttempt:{id:'attempt-a',workspaceId:'workspace-a',simulation:false,provider:'fake',endpoint:'chat',model:'fixture',executionOutcome:'COMPLETED',projectionVersion:'9007199254740993',effectiveSnapshotId:'snapshot-effective',cost:{status:'UNKNOWN',knownSubtotal:null,completeAmount:null,currency:'USD',simulation:false,needsReprice:false,sourceProjectionVersion:'9007199254740993',estimateSourceVersion:null,currentRevision:null,priceRevision:null,reasons:[]},snapshots:{nodes:[{id:'snapshot-effective',rawState:'JSONB',rawUsage:raw}],pageInfo:{hasNextPage:false,endCursor:null}}}}}));
    const transport=new GraphQLCostRevisionTransport(request as typeof fetch);const page=await transport.workspaceUsageAttempt('workspace-a','attempt-a',20,null);expect(page?.snapshots.nodes[0].rawUsage).toBe(raw);expect(page?.effectiveSnapshotId).toBe('snapshot-effective');
    const denied=new GraphQLCostRevisionTransport(vi.fn(async()=>response({errors:[{message:'denied',extensions:{code:'WORKSPACE_MANAGER_REQUIRED'}}]},200)) as typeof fetch);
    await expect(denied.costReviewAttempts('workspace-a',false,20,null)).rejects.toMatchObject({code:'WORKSPACE_MANAGER_REQUIRED'});
  });
});
