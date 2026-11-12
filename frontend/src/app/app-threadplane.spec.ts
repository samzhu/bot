import { TestBed } from '@angular/core/testing';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { App } from './app';

describe('ThreadPlane App integration', () => {
  afterEach(() => vi.unstubAllGlobals());
  it('renders the native chat surface and keeps navigation as a sibling', async () => {
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init?: RequestInit) => {
      const body = JSON.parse(String(init?.body || '{}'));
      if (body.query?.includes('defaultLocalBot')) return Response.json({data:{defaultLocalBot:null,bots:[]}});
      return Response.json({data:{conversations:[],bots:[],defaultLocalBot:null}});
    }));
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('app-chat-navigation')?.parentElement).toBe(root.querySelector('main')?.parentElement);
    expect(root.querySelector('chat-window')).toBeTruthy();
    expect(root.querySelector('chat-input')).toBeTruthy();
    expect([...root.querySelectorAll('*')].some(node => node.tagName.toLowerCase().startsWith('copilot-'))).toBe(false);
  });
  it('keeps a pending create submission on its source when the user switches bots', async () => {
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init?: RequestInit) => {
      const body = JSON.parse(String(init?.body || '{}'));
      if (body.query?.includes('defaultLocalBot')) return Response.json({data:{defaultLocalBot:null,bots:[]}});
      return Response.json({data:{conversations:[],bots:[],defaultLocalBot:null}});
    }));
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    await fixture.whenStable();
    const app = fixture.componentInstance;
    await vi.waitFor(() => expect(app.isInitializing()).toBe(false));
    const bot = { id:'a',name:'A',description:'',instructions:'',target:'GKE_REMOTE' as const,connectionStatus:'NOT_CONNECTED' as const,executionMode:'SPRING_AI_SIMULATION' as const,statusReason:null,canChat:true,createdAt:'',updatedAt:'' };
    const other = {...bot,id:'b',name:'B'};
    let resolve!: (id: string) => void;
    const create = vi.spyOn(app.chatTransport, 'createConversation').mockReturnValue(new Promise(r => resolve = r));
    const run = vi.spyOn(app.chatTransport, 'run').mockResolvedValue({status:'COMPLETED'});
    vi.spyOn(app.chatTransport, 'reconcile').mockResolvedValue({status:'COMPLETED'});
    app.openBotDraft(bot);
    app.chatDraftChanged('  question  ');
    app.submitChat('  question  ');
    app.submitChat('  question  ');
    expect(create).toHaveBeenCalledOnce();
    app.openBotDraft(other);
    app.chatDraftChanged(' B draft ');
    resolve('conversation-a');
    await vi.waitFor(() => expect(run).toHaveBeenCalledOnce());
    expect(app.currentConversationId()).toBeNull();
    expect(app.currentBot()?.id).toBe('b');
    expect(app.chatSession().draft()).toBe(' B draft ');
    expect(run.mock.calls[0][2]).toBe('  question  ');
  });
});
