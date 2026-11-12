import { describe, expect, it, vi } from 'vitest';
import { WorkspaceStore } from './workspace.store';

describe('WorkspaceStore', () => {
  it('shows a provisioning error instead of inventing a personal workspace', async () => {
    const store = new WorkspaceStore(vi.fn(async () => Response.json({errors:[{message:'failed',extensions:{code:'PROVISION_FAILED'}}]})));
    await store.load(); expect(store.error()).toBeTruthy(); expect(store.selectedId()).toBeNull();
  });
  it('selects the personal workspace, preserves errors and ignores stale loads', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(Response.json({data:{workspaces:[{id:'A',name:'A',role:'MANAGER',personal:true},{id:'B',name:'B',role:'MEMBER',personal:false}]}}));
    const store = new WorkspaceStore(fetcher);
    await store.load();
    expect(store.selectedId()).toBe('A');
    store.select('B'); expect(store.selectedId()).toBe('B');
    store.select('unauthorized'); expect(store.selectedId()).toBe('B');
  });
});
