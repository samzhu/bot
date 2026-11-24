import {afterEach, describe, expect, it, vi} from 'vitest';
import {ConversationTitleUpdates} from './conversation-title-updates';

describe('ConversationTitleUpdates SSE framing', () => {
  afterEach(() => vi.restoreAllMocks());
  for (const separator of ['', ' ']) {
    it(`re-reads the exact title after data delimiter ${JSON.stringify(separator)}`, async () => {
      const merge = vi.fn();
      const watch = new ConversationTitleUpdates(merge);
      vi.spyOn(globalThis, 'fetch').mockImplementation(async (_input, init) => {
        const body = JSON.parse(String(init?.body));
        if (body.query.includes('conversationTitleChanged')) {
          const event = {data:{conversationTitleChanged:{id:'  conversation', version:'1', state:'DONE'}}};
          return new Response(`event:next\ndata:${separator}${JSON.stringify(event)}\n\n`, {headers:{'Content-Type':'text/event-stream'}});
        }
        return Response.json({data:{conversation:{id:'  conversation', workspaceId:'workspace', title:'  leading'}}});
      });
      try {
        watch.watch('workspace', ['  conversation']);
        await vi.waitFor(() => expect(merge).toHaveBeenCalledWith('workspace', '  conversation', '  leading'));
      } finally { watch.close(); }
    });
  }
  it('does not retry an unavailable title when an empty complete shares the chunk', async () => {
    const watch = new ConversationTitleUpdates(vi.fn());
    const ids: string[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (_input, init) => {
      const body = JSON.parse(String(init?.body));
      ids.push(body.variables.id);
      const event = {data:{conversationTitleChanged:{id:body.variables.id, version:'0', state:'UNAVAILABLE'}}};
      return new Response(`event:next\ndata:${JSON.stringify(event)}\n\nevent:complete\ndata:\n\n`, {headers:{'Content-Type':'text/event-stream'}});
    });
    try {
      watch.watch('workspace', ['old-a']);
      await vi.waitFor(() => expect(ids).toEqual(['old-a']));
      await new Promise(resolve => setTimeout(resolve, 1250));
      expect(ids).toEqual(['old-a']);
    } finally { watch.close(); }
  });
  it('refreshes a done title once without retry after an empty complete', async () => {
    const merge = vi.fn();
    const watch = new ConversationTitleUpdates(merge);
    const ids: string[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (_input, init) => {
      const body = JSON.parse(String(init?.body));
      if (!body.query.includes('conversationTitleChanged')) return Response.json({data:{conversation:{id:'done-a', workspaceId:'workspace', title:'Title'}}});
      ids.push(body.variables.id);
      const event = {data:{conversationTitleChanged:{id:body.variables.id, version:'1', state:'DONE'}}};
      return new Response(`event:next\ndata:${JSON.stringify(event)}\n\nevent:complete\ndata:\n\n`, {headers:{'Content-Type':'text/event-stream'}});
    });
    try {
      watch.watch('workspace', ['done-a']);
      await vi.waitFor(() => expect(merge).toHaveBeenCalledWith('workspace', 'done-a', 'Title'));
      await new Promise(resolve => setTimeout(resolve, 1250));
      expect(ids).toEqual(['done-a']);
      expect(merge).toHaveBeenCalledTimes(1);
    } finally { watch.close(); }
  });

  for (const initial of ['pending', 'error', 'eof']) {
    it(`retries ${initial} subscriptions`, async () => {
      const watch = new ConversationTitleUpdates(vi.fn());
      const ids: string[] = [];
      vi.spyOn(globalThis, 'fetch').mockImplementation(async (_input, init) => {
        const body = JSON.parse(String(init?.body));
        ids.push(body.variables.id);
        const data = initial === 'pending' ? {data:{conversationTitleChanged:{id:body.variables.id, version:'0', state:'READY'}}} : {errors:[{message:'temporary'}]};
        return new Response(initial === 'eof' ? '' : `data:${JSON.stringify(data)}\n\n`, {headers:{'Content-Type':'text/event-stream'}});
      });
      try {
        watch.watch('workspace', ['retry-a']);
        await vi.waitFor(() => expect(ids).toEqual(['retry-a', 'retry-a']), {timeout:2500});
      } finally { watch.close(); }
    });
  }
});
