import { describe, expect, it, vi } from 'vitest';
import { ConversationListStore } from './conversation-list-store';

describe('workspace conversation scope', () => {
  it('does not change selection or offset on duplicate creation events', () => {
    const store = new ConversationListStore();
    const row = {id:'X',botId:'b',title:'X',pinned:false,createdAt:'',updatedAt:''};
    store.setActiveConversation({...row,id:'Y'});
    store.addCreatedConversation(row); store.addCreatedConversation(row);
    expect(store.activeConversation()?.id).toBe('Y');
    expect(store.unpinnedOffset()).toBe(1); expect(store.conversations()).toHaveLength(1);
  });
  it('discards a late A list when B becomes current', async () => {
    const original = globalThis.fetch;
    const pending: ((response: Response) => void)[] = [];
    const seen: string[] = [];
    globalThis.fetch = vi.fn((_url, init) => {
      const body = JSON.parse(String(init?.body)); seen.push(body.variables.workspaceId);
      if (body.variables.workspaceId === 'A') return new Promise<Response>(resolve => pending.push(resolve));
      return Promise.resolve(Response.json({data:{conversations:[]}}));
    }) as typeof fetch;
    try {
      const store = new ConversationListStore();
      store.setWorkspace('A'); const first = store.loadConversations();
      store.setWorkspace('B'); await store.loadConversations();
      pending.forEach(resolve => resolve(Response.json({data:{conversations:[{id:'A'}]}})));
      await first;
      expect(store.conversations()).toEqual([]); expect(seen).toContain('A'); expect(seen).toContain('B');
    } finally { globalThis.fetch = original; }
  });
});
