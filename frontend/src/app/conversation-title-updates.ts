/** Watches authorized title work without replacing chat sessions or message history. */
export class ConversationTitleUpdates {
  private workspace: string | null = null;
  private readonly watchers = new Map<string, AbortController>();
  private readonly versions = new Map<string, bigint>();
  private readonly queue: string[] = [];
  private readonly rewatch = new Set<string>();
  private active = 0;
  private generation = 0;
  constructor(private readonly merge: (workspaceId: string, id: string, title: string) => void) {}

  watch(workspaceId: string | null, ids: string[]): void {
    if (workspaceId !== this.workspace) {
      this.close();
      this.workspace = workspaceId;
    }
    if (!workspaceId) return;
    for (const id of ids) {
      if (this.watchers.has(id)) { this.rewatch.add(id); continue; }
      this.watchers.set(id, new AbortController());
      this.queue.push(id);
    }
    this.pump(workspaceId);
  }

  private pump(workspaceId: string): void {
    while (this.active < 2 && this.queue.length) {
      const id = this.queue.shift()!;
      const controller = this.watchers.get(id);
      if (!controller || controller.signal.aborted) continue;
      const generation = this.generation;
      this.active++;
      void this.observe(workspaceId, id, controller.signal).then(pending => {
        if (generation !== this.generation) return;
        this.active--;
        if ((pending || this.rewatch.delete(id)) && !controller.signal.aborted) this.queue.push(id);
        else this.watchers.delete(id);
        this.pump(workspaceId);
      });
    }
  }

  close(): void {
    for (const controller of this.watchers.values()) controller.abort();
    this.watchers.clear();
    this.rewatch.clear();
    this.queue.length = 0;
    this.active = 0;
    this.generation++;
    this.versions.clear();
    this.workspace = null;
  }

  private async observe(workspaceId: string, id: string, signal: AbortSignal): Promise<boolean> {
    try {
      if (!signal.aborted && this.workspace === workspaceId) {
        const response = await fetch('/graphql', {method:'POST', signal,
          headers:{'Content-Type':'application/json', 'Accept':'text/event-stream'},
          body:JSON.stringify({query:'subscription ConversationTitleChanges($id: ID!) { conversationTitleChanged(conversationId: $id) { id version state } }', variables:{id}})});
        if ([401, 403, 404].includes(response.status)) return false;
        if (!response.ok || !response.body || !response.headers.get('content-type')?.includes('text/event-stream')) return this.deferRetry(workspaceId, signal);
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '', data: string[] = [], terminal = false, pending = false, error = false, received = false;
        try {
          while (!terminal && !signal.aborted) {
            const chunk = await reader.read();
            if (chunk.done) break;
            buffer += decoder.decode(chunk.value, {stream:true});
            let newline: number;
            while ((newline = buffer.indexOf('\n')) >= 0) {
              let line = buffer.slice(0, newline); buffer = buffer.slice(newline + 1);
              if (line.endsWith('\r')) line = line.slice(0, -1);
              if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
              if (line !== '') continue;
              if (data.length) {
                const event = JSON.parse(data.join('\n'));
                const change = event?.data?.conversationTitleChanged;
                if (change?.id === id && /^\d+$/.test(change.version)) {
                  received = true;
                  const version = BigInt(change.version);
                  let retry = false;
                  if (version > 0n && version > (this.versions.get(id) ?? 0n)) {
                    if (await this.refresh(workspaceId, id, signal)) this.versions.set(id, version);
                    else retry = true;
                  }
                  pending = retry || change.state === 'WAITING' || change.state === 'READY' || change.state === 'CLAIMED';
                  terminal = true;
                } else if (event?.errors) { terminal = true; error = true; }
              }
              data = [];
            }
          }
        } finally { await reader.cancel().catch(() => {}); }
        return error || !received || pending ? this.deferRetry(workspaceId, signal) : false;
      }
    } catch {
      return this.deferRetry(workspaceId, signal);
    }
    return false;
  }

  private async deferRetry(workspaceId: string, signal: AbortSignal): Promise<boolean> {
    if (signal.aborted || this.workspace !== workspaceId) return false;
    await new Promise<void>(resolve => {
      const timer = setTimeout(done, 1000);
      function done() { clearTimeout(timer); signal.removeEventListener('abort', done); resolve(); }
      signal.addEventListener('abort', done, {once:true});
      if (signal.aborted) done();
    });
    return !signal.aborted && this.workspace === workspaceId;
  }

  private async refresh(workspaceId: string, id: string, signal: AbortSignal): Promise<boolean> {
    const response = await fetch('/graphql', {method:'POST', signal, headers:{'Content-Type':'application/json'},
      body:JSON.stringify({query:'query ConversationTitleRead($id: ID!) { conversation(id: $id) { id workspaceId title } }', variables:{id}})});
    if (!response.ok) return false;
    const row = (await response.json())?.data?.conversation;
    if (!signal.aborted && this.workspace === workspaceId && row?.id === id && row.workspaceId === workspaceId && typeof row.title === 'string') {
      this.merge(workspaceId, id, row.title);
      return true;
    }
    return false;
  }
}
