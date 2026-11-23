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
});
