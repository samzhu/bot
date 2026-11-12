import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { App } from './app';
import { BotView } from './bot/bot.model';
import { signal } from '@angular/core';

const local: BotView = { id:'local', name:'General Assistant', target:'LOCAL', connectionStatus:'NOT_APPLICABLE', executionMode:'LOCAL', canChat:true, createdAt:'', updatedAt:'' };
const botA: BotView = { ...local, id:'a', name:'Bot A', target:'GKE_REMOTE', connectionStatus:'NOT_CONNECTED', executionMode:'SPRING_AI_SIMULATION', canChat:true };
const botB: BotView = { ...botA, id:'b', name:'Bot B', executionMode:'UNAVAILABLE', canChat:false };
const conversation = (id: string, botId = local.id) => ({ id, botId, title:id, pinned:false, createdAt:'', updatedAt:'', messages:[], runs:[] });
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
    await vi.waitFor(()=>expect(app.chatSessions.failedCreations('bot:a')()).toHaveLength(1));
    expect(app.chatSession().draft()).toBe(' new '); expect(app.chatSessions.failedCreations('bot:a')()[0].raw).toBe(' old\n ');
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
