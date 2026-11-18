import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { App } from './app';
import { BotView } from './bot/bot.model';
import { signal } from '@angular/core';

const local: BotView = { id:'local', name:'General Assistant', target:'LOCAL', connectionStatus:'NOT_APPLICABLE', executionMode:'LOCAL', canChat:true, createdAt:'', updatedAt:'' };
const botA: BotView = { ...local, id:'a', name:'Bot A', target:'GKE_REMOTE', connectionStatus:'NOT_CONNECTED', executionMode:'SPRING_AI_SIMULATION', canChat:true };
const botB: BotView = { ...botA, id:'b', name:'Bot B', executionMode:'UNAVAILABLE', canChat:false };
const conversation = (id: string, botId = local.id) => ({ id, workspaceId:'personal', botId, title:id, pinned:false, createdAt:'', updatedAt:'', messages:[], runs:[] });
function deferred<T>() { let resolve!: (v:T)=>void; let reject!: (e:unknown)=>void; const promise = new Promise<T>((yes,no)=>{resolve=yes;reject=no;}); return {promise,resolve,reject}; }

describe('App ThreadPlane public behavior', () => {
  let requests: {query:string; variables:any}[];
  beforeEach(() => {
    requests = [];
    const storage = new Map<string,string>();
    vi.stubGlobal("localStorage", { getItem:(key:string)=>storage.get(key) ?? null, setItem:(key:string,value:string)=>storage.set(key,value), removeItem:(key:string)=>storage.delete(key), clear:()=>storage.clear() });
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init?: RequestInit) => {
      const body = JSON.parse(String(init?.body || '{}'));
      requests.push(body);
      if (body.query?.includes('Workspaces')) return Response.json({data:{workspaces:[{id:'personal',name:'Personal',role:'MANAGER',personal:true}]}});
      if (body.query?.includes('defaultLocalBot')) return Response.json({data:{defaultLocalBot:local,bots:[botA,botB]}});
      if (body.query?.includes('conversations(')) return Response.json({data:{conversations:[]}});
      if (body.query?.includes('bots(')) return Response.json({data:{bots:[botA,botB],defaultLocalBot:local}});
      return Response.json({data:{}});
    }));
  });
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
  async function setup() {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    await vi.waitFor(() => expect(fixture.componentInstance.isInitializing()).toBe(false));
    fixture.detectChanges();
    const app = fixture.componentInstance;
    vi.spyOn(app.chatTransport,'reconcile').mockResolvedValue({status:'COMPLETED'});
    return { fixture, app, root: fixture.nativeElement as HTMLElement };
  }
  function input(root: HTMLElement, value: string) {
    const textarea = root.querySelector('chat-input textarea') as HTMLTextAreaElement;
    textarea.value = value;
    textarea.dispatchEvent(new Event('input',{bubbles:true}));
    return textarea;
  }

  it('does not place a late A creation into selected B or steal B draft', async () => {
    const {app} = await setup();
    app.workspaces.workspaces.set([{id:'personal',name:'A',role:'MANAGER',personal:true},{id:'B',name:'B',role:'MEMBER',personal:false}]);
    const creation = deferred<string>();
    const create = vi.spyOn(app.chatTransport,'createConversation').mockReturnValue(creation.promise);
    vi.spyOn(app.chatTransport,'run').mockResolvedValue({status:'COMPLETED'});
    app.openBotDraft(botA); app.chatDraftChanged('A raw'); app.submitChat('A raw');
    await app.selectWorkspace('B'); app.openBotDraft(botA); app.chatDraftChanged('B raw');
    app.chatTransport.created.set('created-A',{id:'created-A',botId:'a',title:'A',createdAt:'',updatedAt:''});
    creation.resolve('created-A');
    await vi.waitFor(() => expect(app.chatTransport.run).toHaveBeenCalledOnce());
    expect(create).toHaveBeenCalledWith('a', 'personal');
    expect(app.workspaces.selectedId()).toBe('B'); expect(app.currentConversationId()).toBeNull();
    expect(app.chatSession().draft()).toBe('B raw');
    expect(app.conversations().some(item => item.id === 'created-A')).toBe(false);
  });
  it('shows cancellation denial without settling the active run', async () => {
    const {app,fixture,root} = await setup();
    const pending = deferred<{status:'COMPLETED'}>();
    vi.spyOn(app.chatTransport,'run').mockReturnValue(pending.promise);
    vi.spyOn(app.chatTransport,'cancel').mockResolvedValue({status:'REJECTED'});
    const stopLocal = vi.spyOn(app.chatTransport,'stopLocal');
    vi.spyOn(app.chatTransport,'createConversation').mockResolvedValue('A');
    app.openBotDraft(botA); app.chatDraftChanged('sent'); app.submitChat('sent');
    await vi.waitFor(() => expect(app.chatSession().conversationId).toBe('A'));
    await vi.waitFor(() => expect(app.chatSession().active()).toBe(true));
    app.stopChat(); await vi.waitFor(() => expect(app.streamError()).toContain('無權取消'));
    fixture.detectChanges(); expect(app.chatSession().active()).toBe(true); expect(app.chatSession().draft()).toBe('');
    expect(stopLocal).not.toHaveBeenCalled(); expect(root.textContent).toContain('查詢');
    pending.resolve({status:'COMPLETED'});
  });
  it('does not select a hydrated conversation from a different workspace', async () => {
    const {app} = await setup();
    vi.spyOn(app.chatTransport, 'hydrate').mockResolvedValue({...conversation('foreign'), workspaceId:'B'});
    await app.selectConversation('foreign');
    expect(app.currentConversationId()).not.toBe('foreign');
    expect(app.activeConversation()?.id).not.toBe('foreign');
  });
  it('retries failed history with the selected workspace and activates the verified conversation', async () => {
    const {app} = await setup();
    const hydrate = vi.spyOn(app.chatTransport, 'hydrate').mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(conversation('C'));
    await app.selectConversation('C'); expect(app.streamError()).toContain('載入對話歷史失敗');
    app.retryHistory();
    await vi.waitFor(() => expect(app.activeConversation()?.id).toBe('C'));
    expect(hydrate).toHaveBeenLastCalledWith('C','personal');
    expect(app.chatCanSend()).toBe(true);
  });
  it('shows real hydrated history after a scoped retry and ignores a retry that finishes after switching', async () => {
    const {app, fixture, root} = await setup();
    const row = {id:'message-C',runId:'r',role:'USER',content:'saved history',createdAt:'2026-01-01T00:00:00Z'};
    let resolve!: (value: Response) => void; let count = 0;
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init?: RequestInit) => {
      const query = JSON.parse(String(init?.body || '{}')).query || '';
      if (!query.includes('SessionHistory')) return Response.json({data:{conversations:[]}});
      if (++count === 1) throw new Error('offline');
      if (count === 2) return Response.json({data:{conversation:{...conversation('C'),messages:[row]}}});
      if (count === 3) throw new Error('offline again');
      return new Promise<Response>(r => resolve = r);
    }));
    await app.selectConversation('C'); expect(app.streamError()).toContain('載入對話歷史失敗');
    app.retryHistory(); await vi.waitFor(() => expect(app.activeConversation()?.id).toBe('C'));
    fixture.detectChanges(); expect(app.chatCanSend()).toBe(true); expect(root.textContent).toContain('saved history');
    await app.selectConversation('D'); expect(app.streamError()).toContain('載入對話歷史失敗');
    app.retryHistory(); await vi.waitFor(() => expect(count).toBe(4));
    app.workspaces.workspaces.set([{id:'personal',name:'A',role:'MANAGER',personal:true},{id:'B',name:'B',role:'MEMBER',personal:false}]);
    await app.selectWorkspace('B'); app.chatDraftChanged('B draft');
    resolve(Response.json({data:{conversation:{...conversation('D'),messages:[row]}}}));
    await vi.waitFor(() => expect(app.workspaces.selectedId()).toBe('B'));
    await new Promise(r => setTimeout(r, 0));
    expect(app.currentConversationId()).toBeNull(); expect(app.chatSession().draft()).toBe('B draft');
    expect(app.chatAgent().messages().some(m => m.content === 'saved history')).toBe(false);
  });
  it('does not project a conversation draft before its workspace is verified', async () => {
    const {app} = await setup();
    app.chatSessions.edit('conversation:C', 'private draft');
    const history = deferred<any>(); vi.spyOn(app.chatTransport,'hydrate').mockReturnValue(history.promise);
    const loading = app.selectConversation('C');
    expect(app.chatSession().draft()).not.toBe('private draft');
    history.resolve({...conversation('C'), workspaceId:'B'}); await loading;
    expect(app.chatSession().draft()).not.toBe('private draft');
  });
  it('keeps a deep selected conversation when an earlier creation completes', async () => {
    const {app} = await setup();
    const creating = deferred<string>();
    vi.spyOn(app.chatTransport, 'createConversation').mockReturnValue(creating.promise);
    vi.spyOn(app.chatTransport, 'run').mockResolvedValue({status:'COMPLETED'});
    vi.spyOn(app.chatTransport, 'hydrate').mockResolvedValue(conversation('deep-Y'));
    app.openBotDraft(botA); app.chatDraftChanged('new X'); app.submitChat('new X');
    await app.selectConversation('deep-Y');
    expect(app.currentConversationId()).toBe('deep-Y'); expect(app.activeConversation()?.id).toBe('deep-Y');
    expect(app.conversations().some(item => item.id === 'deep-Y')).toBe(false); const agent = app.chatAgent();
    app.chatTransport.created.set('new-X',{id:'new-X',botId:'a',title:'X',createdAt:'',updatedAt:''});
    creating.resolve('new-X'); await vi.waitFor(() => expect(app.chatTransport.run).toHaveBeenCalledOnce());
    expect(app.currentConversationId()).toBe('deep-Y'); expect(app.activeConversation()?.id).toBe('deep-Y');
    expect(app.currentTitle()).toBe('deep-Y'); expect(app.chatAgent()).toBe(agent); expect(app.navigation()?.deepActive().map(item => item.id)).toEqual(['deep-Y']);
    expect(app.conversations().some(item => item.id === 'new-X')).toBe(true);
  });
  it('opens personal usage and returns to the same unsent chat draft', async () => {
    const {app,fixture,root} = await setup(); app.chatDraftChanged('unsent draft'); fixture.detectChanges();
    const usage = root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="個人用量"]');
    expect(usage).toBeTruthy(); usage!.click(); fixture.detectChanges();
    expect(root.querySelector('app-usage')).toBeTruthy();
    const back = Array.from(root.querySelectorAll('button')).find(button => button.textContent?.includes('返回對話'));
    back!.click(); fixture.detectChanges(); expect(app.chatSession().draft()).toBe('unsent draft');
  });
  it('keeps a background run alive while visiting personal usage', async () => {
    const {app,fixture,root} = await setup(); const running = deferred<{status:'COMPLETED'}>();
    vi.spyOn(app.chatTransport,'createConversation').mockResolvedValue('usage-run');
    vi.spyOn(app.chatTransport,'run').mockReturnValue(running.promise);
    app.openBotDraft(botA); app.chatDraftChanged('question'); app.submitChat('question');
    await vi.waitFor(() => expect(app.currentConversationId()).toBe('usage-run'));
    fixture.detectChanges(); root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="個人用量"]')!.click(); fixture.detectChanges();
    expect(app.chatSessions.session('conversation:usage-run').active()).toBe(true);
    running.resolve({status:'COMPLETED'}); await vi.waitFor(() => expect(app.chatSessions.session('conversation:usage-run').active()).toBe(false));
    fixture.detectChanges(); Array.from(root.querySelectorAll('button')).find(b => b.textContent?.includes('返回對話'))!.click(); fixture.detectChanges();
    expect(app.currentConversationId()).toBe('usage-run'); expect(app.chatSession().result()?.status).toBe('COMPLETED');
  });
  it('toggles the context without replacing the chat or making requests', async () => {
    const {fixture, root} = await setup();
    const textarea = input(root, '  raw\n draft '); fixture.detectChanges();
    const surface = root.querySelector('app-chat-surface');
    const before = requests.length;
    const toggle = root.querySelector<HTMLButtonElement>('button[aria-label="切換側欄"]');
    expect(toggle).toBeTruthy();
    expect(toggle!.getAttribute('aria-expanded')).toBe('true');
    expect(root.querySelector('[aria-label="主要功能"]')).toBeTruthy();
    toggle!.click(); fixture.detectChanges();
    const context = root.querySelector<HTMLElement>('#navigation-context')!;
    expect(toggle!.getAttribute('aria-expanded')).toBe('false');
    expect(context.hidden || context.hasAttribute('inert')).toBe(true);
    expect(root.querySelector('app-chat-surface')).toBe(surface);
    expect(textarea.value).toBe('  raw\n draft ');
    toggle!.click(); fixture.detectChanges();
    expect(toggle!.getAttribute('aria-expanded')).toBe('true');
    expect(context.hidden || context.hasAttribute('inert')).toBe(false);
    expect(root.querySelector('app-chat-surface')).toBe(surface);
    expect(requests).toHaveLength(before);
  });
  it('keeps the same chat and raw draft while visiting usage and returning', async () => {
    const {fixture, root} = await setup();
    const textarea = input(root, '  saved\n draft '); fixture.detectChanges();
    const surface = root.querySelector('app-chat-surface');
    root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="個人用量"]')!.click();
    fixture.detectChanges();
    expect(root.querySelector('app-usage')).toBeTruthy();
    const back = Array.from(root.querySelectorAll('button')).find(button => button.textContent?.includes('返回對話'))!;
    back.click(); fixture.detectChanges();
    expect(root.querySelector('app-chat-surface')).toBe(surface);
    expect(root.querySelector<HTMLTextAreaElement>('chat-input textarea')!.value).toBe('  saved\n draft ');
  });
  it('switches the active feature and context to chat when starting a Bot draft', async () => {
    const {fixture, root} = await setup();
    const bots = root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="Bot"]')!;
    bots.click(); fixture.detectChanges();
    await vi.waitFor(() => { fixture.detectChanges(); expect(root.querySelector('[data-testid="bot-item-a"]')).toBeTruthy(); });
    root.querySelector<HTMLButtonElement>('[data-testid="bot-item-a"]')!.click(); fixture.detectChanges();
    expect(root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="對話"]')!.getAttribute('aria-pressed')).toBe('true');
    expect(root.querySelector('[aria-label="近期對話"]')).toBeTruthy();
    expect(root.querySelector('[aria-label="Bot 清單"]')).toBeNull();
    expect(root.querySelector('[data-testid="header-bot-name"]')?.textContent).toContain('Bot A');
  });
  it('reselecting the active conversation feature preserves the draft without requests', async () => {
    const {fixture, root} = await setup();
    const textarea = input(root, '  draft '); fixture.detectChanges();
    const before = requests.length;
    root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="對話"]')!.click();
    fixture.detectChanges();
    expect(textarea.value).toBe('  draft ');
    expect(requests).toHaveLength(before);
  });
  it('returns focus to the header toggle when collapsing a focused context', async () => {
    const {fixture, root} = await setup();
    const toggle = root.querySelector<HTMLButtonElement>('button[aria-label="切換側欄"]')!;
    const action = root.querySelector<HTMLButtonElement>('#navigation-context button[aria-label="搜尋對話"]')!;
    document.body.appendChild(root); action.focus(); expect(document.activeElement).toBe(action);
    toggle.click(); fixture.detectChanges();
    expect(document.activeElement).toBe(toggle);
  });
  it('offers the context toggle from Bot management', async () => {
    const {fixture, root} = await setup();
    root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="Bot"]')!.click();
    fixture.detectChanges();
    const toggle = root.querySelector<HTMLButtonElement>('main button[aria-label="切換側欄"]');
    expect(toggle).toBeTruthy();
    expect(toggle!.closest('[hidden]')).toBeNull();
    toggle!.click(); fixture.detectChanges();
    expect(toggle!.getAttribute('aria-expanded')).toBe('false');
    expect(root.querySelector<HTMLElement>('#navigation-context')!.hidden).toBe(true);
  });
  it('keeps the existing narrow-screen navigation usable', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:true, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup();
    const open = root.querySelector<HTMLButtonElement>('button[aria-label="開啟導覽"]')!;
    const context = root.querySelector<HTMLElement>('#navigation-context')!;
    expect(context.hidden).toBe(true);
    open.click(); fixture.detectChanges();
    expect(context.hidden).toBe(false);
    expect(open.getAttribute('aria-expanded')).toBe('true');
  });
  it('keeps a partial AG-UI run active across a usage visit', async () => {
    const {fixture, root} = await setup();
    const encoder = new TextEncoder(); let push!: (event: object) => void; let close!: () => void; let runId = ''; let calls = 0;
    const stream = new ReadableStream<Uint8Array>({ start(controller) { push = event => controller.enqueue(encoder.encode(`data: ${JSON.stringify(event)}\n\n`)); close = () => controller.close(); } });
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      const body = JSON.parse(String(init?.body || '{}'));
      if (String(url).includes('/api/ag-ui')) { calls++; runId = body.runId; return new Response(stream, {headers:{'Content-Type':'text/event-stream'}}); }
      if (body.query?.includes('CreateConversation')) return Response.json({data:{createConversation:conversation('stream')}});
      if (body.query?.includes('myUsageAttempts')) return Response.json({data:{myUsageAttempts:{nodes:[],pageInfo:{hasNextPage:false,endCursor:null}}}});
      return Response.json({data:{}});
    }));
    const textarea = input(root, '  question  '); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('chat-input button[aria-label="Send message"]')!.click();
    await vi.waitFor(() => expect(calls).toBe(1));
    push({type:'RUN_STARTED',threadId:'stream',runId});
    push({type:'TEXT_MESSAGE_START',messageId:'answer',role:'assistant'});
    push({type:'TEXT_MESSAGE_CONTENT',messageId:'answer',delta:'partial answer'});
    await vi.waitFor(() => { fixture.detectChanges(); expect(root.textContent).toContain('partial answer'); });
    const surface = root.querySelector('app-chat-surface');
    root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="個人用量"]')!.click(); fixture.detectChanges();
    push({type:'TEXT_MESSAGE_CONTENT',messageId:'answer',delta:' complete text'});
    push({type:'TEXT_MESSAGE_END',messageId:'answer'});
    await vi.waitFor(() => { fixture.detectChanges(); expect(root.textContent).toContain('partial answer complete text'); });
    Array.from(root.querySelectorAll('button')).find(button => button.textContent?.includes('返回對話'))!.click(); fixture.detectChanges();
    expect(root.querySelector('app-chat-surface')).toBe(surface);
    expect(root.textContent).toContain('partial answer complete text');
    expect(root.querySelector('chat-input button[aria-label="Stop generating"]')).toBeTruthy();
    expect(calls).toBe(1);
    expect(textarea.value).toBe('');
    push({type:'RUN_FINISHED',threadId:'stream',runId}); close();
  });
  it('shows a partial run failure after returning from Bot management', async () => {
    const {fixture, root} = await setup();
    const encoder = new TextEncoder(); let push!: (event: object) => void; let close!: () => void; let runId = ''; let calls = 0;
    const stream = new ReadableStream<Uint8Array>({ start(controller) { push = event => controller.enqueue(encoder.encode(`data: ${JSON.stringify(event)}\n\n`)); close = () => controller.close(); } });
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      const body = JSON.parse(String(init?.body || '{}'));
      if (String(url).includes('/api/ag-ui')) { calls++; runId = body.runId; return new Response(stream, {headers:{'Content-Type':'text/event-stream'}}); }
      if (body.query?.includes('CreateConversation')) return Response.json({data:{createConversation:conversation('stream')}});
      if (body.query?.includes('bots(')) return Response.json({data:{bots:[botA,botB],defaultLocalBot:local}});
      return Response.json({data:{}});
    }));
    input(root, '  raw input  '); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('chat-input button[aria-label="Send message"]')!.click();
    await vi.waitFor(() => expect(calls).toBe(1));
    push({type:'RUN_STARTED',threadId:'stream',runId});
    push({type:'TEXT_MESSAGE_START',messageId:'answer',role:'assistant'});
    push({type:'TEXT_MESSAGE_CONTENT',messageId:'answer',delta:'partial failure'});
    await vi.waitFor(() => { fixture.detectChanges(); expect(root.textContent).toContain('partial failure'); });
    root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="Bot"]')!.click(); fixture.detectChanges();
    push({type:'RUN_ERROR',message:'failed'}); close();
    await vi.waitFor(() => { fixture.detectChanges(); expect(root.textContent).toContain('執行失敗'); });
    root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="對話"]')!.click(); fixture.detectChanges();
    expect(root.textContent).toContain('partial failure');
    expect(root.querySelector<HTMLTextAreaElement>('chat-input textarea')!.value).toBe('  raw input  ');
    expect(root.querySelector('chat-input button[aria-label="Stop generating"]')).toBeNull();
    expect(calls).toBe(1);
  });
  it('keeps a newer draft when a partial run is cancelled', async () => {
    const {fixture, root} = await setup();
    const encoder = new TextEncoder(); let push!: (event: object) => void; let close!: () => void; let runId = ''; let calls = 0;
    const stream = new ReadableStream<Uint8Array>({ start(controller) { push = event => controller.enqueue(encoder.encode(`data: ${JSON.stringify(event)}\n\n`)); close = () => controller.close(); } });
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      const body = JSON.parse(String(init?.body || '{}'));
      if (String(url).includes('/api/ag-ui')) { calls++; runId = body.runId; return new Response(stream, {headers:{'Content-Type':'text/event-stream'}}); }
      if (body.query?.includes('CreateConversation')) return Response.json({data:{createConversation:conversation('stream')}});
      if (body.query?.includes('bots(')) return Response.json({data:{bots:[botA,botB],defaultLocalBot:local}});
      return Response.json({data:{}});
    }));
    input(root, '  raw input  '); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('chat-input button[aria-label="Send message"]')!.click();
    await vi.waitFor(() => expect(calls).toBe(1));
    push({type:'RUN_STARTED',threadId:'stream',runId});
    push({type:'TEXT_MESSAGE_START',messageId:'answer',role:'assistant'});
    push({type:'TEXT_MESSAGE_CONTENT',messageId:'answer',delta:'partial cancelled'});
    await vi.waitFor(() => { fixture.detectChanges(); expect(root.textContent).toContain('partial cancelled'); });
    root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="Bot"]')!.click(); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="對話"]')!.click(); fixture.detectChanges();
    input(root, ' newer draft '); fixture.detectChanges();
    push({type:'TEXT_MESSAGE_END',messageId:'answer'});
    push({type:'RUN_FINISHED',threadId:'stream',runId,outcome:{type:'cancelled'}}); close();
    await vi.waitFor(() => { fixture.detectChanges(); expect(root.textContent).toContain('回覆已停止'); });
    expect(root.textContent).toContain('partial cancelled');
    expect(root.querySelector<HTMLTextAreaElement>('chat-input textarea')!.value).toBe(' newer draft ');
    expect(root.querySelector('chat-input button[aria-label="Stop generating"]')).toBeNull();
    expect(calls).toBe(1);
  });
  it('keeps a newer draft when a partial run fails', async () => {
    const {fixture, root} = await setup();
    const encoder = new TextEncoder(); let push!: (event: object) => void; let close!: () => void; let runId = ''; let calls = 0;
    const stream = new ReadableStream<Uint8Array>({ start(controller) { push = event => controller.enqueue(encoder.encode(`data: ${JSON.stringify(event)}\n\n`)); close = () => controller.close(); } });
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      const body = JSON.parse(String(init?.body || '{}'));
      if (String(url).includes('/api/ag-ui')) { calls++; runId = body.runId; return new Response(stream, {headers:{'Content-Type':'text/event-stream'}}); }
      if (body.query?.includes('CreateConversation')) return Response.json({data:{createConversation:conversation('stream')}});
      if (body.query?.includes('bots(')) return Response.json({data:{bots:[botA,botB],defaultLocalBot:local}});
      return Response.json({data:{}});
    }));
    input(root, '  raw input  '); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('chat-input button[aria-label="Send message"]')!.click();
    await vi.waitFor(() => expect(calls).toBe(1));
    push({type:'RUN_STARTED',threadId:'stream',runId});
    push({type:'TEXT_MESSAGE_START',messageId:'answer',role:'assistant'});
    push({type:'TEXT_MESSAGE_CONTENT',messageId:'answer',delta:'partial newer failure'});
    await vi.waitFor(() => { fixture.detectChanges(); expect(root.textContent).toContain('partial newer failure'); });
    root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="Bot"]')!.click(); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="對話"]')!.click(); fixture.detectChanges();
    input(root, ' newer draft '); fixture.detectChanges();
    push({type:'RUN_ERROR',message:'failed'}); close();
    await vi.waitFor(() => { fixture.detectChanges(); expect(root.textContent).toContain('執行失敗'); });
    expect(root.textContent).toContain('partial newer failure');
    expect(root.querySelector<HTMLTextAreaElement>('chat-input textarea')!.value).toBe(' newer draft ');
    expect(root.querySelector('chat-input button[aria-label="Stop generating"]')).toBeNull();
    expect(calls).toBe(1);
  });
  it('restores original raw draft when a partial run is cancelled without a new edit', async () => {
    const {fixture, root} = await setup();
    const encoder = new TextEncoder(); let push!: (event: object) => void; let close!: () => void; let runId = ''; let calls = 0;
    const stream = new ReadableStream<Uint8Array>({ start(controller) { push = event => controller.enqueue(encoder.encode(`data: ${JSON.stringify(event)}\n\n`)); close = () => controller.close(); } });
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      const body = JSON.parse(String(init?.body || '{}'));
      if (String(url).includes('/api/ag-ui')) { calls++; runId = body.runId; return new Response(stream, {headers:{'Content-Type':'text/event-stream'}}); }
      if (body.query?.includes('CreateConversation')) return Response.json({data:{createConversation:conversation('stream')}});
      if (body.query?.includes('bots(')) return Response.json({data:{bots:[botA,botB],defaultLocalBot:local}});
      return Response.json({data:{}});
    }));
    input(root, '  raw input  '); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('chat-input button[aria-label="Send message"]')!.click();
    await vi.waitFor(() => expect(calls).toBe(1));
    push({type:'RUN_STARTED',threadId:'stream',runId});
    push({type:'TEXT_MESSAGE_START',messageId:'answer',role:'assistant'});
    push({type:'TEXT_MESSAGE_CONTENT',messageId:'answer',delta:'partial cancelled'});
    await vi.waitFor(() => { fixture.detectChanges(); expect(root.textContent).toContain('partial cancelled'); });
    root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="Bot"]')!.click(); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="對話"]')!.click(); fixture.detectChanges();
    push({type:'TEXT_MESSAGE_END',messageId:'answer'});
    push({type:'RUN_FINISHED',threadId:'stream',runId,outcome:{type:'cancelled'}}); close();
    await vi.waitFor(() => { fixture.detectChanges(); expect(root.textContent).toContain('回覆已停止'); });
    expect(root.textContent).toContain('partial cancelled');
    expect(root.querySelector<HTMLTextAreaElement>('chat-input textarea')!.value).toBe('  raw input  ');
    expect(root.querySelector('chat-input button[aria-label="Stop generating"]')).toBeNull();
    expect(calls).toBe(1);
  });
  it('identifies each feature with an icon and an accessible label', async () => {
    const {root} = await setup();
    for (const label of ['對話', 'Bot', '個人用量']) {
      const button = root.querySelector<HTMLButtonElement>(`nav[aria-label="主要功能"] button[aria-label="${label}"]`)!;
      expect(button.querySelector('svg[aria-hidden="true"]')).toBeTruthy();
      expect(button.querySelector('.rail-label')?.textContent).toContain(label);
    }
  });
  it('keeps the workspace and full conversation title available in the chat header', async () => {
    const {fixture, root} = await setup();
    const header = root.querySelector('app-chat-surface header')!;
    expect(header.textContent).toContain('Personal');
    const title = header.querySelector<HTMLElement>('[data-testid="chat-header-title"]')!;
    expect(title.getAttribute('title')).toBe('New Conversation');
    expect(title.getAttribute('tabindex')).toBe('0');
  });
  it('names the context for the selected feature', async () => {
    const {fixture, root} = await setup();
    const context = root.querySelector('#navigation-context')!;
    expect(context.querySelector('.navigation-title h2')?.textContent).toContain('對話');
    root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="Bot"]')!.click(); fixture.detectChanges();
    expect(context.querySelector('.navigation-title h2')?.textContent).toContain('Bot');
  });
  it('preserves the desktop sidebar shortcut without intercepting composer input', async () => {
    const {fixture, root} = await setup();
    const context = root.querySelector<HTMLElement>('#navigation-context')!;
    const textarea = root.querySelector<HTMLTextAreaElement>('chat-input textarea')!;
    const editing = new KeyboardEvent('keydown', {key:'b',ctrlKey:true,bubbles:true,cancelable:true});
    textarea.dispatchEvent(editing); fixture.detectChanges();
    expect(editing.defaultPrevented).toBe(false); expect(context.hidden).toBe(false);
    const shortcut = new KeyboardEvent('keydown', {key:'b',ctrlKey:true,bubbles:true,cancelable:true});
    window.dispatchEvent(shortcut); fixture.detectChanges();
    expect(shortcut.defaultPrevented).toBe(true); expect(context.hidden).toBe(true);
  });
  it('opens search with the existing shortcut while leaving editable input alone', async () => {
    const {fixture, root} = await setup();
    const inputArea = root.querySelector<HTMLTextAreaElement>('chat-input textarea')!;
    const editing = new KeyboardEvent('keydown', {key:'k',metaKey:true,bubbles:true,cancelable:true});
    inputArea.dispatchEvent(editing); fixture.detectChanges();
    expect(editing.defaultPrevented).toBe(false); expect(root.querySelector('[role="combobox"]')).toBeNull();
    const editable = document.createElement('div'); editable.setAttribute('contenteditable', 'true');
    Object.defineProperty(editable, 'isContentEditable', {value:true}); root.appendChild(editable);
    const editShortcut = new KeyboardEvent('keydown', {key:'k',ctrlKey:true,bubbles:true,cancelable:true});
    editable.dispatchEvent(editShortcut); fixture.detectChanges();
    expect(editShortcut.defaultPrevented).toBe(false); expect(root.querySelector('[role="combobox"]')).toBeNull();
    const shortcut = new KeyboardEvent('keydown', {key:'k',metaKey:true,bubbles:true,cancelable:true});
    window.dispatchEvent(shortcut); fixture.detectChanges();
    expect(shortcut.defaultPrevented).toBe(true); expect(root.querySelector('[role="combobox"]')).toBeTruthy();
  });
  it('does not intercept the sidebar shortcut in usage', async () => {
    const {fixture, root} = await setup();
    root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="個人用量"]')!.click(); fixture.detectChanges();
    const shortcut = new KeyboardEvent('keydown', {key:'b',metaKey:true,bubbles:true,cancelable:true});
    window.dispatchEvent(shortcut); fixture.detectChanges();
    expect(shortcut.defaultPrevented).toBe(false);
    expect(root.querySelector<HTMLElement>('#navigation-context')!.hidden).toBe(true);
  });
  it('does not intercept the sidebar shortcut in drawer mode', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:true, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup();
    const shortcut = new KeyboardEvent('keydown', {key:'b',ctrlKey:true,bubbles:true,cancelable:true});
    window.dispatchEvent(shortcut); fixture.detectChanges();
    expect(shortcut.defaultPrevented).toBe(false);
    expect(root.querySelector<HTMLElement>('#navigation-context')!.hidden).toBe(true);
  });
  it('opens narrow navigation after a desktop collapse without changing desktop preference', async () => {
    let change!: () => void; const media = {matches:false, addEventListener:(_:string, fn:()=>void)=>{change=fn;}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}};
    vi.stubGlobal('matchMedia', () => media);
    const {fixture, root} = await setup();
    root.querySelector<HTMLButtonElement>('button[aria-label="切換側欄"]')!.click(); fixture.detectChanges();
    media.matches = true; change(); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('button[aria-label="開啟導覽"]')!.click(); fixture.detectChanges();
    expect(root.querySelector<HTMLElement>('#navigation-context')!.hidden).toBe(false);
    media.matches = false; change(); fixture.detectChanges();
    expect(root.querySelector<HTMLElement>('#navigation-context')!.hidden).toBe(true);
  });
  it('does not expose a mobile context opener on usage', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:true, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup();
    root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="個人用量"]')!.click(); fixture.detectChanges();
    expect(root.querySelector('button[aria-label="開啟導覽"]')).toBeNull();
  });
  it('does not intercept modified, already-handled, or composing shortcuts', async () => {
    const {fixture, root} = await setup();
    for (const options of [{shiftKey:true}, {altKey:true}, {isComposing:true}]) {
      const event = new KeyboardEvent('keydown', {key:'b',ctrlKey:true,bubbles:true,cancelable:true,...options});
      window.dispatchEvent(event); fixture.detectChanges();
      expect(event.defaultPrevented).toBe(false);
      expect(root.querySelector<HTMLElement>('#navigation-context')!.hidden).toBe(false);
    }
    const handled = new KeyboardEvent('keydown', {key:'k',metaKey:true,bubbles:true,cancelable:true}); handled.preventDefault();
    window.dispatchEvent(handled); fixture.detectChanges();
    expect(root.querySelector('[role="combobox"]')).toBeNull();
  });
  it('makes the rail and main inert while the mobile navigation is modal', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:true, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup();
    const rail = root.querySelector<HTMLElement>('nav[aria-label="主要功能"]')!;
    const main = root.querySelector<HTMLElement>('main')!;
    root.querySelector<HTMLButtonElement>('button[aria-label="開啟導覽"]')!.click(); fixture.detectChanges();
    expect(rail.hasAttribute('inert')).toBe(true); expect(main.hasAttribute('inert')).toBe(true);
    root.querySelector<HTMLElement>('[role="dialog"][aria-label="側欄導覽"]')!.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape',bubbles:true})); fixture.detectChanges();
    expect(rail.hasAttribute('inert')).toBe(false); expect(main.hasAttribute('inert')).toBe(false);
  });
  it('closes the mobile drawer with its visible close control and returns focus', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:true, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup(); document.body.appendChild(root);
    const open = root.querySelector<HTMLButtonElement>('button[aria-label="開啟導覽"]')!;
    open.style.display = 'block'; // jsdom does not apply the stubbed media query.
    open.focus(); expect(document.activeElement).toBe(open); open.click(); fixture.detectChanges();
    const dialog = root.querySelector<HTMLElement>('[role="dialog"][aria-label="側欄導覽"]')!;
    expect(dialog).toBeTruthy();
    const before = requests.length;
    const close = dialog.querySelector<HTMLButtonElement>('button[aria-label="關閉導覽"]');
    expect(close).toBeTruthy();
    expect(close!.hidden).toBe(false);
    close!.click(); fixture.detectChanges();
    await vi.waitFor(() => expect(document.activeElement).toBe(open));
    expect(root.querySelector<HTMLElement>('#navigation-context')!.hidden).toBe(true);
    expect(root.querySelector('main')!.hasAttribute('inert')).toBe(false);
    expect(root.querySelector('nav[aria-label="主要功能"]')!.hasAttribute('inert')).toBe(false);
    expect(requests).toHaveLength(before);
    root.remove();
  });
  it('opens workspace title search from the collapsed main header without changing the draft', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:false, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup(); document.body.appendChild(root);
    const raw = '  draft\nnext line  '; input(root, raw); fixture.detectChanges();
    const surface = root.querySelector('app-chat-surface');
    root.querySelector<HTMLButtonElement>('button[aria-label="切換側欄"]')!.click(); fixture.detectChanges();
    const trigger = root.querySelector<HTMLButtonElement>('main button[aria-label="搜尋對話"]');
    expect(trigger).not.toBeNull();
    const before = requests.length;
    trigger!.click(); fixture.detectChanges();
    const palette = root.querySelector<HTMLElement>('chat-history-search-palette [role="dialog"]')!;
    expect(palette).toBeTruthy();
    await vi.waitFor(() => expect(palette.getAttribute('aria-label')).toContain('Personal'));
    const hint = palette.querySelector<HTMLElement>('[data-search-scope]')!;
    expect(hint).not.toBeNull();
    expect(hint.textContent).toContain('Personal');
    expect(hint.textContent).toContain('搜尋對話標題');
    const searchInput = root.querySelector<HTMLInputElement>('chat-history-search-palette [role="dialog"] input')!;
    expect(searchInput.placeholder).toContain('Personal');
    searchInput.value = 'example'; searchInput.dispatchEvent(new Event('input', {bubbles:true}));
    await vi.waitFor(() => expect(requests.some(request => request.variables?.search === 'example' && request.variables?.workspaceId === 'personal' && request.variables?.limit === 20)).toBe(true));
    expect(root.querySelector('app-chat-surface')).toBe(surface);
    expect(root.querySelector<HTMLTextAreaElement>('chat-input textarea')!.value).toBe(raw);
    expect(requests).toHaveLength(before + 1); root.remove();
  });
  it('accepts a 100-character workspace title query from the search control', async () => {
    const {fixture, root} = await setup();
    root.querySelector<HTMLButtonElement>('#navigation-context button[aria-label="搜尋對話"]')!.click(); fixture.detectChanges();
    const field = root.querySelector<HTMLInputElement>('chat-history-search-palette [role="dialog"] input')!;
    const query = 'a'.repeat(100); field.value = query; field.dispatchEvent(new Event('input',{bubbles:true})); fixture.detectChanges();
    await vi.waitFor(() => expect(requests.some(request => request.variables?.search === query && request.variables?.workspaceId === 'personal')).toBe(true));
    expect(root.querySelector('chat-history-search-palette [role="dialog"]')).not.toBeNull();
  });
  it('keeps one current scope when search is closed and reopened quickly', async () => {
    const {fixture, root} = await setup(); document.body.appendChild(root);
    const trigger = root.querySelector<HTMLButtonElement>('#navigation-context button[aria-label="搜尋對話"]')!;
    trigger.click(); fixture.detectChanges();
    const field = root.querySelector<HTMLInputElement>('chat-history-search-palette [role="dialog"] input')!;
    field.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true})); fixture.detectChanges();
    trigger.click(); fixture.detectChanges();
    await vi.waitFor(() => expect(root.querySelectorAll('[role="dialog"] [data-search-scope]').length).toBe(1));
    expect(root.querySelector('[role="dialog"] [data-search-scope]')?.textContent).toContain('Personal');
    fixture.destroy(); await new Promise(resolve => setTimeout(resolve, 20)); root.remove();
  });
  it('can destroy the app immediately after opening search', async () => {
    const {fixture, root} = await setup(); document.body.appendChild(root);
    root.querySelector<HTMLButtonElement>('#navigation-context button[aria-label="搜尋對話"]')!.click(); fixture.detectChanges();
    fixture.destroy(); root.remove(); await new Promise(resolve => setTimeout(resolve, 20));
  });
  it('returns focus to the collapsed main search control after pointer activation', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:false, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup(); document.body.appendChild(root);
    root.querySelector<HTMLButtonElement>('button[aria-label="切換側欄"]')!.click(); fixture.detectChanges();
    const trigger = root.querySelector<HTMLButtonElement>('main button[aria-label="搜尋對話"]')!;
    root.querySelector<HTMLTextAreaElement>('chat-input textarea')!.focus();
    trigger.click(); fixture.detectChanges();
    const field = root.querySelector<HTMLInputElement>('chat-history-search-palette [role="dialog"] input')!;
    field.focus(); field.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true})); fixture.detectChanges();
    await vi.waitFor(() => expect(document.activeElement).toBe(trigger)); root.remove();
  });
  it('offers one named search control in the visible sidebar and returns focus after pointer activation', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:false, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup(); document.body.appendChild(root);
    const search = root.querySelector<HTMLButtonElement>('#navigation-context button[aria-label="搜尋對話"]');
    expect(search).not.toBeNull();
    expect(root.querySelector('main button[aria-label="搜尋對話"]')).toBeNull();
    root.querySelector<HTMLTextAreaElement>('chat-input textarea')!.focus();
    search!.click(); fixture.detectChanges();
    const field = root.querySelector<HTMLInputElement>('chat-history-search-palette [role="dialog"] input')!;
    field.focus(); field.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true})); fixture.detectChanges();
    await vi.waitFor(() => expect(document.activeElement).toBe(search)); root.remove();
  });
  it('shows the only workspace by name in the sidebar without a redundant selector', async () => {
    const {root} = await setup();
    const sidebar = root.querySelector<HTMLElement>('#navigation-context')!;
    expect(sidebar.textContent).toContain('Personal');
    expect(sidebar.querySelector('select#workspace-select')).toBeNull();
  });
  it('keeps a workspace selector when more than one workspace is available', async () => {
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init?: RequestInit) => {
      const body = JSON.parse(String(init?.body || '{}'));
      if (body.query?.includes('Workspaces')) return Response.json({data:{workspaces:[{id:'personal',name:'Personal',role:'MANAGER',personal:true},{id:'other',name:'Other',role:'MEMBER',personal:false}]}});
      if (body.query?.includes('defaultLocalBot')) return Response.json({data:{defaultLocalBot:local,bots:[]}});
      if (body.query?.includes('conversations(')) return Response.json({data:{conversations:[]}});
      return Response.json({data:{}});
    }));
    const {root} = await setup();
    const select = root.querySelector<HTMLSelectElement>('#workspace-select');
    expect(select).not.toBeNull();
    expect(Array.from(select!.options).map(option => option.textContent)).toEqual(['Personal', 'Other']);
  });
  it('keeps named new conversation and reload controls available in the compact sidebar', async () => {
    const {root} = await setup();
    expect(root.querySelector('#navigation-context button[aria-label="新對話"]')).not.toBeNull();
    expect(root.querySelector('#navigation-context button[aria-label="重新載入對話"]')).not.toBeNull();
  });
  it('peeks from the collapsed desktop rail without changing the draft or docking preference', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:false, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup();
    const raw = '  draft\nnext line  ';
    input(root, raw); fixture.detectChanges();
    const surface = root.querySelector('app-chat-surface');
    const toggle = root.querySelector<HTMLButtonElement>('button[aria-label="切換側欄"]')!;
    toggle.click(); fixture.detectChanges();
    const before = requests.length;
    root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="對話"]')!.click(); fixture.detectChanges();
    expect(root.querySelector<HTMLElement>('#navigation-context')!.hidden).toBe(false);
    root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="對話"]')!.click(); fixture.detectChanges();
    expect(root.querySelector<HTMLElement>('#navigation-context')!.hidden).toBe(true);
    expect(root.querySelector('app-chat-surface')).toBe(surface);
    expect(root.querySelector<HTMLTextAreaElement>('chat-input textarea')!.value).toBe(raw);
    expect(requests).toHaveLength(before);
  });
  it('closes the desktop peek with its close control and returns focus to the rail', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:false, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup(); document.body.appendChild(root);
    root.querySelector<HTMLButtonElement>('button[aria-label="切換側欄"]')!.click(); fixture.detectChanges();
    const rail = root.querySelector<HTMLButtonElement>('nav button[aria-label="對話"]')!;
    rail.focus(); rail.click(); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('button[aria-label="關閉導覽"]')!.click(); fixture.detectChanges();
    expect(root.querySelector<HTMLElement>('#navigation-context')!.hidden).toBe(true);
    await vi.waitFor(() => expect(document.activeElement).toBe(rail));
    root.remove();
  });
  it('pins a desktop peek and keeps it open when the active rail is clicked', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:false, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup();
    const toggle = root.querySelector<HTMLButtonElement>('button[aria-label="切換側欄"]')!;
    const rail = root.querySelector<HTMLButtonElement>('nav button[aria-label="對話"]')!;
    toggle.click(); fixture.detectChanges(); rail.click(); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('button[aria-label="固定展開導覽"]')!.click(); fixture.detectChanges();
    rail.click(); fixture.detectChanges();
    expect(root.querySelector<HTMLElement>('#navigation-context')!.hidden).toBe(false);
    toggle.click(); fixture.detectChanges();
    expect(root.querySelector<HTMLElement>('#navigation-context')!.hidden).toBe(true);
  });
  it('dismisses desktop peek on Escape and restores the rail', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:false, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup(); document.body.appendChild(root);
    root.querySelector<HTMLButtonElement>('button[aria-label="切換側欄"]')!.click(); fixture.detectChanges();
    const rail = root.querySelector<HTMLButtonElement>('nav button[aria-label="對話"]')!;
    rail.focus(); rail.click(); fixture.detectChanges();
    root.querySelector<HTMLElement>('#navigation-context')!.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true})); fixture.detectChanges();
    expect(root.querySelector<HTMLElement>('#navigation-context')!.hidden).toBe(true);
    await vi.waitFor(() => expect(document.activeElement).toBe(rail)); root.remove();
  });
  it('dismisses desktop peek on an outside click without stealing focus', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:false, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup(); document.body.appendChild(root);
    const toggle = root.querySelector<HTMLButtonElement>('button[aria-label="切換側欄"]')!;
    toggle.click(); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('nav button[aria-label="對話"]')!.click(); fixture.detectChanges();
    const textarea = root.querySelector<HTMLTextAreaElement>('chat-input textarea')!;
    textarea.focus(); textarea.click(); fixture.detectChanges();
    expect(root.querySelector<HTMLElement>('#navigation-context')!.hidden).toBe(true);
    await new Promise(resolve => setTimeout(resolve, 10)); expect(document.activeElement).toBe(textarea); root.remove();
  });
  it('closes desktop peek before search and returns to the rail after search', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:false, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup(); document.body.appendChild(root);
    root.querySelector<HTMLButtonElement>('button[aria-label="切換側欄"]')!.click(); fixture.detectChanges();
    const rail = root.querySelector<HTMLButtonElement>('nav button[aria-label="對話"]')!;
    rail.focus(); rail.click(); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('button[aria-label="搜尋對話"]')!.click(); fixture.detectChanges();
    expect(root.querySelector<HTMLElement>('#navigation-context')!.hidden).toBe(true);
    const dialog = root.querySelector<HTMLElement>('chat-history-search-palette [role="dialog"]')!;
    expect(dialog).toBeTruthy();
    dialog.querySelector<HTMLInputElement>('input')!.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true})); fixture.detectChanges();
    await vi.waitFor(() => expect(document.activeElement).toBe(rail)); root.remove();
  });
  it('closes the desktop peek when starting a new conversation', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:false, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup();
    root.querySelector<HTMLButtonElement>('button[aria-label="切換側欄"]')!.click(); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('nav button[aria-label="對話"]')!.click(); fixture.detectChanges();
    const button = root.querySelector<HTMLButtonElement>('#navigation-context button[aria-label="新對話"]')!;
    button.click(); fixture.detectChanges();
    expect(root.querySelector<HTMLElement>('#navigation-context')!.hidden).toBe(true);
  });
  it('exposes the visible sidebar state separately from the active rail feature', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:false, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup();
    const toggle = root.querySelector<HTMLButtonElement>('button[aria-label="切換側欄"]')!;
    const rail = root.querySelector<HTMLButtonElement>('nav button[aria-label="對話"]')!;
    toggle.click(); fixture.detectChanges();
    expect(rail.getAttribute('aria-controls')).toBe('navigation-context');
    expect(rail.getAttribute('aria-expanded')).toBe('false');
    rail.click(); fixture.detectChanges();
    expect(rail.getAttribute('aria-expanded')).toBe('true');
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(rail.getAttribute('aria-pressed')).toBe('true');
  });
  it('moves keyboard focus into a desktop peek', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:false, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup(); document.body.appendChild(root);
    root.querySelector<HTMLButtonElement>('button[aria-label="切換側欄"]')!.click(); fixture.detectChanges();
    const rail = root.querySelector<HTMLButtonElement>('nav button[aria-label="對話"]')!;
    rail.focus(); rail.click(); fixture.detectChanges();
    await vi.waitFor(() => expect(root.querySelector('#navigation-context')!.contains(document.activeElement)).toBe(true)); root.remove();
  });
  it('clears a peek when entering usage and preserves the collapsed preference', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:false, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup();
    root.querySelector<HTMLButtonElement>('button[aria-label="切換側欄"]')!.click(); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('nav button[aria-label="對話"]')!.click(); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('nav button[aria-label="個人用量"]')!.click(); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('nav button[aria-label="對話"]')!.click(); fixture.detectChanges();
    expect(root.querySelector<HTMLElement>('#navigation-context')!.hidden).toBe(false);
    root.querySelector<HTMLButtonElement>('nav button[aria-label="對話"]')!.click(); fixture.detectChanges();
    expect(root.querySelector<HTMLElement>('#navigation-context')!.hidden).toBe(true);
  });
  it('clears a desktop peek across the mobile breakpoint and restores collapsed preference', async () => {
    let change!: () => void; const media = {matches:false, addEventListener:(_:string, fn:()=>void)=>{change=fn;}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}};
    vi.stubGlobal('matchMedia', () => media);
    const {fixture, root} = await setup();
    root.querySelector<HTMLButtonElement>('button[aria-label="切換側欄"]')!.click(); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('nav button[aria-label="對話"]')!.click(); fixture.detectChanges();
    media.matches=true; change(); fixture.detectChanges();
    media.matches=false; change(); fixture.detectChanges();
    expect(root.querySelector<HTMLElement>('#navigation-context')!.hidden).toBe(true);
  });
  it('pins a desktop peek with the existing sidebar shortcut', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:false, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup(); document.body.appendChild(root);
    root.querySelector<HTMLButtonElement>('button[aria-label="切換側欄"]')!.click(); fixture.detectChanges();
    const rail = root.querySelector<HTMLButtonElement>('nav button[aria-label="對話"]')!;
    rail.click(); fixture.detectChanges();
    window.dispatchEvent(new KeyboardEvent('keydown',{key:'b',ctrlKey:true,bubbles:true,cancelable:true})); fixture.detectChanges();
    rail.click(); fixture.detectChanges();
    expect(root.querySelector<HTMLElement>('#navigation-context')!.hidden).toBe(false);
    expect(root.querySelector('button[aria-label="固定展開導覽"]')).toBeNull(); root.remove();
  });
  it('does not steal focus moved after pinning a peek', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:false, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup(); document.body.appendChild(root);
    root.querySelector<HTMLButtonElement>('button[aria-label="切換側欄"]')!.click(); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('nav button[aria-label="對話"]')!.click(); fixture.detectChanges();
    const pin = root.querySelector<HTMLButtonElement>('button[aria-label="固定展開導覽"]')!;
    pin.focus(); pin.click(); fixture.detectChanges();
    const textarea = root.querySelector<HTMLTextAreaElement>('chat-input textarea')!;
    textarea.focus();
    await new Promise(resolve => setTimeout(resolve, 20));
    expect(document.activeElement).toBe(textarea); root.remove();
  });
  it('does not steal focus moved after closing a peek', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:false, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup(); document.body.appendChild(root);
    root.querySelector<HTMLButtonElement>('button[aria-label="切換側欄"]')!.click(); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('nav button[aria-label="對話"]')!.click(); fixture.detectChanges();
    const close = root.querySelector<HTMLButtonElement>('button[aria-label="關閉導覽"]')!;
    close.focus(); close.click(); fixture.detectChanges();
    const textarea = root.querySelector<HTMLTextAreaElement>('chat-input textarea')!;
    textarea.focus(); await new Promise(resolve => setTimeout(resolve, 20));
    expect(document.activeElement).toBe(textarea); root.remove();
  });
  it('dismisses a nonmodal peek with Escape after focus moves to the main area', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:false, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup(); document.body.appendChild(root);
    root.querySelector<HTMLButtonElement>('button[aria-label="切換側欄"]')!.click(); fixture.detectChanges();
    const rail = root.querySelector<HTMLButtonElement>('nav button[aria-label="對話"]')!;
    rail.click(); fixture.detectChanges();
    const textarea = root.querySelector<HTMLTextAreaElement>('chat-input textarea')!; textarea.focus();
    textarea.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true})); fixture.detectChanges();
    expect(root.querySelector<HTMLElement>('#navigation-context')!.hidden).toBe(true);
    await vi.waitFor(() => expect(document.activeElement).toBe(rail)); root.remove();
  });
  it('does not steal newer main focus after closing search opened from a peek', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:false, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup(); document.body.appendChild(root);
    root.querySelector<HTMLButtonElement>('button[aria-label="切換側欄"]')!.click(); fixture.detectChanges();
    const rail = root.querySelector<HTMLButtonElement>('nav button[aria-label="對話"]')!;
    rail.focus(); rail.click(); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('button[aria-label="搜尋對話"]')!.click(); fixture.detectChanges();
    const field = root.querySelector<HTMLInputElement>('chat-history-search-palette [role="dialog"] input')!;
    field.focus(); field.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true})); fixture.detectChanges();
    const textarea = root.querySelector<HTMLTextAreaElement>('chat-input textarea')!; textarea.focus();
    await new Promise(resolve => setTimeout(resolve, 20)); expect(document.activeElement).toBe(textarea); root.remove();
  });
  it('uses a visible rail when the search return trigger becomes hidden', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:false, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup(); document.body.appendChild(root);
    root.querySelector<HTMLButtonElement>('button[aria-label="切換側欄"]')!.click(); fixture.detectChanges();
    const rail = root.querySelector<HTMLButtonElement>('nav button[aria-label="對話"]')!;
    rail.focus(); rail.click(); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('button[aria-label="搜尋對話"]')!.click(); fixture.detectChanges();
    rail.style.display = 'none';
    const field = root.querySelector<HTMLInputElement>('chat-history-search-palette [role="dialog"] input')!;
    field.focus(); field.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true})); fixture.detectChanges();
    const bot = root.querySelector<HTMLButtonElement>('nav button[aria-label="Bot"]')!;
    await vi.waitFor(() => expect(document.activeElement).toBe(bot)); root.remove();
  });
  it('opens the mobile drawer from the conversation rail without changing desktop preference', async () => {
    let change!: () => void; const media = {matches:false, addEventListener:(_:string, fn:()=>void)=>{change=fn;}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}};
    vi.stubGlobal('matchMedia', () => media);
    const {fixture, root} = await setup();
    root.querySelector<HTMLButtonElement>('button[aria-label="切換側欄"]')!.click(); fixture.detectChanges();
    media.matches = true; change(); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="對話"]')!.click(); fixture.detectChanges();
    expect(root.querySelector('[role="dialog"][aria-label="側欄導覽"]')).toBeTruthy();
    expect(root.querySelector<HTMLElement>('#navigation-context')!.hidden).toBe(false);
    media.matches = false; change(); fixture.detectChanges();
    expect(root.querySelector<HTMLElement>('#navigation-context')!.hidden).toBe(true);
  });
  it('opens the Bot drawer from the mobile rail and returns focus to that rail button', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:true, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup(); document.body.appendChild(root);
    const bot = root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="Bot"]')!;
    bot.focus(); bot.click(); fixture.detectChanges();
    const dialog = root.querySelector<HTMLElement>('[role="dialog"][aria-label="側欄導覽"]')!;
    expect(dialog).toBeTruthy(); expect(dialog.querySelector('[aria-label="Bot 清單"]')).toBeTruthy();
    dialog.querySelector<HTMLButtonElement>('button[aria-label="關閉導覽"]')!.click(); fixture.detectChanges();
    await vi.waitFor(() => expect(document.activeElement).toBe(bot));
    root.remove();
  });
  it('closes search before the mobile drawer when Escape is pressed twice', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:true, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup(); document.body.appendChild(root);
    root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="對話"]')!.click(); fixture.detectChanges();
    const search = root.querySelector<HTMLButtonElement>('button[aria-label="搜尋對話"]')!;
    search.focus(); search.click(); fixture.detectChanges();
    const palette = root.querySelector<HTMLElement>('chat-history-search-palette [role="dialog"]')!;
    expect(palette).toBeTruthy();
    palette.querySelector<HTMLInputElement>('input')!.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true})); fixture.detectChanges();
    expect(root.querySelector('chat-history-search-palette [role="dialog"]')).toBeNull();
    expect(root.querySelector('[role="dialog"][aria-label="側欄導覽"]')).toBeTruthy();
    await vi.waitFor(() => expect(document.activeElement).toBe(search));
    root.querySelector<HTMLElement>('[role="dialog"][aria-label="側欄導覽"]')!.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true})); fixture.detectChanges();
    expect(root.querySelector('[role="dialog"][aria-label="側欄導覽"]')).toBeNull();
    root.remove();
  });
  it('does not expose a desktop-only context toggle on narrow screens', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:true, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {root} = await setup();
    expect(root.querySelector('button[aria-label="切換側欄"]')).toBeNull();
    expect(root.querySelector('button[aria-label="開啟導覽"]')).toBeTruthy();
  });
  it('dismisses mobile navigation through its backdrop and restores the rail trigger', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:true, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup(); document.body.appendChild(root);
    const trigger = root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="對話"]')!;
    trigger.focus(); trigger.click(); fixture.detectChanges();
    const backdrop = root.querySelector<HTMLButtonElement>('chat-sidenav-scrim button')!;
    expect(backdrop).toBeTruthy(); backdrop.click(); fixture.detectChanges();
    await vi.waitFor(() => expect(document.activeElement).toBe(trigger));
    expect(root.querySelector<HTMLElement>('#navigation-context')!.hidden).toBe(true);
    expect(root.querySelector('main')!.hasAttribute('inert')).toBe(false);
    root.remove();
  });
  it('closes the mobile drawer after selecting a conversation', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:true, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup(); document.body.appendChild(root);
    vi.stubGlobal('fetch', vi.fn(async (_url:string, init?:RequestInit) => {
      const body = JSON.parse(String(init?.body || '{}'));
      if (body.query?.includes('conversations(')) return Response.json({data:{conversations:body.variables.pinned ? [] : [conversation('selected')]}});
      if (body.query?.includes('SessionHistory')) return Response.json({data:{conversation:conversation('selected')}});
      return Response.json({data:{}});
    }));
    const trigger = root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="對話"]')!;
    trigger.focus(); trigger.click(); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('button[aria-label="重新載入對話"]')!.click();
    await vi.waitFor(() => { fixture.detectChanges(); expect(root.textContent).toContain('selected'); });
    Array.from(root.querySelectorAll<HTMLButtonElement>('chat-thread-list button')).find(button => button.textContent?.includes('selected'))!.click(); fixture.detectChanges();
    expect(root.querySelector<HTMLElement>('#navigation-context')!.hidden).toBe(true);
    await vi.waitFor(() => expect(document.activeElement).toBe(trigger));
    root.remove();
  });
  it('clears the mobile search and modal when crossing to desktop', async () => {
    let change!: () => void; const media = {matches:true, addEventListener:(_:string, fn:()=>void)=>{change=fn;}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}};
    vi.stubGlobal('matchMedia', () => media);
    const {fixture, root} = await setup();
    root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="對話"]')!.click(); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('button[aria-label="搜尋對話"]')!.click(); fixture.detectChanges();
    expect(root.querySelector('chat-history-search-palette [role="dialog"]')).toBeTruthy();
    media.matches = false; change(); fixture.detectChanges();
    expect(root.querySelector('chat-history-search-palette [role="dialog"]')).toBeNull();
    expect(root.querySelector('[role="dialog"][aria-label="側欄導覽"]')).toBeNull();
    expect(root.querySelector('main')!.hasAttribute('inert')).toBe(false);
    expect(root.querySelector('nav[aria-label="主要功能"]')!.hasAttribute('inert')).toBe(false);
  });
  it('keeps a partial and full-but-unfinished run through drawer and resize', async () => {
    let change!: () => void; const media = {matches:false, addEventListener:(_:string, fn:()=>void)=>{change=fn;}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}};
    vi.stubGlobal('matchMedia', () => media);
    const {fixture, root} = await setup();
    const encoder = new TextEncoder(); let push!: (event:object)=>void; let finish!: ()=>void; let runId = ''; let calls = 0;
    const stream = new ReadableStream<Uint8Array>({start(controller) { push = event => controller.enqueue(encoder.encode(`data: ${JSON.stringify(event)}\n\n`)); finish = () => controller.close(); }});
    vi.stubGlobal('fetch', vi.fn(async (url:string, init?:RequestInit) => {
      const body = JSON.parse(String(init?.body || '{}'));
      if (String(url).includes('/api/ag-ui')) { calls++; runId=body.runId; return new Response(stream,{headers:{'Content-Type':'text/event-stream'}}); }
      if (body.query?.includes('CreateConversation')) return Response.json({data:{createConversation:conversation('stream')}});
      return Response.json({data:{}});
    }));
    input(root,'  original raw  '); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('chat-input button[aria-label="Send message"]')!.click();
    await vi.waitFor(()=>expect(calls).toBe(1));
    push({type:'RUN_STARTED',threadId:'stream',runId}); push({type:'TEXT_MESSAGE_START',messageId:'answer',role:'assistant'}); push({type:'TEXT_MESSAGE_CONTENT',messageId:'answer',delta:'partial'});
    await vi.waitFor(()=>{fixture.detectChanges(); expect(root.textContent).toContain('partial');});
    const surface=root.querySelector('app-chat-surface'); const textarea=root.querySelector<HTMLTextAreaElement>('chat-input textarea')!;
    media.matches=true; change(); fixture.detectChanges(); root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="對話"]')!.click(); fixture.detectChanges();
    push({type:'TEXT_MESSAGE_CONTENT',messageId:'answer',delta:' full'}); push({type:'TEXT_MESSAGE_END',messageId:'answer'});
    await vi.waitFor(()=>{fixture.detectChanges(); expect(root.textContent).toContain('partial full');});
    root.querySelector<HTMLButtonElement>('button[aria-label="關閉導覽"]')!.click(); fixture.detectChanges(); media.matches=false; change(); fixture.detectChanges();
    expect(root.querySelector('app-chat-surface')).toBe(surface); expect(root.querySelector('chat-input textarea')).toBe(textarea);
    expect(root.querySelector('chat-input button[aria-label="Stop generating"]')).toBeTruthy(); expect(calls).toBe(1); expect(runId).toBeTruthy();
    push({type:'RUN_FINISHED',threadId:'stream',runId}); finish();
  });
  it('returns focus to the mobile opener after a pointer activation that did not focus it', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:true, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup(); document.body.appendChild(root);
    const other=root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="對話"]')!;
    const open=root.querySelector<HTMLButtonElement>('button[aria-label="開啟導覽"]')!; open.style.display='block';
    other.focus(); expect(document.activeElement).toBe(other); open.click(); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('button[aria-label="關閉導覽"]')!.click(); fixture.detectChanges();
    await vi.waitFor(()=>expect(document.activeElement).toBe(open)); root.remove();
  });
  it('exposes the mobile opener relationship and actual expanded state', async () => {
    vi.stubGlobal('matchMedia', () => ({matches:true, addEventListener:()=>{}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}}));
    const {fixture, root} = await setup();
    const open=root.querySelector<HTMLButtonElement>('button[aria-label="開啟導覽"]')!;
    const controlled = open.getAttribute('aria-controls');
    expect(controlled).toBeTruthy(); expect(root.querySelector(`#${controlled}`)).toBeTruthy();
    expect(open.getAttribute('aria-expanded')).toBe('false');
    open.click(); fixture.detectChanges(); expect(open.getAttribute('aria-expanded')).toBe('true');
    root.querySelector<HTMLButtonElement>('button[aria-label="關閉導覽"]')!.click(); fixture.detectChanges();
    expect(open.getAttribute('aria-expanded')).toBe('false');
  });
  it.each([['FAILED',true],['FAILED',false],['CANCELLED',true],['CANCELLED',false]] as const)('preserves the same-source draft after %s across drawer and resize (new edit: %s)', async (outcome, edited) => {
    let change!: () => void; const media = {matches:false, addEventListener:(_:string, fn:()=>void)=>{change=fn;}, removeEventListener:()=>{}, addListener:()=>{}, removeListener:()=>{}};
    vi.stubGlobal('matchMedia', () => media);
    const {fixture, root} = await setup();
    const encoder=new TextEncoder(); let push!:(event:object)=>void; let finish!:()=>void; let runId=''; let calls=0;
    const stream=new ReadableStream<Uint8Array>({start(controller){push=event=>controller.enqueue(encoder.encode(`data: ${JSON.stringify(event)}\n\n`)); finish=()=>controller.close();}});
    vi.stubGlobal('fetch',vi.fn(async (url:string, init?:RequestInit)=>{
      const body=JSON.parse(String(init?.body || '{}'));
      if (String(url).includes('/api/ag-ui')) {calls++;runId=body.runId;return new Response(stream,{headers:{'Content-Type':'text/event-stream'}});}
      if (body.query?.includes('CreateConversation')) return Response.json({data:{createConversation:conversation('stream')}});
      return Response.json({data:{}});
    }));
    input(root,'  raw sent  ');fixture.detectChanges();root.querySelector<HTMLButtonElement>('chat-input button[aria-label="Send message"]')!.click();
    await vi.waitFor(()=>expect(calls).toBe(1));
    push({type:'RUN_STARTED',threadId:'stream',runId});push({type:'TEXT_MESSAGE_START',messageId:'answer',role:'assistant'});push({type:'TEXT_MESSAGE_CONTENT',messageId:'answer',delta:'partial result'});
    await vi.waitFor(()=>{fixture.detectChanges();expect(root.textContent).toContain('partial result');});
    media.matches=true;change();fixture.detectChanges();root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="對話"]')!.click();fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('button[aria-label="關閉導覽"]')!.click();fixture.detectChanges();
    if (edited) { input(root,' newer draft '); fixture.detectChanges(); }
    if (outcome==='FAILED') push({type:'RUN_ERROR',message:'failed'});
    else {push({type:'TEXT_MESSAGE_END',messageId:'answer'});push({type:'RUN_FINISHED',threadId:'stream',runId,outcome:{type:'cancelled'}});}
    finish();await vi.waitFor(()=>{fixture.detectChanges();expect(root.textContent).toContain(outcome==='FAILED' ? '執行失敗' : '回覆已停止');});
    media.matches=false;change();fixture.detectChanges();
    expect(root.querySelector<HTMLTextAreaElement>('chat-input textarea')!.value).toBe(edited ? ' newer draft ' : '  raw sent  ');
    expect(root.textContent).toContain('partial result');expect(calls).toBe(1);expect(runId).toBeTruthy();
  });
  it('moves focus to an available rail control when mobile close disappears on desktop', async () => {
    let change!:()=>void; const media={matches:false,addEventListener:(_:string,fn:()=>void)=>{change=fn;},removeEventListener:()=>{},addListener:()=>{},removeListener:()=>{}};
    vi.stubGlobal('matchMedia',()=>media);
    const {fixture,root}=await setup();document.body.appendChild(root);
    root.querySelector<HTMLButtonElement>('button[aria-label="切換側欄"]')!.click();fixture.detectChanges();
    media.matches=true;change();fixture.detectChanges();
    const rail=root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="對話"]')!;
    rail.click();fixture.detectChanges();
    const close=root.querySelector<HTMLButtonElement>('button[aria-label="關閉導覽"]')!;close.focus();expect(document.activeElement).toBe(close);
    media.matches=false;change();fixture.detectChanges();
    await vi.waitFor(()=>expect(document.activeElement).toBe(rail));root.remove();
  });
  it('restores focus after breakpoint removes search or the desktop toggle', async () => {
    let change!:()=>void;const media={matches:false,addEventListener:(_:string,fn:()=>void)=>{change=fn;},removeEventListener:()=>{},addListener:()=>{},removeListener:()=>{}};
    vi.stubGlobal('matchMedia',()=>media);
    const {fixture,root}=await setup();document.body.appendChild(root);
    const rail=root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="對話"]')!;
    const toggle=root.querySelector<HTMLButtonElement>('button[aria-label="切換側欄"]')!;toggle.focus();expect(document.activeElement).toBe(toggle);
    media.matches=true;change();fixture.detectChanges();await vi.waitFor(()=>expect(document.activeElement).toBe(rail));
    rail.click();fixture.detectChanges();root.querySelector<HTMLButtonElement>('button[aria-label="搜尋對話"]')!.click();fixture.detectChanges();
    const input=root.querySelector<HTMLInputElement>('chat-history-search-palette input')!;input.focus();expect(document.activeElement).toBe(input);
    media.matches=false;change();fixture.detectChanges();await vi.waitFor(()=>expect(document.activeElement).toBe(rail));
    root.remove();
  });
  it('does not steal focus that remains valid during a breakpoint change', async () => {
    let change!:()=>void;const media={matches:false,addEventListener:(_:string,fn:()=>void)=>{change=fn;},removeEventListener:()=>{},addListener:()=>{},removeListener:()=>{}};
    vi.stubGlobal('matchMedia',()=>media);
    const {fixture,root}=await setup();document.body.appendChild(root);
    const textarea=root.querySelector<HTMLTextAreaElement>('chat-input textarea')!; vi.spyOn(textarea,'getClientRects').mockReturnValue([{width:100,height:30}] as unknown as DOMRectList); textarea.focus();expect(document.activeElement).toBe(textarea);
    media.matches=true;change();fixture.detectChanges();await new Promise(resolve=>setTimeout(resolve,30));
    expect(document.activeElement).toBe(textarea);root.remove();
  });
  it('moves focus from the mobile opener when it becomes unavailable on desktop', async () => {
    let change!:()=>void;const media={matches:true,addEventListener:(_:string,fn:()=>void)=>{change=fn;},removeEventListener:()=>{},addListener:()=>{},removeListener:()=>{}};
    vi.stubGlobal('matchMedia',()=>media);const {fixture,root}=await setup();document.body.appendChild(root);
    const open=root.querySelector<HTMLButtonElement>('button[aria-label="開啟導覽"]')!;open.style.display='block';open.focus();expect(document.activeElement).toBe(open);
    const rail=root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="對話"]')!;
    open.style.removeProperty('display'); media.matches=false;change();fixture.detectChanges();await vi.waitFor(()=>expect(document.activeElement).toBe(rail));root.remove();
  });
  it('does not override a new focus chosen before breakpoint recovery runs', async () => {
    let change!:()=>void;const media={matches:false,addEventListener:(_:string,fn:()=>void)=>{change=fn;},removeEventListener:()=>{},addListener:()=>{},removeListener:()=>{}};
    vi.stubGlobal('matchMedia',()=>media);const {fixture,root}=await setup();document.body.appendChild(root);
    root.querySelector<HTMLButtonElement>('button[aria-label="切換側欄"]')!.focus();
    media.matches=true;change();fixture.detectChanges();const textarea=root.querySelector<HTMLTextAreaElement>('chat-input textarea')!;textarea.focus();
    await new Promise(resolve=>setTimeout(resolve,30));expect(document.activeElement).toBe(textarea);root.remove();
  });
  it('moves initial drawer host focus to a usable control when returning to desktop', async () => {
    let change!:()=>void;const media={matches:true,addEventListener:(_:string,fn:()=>void)=>{change=fn;},removeEventListener:()=>{},addListener:()=>{},removeListener:()=>{}};
    vi.stubGlobal('matchMedia',()=>media);const {fixture,root}=await setup();document.body.appendChild(root);
    const rail=root.querySelector<HTMLButtonElement>('nav[aria-label="主要功能"] button[aria-label="對話"]')!;rail.click();fixture.detectChanges();
    const dialog=root.querySelector<HTMLElement>('[role="dialog"][aria-label="側欄導覽"]')!;dialog.focus();expect(document.activeElement).toBe(dialog);
    media.matches=false;change();fixture.detectChanges();await vi.waitFor(()=>expect(document.activeElement).toBe(rail));root.remove();
  });
  it('does not move initial body focus on a breakpoint change', async () => {
    let change!:()=>void;const media={matches:false,addEventListener:(_:string,fn:()=>void)=>{change=fn;},removeEventListener:()=>{},addListener:()=>{},removeListener:()=>{}};
    vi.stubGlobal('matchMedia',()=>media);const {fixture,root}=await setup();document.body.appendChild(root);
    expect(document.activeElement).toBe(document.body);media.matches=true;change();fixture.detectChanges();
    await new Promise(resolve=>setTimeout(resolve,30));expect(document.activeElement).toBe(document.body);root.remove();
  });
  it('renders native window and composer with the general chat title', async () => {
    const {root} = await setup();
    expect(root.querySelector('chat-window')).toBeTruthy();
    expect(root.querySelector('chat-input textarea')).toBeTruthy();
    expect(root.querySelector('[data-testid="chat-header-title"]')?.textContent).toContain('New Conversation');
    expect(root.querySelector('[data-testid="header-bot-meta"]')).toBeNull();
  });
  it('initializes each conversation section once', async () => {
    await setup();
    expect(requests.filter(r=>r.query?.includes('conversations(')).map(r=>r.variables.pinned).sort()).toEqual([false,true]);
  });
  it('new chat opens an in-memory draft repeatedly without creating conversations', async () => {
    const {app}=await setup(); const create=vi.spyOn(app.chatTransport,'createConversation');
    await app.newConversation(); await app.newConversation();
    expect(app.currentConversationId()).toBeNull(); expect(create).not.toHaveBeenCalled();
  });
  it('ignores blank submissions and preserves raw whitespace', async () => {
    const {app}=await setup(); const create=vi.spyOn(app.chatTransport,'createConversation');
    app.chatDraftChanged(' \n '); app.submitChat(' \n ');
    expect(create).not.toHaveBeenCalled(); expect(app.chatSession().draft()).toBe(' \n ');
  });
  it('first valid submission creates once and sends exact raw text despite a duplicate click', async () => {
    const {app}=await setup(); const creation=deferred<string>();
    const create=vi.spyOn(app.chatTransport,'createConversation').mockReturnValue(creation.promise);
    const run=vi.spyOn(app.chatTransport,'run').mockResolvedValue({status:'COMPLETED'});
    app.chatDraftChanged('  hello\n '); app.submitChat('  hello\n '); app.submitChat('  hello\n ');
    expect(create).toHaveBeenCalledOnce(); creation.resolve('c1');
    await vi.waitFor(()=>expect(run).toHaveBeenCalledOnce());
    expect(run.mock.calls[0][2]).toBe('  hello\n ');
  });
  it('initialization blocks sending while retaining input, then allows it', async () => {
    const {app}=await setup(); const create=vi.spyOn(app.chatTransport,'createConversation').mockResolvedValue('c1');
    vi.spyOn(app.chatTransport,'run').mockResolvedValue({status:'COMPLETED'});
    app.isInitializing.set(true); app.chatDraftChanged('raw'); app.submitChat('raw');
    expect(create).not.toHaveBeenCalled(); expect(app.chatSession().draft()).toBe('raw');
    app.isInitializing.set(false); app.submitChat('raw'); await vi.waitFor(()=>expect(create).toHaveBeenCalledOnce());
  });
  it('failed creation retains the exact raw draft', async () => {
    const {app}=await setup(); vi.spyOn(app.chatTransport,'createConversation').mockRejectedValue(new Error('offline'));
    app.chatDraftChanged('  line\n\n '); app.submitChat('  line\n\n ');
    await vi.waitFor(()=>expect(app.chatSession().draft()).toBe('  line\n\n '));
  });
  it('preserves both new and failed original drafts when creation fails after switching', async () => {
    const {app}=await setup(); const creation=deferred<string>(); vi.spyOn(app.chatTransport,'createConversation').mockReturnValue(creation.promise);
    app.openBotDraft(botA); app.chatDraftChanged(' old\n '); app.submitChat(' old\n ');
    app.openBotDraft(botA); app.chatDraftChanged(' new '); creation.reject(new Error('failed'));
    await vi.waitFor(()=>expect(app.chatSessions.failedCreations(`draft:${JSON.stringify(['personal', 'a'])}`)()).toHaveLength(1));
    expect(app.chatSession().draft()).toBe(' new '); expect(app.chatSessions.failedCreations(`draft:${JSON.stringify(['personal', 'a'])}`)()[0].raw).toBe(' old\n ');
  });
  it('switches bots while a creation is pending without stealing selection or new draft', async () => {
    const {app}=await setup(); const creation=deferred<string>(); vi.spyOn(app.chatTransport,'createConversation').mockReturnValue(creation.promise);
    const run=vi.spyOn(app.chatTransport,'run').mockResolvedValue({status:'COMPLETED'});
    app.openBotDraft(botA); app.chatDraftChanged('first'); app.submitChat('first'); app.openBotDraft(botB); app.chatDraftChanged('other');
    creation.resolve('c1'); await vi.waitFor(()=>expect(run).toHaveBeenCalledOnce());
    expect(app.currentBot()?.id).toBe('b'); expect(app.chatSession().draft()).toBe('other');
  });
  it('keeps separate bot drafts without creating a conversation', async () => {
    const {app}=await setup(); const create=vi.spyOn(app.chatTransport,'createConversation');
    app.openBotDraft(botA); app.chatDraftChanged(' A \n'); app.openBotDraft(botB); app.chatDraftChanged(' B ');
    app.openBotDraft(botA); expect(app.chatSession().draft()).toBe(' A \n');
    app.openBotDraft(botB); expect(app.chatSession().draft()).toBe(' B '); expect(create).not.toHaveBeenCalled();
  });
  it('late conversation hydration cannot replace a newer draft selection', async () => {
    const {app}=await setup(); const history=deferred<any>(); vi.spyOn(app.chatTransport,'hydrate').mockReturnValue(history.promise);
    const selecting=app.selectConversation('slow'); app.openBotDraft(botA); app.chatDraftChanged('current');
    history.resolve(conversation('slow')); await selecting;
    expect(app.currentConversationId()).toBeNull(); expect(app.currentBot()?.id).toBe('a'); expect(app.chatSession().draft()).toBe('current');
  });
  it('does not allow sending while selected conversation metadata is unresolved', async () => {
    const {app}=await setup(); const history=deferred<any>(); vi.spyOn(app.chatTransport,'hydrate').mockReturnValue(history.promise);
    const selecting=app.selectConversation('slow'); app.chatDraftChanged(' raw ');
    const run=vi.spyOn(app.chatTransport,'run'); app.submitChat(' raw ');
    expect(run).not.toHaveBeenCalled(); expect(app.chatSession().draft()).toBe(' raw ');
    history.resolve(conversation('slow')); await selecting;
  });
  it('unavailable bot keeps raw draft and does not submit via Enter', async () => {
    const {app,fixture,root}=await setup(); app.openBotDraft(botB); fixture.detectChanges();
    const create=vi.spyOn(app.chatTransport,'createConversation'); const textarea=input(root,' raw\n '); fixture.detectChanges();
    textarea.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true})); fixture.detectChanges();
    expect(create).not.toHaveBeenCalled(); expect(app.chatSession().draft()).toBe(' raw\n ');
    expect(root.querySelector('[data-testid="disabled-bot-banner"]')?.textContent).toContain('未連線');
  });
  it('simulated bot is labelled honestly and can submit via the native composer', async () => {
    const {app,fixture,root}=await setup(); app.openBotDraft(botA); fixture.detectChanges();
    const create=vi.spyOn(app.chatTransport,'createConversation').mockResolvedValue('c1'); vi.spyOn(app.chatTransport,'run').mockResolvedValue({status:'COMPLETED'});
    const textarea=input(root,'  question  '); fixture.detectChanges();
    textarea.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}));
    await vi.waitFor(()=>expect(create).toHaveBeenCalledOnce());
    expect(root.querySelector('[data-testid="simulation-banner"]')?.textContent).toContain('本機模擬');
  });
  it('does not submit during IME composition', async () => {
    const {app,fixture,root}=await setup(); const create=vi.spyOn(app.chatTransport,'createConversation');
    const textarea=input(root,'中文'); fixture.detectChanges();
    textarea.dispatchEvent(new CompositionEvent('compositionstart',{bubbles:true}));
    textarea.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true,isComposing:true}));
    expect(create).not.toHaveBeenCalled(); expect(app.chatSession().draft()).toBe('中文');
    textarea.dispatchEvent(new CompositionEvent('compositionend',{bubbles:true}));
  });
  it('a selected conversation has its own draft independent of a Bot draft', async () => {
    const {app}=await setup(); vi.spyOn(app.chatTransport,'hydrate').mockResolvedValue(conversation('c1'));
    app.openBotDraft(botA); app.chatDraftChanged(' bot draft ');
    await app.selectConversation('c1'); app.chatDraftChanged(' history draft ');
    app.openBotDraft(botA); expect(app.chatSession().draft()).toBe(' bot draft ');
    await app.selectConversation('c1'); expect(app.chatSession().draft()).toBe(' history draft ');
  });
  it('background failure restores only its own source and leaves the selected Bot draft intact', async () => {
    const {app}=await setup(); const runResult=deferred<{status:'FAILED'}>();
    vi.spyOn(app.chatTransport,'createConversation').mockResolvedValue('c1');
    const run=vi.spyOn(app.chatTransport,'run').mockReturnValue(runResult.promise);
    app.openBotDraft(botA); app.chatDraftChanged('  source  '); app.submitChat('  source  ');
    await vi.waitFor(()=>expect(run).toHaveBeenCalledOnce());
    app.openBotDraft(botB); app.chatDraftChanged('other'); runResult.resolve({status:'FAILED'});
    await vi.waitFor(()=>expect(app.chatSessions.session('conversation:c1').draft()).toBe('  source  '));
    expect(app.chatSession().draft()).toBe('other');
  });
  it('a later identical draft survives completion of an older request', async () => {
    const {app}=await setup(); const pending=deferred<{status:'COMPLETED'}>();
    vi.spyOn(app.chatTransport,'createConversation').mockResolvedValue('c1');
    const run=vi.spyOn(app.chatTransport,'run').mockReturnValue(pending.promise);
    app.openBotDraft(botA); app.chatDraftChanged('same'); app.submitChat('same');
    await vi.waitFor(()=>expect(run).toHaveBeenCalledOnce());
    app.chatDraftChanged('same'); pending.resolve({status:'COMPLETED'});
    await vi.waitFor(()=>expect(app.chatSession().active()).toBe(false));
    expect(app.chatSession().draft()).toBe('same');
  });
  it('navigates to another conversation while the first run is active', async () => {
    const {app}=await setup(); const pending=deferred<{status:'COMPLETED'}>();
    vi.spyOn(app.chatTransport,'createConversation').mockResolvedValue('c1');
    const run=vi.spyOn(app.chatTransport,'run').mockReturnValue(pending.promise);
    vi.spyOn(app.chatTransport,'hydrate').mockResolvedValue(conversation('c2'));
    app.openBotDraft(botA); app.chatDraftChanged('A'); app.submitChat('A');
    await vi.waitFor(()=>expect(run).toHaveBeenCalledOnce()); await app.selectConversation('c2');
    expect(app.currentConversationId()).toBe('c2'); expect(app.chatSessions.session('conversation:c1').active()).toBe(true);
    pending.resolve({status:'COMPLETED'}); await vi.waitFor(()=>expect(app.chatSessions.session('conversation:c1').active()).toBe(false));
    expect(app.currentConversationId()).toBe('c2');
  });
  it('stop targets the active source and restores its raw input on cancellation', async () => {
    const {app}=await setup(); const pending=deferred<{status:'COMPLETED'}>();
    vi.spyOn(app.chatTransport,'createConversation').mockResolvedValue('c1');
    const run=vi.spyOn(app.chatTransport,'run').mockReturnValue(pending.promise);
    const cancel=vi.spyOn(app.chatTransport,'cancel').mockResolvedValue({status:'CANCELLED'});
    vi.spyOn(app.chatTransport,'stopLocal').mockImplementation(()=>{});
    app.openBotDraft(botA); app.chatDraftChanged(' raw '); app.submitChat(' raw ');
    await vi.waitFor(()=>expect(run).toHaveBeenCalledOnce()); const id=app.chatSession().runId;
    app.stopChat(); await vi.waitFor(()=>expect(app.chatSession().active()).toBe(false));
    expect(cancel).toHaveBeenCalledWith('c1',id); expect(app.chatSession().draft()).toBe(' raw ');
    pending.resolve({status:'COMPLETED'}); await Promise.resolve(); expect(app.chatSession().result()?.status).toBe('CANCELLED');
  });
  it('shows Send and Stop for the agent state and routes Stop to its source', async () => {
    const {app,fixture,root}=await setup();
    const loading=signal(false); const agent={...app.chatAgent(),isLoading:loading};
    vi.spyOn(app.chatTransport,'agent').mockReturnValue(agent);
    app.openBotDraft(botA); fixture.detectChanges();
    expect(root.querySelector('chat-input button[aria-label="Send message"]')).toBeTruthy();
    const stop=vi.spyOn(app,'stopChat'); loading.set(true); fixture.detectChanges();
    const button=root.querySelector('chat-input button[aria-label="Stop generating"]') as HTMLButtonElement;
    expect(button).toBeTruthy(); button.click(); expect(stop).toHaveBeenCalledOnce();
  });
  it('renders assistant Markdown and a copy-only action', async () => {
    const {app,fixture,root}=await setup();
    vi.spyOn(app.chatTransport,'hydrate').mockResolvedValue(conversation('c1'));
    app.chatTransport.mergeHistory('c1',[{id:'a1',role:'ASSISTANT',content:'**bold**',runId:'r1'}]);
    await app.selectConversation('c1'); fixture.detectChanges();
    expect(root.querySelector('chat-streaming-md')).toBeTruthy();
    const write=vi.fn().mockResolvedValue(undefined); Object.defineProperty(navigator,'clipboard',{value:{writeText:write},configurable:true});
    (root.querySelector('button[aria-label="複製回覆"]') as HTMLButtonElement).click();
    await vi.waitFor(()=>expect(write).toHaveBeenCalledWith('**bold**'));
    expect(root.querySelector('chat-message-actions')).toBeNull(); Reflect.deleteProperty(navigator,'clipboard');
  });
  it('unavailable default assistant blocks submission instead of choosing an arbitrary Bot', async () => {
    const {app,fixture,root}=await setup();
    app.botStore.defaultLocalBot.set(null); await app.newConversation(); fixture.detectChanges();
    const create=vi.spyOn(app.chatTransport,'createConversation'); app.chatDraftChanged('raw'); app.submitChat('raw');
    expect(create).not.toHaveBeenCalled(); expect(root.querySelector('[data-testid="default-bot-missing-banner"]')).toBeTruthy();
  });
  it('shows selected Bot name once and returns to general context', async () => {
    const {app,fixture,root}=await setup(); app.openBotDraft(botA); fixture.detectChanges();
    expect(root.querySelector('[data-testid="header-bot-name"]')?.textContent).toContain('Bot A');
    expect(root.querySelector('[data-testid="chat-header-title"]')?.textContent).not.toContain('Bot A');
    await app.newConversation(); fixture.detectChanges(); expect(root.querySelector('[data-testid="header-bot-meta"]')).toBeNull();
  });

  it('shows a read-only status check for UNKNOWN without submitting another Run', async () => {
    const {app,fixture,root}=await setup(); const pending=deferred<{status:'UNKNOWN'}>();
    vi.spyOn(app.chatTransport,'createConversation').mockResolvedValue('c1');
    const run=vi.spyOn(app.chatTransport,'run').mockReturnValue(pending.promise);
    const reconcile=vi.mocked(app.chatTransport.reconcile); reconcile.mockResolvedValue({status:'UNKNOWN'});
    app.openBotDraft(botA); app.chatDraftChanged('input'); app.submitChat('input');
    await vi.waitFor(()=>expect(run).toHaveBeenCalledOnce()); const runId=app.chatSession().runId;
    pending.resolve({status:'UNKNOWN'}); await vi.waitFor(()=>expect(app.chatSession().result()?.status).toBe('UNKNOWN'));
    fixture.detectChanges();
    const button=Array.from(root.querySelectorAll('chat-error button')).find(b=>b.textContent?.includes('Check status')) as HTMLButtonElement;
    expect(button).toBeTruthy(); const before=reconcile.mock.calls.length; button.click();
    await vi.waitFor(()=>expect(reconcile.mock.calls.length).toBeGreaterThan(before));
    expect(reconcile.mock.calls.at(-1)).toEqual(['c1',runId]); expect(run).toHaveBeenCalledOnce();
    expect(root.querySelector('chat-error')?.textContent).not.toContain('Retry');
  });
  it('failed history send keeps a separate new draft of the same Bot', async () => {
    const {app}=await setup(); vi.spyOn(app.chatTransport,'hydrate').mockResolvedValue(conversation('c1','a'));
    vi.spyOn(app.chatTransport,'run').mockResolvedValue({status:'FAILED'});
    app.openBotDraft(botA); app.chatDraftChanged('unsent bot'); await app.selectConversation('c1');
    app.chatDraftChanged('history input'); app.submitChat('history input');
    await vi.waitFor(()=>expect(app.chatSession().result()?.status).toBe('FAILED'));
    expect(app.chatSession().draft()).toBe('history input'); app.openBotDraft(botA); expect(app.chatSession().draft()).toBe('unsent bot');
  });
  it('general history does not show a Bot label', async () => {
    const {app,fixture,root}=await setup(); vi.spyOn(app.chatTransport,'hydrate').mockResolvedValue(conversation('c1'));
    await app.selectConversation('c1'); fixture.detectChanges();
    expect(root.querySelector('[data-testid="header-bot-meta"]')).toBeNull();
  });
  it('Shift+Enter does not start a run', async () => {
    const {app,fixture,root}=await setup(); const create=vi.spyOn(app.chatTransport,'createConversation');
    const textarea=input(root,'line'); fixture.detectChanges();
    textarea.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',shiftKey:true,bubbles:true}));
    expect(create).not.toHaveBeenCalled(); expect(app.chatSession().draft()).toBe('line');
  });

  it.each(['COMPLETED','FAILED'] as const)('history %s leaves the same Bot new draft untouched', async (outcome) => {
    const {app}=await setup(); vi.spyOn(app.chatTransport,'hydrate').mockResolvedValue(conversation('c1','a'));
    vi.spyOn(app.chatTransport,'run').mockResolvedValue({status:outcome});
    app.openBotDraft(botA); app.chatDraftChanged('unsubmitted'); await app.selectConversation('c1');
    app.chatDraftChanged('history'); app.submitChat('history');
    await vi.waitFor(()=>expect(app.chatSession().result()?.status).toBe(outcome));
    app.openBotDraft(botA); expect(app.chatSession().draft()).toBe('unsubmitted');
  });
  it('Bot failure does not populate a separate empty general draft', async () => {
    const {app}=await setup(); vi.spyOn(app.chatTransport,'createConversation').mockResolvedValue('c1');
    vi.spyOn(app.chatTransport,'run').mockResolvedValue({status:'FAILED'});
    app.openBotDraft(botA); app.chatDraftChanged('failed Bot'); app.submitChat('failed Bot');
    await vi.waitFor(()=>expect(app.chatSession().result()?.status).toBe('FAILED'));
    await app.newConversation(); expect(app.chatSession().draft()).toBe('');
  });
  it('restores a failed creation in the visible native textarea', async () => {
    const {app,fixture,root}=await setup(); vi.spyOn(app.chatTransport,'createConversation').mockRejectedValue(new Error('offline'));
    const textarea=input(root,'  raw\n '); fixture.detectChanges();
    (root.querySelector('chat-input button[aria-label="Send message"]') as HTMLButtonElement).click();
    await vi.waitFor(()=>expect(app.chatSession().draft()).toBe('  raw\n '));
    fixture.detectChanges(); expect(textarea.value).toBe('  raw\n ');
  });

  it('completion of a Bot run does not clear identical general draft', async () => {
    const {app}=await setup(); const pending=deferred<{status:'COMPLETED'}>();
    vi.spyOn(app.chatTransport,'createConversation').mockResolvedValue('c1');
    const run=vi.spyOn(app.chatTransport,'run').mockReturnValue(pending.promise);
    app.chatDraftChanged('same'); app.openBotDraft(botA); app.chatDraftChanged('same'); app.submitChat('same');
    await vi.waitFor(()=>expect(run).toHaveBeenCalledOnce()); pending.resolve({status:'COMPLETED'});
    await vi.waitFor(()=>expect(app.chatSession().active()).toBe(false));
    await app.newConversation(); expect(app.chatSession().draft()).toBe('same');
  });
  it('LOCAL history omits Bot metadata even if the default Bot is unavailable', async () => {
    const {app,fixture,root}=await setup(); app.botStore.defaultLocalBot.set(null);
    vi.spyOn(app.botStore,'loadBot').mockResolvedValue(local);
    vi.spyOn(app.chatTransport,'hydrate').mockResolvedValue(conversation('c1'));
    await app.selectConversation('c1'); await vi.waitFor(()=>expect(app.currentBot()?.id).toBe('local'));
    fixture.detectChanges(); expect(root.querySelector('[data-testid="header-bot-meta"]')).toBeNull();
  });

});
