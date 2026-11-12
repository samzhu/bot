import { describe, expect, it, vi } from 'vitest';
import { ChatSessionRegistry, ChatSessionTransport, RunResult } from './chat-session-registry';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(r => resolve = r);
  return { promise, resolve };
}

describe('ChatSessionRegistry', () => {
  it('deduplicates concurrent checks for one run', async () => {
    const check = deferred<RunResult>();
    const run = deferred<RunResult>();
    const transport: ChatSessionTransport = {createConversation: vi.fn(), run: vi.fn(() => run.promise), reconcile: vi.fn(() => check.promise), cancel: vi.fn()};
    const registry = new ChatSessionRegistry(transport, () => 'run');
    registry.edit('conversation:A', 'question');
    const pending = registry.submit('conversation:A');
    const first = registry.reconcile('conversation:A');
    const second = registry.reconcile('conversation:A');
    expect(transport.reconcile).toHaveBeenCalledOnce();
    check.resolve({status:'COMPLETED'});
    await Promise.all([first, second]);
    run.resolve({status:'COMPLETED'});
    await pending;
  });
  it('does not apply a late status check to the next run', async () => {
    const check = deferred<RunResult>();
    const first = deferred<RunResult>();
    const second = deferred<RunResult>();
    const transport: ChatSessionTransport = {
      createConversation: vi.fn(), run: vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise),
      reconcile: vi.fn(() => check.promise), cancel: vi.fn(async () => ({status: 'CANCELLED' as const}))
    };
    let n = 0;
    const registry = new ChatSessionRegistry(transport, () => `run-${++n}`);
    registry.edit('conversation:A', 'first');
    const firstRun = registry.submit('conversation:A');
    const checking = registry.reconcile('conversation:A');
    await registry.stop('conversation:A');
    registry.edit('conversation:A', 'second');
    const secondRun = registry.submit('conversation:A');
    check.resolve({ status: 'COMPLETED' });
    await checking;
    expect(registry.session('conversation:A').active()).toBe(true);
    expect(registry.session('conversation:A').runId).toBe('run-2');
    first.resolve({ status: 'COMPLETED' });
    second.resolve({ status: 'COMPLETED' });
    await Promise.all([firstRun, secondRun]);
  });
  it('settles a confirmed cancellation even when the stream hangs, and ignores its late result', async () => {
    const stream = deferred<RunResult>();
    const transport: ChatSessionTransport = {
      createConversation: vi.fn(), run: vi.fn(() => stream.promise), reconcile: vi.fn(),
      cancel: vi.fn(async () => ({ status: 'CANCELLED' as const })), stopLocal: vi.fn()
    };
    const registry = new ChatSessionRegistry(transport, () => 'run-a');
    registry.edit('conversation:A', '  raw  ');
    const running = registry.submit('conversation:A');
    await registry.stop('conversation:A');
    expect(registry.session('conversation:A').active()).toBe(false);
    expect(registry.session('conversation:A').result()?.status).toBe('CANCELLED');
    expect(registry.session('conversation:A').draft()).toBe('  raw  ');
    stream.resolve({ status: 'COMPLETED' });
    await running;
    expect(registry.session('conversation:A').result()?.status).toBe('CANCELLED');
  });

  it('keeps failed creation input separately when a newer bot draft exists', async () => {
    let reject!: (reason: Error) => void;
    const create = new Promise<string>((_resolve, fail) => reject = fail);
    const transport: ChatSessionTransport = { createConversation: vi.fn(() => create), run: vi.fn(), reconcile: vi.fn(), cancel: vi.fn() };
    const registry = new ChatSessionRegistry(transport, () => 'create-a');
    registry.edit('bot:a', ' old raw ');
    const pending = registry.submit('bot:a', 'a');
    registry.edit('bot:a', ' new raw ');
    reject(new Error('failed'));
    await pending;
    expect(registry.session('bot:a').draft()).toBe(' new raw ');
    expect(registry.failedCreations('bot:a')()).toEqual([{ runId: 'create-a', raw: ' old raw ' }]);
  });
  it('keeps a draft submission alive across creation and selection changes', async () => {
    const created = deferred<string>();
    const completed = deferred<RunResult>();
    const transport: ChatSessionTransport = {
      createConversation: vi.fn(() => created.promise),
      run: vi.fn(() => completed.promise),
      reconcile: vi.fn(), cancel: vi.fn()
    };
    const registry = new ChatSessionRegistry(transport, () => 'request-a');
    registry.edit('bot:a', '  question\n ');
    const run = registry.submit('bot:a', 'a');
    registry.edit('conversation:b', 'other');
    created.resolve('conversation-a');
    await vi.waitFor(() => expect(transport.run).toHaveBeenCalledOnce());
    expect(transport.run).toHaveBeenCalledWith('conversation-a', 'request-a', '  question\n ', expect.any(Function));
    expect(registry.session('conversation:conversation-a')).not.toBe(registry.session('bot:a'));
    expect(registry.session('bot:a').conversationId).toBeNull();
    completed.resolve({ status: 'COMPLETED' });
    await run;
    expect(registry.session('conversation:b').draft()).toBe('other');
  });

  it('isolates concurrent runs and restores only an unchanged source draft on failure', async () => {
    const pending = new Map<string, ReturnType<typeof deferred<RunResult>>>();
    const transport: ChatSessionTransport = {
      createConversation: vi.fn(),
      run: vi.fn((id, _runId, _text, onDelta) => { onDelta(id); const d = deferred<RunResult>(); pending.set(id, d); return d.promise; }),
      reconcile: vi.fn(), cancel: vi.fn()
    };
    let n = 0;
    const registry = new ChatSessionRegistry(transport, () => `run-${++n}`);
    registry.edit('conversation:A', '  raw A  ');
    registry.edit('conversation:B', 'raw B');
    const a = registry.submit('conversation:A');
    const b = registry.submit('conversation:B');
    expect(registry.session('conversation:A').partial()).toBe('A');
    expect(registry.session('conversation:B').partial()).toBe('B');
    registry.edit('conversation:A', '  raw A  '); // Same text, new version.
    pending.get('A')!.resolve({ status: 'FAILED' });
    pending.get('B')!.resolve({ status: 'FAILED' });
    await Promise.all([a, b]);
    expect(registry.session('conversation:A').draftVersion()).toBe(2);
    expect(registry.session('conversation:A').draft()).toBe('  raw A  ');
    expect(registry.session('conversation:B').draft()).toBe('raw B');
  });

  it('reconciles duplicate runs instead of resending and cancels remotely before local stop', async () => {
    const wait = deferred<RunResult>();
    const order: string[] = [];
    const transport: ChatSessionTransport = {
      createConversation: vi.fn(), run: vi.fn(() => wait.promise),
      reconcile: vi.fn(async () => ({ status: 'COMPLETED' as const })),
      cancel: vi.fn(async () => { order.push('remote'); return { status: 'CANCELLED' as const }; }),
      stopLocal: vi.fn(() => { order.push('local'); })
    };
    const registry = new ChatSessionRegistry(transport, () => 'id');
    registry.edit('conversation:A', 'hello');
    const active = registry.submit('conversation:A');
    await registry.stop('conversation:A');
    expect(order).toEqual(['remote', 'local']);
    wait.resolve({ status: 'CANCELLED' });
    await active;
    expect(transport.run).toHaveBeenCalledOnce();
    expect(transport.reconcile).not.toHaveBeenCalled();
  });
  it('reconciles uncertain transport failure and keeps an unresolved run locked', async () => {
    const transport: ChatSessionTransport = {
      createConversation: vi.fn(), run: vi.fn(async () => { throw new Error('connection lost'); }),
      reconcile: vi.fn(async () => ({ status: 'UNKNOWN' as const })), cancel: vi.fn()
    };
    const registry = new ChatSessionRegistry(transport, () => 'same-id');
    registry.edit('conversation:A', 'original');
    await registry.submit('conversation:A');
    expect(transport.reconcile).toHaveBeenCalledWith('A', 'same-id');
    expect(registry.session('conversation:A').active()).toBe(true);
    registry.edit('conversation:A', 'new input');
    await registry.submit('conversation:A');
    expect(transport.run).toHaveBeenCalledOnce();
  });

  it('rotates the bot draft while creation is pending and preserves later identical text', async () => {
    const created = deferred<string>();
    const done = deferred<RunResult>();
    const transport: ChatSessionTransport = {
      createConversation: vi.fn(() => created.promise), run: vi.fn(() => done.promise),
      reconcile: vi.fn(), cancel: vi.fn()
    };
    const registry = new ChatSessionRegistry(transport, () => 'first-id');
    registry.edit('bot:a', '  same  ');
    const first = registry.submit('bot:a', 'a');
    registry.edit('bot:a', '  same  ');
    created.resolve('A');
    await vi.waitFor(() => expect(transport.run).toHaveBeenCalledOnce());
    done.resolve({ status: 'COMPLETED' });
    await first;
    expect(registry.session('bot:a').conversationId).toBeNull();
    expect(registry.session('bot:a').draft()).toBe('  same  ');
    expect(registry.session('conversation:A').conversationId).toBe('A');
  });

  it('reconciles an uncertain existing run with its original id without starting another', async () => {
    const transport: ChatSessionTransport = {
      createConversation: vi.fn(), run: vi.fn(async () => ({ status: 'UNKNOWN' as const })),
      reconcile: vi.fn().mockResolvedValueOnce({status:'UNKNOWN'}).mockResolvedValueOnce({status:'COMPLETED'}), cancel: vi.fn()
    };
    const registry = new ChatSessionRegistry(transport, () => 'original-id');
    registry.edit('conversation:A', 'question');
    await registry.submit('conversation:A');
    await registry.reconcile('conversation:A');
    expect(transport.run).toHaveBeenCalledOnce();
    expect(transport.reconcile).toHaveBeenLastCalledWith('A', 'original-id');
    expect(registry.session('conversation:A').active()).toBe(false);
  });

});
