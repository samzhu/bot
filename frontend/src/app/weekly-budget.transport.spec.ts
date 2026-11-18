import { describe, expect, it, vi } from 'vitest';
import { GraphQLWeeklyBudgetTransport } from './weekly-budget.store';

describe('weekly budget transport', () => {
  it('reads the selected member with exact decimal and null unchanged', async () => {
    const budget = { workspaceId: 'w', memberUserId: 'user-a', simulation: false, capUsd: '999999999999999999.123456789012', knownSubtotalUsd: null };
    const request = vi.fn(async (_url: RequestInfo | URL, _init?: RequestInit) => Response.json({ data: { memberWeeklyBudget: budget } }));
    const result = await new GraphQLWeeklyBudgetTransport(request as typeof fetch).read('w', 'user-a', false);
    expect(result).toEqual(budget);
    const body = JSON.parse(String(request.mock.calls[0][1]?.body));
    expect(body.query).toContain('memberWeeklyBudget');
    expect(body.variables).toEqual({workspaceId: 'w', memberUserId: 'user-a', simulation: false});
  });
  it('uses the self query without sending a member identity', async () => {
    const request = vi.fn(async (_url: RequestInfo | URL, _init?: RequestInit) => Response.json({data: {myMemberWeeklyBudget: {memberUserId: 'self'}}}));
    expect(await new GraphQLWeeklyBudgetTransport(request as typeof fetch).read('w', null, true)).toEqual({memberUserId: 'self'});
    const body = JSON.parse(String(request.mock.calls[0][1]?.body));
    expect(body.query).toContain('myMemberWeeklyBudget');
    expect(body.variables).toEqual({workspaceId: 'w', simulation: true});
  });
  it('exposes a server authorization code instead of treating errors as data', async () => {
    const request = vi.fn(async () => Response.json({errors: [{message: 'denied', extensions: {code: 'WORKSPACE_MANAGER_REQUIRED'}}]}));
    await expect(new GraphQLWeeklyBudgetTransport(request as typeof fetch).read('w', 'u', false)).rejects.toMatchObject({code:'WORKSPACE_MANAGER_REQUIRED'});
  });

  it('submits the exact command and returns the current replayed budget', async () => {
    const command = {workspaceId:'w', memberUserId:'u', simulation:false, enabled:true, capUsd:'0.123456789012', expectedConfigVersion:'7', idempotencyKey:'key'};
    const result = {outcome:'REPLAYED', budget:{capUsd:'2', configVersion:'9'}};
    const request = vi.fn(async (_url: RequestInfo | URL, _init?: RequestInit) => Response.json({data:{configureMemberWeeklyBudget:result}}));
    expect(await new GraphQLWeeklyBudgetTransport(request as typeof fetch).configure(command)).toEqual(result);
    expect(JSON.parse(String(request.mock.calls[0][1]?.body)).variables).toEqual({input:command});
  });

  it('reads a bounded manager member page with its cursor', async()=>{
    const page={nodes:[{userId:'u',role:'MEMBER'}],pageInfo:{hasNextPage:true,endCursor:'next'}};
    const request=vi.fn(async (_url:RequestInfo|URL,_init?:RequestInit)=>Response.json({data:{workspaceActiveMembers:page}}));
    expect(await new GraphQLWeeklyBudgetTransport(request as typeof fetch).members('w','cursor')).toEqual(page);
    const body=JSON.parse(String(request.mock.calls[0][1]?.body));
    expect(body.query).toContain('workspaceActiveMembers');expect(body.variables).toEqual({workspaceId:'w',first:20,after:'cursor'});
  });

});
