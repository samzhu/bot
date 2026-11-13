import { describe, expect, it, vi } from 'vitest';
import { ChatAgUiTransport } from './chat-ag-ui-transport';
import { ChatSessionRegistry } from './chat-session-registry';

describe('ChatAgUiTransport', () => {
  it('recognizes only the exact access-denied codes for start and cancel', async () => {
    for (const [code, expected] of [['CONVERSATION_ACCESS_DENIED','REJECTED'], ['OTHER','UNKNOWN']] as const) {
      const transport = new ChatAgUiTransport(vi.fn(async () => Response.json({code}, {status:404})));
      expect((await transport.run('A', 'run', 'raw', () => {})).status).toBe(expected);
    }
    for (const [code, expected] of [['RUN_ACCESS_DENIED','REJECTED'], ['OTHER','UNKNOWN']] as const) {
      const transport = new ChatAgUiTransport(vi.fn(async () => Response.json({code}, {status:404})));
      expect((await transport.cancel('A', 'run')).status).toBe(expected);
    }
  });
  it('retains GraphQL error extensions for workspace access denial', async () => {
    const transport = new ChatAgUiTransport(vi.fn(async () => Response.json({errors:[{message:'denied',extensions:{code:'WORKSPACE_ACCESS_DENIED'}}]})));
    await expect(transport.createConversation('bot', 'A')).rejects.toMatchObject({extensions:{code:'WORKSPACE_ACCESS_DENIED'}});
  });
  it.each([['B','A'], ['A','B']])('validates each concurrent hydration scope (%s then %s)', async (firstScope, secondScope) => {
    let resolve!: (value: Response) => void;
    const fetcher = vi.fn(() => new Promise<Response>(r => resolve = r));
    const transport = new ChatAgUiTransport(fetcher);
    const first = transport.hydrate('C', firstScope);
    const second = transport.hydrate('C', secondScope);
    resolve(Response.json({data:{conversation:{id:'C', workspaceId:'A', messages:[], runs:[]}}}));
    const results = await Promise.allSettled([first, second]);
    expect(fetcher).toHaveBeenCalledOnce();
    expect(results.map(r => r.status)).toEqual([firstScope === 'A' ? 'fulfilled' : 'rejected', secondScope === 'A' ? 'fulfilled' : 'rejected']);
  });
  it('calls the default browser fetch with its global receiver', async () => {
    const original = globalThis.fetch;
    try {
      globalThis.fetch = async function(this: unknown) {
        if (this !== globalThis) throw new TypeError('Illegal invocation');
        return Response.json({data:{conversation:{messages:[]}}});
      } as typeof fetch;
      const transport = new ChatAgUiTransport();
      await expect(transport.hydrate('A')).resolves.toBeTruthy();
    } finally { globalThis.fetch = original; }
  });
  it('hydrates failed and cancelled assistant messages with non-success delivery', async () => {
    const transport = new ChatAgUiTransport(vi.fn(async () => Response.json({data:{conversation:{
      messages:[{id:'cancelled',runId:'r2',role:'ASSISTANT',content:'partial',interrupted:true},{id:'failed',runId:'r1',role:'ASSISTANT',content:'partial',interrupted:true}],
      runs:[{id:'r1',status:'FAILED'},{id:'r2',status:'CANCELLED'}]
    }}})));
    await transport.hydrate('A');
    const messages = transport.agent('A').messages();
    expect(messages.find(m => m.id === 'failed')?.delivery).toMatchObject({phase:'complete',outcome:'error'});
    expect(messages.find(m => m.id === 'cancelled')?.delivery).toMatchObject({phase:'complete',outcome:'aborted'});
  });
  it('does not report reconciliation complete when authoritative messages are missing', async () => {
    const transport = new ChatAgUiTransport(vi.fn(async () => Response.json({data:{run:{id:'r',status:'COMPLETED'},conversation:null}})));
    expect((await transport.reconcile('A','r')).status).toBe('UNKNOWN');
  });
  it('counts unique persisted rows even when their role is not rendered', async () => {
    const rows = [
      {id:'system',runId:'r',role:'SYSTEM',content:'hidden',createdAt:'2026-01-01T00:00:00Z'},
      {id:'user',runId:'r',role:'USER',content:'hello',createdAt:'2026-01-01T00:00:01Z'}
    ];
    const transport = new ChatAgUiTransport(vi.fn(async () => Response.json({data:{conversation:{messages:[...rows].reverse()}}})));
    await transport.hydrate('A');
    expect(transport.agent('A').messages().map(m => m.id)).toEqual(['user']);
    expect(transport.historyOffset('A')).toBe(2);
    transport.mergeHistory('A', [rows[0]], true);
    expect(transport.historyOffset('A')).toBe(2);
  });
  it('orders a late hydration snapshot before a newer reconciled snapshot', async () => {
    const rows = Array.from({length:62}, (_, i) => ({id:`m${String(i+1).padStart(2,'0')}`,runId:`r${i+1}`,role:'USER',content:`m${i+1}`,createdAt:new Date(1700000000000+i*1000).toISOString()}));
    let resolveHistory!: (response: Response) => void;
    const fetcher = vi.fn((_url: string | URL | Request, init?: RequestInit) => {
      const query = JSON.parse(String(init?.body)).query;
      if (query.includes('SessionHistory')) return new Promise<Response>(resolve => resolveHistory = resolve);
      return Promise.resolve(Response.json({data:{run:{status:'COMPLETED'},conversation:{messages:rows.slice(42,62).reverse()}}}));
    });
    const transport = new ChatAgUiTransport(fetcher);
    const hydration = transport.hydrate('A');
    await transport.reconcile('A','r62');
    resolveHistory(Response.json({data:{conversation:{messages:rows.slice(40,60).reverse()}}}));
    await hydration;
    expect(transport.agent('A').messages().map(m => m.id)).toEqual(rows.slice(40,62).map(m => m.id));
    expect(transport.historyOffset('A')).toBe(22);
  });
  it('does not double-count overlapping older-page rows and newly reconciled messages', async () => {
    const rows = Array.from({length:62}, (_, i) => ({id:`m${String(i+1).padStart(2,'0')}`,runId:`r${i+1}`,role:'USER',content:`m${i+1}`,createdAt:new Date(1700000000000+i*1000).toISOString()}));
    let terminal = false;
    const transport = new ChatAgUiTransport(vi.fn(async () => Response.json({data:{run:{status:'COMPLETED'},conversation:{messages:(terminal ? rows.slice(42,62) : rows.slice(40,60)).reverse()}}})));
    await transport.hydrate('A');
    expect(transport.historyOffset('A')).toBe(20);
    let resolvePage!: (value: typeof rows) => void;
    const page = new Promise<typeof rows>(resolve => resolvePage = resolve).then(result => transport.mergeHistory('A', result, true));
    terminal = true;
    await transport.reconcile('A','r62');
    resolvePage(rows.slice(22,42).reverse());
    await page;
    expect(transport.historyOffset('A')).toBe(40);
    expect(transport.agent('A').messages().map(m => m.id)).toEqual(rows.slice(22,62).map(m => m.id));
  });
  it('preserves the same range when an overlapping older page finishes before reconciliation', async () => {
    const rows = Array.from({length:62}, (_, i) => ({id:`m${String(i+1).padStart(2,'0')}`,runId:`r${i+1}`,role:'USER',content:`m${i+1}`,createdAt:new Date(1700000000000+i*1000).toISOString()}));
    let terminal = false;
    const transport = new ChatAgUiTransport(vi.fn(async () => Response.json({data:{run:{status:'COMPLETED'},conversation:{messages:(terminal ? rows.slice(42,62) : rows.slice(40,60)).reverse()}}})));
    await transport.hydrate('A');
    let resolvePage!: (value: typeof rows) => void;
    const page = new Promise<typeof rows>(resolve => resolvePage = resolve).then(result => transport.mergeHistory('A', result, true));
    resolvePage(rows.slice(22,42).reverse());
    await page;
    expect(transport.historyOffset('A')).toBe(38);
    terminal = true;
    await transport.reconcile('A','r62');
    expect(transport.historyOffset('A')).toBe(40);
    transport.mergeHistory('A', rows.slice(2,22).reverse(), true);
    transport.mergeHistory('A', rows.slice(0,2).reverse(), true);
    expect(transport.agent('A').messages().map(m => m.id)).toEqual(rows.map(m => m.id));
  });
  it('tracks the loaded offset when reconciliation adds newly persisted messages', async () => {
    const rows = Array.from({length:22}, (_, i) => ({id:`m${i + 1}`,runId:`r${i + 1}`,role:'USER',content:`m${i + 1}`}));
    let terminal = false;
    const transport = new ChatAgUiTransport(vi.fn(async () => Response.json({data:{run:{status:'COMPLETED'},conversation:{messages:(terminal ? rows.slice(2,22) : rows.slice(0,20)).reverse()}}})));
    await transport.hydrate('A');
    expect(transport.historyOffset('A')).toBe(20);
    terminal = true;
    await transport.reconcile('A','r22');
    expect(transport.historyOffset('A')).toBe(22);
    await transport.reconcile('A','r22');
    expect(transport.historyOffset('A')).toBe(22);
  });
  it('keeps the latest 20 ordered after reconciliation and loading older pages', async () => {
    const rows = Array.from({length:60}, (_, i) => ({id:`m${i + 1}`,runId:`r${i + 1}`,role:'USER',content:`message ${i + 1}`}));
    const fetcher = vi.fn(async (_url: string | URL | Request, init?: RequestInit) => {
      const body = JSON.parse(String(init?.body));
      expect(body.query).toContain('messages(limit: 20, offset: 0)');
      return Response.json({data:{run:{id:'r60',status:'COMPLETED'},conversation:{messages:rows.slice(40).reverse()}}});
    });
    const transport = new ChatAgUiTransport(fetcher);
    await transport.hydrate('A');
    await transport.reconcile('A','r60');
    expect(transport.agent('A').messages().map(m => m.id)).toEqual(rows.slice(40).map(m => m.id));
    transport.mergeHistory('A', rows.slice(20,40).reverse(), true);
    transport.mergeHistory('A', rows.slice(0,20).reverse(), true);
    expect(transport.agent('A').messages().map(m => m.id)).toEqual(rows.map(m => m.id));
  });
  it('removes optimistic input only for explicit no-run server codes', async () => {
    for (const [status, code, expected] of [[400,'INVALID_AG_UI_INPUT','REJECTED'],[503,'GKE_AGENT_NOT_CONNECTED','REJECTED'],[500,'OTHER','UNKNOWN'],[503,'OTHER','UNKNOWN']] as const) {
      const transport = new ChatAgUiTransport(vi.fn(async () => Response.json({code},{status})));
      expect((await transport.run('A','run','  original  ',() => {})).status).toBe(expected);
      expect(transport.agent('A').messages().filter(m => m.role === 'user').length).toBe(expected === 'REJECTED' ? 0 : 1);
    }
  });
  it('treats a monthly cap denial as a definite rejection without retaining an optimistic message', async () => {
    const fetcher = vi.fn(async () => Response.json({code:'MONTH_CAP', message:'untrusted server text'}, {status:503}));
    const transport = new ChatAgUiTransport(fetcher);
    const registry = new ChatSessionRegistry(transport, () => 'budget-run');
    registry.edit('conversation:A', '  draft\n  ');
    await registry.submit('conversation:A');
    expect(registry.session('conversation:A').result()).toMatchObject({status:'REJECTED', reason:'MONTH_CAP'});
    expect(registry.session('conversation:A').draft()).toBe('  draft\n  ');
    expect(transport.agent('A').messages().filter(m => m.role === 'user')).toEqual([]);
    expect(fetcher).toHaveBeenCalledOnce();
  });
  it('keeps a late unknown-budget rejection in its original conversation', async () => {
    let reply!: (value:Response)=>void;
    const transport = new ChatAgUiTransport(vi.fn(() => new Promise<Response>(resolve => reply=resolve)));
    const registry = new ChatSessionRegistry(transport, () => 'budget-original');
    registry.edit('conversation:A', '  A raw  ');
    const pending = registry.submit('conversation:A');
    registry.edit('conversation:B', '  B draft  ');
    await vi.waitFor(() => expect(reply).toBeDefined());
    reply(Response.json({code:'MONTH_UNKNOWN',message:'do not show'}, {status:503}));
    await pending;
    expect(registry.session('conversation:A').result()).toMatchObject({status:'REJECTED',reason:'MONTH_UNKNOWN'});
    expect(registry.session('conversation:A').draft()).toBe('  A raw  ');
    expect(registry.session('conversation:B').draft()).toBe('  B draft  ');
    expect(registry.session('conversation:B').result()).toBeNull();
    expect(transport.agent('A').messages().filter(m=>m.role==='user')).toEqual([]);
  });
  it('restores exact raw input when the server explicitly rejects without creating a run', async () => {
    const transport = new ChatAgUiTransport(vi.fn(async () => Response.json({code:'INVALID_AG_UI_INPUT'}, {status:400})));
    const registry = new ChatSessionRegistry(transport, () => 'run');
    registry.edit('conversation:A', '  original\n  ');
    await registry.submit('conversation:A');
    expect(registry.session('conversation:A').result()?.status).toBe('REJECTED');
    expect(registry.session('conversation:A').draft()).toBe('  original\n  ');
    expect(transport.agent('A').messages().filter(m => m.role === 'user')).toEqual([]);
  });
  it('hydrates 20 latest messages and prepends the next page in chronological order', async () => {
    const rows = Array.from({length:60}, (_, i) => ({id:`m${i + 1}`,runId:`r${i + 1}`,role:'USER',content:`message ${i + 1}`}));
    const fetcher = vi.fn(async (_url: string | URL | Request, init?: RequestInit) => {
      const body = JSON.parse(String(init?.body));
      expect(body.query).toContain('limit: 20');
      return Response.json({data:{conversation:{messages:rows.slice(40).reverse()}}});
    });
    const transport = new ChatAgUiTransport(fetcher);
    await transport.hydrate('A');
    transport.mergeHistory('A', rows.slice(20,40).reverse(), true);
    expect(transport.agent('A').messages().map(m => m.id)).toEqual(rows.slice(20).map(m => m.id));
    transport.mergeHistory('A', rows.slice(0,25).reverse(), true);
    expect(transport.agent('A').messages().map(m => m.id)).toEqual(rows.map(m => m.id));
  });
  it('removes only the optimistic user rejected by RUN_CONFLICT', async () => {
    const fetcher = vi.fn(async () => Response.json({code:'RUN_CONFLICT'}, {status:409}));
    const transport = new ChatAgUiTransport(fetcher);
    expect((await transport.run('A','conflict-run','not delivered',() => {})).status).toBe('REJECTED');
    expect(transport.agent('A').messages().filter(m => m.role === 'user')).toEqual([]);
  });
  it('blocks sending during history hydration and after failure until a successful retry', async () => {
    let resolve!: (response: Response) => void;
    const fetcher = vi.fn().mockReturnValueOnce(new Promise<Response>(r => resolve = r));
    const transport = new ChatAgUiTransport(fetcher);
    const pending = transport.hydrate('A');
    expect(transport.agent('A').isInputBlocked?.()).toBe(true);
    resolve(new Response('', {status:500}));
    await expect(pending).rejects.toThrow();
    expect(transport.agent('A').isInputBlocked?.()).toBe(true);
    fetcher.mockResolvedValueOnce(Response.json({data:{conversation:{messages:[]}}}));
    await transport.hydrate('A');
    expect(transport.agent('A').isInputBlocked?.()).toBe(false);
  });
  it('reconciles the optimistic user even if duplicate 409 has no RUN_STARTED', async () => {
    const fetcher = vi.fn(async (url: string | URL | Request) => url === '/graphql'
      ? Response.json({data:{run:{id:'run',status:'COMPLETED'},conversation:{messages:[{id:'db-user',runId:'run',role:'USER',content:'hello'}]}}})
      : Response.json({code:'DUPLICATE_RUN'},{status:409}));
    const transport = new ChatAgUiTransport(fetcher);
    expect((await transport.run('A','run','hello',() => {})).status).toBe('DUPLICATE');
    await transport.reconcile('A','run');
    expect(transport.agent('A').messages().filter(message => message.role === 'user').map(message => message.id)).toEqual(['db-user']);
  });
  it('does not replace a live assistant delta with a late history snapshot', async () => {
    let push!: (chunk: Uint8Array) => void;
    const encoder = new TextEncoder();
    const stream = new ReadableStream<Uint8Array>({start(controller) { push = chunk => controller.enqueue(chunk); }});
    const fetcher = vi.fn(async (url: string | URL | Request) => url === '/graphql'
      ? Response.json({ data:{conversation:{messages:[{id:'assistant',runId:'run',role:'ASSISTANT',content:'old'}]}} })
      : new Response(stream,{headers:{'Content-Type':'text/event-stream'}}));
    const transport = new ChatAgUiTransport(fetcher);
    const running = transport.run('A', 'run', 'hi', () => {});
    const send = (e: object) => push(encoder.encode(`data: ${JSON.stringify(e)}\n\n`));
    send({type:'RUN_STARTED',threadId:'A',runId:'run'});
    send({type:'TEXT_MESSAGE_START',messageId:'assistant',role:'assistant'});
    send({type:'TEXT_MESSAGE_CONTENT',messageId:'assistant',delta:'new partial'});
    await vi.waitFor(() => expect(transport.agent('A').messages().some(m => m.content === 'new partial')).toBe(true));
    await transport.hydrate('A');
    expect(transport.agent('A').messages().find(m => m.id === 'assistant')?.content).toBe('new partial');
    void running;
  });
  it('hydrates history and reconciles optimistic user identity against database messages', async () => {
    let saved = false;
    const fetcher = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      if (url === '/graphql') {
        const messages = saved
          ? [{id:'db-user',runId:'run-id',role:'USER',content:'hello'}, {id:'db-assistant',runId:'run-id',role:'ASSISTANT',content:'answer'}]
          : [{id:'history',runId:'old',role:'USER',content:'earlier'}];
        return Response.json({data:{conversation:{messages:messages.reverse()},run:{id:'run-id',status:'COMPLETED'}}});
      }
      const input = JSON.parse(String(init?.body));
      saved = true;
      const events = [
        {type:'RUN_STARTED',threadId:'A',runId:input.runId},
        {type:'TEXT_MESSAGE_START',messageId:'db-assistant',role:'assistant'},
        {type:'TEXT_MESSAGE_CONTENT',messageId:'db-assistant',delta:'answer'},
        {type:'TEXT_MESSAGE_END',messageId:'db-assistant'},
        {type:'RUN_FINISHED',threadId:'A',runId:input.runId}
      ];
      return new Response(events.map(e => `data: ${JSON.stringify(e)}\n\n`).join(''),{headers:{'Content-Type':'text/event-stream'}});
    });
    const transport = new ChatAgUiTransport(fetcher);
    await transport.hydrate('A');
    expect(transport.agent('A').messages().map(m => m.id)).toEqual(['history']);
    await transport.run('A', 'run-id', 'hello', () => {});
    await transport.reconcile('A', 'run-id');
    const ids = transport.agent('A').messages().map(m => m.id);
    expect(ids).toEqual(['history','db-user','db-assistant']);
  });
  it('distinguishes duplicate runs from other conflicts', async () => {
    for (const [code, expected] of [['DUPLICATE_RUN', 'DUPLICATE'], ['RUN_CONFLICT', 'REJECTED']] as const) {
      const fetcher = vi.fn(async () => new Response(JSON.stringify({ code }), { status: 409, headers: { 'Content-Type': 'application/json' } }));
      const transport = new ChatAgUiTransport(fetcher);
      const result = await transport.run('A', '123e4567-e89b-42d3-a456-426614174000', 'hello', () => {});
      expect(result.status).toBe(expected);
    }
  });
  it('uses the supplied run UUID and reduces a real AG-UI SSE response', async () => {
    let request: any;
    const fetcher = vi.fn(async (_url: string | URL | Request, init?: RequestInit) => {
      request = JSON.parse(String(init?.body));
      const events = [
        { type: 'RUN_STARTED', threadId: 'A', runId: request.runId },
        { type: 'TEXT_MESSAGE_START', messageId: 'db-assistant', role: 'assistant' },
        { type: 'TEXT_MESSAGE_CONTENT', messageId: 'db-assistant', delta: '**hello**' },
        { type: 'TEXT_MESSAGE_END', messageId: 'db-assistant' },
        { type: 'RUN_FINISHED', threadId: 'A', runId: request.runId }
      ];
      return new Response(events.map(event => `data: ${JSON.stringify(event)}\n\n`).join(''), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
    });
    const transport = new ChatAgUiTransport(fetcher);
    let text = '';
    const result = await transport.run('A', '123e4567-e89b-42d3-a456-426614174000', '  input  ', chunk => text += chunk);
    expect(request.runId).toBe('123e4567-e89b-42d3-a456-426614174000');
    expect(request.threadId).toBe('A');
    expect(request.messages.at(-1).content).toBe('input');
    expect(text).toBe('**hello**');
    expect(result.status).toBe('COMPLETED');
    expect(transport.agent('A').messages().at(-1)?.id).toBe('db-assistant');
  });
});
