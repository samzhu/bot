import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { App } from './app';

const bot = { id:'local', name:'General Assistant', target:'LOCAL', connectionStatus:'NOT_APPLICABLE', executionMode:'LOCAL', canChat:true, createdAt:'', updatedAt:'' };

describe('#46 empty conversation recovery', () => {
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
  it('recovers a saved unavailable conversation to an editable new conversation', async () => {
    const storage = new Map([['bot_active_conversation_id','stale'], ['unrelated','keep']]);
    vi.stubGlobal('localStorage', {getItem:(key:string)=>storage.get(key) ?? null, setItem:(key:string,value:string)=>storage.set(key,value), removeItem:(key:string)=>storage.delete(key)});
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init?: RequestInit) => {
      const query = JSON.parse(String(init?.body || '{}')).query || '';
      if (query.includes('Workspaces')) return Response.json({data:{workspaces:[{id:'personal',name:'Personal',role:'MANAGER',personal:true}]}});
      if (query.includes('defaultLocalBot')) return Response.json({data:{defaultLocalBot:bot,bots:[]}});
      if (query.includes('SessionHistory')) return Response.json({data:{conversation:null}});
      return Response.json({data:{conversations:[],bots:[],defaultLocalBot:bot}});
    }));
    const fixture = TestBed.createComponent(App); fixture.detectChanges();
    await vi.waitFor(() => expect(fixture.componentInstance.isInitializing()).toBe(false));
    fixture.detectChanges();
    expect(fixture.componentInstance.currentConversationId()).toBeNull();
    expect(storage.get('bot_active_conversation_id')).toBeUndefined();
    expect(storage.get('unrelated')).toBe('keep');
    expect(fixture.componentInstance.chatCanSend()).toBe(true);
    expect(fixture.nativeElement.textContent).not.toContain('載入對話歷史失敗');
  });
  it('does not replace an edited initial draft when initialization finishes late', async () => {
    const storage = new Map([['bot_active_conversation_id','stale']]);
    vi.stubGlobal('localStorage', {getItem:(key:string)=>storage.get(key) ?? null, setItem:(key:string,value:string)=>storage.set(key,value), removeItem:(key:string)=>storage.delete(key)});
    const finish: ((response: Response) => void)[] = [];
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init?: RequestInit) => {
      const query = JSON.parse(String(init?.body || '{}')).query || '';
      if (query.includes('Workspaces')) return Response.json({data:{workspaces:[{id:'personal',name:'Personal',role:'MANAGER',personal:true}]}});
      if (query.includes('defaultLocalBot')) return Response.json({data:{defaultLocalBot:bot,bots:[]}});
      if (query.includes('conversations(')) return new Promise<Response>(resolve => { finish.push(resolve); });
      if (query.includes('SessionHistory')) return Response.json({data:{conversation:{id:'old',workspaceId:'personal',botId:'local',title:'Old',pinned:false,messages:[],runs:[]}}});
      return Response.json({data:{conversations:[],bots:[],defaultLocalBot:bot}});
    }));
    const fixture = TestBed.createComponent(App); fixture.detectChanges();
    await vi.waitFor(() => expect(finish.length).toBe(2));
    fixture.componentInstance.chatDraftChanged('my draft');
    finish.forEach(resolve => resolve(Response.json({data:{conversations:[{id:'old',botId:'local',title:'Old',pinned:false,createdAt:'',updatedAt:''}]}})));
    await vi.waitFor(() => expect(fixture.componentInstance.isInitializing()).toBe(false));
    expect(fixture.componentInstance.chatSession().draft()).toBe('my draft');
  });
  it('restores a valid saved conversation outside the first page with no messages', async () => {
    const storage = new Map([['bot_active_conversation_id','valid']]);
    vi.stubGlobal('localStorage', {getItem:(key:string)=>storage.get(key) ?? null, setItem:(key:string,value:string)=>storage.set(key,value), removeItem:(key:string)=>storage.delete(key)});
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init?: RequestInit) => {
      const query = JSON.parse(String(init?.body || '{}')).query || '';
      if (query.includes('Workspaces')) return Response.json({data:{workspaces:[{id:'personal',name:'Personal',role:'MANAGER',personal:true}]}});
      if (query.includes('defaultLocalBot')) return Response.json({data:{defaultLocalBot:bot,bots:[]}});
      if (query.includes('SessionHistory')) return Response.json({data:{conversation:{id:'valid',workspaceId:'personal',botId:'local',title:'Saved',pinned:false,messages:[],runs:[]}}});
      return Response.json({data:{conversations:[],bots:[],defaultLocalBot:bot}});
    }));
    const fixture = TestBed.createComponent(App); fixture.detectChanges();
    await vi.waitFor(() => expect(fixture.componentInstance.isInitializing()).toBe(false));
    expect(fixture.componentInstance.currentConversationId()).toBe('valid');
    expect(fixture.componentInstance.activeConversation()?.id).toBe('valid');
    expect(fixture.componentInstance.streamError()).toBeNull();
    expect(storage.get('bot_active_conversation_id')).toBe('valid');
  });
  it('keeps a new draft when unavailable history arrives after the user switches', async () => {
    const storage = new Map<string,string>();
    vi.stubGlobal('localStorage', {getItem:(key:string)=>storage.get(key) ?? null, setItem:(key:string,value:string)=>storage.set(key,value), removeItem:(key:string)=>storage.delete(key)});
    let finish!: (response: Response) => void;
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init?: RequestInit) => {
      const query = JSON.parse(String(init?.body || '{}')).query || '';
      if (query.includes('Workspaces')) return Response.json({data:{workspaces:[{id:'personal',name:'Personal',role:'MANAGER',personal:true}]}});
      if (query.includes('defaultLocalBot')) return Response.json({data:{defaultLocalBot:bot,bots:[]}});
      if (query.includes('SessionHistory')) return new Promise<Response>(resolve => { finish = resolve; });
      return Response.json({data:{conversations:[],bots:[],defaultLocalBot:bot}});
    }));
    const fixture = TestBed.createComponent(App); fixture.detectChanges();
    await vi.waitFor(() => expect(fixture.componentInstance.isInitializing()).toBe(false));
    const pending = fixture.componentInstance.selectConversation('stale');
    await vi.waitFor(() => expect(finish).toBeDefined());
    await fixture.componentInstance.newConversation(); fixture.componentInstance.chatDraftChanged('keep me');
    finish(Response.json({data:{conversation:null}})); await pending;
    expect(fixture.componentInstance.currentConversationId()).toBeNull();
    expect(fixture.componentInstance.chatSession().draft()).toBe('keep me');
    expect(fixture.componentInstance.streamError()).toBeNull();
  });
  it('retains an actual failure for retry, then recovers when retry finds it unavailable', async () => {
    const storage = new Map([['bot_active_conversation_id','stale']]);
    vi.stubGlobal('localStorage', {getItem:(key:string)=>storage.get(key) ?? null, setItem:(key:string,value:string)=>storage.set(key,value), removeItem:(key:string)=>storage.delete(key)});
    let attempt = 0;
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init?: RequestInit) => {
      const query = JSON.parse(String(init?.body || '{}')).query || '';
      if (query.includes('Workspaces')) return Response.json({data:{workspaces:[{id:'personal',name:'Personal',role:'MANAGER',personal:true}]}});
      if (query.includes('defaultLocalBot')) return Response.json({data:{defaultLocalBot:bot,bots:[]}});
      if (query.includes('SessionHistory')) return ++attempt === 1 ? new Response('', {status:503}) : Response.json({data:{conversation:null}});
      return Response.json({data:{conversations:[],bots:[],defaultLocalBot:bot}});
    }));
    const fixture = TestBed.createComponent(App); fixture.detectChanges();
    await vi.waitFor(() => expect(fixture.componentInstance.isInitializing()).toBe(false));
    expect(fixture.componentInstance.streamError()).toContain('載入對話歷史失敗');
    expect(storage.get('bot_active_conversation_id')).toBe('stale');
    fixture.componentInstance.retryHistory();
    await vi.waitFor(() => expect(fixture.componentInstance.currentConversationId()).toBeNull());
    expect(fixture.componentInstance.streamError()).toBeNull();
    expect(storage.has('bot_active_conversation_id')).toBe(false);
  });
  it('preserves text entered while unavailable saved history is loading', async () => {
    const storage = new Map([['bot_active_conversation_id','stale']]);
    vi.stubGlobal('localStorage', {getItem:(key:string)=>storage.get(key) ?? null, setItem:(key:string,value:string)=>storage.set(key,value), removeItem:(key:string)=>storage.delete(key)});
    let finish!: (response: Response) => void;
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init?: RequestInit) => {
      const query = JSON.parse(String(init?.body || '{}')).query || '';
      if (query.includes('Workspaces')) return Response.json({data:{workspaces:[{id:'personal',name:'Personal',role:'MANAGER',personal:true}]}});
      if (query.includes('defaultLocalBot')) return Response.json({data:{defaultLocalBot:bot,bots:[]}});
      if (query.includes('SessionHistory')) return new Promise<Response>(resolve => {finish = resolve;});
      return Response.json({data:{conversations:[],bots:[],defaultLocalBot:bot}});
    }));
    const fixture = TestBed.createComponent(App); fixture.detectChanges();
    await vi.waitFor(() => expect(finish).toBeDefined());
    fixture.componentInstance.chatDraftChanged('typed while loading');
    finish(Response.json({data:{conversation:null}}));
    await vi.waitFor(() => expect(fixture.componentInstance.isInitializing()).toBe(false));
    expect(fixture.componentInstance.chatSession().draft()).toBe('typed while loading');
  });
  it('keeps malformed history as a retryable failure until valid history arrives', async () => {
    const storage = new Map([['bot_active_conversation_id','C']]);
    vi.stubGlobal('localStorage', {getItem:(key:string)=>storage.get(key) ?? null, setItem:(key:string,value:string)=>storage.set(key,value), removeItem:(key:string)=>storage.delete(key)});
    let attempt = 0;
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init?: RequestInit) => {
      const query = JSON.parse(String(init?.body || '{}')).query || '';
      if (query.includes('Workspaces')) return Response.json({data:{workspaces:[{id:'personal',name:'Personal',role:'MANAGER',personal:true}]}});
      if (query.includes('defaultLocalBot')) return Response.json({data:{defaultLocalBot:bot,bots:[]}});
      if (query.includes('SessionHistory')) return Response.json({data:{conversation:++attempt === 1 ? {workspaceId:'personal',messages:[]} : {id:'C',workspaceId:'personal',botId:'local',title:'Saved',pinned:false,messages:[],runs:[]}}});
      return Response.json({data:{conversations:[],bots:[],defaultLocalBot:bot}});
    }));
    const fixture = TestBed.createComponent(App); fixture.detectChanges();
    await vi.waitFor(() => expect(fixture.componentInstance.isInitializing()).toBe(false));
    expect(fixture.componentInstance.streamError()).toContain('載入對話歷史失敗');
    expect(fixture.componentInstance.activeConversation()).toBeNull();
    expect(storage.get('bot_active_conversation_id')).toBe('C');
    fixture.componentInstance.retryHistory();
    await vi.waitFor(() => expect(fixture.componentInstance.activeConversation()?.id).toBe('C'));
    expect(fixture.componentInstance.streamError()).toBeNull();
  });
});
