import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { BotStore } from './bot-store';
import { BotManagerComponent } from './bot-manager.component';
import { BotView, getBotExecutionModeDisplay } from './bot.model';
import { App } from '../app';
import { appConfig } from '../app.config';
import { DraftStore } from '../draft-store';
import { ConversationListStore } from '../conversation-list-store';

describe('Bot Management, Signal Forms & Search/Paging Integration', () => {
  let fetchMock: any;

  const mockLocalBot: BotView = {
    id: 'bot-general',
    name: 'General Assistant',
    description: '預設本地助理',
    instructions: 'You are a helpful assistant.',
    target: 'LOCAL',
    connectionStatus: 'NOT_APPLICABLE',
    executionMode: 'LOCAL',
    statusReason: null,
    canChat: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z'
  };

  const mockGkeSimulatedBot: BotView = {
    id: 'bot-gke-01',
    name: 'GKE Cloud Data Agent',
    description: '負責處理 GKE 數據管線任務',
    instructions: 'You are an autonomous GKE agent running on Kubernetes substrate.',
    target: 'GKE_REMOTE',
    connectionStatus: 'NOT_CONNECTED',
    executionMode: 'SPRING_AI_SIMULATION',
    statusReason: '本機模擬，GKE尚未串接',
    canChat: true,
    createdAt: '2026-11-12T00:00:00Z',
    updatedAt: '2026-11-12T00:00:00Z'
  };

  const mockGkeDisabledBot: BotView = {
    id: 'bot-gke-02',
    name: 'GKE Sandbox Agent',
    description: 'GKE 未串接沙盒代理',
    instructions: 'Work instructions here',
    target: 'GKE_REMOTE',
    connectionStatus: 'NOT_CONNECTED',
    executionMode: 'UNAVAILABLE',
    statusReason: 'GKE 專案與叢集尚未配置',
    canChat: false,
    createdAt: '2026-11-12T00:00:00Z',
    updatedAt: '2026-11-12T00:00:00Z'
  };

  beforeEach(() => {
    try {
      localStorage.clear();
      sessionStorage.clear();
    } catch {}

    fetchMock = vi.fn().mockImplementation((url: string, opts?: any) => {
      let body: any = {};
      try {
        body = JSON.parse(opts?.body || '{}');
      } catch {}

      if (
        body.operationName === 'GetBots' ||
        body.query?.includes('query GetBots') ||
        body.query?.includes('bots(limit') ||
        body.query?.includes('bots(search')
      ) {
        const search: string | undefined = body.variables?.search;
        const target: string | undefined = body.variables?.target;
        const limit: number = body.variables?.limit ?? 20;
        const offset: number = body.variables?.offset ?? 0;

        let filtered = [mockLocalBot, mockGkeSimulatedBot, mockGkeDisabledBot];
        if (target) {
          filtered = filtered.filter(b => b.target === target);
        }
        if (search) {
          const s = search.toLowerCase();
          filtered = filtered.filter(
            b =>
              b.name.toLowerCase().includes(s) ||
              (b.description && b.description.toLowerCase().includes(s))
          );
        }
        const paged = filtered.slice(offset, offset + limit);

        return Promise.resolve({
          ok: true,
          status: 200,
          json: () =>
            Promise.resolve({
              data: {
                defaultLocalBot: mockLocalBot,
                bots: paged
              }
            })
        });
      }

      if (
        body.operationName === 'GetDefaultLocalBot' ||
        body.query?.includes('query GetDefaultLocalBot') ||
        (body.query?.includes('defaultLocalBot') && !body.query?.includes('bots('))
      ) {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () =>
            Promise.resolve({
              data: { defaultLocalBot: mockLocalBot }
            })
        });
      }

      if (
        body.operationName === 'GetBot' ||
        body.query?.includes('query GetBot(') ||
        body.query?.includes('bot(id:')
      ) {
        const id = body.variables?.id;
        const all = [mockLocalBot, mockGkeSimulatedBot, mockGkeDisabledBot];
        const found = all.find(b => b.id === id) || null;
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () =>
            Promise.resolve({
              data: { bot: found }
            })
        });
      }

      if (body.query?.includes('mutation CreateBot')) {
        const input = body.variables?.input;
        const newBot: BotView = {
          id: 'bot-new-created',
          name: input.name,
          description: input.description ?? null,
          instructions: input.instructions ?? null,
          target: 'GKE_REMOTE',
          connectionStatus: 'NOT_CONNECTED',
          executionMode: 'SPRING_AI_SIMULATION',
          statusReason: '本機模擬，GKE尚未串接',
          canChat: true,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString()
        };
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () =>
            Promise.resolve({
              data: { createBot: newBot }
            })
        });
      }

      if (body.query?.includes('mutation UpdateBot')) {
        const input = body.variables?.input;
        const updatedBot: BotView = {
          id: input.id,
          name: input.name,
          description: input.description ?? null,
          instructions: input.instructions ?? null,
          target: 'GKE_REMOTE',
          connectionStatus: 'NOT_CONNECTED',
          executionMode: 'SPRING_AI_SIMULATION',
          statusReason: '本機模擬，GKE尚未串接',
          canChat: true,
          createdAt: '2026-11-12T00:00:00Z',
          updatedAt: new Date().toISOString()
        };
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () =>
            Promise.resolve({
              data: { updateBot: updatedBot }
            })
        });
      }

      if (body.query?.includes('conversations')) {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () =>
            Promise.resolve({
              data: { conversations: [] }
            })
        });
      }

      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ data: {} })
      });
    });

    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    try {
      localStorage.clear();
      sessionStorage.clear();
    } catch {}
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  describe('BotModel & Status Interpretation', () => {
    it('correctly interprets LOCAL bot display', () => {
      const display = getBotExecutionModeDisplay(mockLocalBot);
      expect(display.badgeText).toBe('LOCAL');
      expect(display.isSimulated).toBe(false);
    });

    it('correctly interprets SPRING_AI_SIMULATION as 本機模擬，GKE尚未串接', () => {
      const display = getBotExecutionModeDisplay(mockGkeSimulatedBot);
      expect(display.badgeText).toBe('本機模擬，GKE尚未串接');
      expect(display.isSimulated).toBe(true);
    });

    it('correctly interprets UNAVAILABLE as 未連線 (停用)', () => {
      const display = getBotExecutionModeDisplay(mockGkeDisabledBot);
      expect(display.badgeText).toBe('未連線 (停用)');
      expect(display.isSimulated).toBe(false);
    });
  });

  describe('BotStore', () => {
    let store: BotStore;

    beforeEach(() => {
      TestBed.configureTestingModule({
        providers: [BotStore]
      });
      store = TestBed.inject(BotStore);
    });

    it('loads defaultLocalBot authoritatively via GraphQL query', async () => {
      const defaultBot = await store.loadDefaultLocalBot();
      expect(defaultBot).not.toBeNull();
      expect(defaultBot?.id).toBe('bot-general');
      expect(store.defaultLocalBot()?.name).toBe('General Assistant');
    });

    it('loads bots list via GraphQL query', async () => {
      const bots = await store.loadBots();
      expect(bots.length).toBe(3);
      expect(store.bots().length).toBe(3);
    });

    it('clears stale defaultLocalBot when loadBots fails', async () => {
      await store.loadBots();
      expect(store.defaultLocalBot()).not.toBeNull();

      fetchMock.mockImplementationOnce(() =>
        Promise.reject(new Error('Network failure loading bots'))
      );
      await expect(store.loadBots()).rejects.toThrow('Network failure loading bots');
      expect(store.defaultLocalBot()).toBeNull();
    });

    it('clears stale defaultLocalBot when loadBots response has null or missing defaultLocalBot', async () => {
      await store.loadBots();
      expect(store.defaultLocalBot()).not.toBeNull();

      fetchMock.mockImplementationOnce(() =>
        Promise.resolve({
          ok: true,
          status: 200,
          json: () =>
            Promise.resolve({
              data: {
                defaultLocalBot: null,
                bots: []
              }
            })
        })
      );

      await store.loadBots();
      expect(store.defaultLocalBot()).toBeNull();
    });

    describe('loadBot(id)', () => {
      it('returns BotView when bot exists without modifying bots list or defaultLocalBot', async () => {
        const bot = await store.loadBot('bot-gke-01');
        expect(bot).not.toBeNull();
        expect(bot?.id).toBe('bot-gke-01');
        expect(bot?.name).toBe('GKE Cloud Data Agent');
        expect(store.bots().length).toBe(0);
        expect(store.defaultLocalBot()).toBeNull();
      });

      it('returns null when bot is not found or null', async () => {
        const bot = await store.loadBot('non-existent-id');
        expect(bot).toBeNull();
      });

      it('returns null on network failure without throwing error', async () => {
        fetchMock.mockImplementationOnce(() => Promise.reject(new Error('Network error')));
        const bot = await store.loadBot('bot-1');
        expect(bot).toBeNull();
      });
    });

    it('creates new GKE bot and updates store bots list', async () => {
      await store.loadBots();
      const created = await store.createBot({
        name: 'New Pipeline Bot',
        description: 'New Pipeline Description',
        instructions: 'Pipeline Instructions'
      });

      expect(created.id).toBe('bot-new-created');
      expect(created.name).toBe('New Pipeline Bot');
      expect(store.bots()[0].id).toBe('bot-new-created');
    });

    it('updates existing GKE bot and replaces in store bots list', async () => {
      await store.loadBots();
      const updated = await store.updateBot({
        id: 'bot-gke-01',
        name: 'Updated Pipeline Bot',
        description: 'Updated Description',
        instructions: 'Updated Instructions'
      });

      expect(updated.id).toBe('bot-gke-01');
      expect(updated.name).toBe('Updated Pipeline Bot');
      const found = store.bots().find(b => b.id === 'bot-gke-01');
      expect(found?.name).toBe('Updated Pipeline Bot');
    });

    it('propagates GraphQL errors on createBot and sets error signal', async () => {
      fetchMock.mockImplementationOnce(() =>
        Promise.resolve({
          ok: true,
          status: 200,
          json: () =>
            Promise.resolve({
              errors: [{ message: 'Validation failed: Name cannot be blank' }]
            })
        })
      );

      await expect(store.createBot({ name: '   ' })).rejects.toThrow(
        'Validation failed: Name cannot be blank'
      );
      expect(store.error()).toBe('Validation failed: Name cannot be blank');
    });
  });

  describe('BotManagerComponent (獨立 Bot 管理清單, PDF p.57/66)', () => {
    let fixture: any;
    let component: BotManagerComponent;

    const getBotsCalls = () =>
      fetchMock.mock.calls
        .map((c: any[]) => JSON.parse(c[1]?.body || '{}'))
        .filter((b: any) => b.query?.includes('query GetBots'));
    const lastGetBots = () => getBotsCalls().at(-1);
    const text = () => (fixture.nativeElement as HTMLElement).textContent || '';
    const q = (sel: string) => (fixture.nativeElement as HTMLElement).querySelector(sel) as HTMLElement | null;

    // fetch 為 fake Promise；讓出數個 macrotask 後再重繪，確保 signal 已更新（不依賴 zone）。
    async function settle() {
      for (let i = 0; i < 3; i++) {
        await new Promise(r => setTimeout(r, 0));
        fixture.detectChanges();
      }
    }

    beforeEach(async () => {
      await TestBed.configureTestingModule({
        imports: [BotManagerComponent],
        providers: [BotStore]
      }).compileComponents();

      fixture = TestBed.createComponent(BotManagerComponent);
      component = fixture.componentInstance;
      fixture.detectChanges();
      await settle();
    });

    it('initial query is fixed to GKE_REMOTE at offset 0 and never lists the default LOCAL bot', () => {
      const body = lastGetBots();
      expect(body.variables.target).toBe('GKE_REMOTE');
      expect(body.variables.offset).toBe(0);
      expect(component.botStore.bots().every(b => b.target === 'GKE_REMOTE')).toBe(true);
      expect(q('[data-testid="bot-card-bot-general"]')).toBeNull();
      expect(q('[data-testid="bot-card-bot-gke-01"]')).not.toBeNull();
    });

    it('does not mutate defaultLocalBot when managing the list', () => {
      expect(component.botStore.defaultLocalBot()?.id).toBe('bot-general');
    });

    it('never displays a previous general-chat list while the managed query is pending or failed', async () => {
      component.botStore.bots.set([component.botStore.defaultLocalBot()!]);
      component.hasManagedResult.set(false);
      let rejectRequest!: (error: Error) => void;
      fetchMock.mockImplementationOnce(() => new Promise((_resolve, reject) => { rejectRequest = reject; }));
      const pending = component.fetchBots();
      fixture.detectChanges();
      expect(q('[data-testid="bots-loading-indicator"]')).not.toBeNull();
      expect(q('[data-testid="bot-card-bot-general"]')).toBeNull();
      rejectRequest(new Error('Managed list unavailable'));
      await expect(pending).rejects.toThrow('Managed list unavailable');
      fixture.detectChanges();
      expect(q('[data-testid="bot-store-error"]')).not.toBeNull();
      expect(q('[data-testid="bot-card-bot-general"]')).toBeNull();
    });

    it('shows PDF p.57 wording: 機器人 / 新增 Bot / search placeholder, without GKE agent wording', () => {
      expect(q('h1')?.textContent?.trim()).toBe('機器人');
      expect(q('[data-testid="create-bot-modal-btn"]')?.textContent).toContain('新增 Bot');
      expect(q('[data-testid="bot-search-input"]')?.getAttribute('placeholder')).toBe('搜尋機器人名稱或用途');
      expect(text()).not.toContain('新增 GKE 代理');
      expect(text()).not.toContain('開啟草稿');
    });

    it('replaces the target select with 全部狀態/未連線 only (no fabricated READY or LOCAL option)', () => {
      expect(q('[data-testid="target-filter-select"]')).toBeNull();
      const options = Array.from(
        (q('[data-testid="status-filter-select"]') as HTMLSelectElement).options
      ).map(o => [o.value, o.textContent?.trim()]);
      expect(options).toEqual([
        ['ALL', '全部狀態'],
        ['NOT_CONNECTED', '未連線']
      ]);
      expect(text()).not.toMatch(/READY|在線|部署完成/);
    });

    it('cards focus on name, purpose and 開始對話/設定; instructions full text and technical details are absent', () => {
      const card = q('[data-testid="bot-card-bot-gke-01"]') as HTMLElement;
      expect(card.textContent).toContain('GKE Cloud Data Agent');
      expect(card.textContent).toContain('負責處理 GKE 數據管線任務');
      expect(card.textContent).toContain('開始對話');
      expect(card.textContent).toContain('設定');
      expect(card.textContent).toContain('本機模擬，GKE尚未串接');
      expect(card.textContent).not.toContain(mockGkeSimulatedBot.instructions!);
      expect(card.textContent).not.toMatch(/GKE_REMOTE|LOCAL|NOT_CONNECTED|連線狀態|建立於/);
      expect(card.querySelector('pre')).toBeNull();
    });

    it('does not present page size as total, nor invent recent usage or memory data', () => {
      expect(text()).not.toMatch(/\d+\s*個機器人|最近使用|專屬記憶/);
    });

    it('copy does not imply group/workspace features nor unconditional simulation; per-card hint carries the real mode', () => {
      expect(q('.manager-subtitle')?.textContent?.trim()).toBe('管理額外建立的獨立 Bot；一般對話不需建立 Bot。');
      expect(text()).not.toMatch(/群組|隸屬目前工作區|加入群組/);
      const foot = q('[data-testid="manager-footnote"]')?.textContent?.trim();
      expect(foot).toBe('正式 Agent 尚未連線，請依各 Bot 狀態開始對話');
      // OFF（UNAVAILABLE）卡片不得宣稱可模擬執行；SIMULATION 卡片才顯示模擬
      const off = q('[data-testid="bot-card-bot-gke-02"]')!.textContent!;
      expect(off).not.toMatch(/模擬|可開始/);
      expect(q('[data-testid="bot-card-bot-gke-01"]')!.textContent).toContain('本機模擬');
      expect(foot).not.toMatch(/模擬/);
    });

    it('shows truthful 未連線 hint for UNAVAILABLE bot, still without enum text', () => {
      const card = q('[data-testid="bot-card-bot-gke-02"]') as HTMLElement;
      expect(card.textContent).toContain('未連線');
      expect(card.textContent).not.toContain('NOT_CONNECTED');
    });

    it('emits startChat with the correct bot via 開始對話 (even when canChat is false)', () => {
      const emitted: BotView[] = [];
      component.startChat.subscribe(b => emitted.push(b));
      (q('[data-testid="open-draft-btn-bot-gke-01"]') as HTMLButtonElement).click();
      (q('[data-testid="open-draft-btn-bot-gke-02"]') as HTMLButtonElement).click();
      expect(emitted.map(b => b.id)).toEqual(['bot-gke-01', 'bot-gke-02']);
    });

    it('新增 Bot and 設定 delegate to the embedded BotEditorComponent with the trigger element', () => {
      const editor = component.editor()!;
      expect(editor).toBeDefined();
      const createSpy = vi.spyOn(editor, 'openCreate').mockImplementation(() => {});
      const editSpy = vi.spyOn(editor, 'openEdit').mockImplementation(() => {});

      const createBtn = q('[data-testid="create-bot-modal-btn"]') as HTMLButtonElement;
      createBtn.click();
      expect(createSpy).toHaveBeenCalledWith(createBtn);

      const editBtn = q('[data-testid="edit-bot-btn-bot-gke-01"]') as HTMLButtonElement;
      editBtn.click();
      expect(editSpy).toHaveBeenCalledWith(mockGkeSimulatedBot, editBtn);
    });

    it('search resets offset to 0 and keeps the fixed GKE_REMOTE scope', () => {
      component.currentOffset.set(40);
      component.onSearchInput({ target: { value: ' Data ' } } as unknown as Event);
      expect(component.currentOffset()).toBe(0);
      const body = lastGetBots();
      expect(body.variables.search).toBe('Data');
      expect(body.variables.target).toBe('GKE_REMOTE');
      expect(body.variables.offset).toBe(0);
    });

    it('status change resets offset and never turns into a LOCAL or client-side page filter', async () => {
      component.currentOffset.set(20);
      component.onStatusChange({ target: { value: 'NOT_CONNECTED' } } as unknown as Event);
      expect(component.selectedStatus()).toBe('NOT_CONNECTED');
      expect(component.currentOffset()).toBe(0);
      const body = lastGetBots();
      expect(body.variables.target).toBe('GKE_REMOTE');
      await settle();
      // 後端回傳的整頁原樣顯示，前端不二次刪除
      expect(component.botStore.bots().length).toBe(2);
    });

    it('paginates with offset steps of 20 while keeping scope and search', () => {
      component.searchQuery.set('GKE');
      component.botStore.hasMore.set(true);
      component.nextPage();
      expect(component.currentOffset()).toBe(20);
      expect(lastGetBots().variables).toMatchObject({ search: 'GKE', target: 'GKE_REMOTE', offset: 20 });
      component.prevPage();
      expect(component.currentOffset()).toBe(0);
    });

    it('renders an empty state for an empty result', async () => {
      component.onSearchInput({ target: { value: 'no-such-bot' } } as unknown as Event);
      await settle();
      expect(q('[data-testid="bots-empty-state"]')).not.toBeNull();
    });

    describe('saved from editor: refresh contract', () => {
      it('goes back to offset 0 and refetches with active search; no mutation is sent by the manager', async () => {
        component.searchQuery.set('Data');
        component.currentOffset.set(20);
        const before = fetchMock.mock.calls.length;

        await component.onSaved(mockGkeSimulatedBot);

        const newCalls = fetchMock.mock.calls
          .slice(before)
          .map((c: any[]) => JSON.parse(c[1]?.body || '{}'));
        expect(newCalls.length).toBe(1);
        expect(newCalls[0].query).toContain('query GetBots');
        expect(newCalls[0].variables).toMatchObject({ search: 'Data', target: 'GKE_REMOTE', offset: 0 });
        expect(component.currentOffset()).toBe(0);
      });

      it('refresh failure only exposes list retry and never re-sends a mutation', async () => {
        let mutationCount = 0;
        fetchMock.mockImplementation((_url: string, opts?: any) => {
          const body = JSON.parse(opts?.body || '{}');
          if (body.query?.includes('mutation')) {
            mutationCount++;
          }
          if (body.query?.includes('query GetBots')) {
            return Promise.resolve({
              ok: false,
              status: 500,
              json: () => Promise.resolve({ errors: [{ message: 'HTTP 500: Internal Server Error' }] })
            });
          }
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: {} }) });
        });

        await component.onSaved(mockGkeSimulatedBot);
        await settle();
        expect(component.botStore.error()).toContain('500');
        expect(q('[data-testid="bot-store-error"]')).not.toBeNull();

        fetchMock.mockImplementation((_url: string, opts?: any) => {
          const body = JSON.parse(opts?.body || '{}');
          if (body.query?.includes('mutation')) {
            mutationCount++;
          }
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ data: { defaultLocalBot: mockLocalBot, bots: [mockGkeSimulatedBot] } })
          });
        });
        (q('.retry-link-btn') as HTMLButtonElement).click();
        await settle();

        expect(mutationCount).toBe(0);
        expect(component.botStore.error()).toBeNull();
        expect(component.botStore.bots()[0].id).toBe('bot-gke-01');
      });
    });

    describe('stale response protection', () => {
      it('an older slow search response never overwrites a newer result', async () => {
        let resolveStale: any;
        const stale = new Promise(resolve => (resolveStale = resolve));
        fetchMock.mockImplementationOnce(() => stale);
        component.onSearchInput({ target: { value: 'Old' } } as any);

        fetchMock.mockImplementation(() =>
          Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                data: { defaultLocalBot: mockLocalBot, bots: [{ ...mockGkeSimulatedBot, name: 'Fresh Result' }] }
              })
          })
        );
        component.onSearchInput({ target: { value: 'New' } } as any);
        await settle();
        expect(component.botStore.bots()[0].name).toBe('Fresh Result');

        resolveStale({
          ok: true,
          status: 200,
          json: () =>
            Promise.resolve({ data: { defaultLocalBot: mockLocalBot, bots: [{ ...mockLocalBot, name: 'Stale Result' }] } })
        });
        await new Promise(r => setTimeout(r, 20));
        expect(component.botStore.bots()[0].name).toBe('Fresh Result');
      });

      it('an older failed response does not set error over a newer success', async () => {
        let rejectStale: any;
        const stale = new Promise((_, reject) => (rejectStale = reject));
        fetchMock.mockImplementationOnce(() => stale);
        component.onSearchInput({ target: { value: 'Old' } } as any);
        fetchMock.mockImplementation(() =>
          Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ data: { defaultLocalBot: mockLocalBot, bots: [mockGkeSimulatedBot] } })
          })
        );
        component.onSearchInput({ target: { value: 'New' } } as any);
        await settle();
        rejectStale(new Error('late failure'));
        await new Promise(r => setTimeout(r, 20));
        expect(component.botStore.error()).toBeNull();
        expect(component.botStore.bots().length).toBe(1);
      });
    });
  });

  describe('App Integration & Authoritative Default Bot', () => {
    let fixture: any;
    let app: App;

    beforeEach(async () => {
      await TestBed.configureTestingModule({
        imports: [App],
        providers: [
          ...appConfig.providers,
          DraftStore,
          ConversationListStore,
          BotStore
        ]
      }).compileComponents();

      fixture = TestBed.createComponent(App);
      app = fixture.componentInstance;
      fixture.detectChanges();
      await app.ngOnInit();
    });

    it('has default currentView set to chat', () => {
      expect(app.currentView()).toBe('chat');
    });

    it('switches currentView to bots when switchToBotManager() is called', () => {
      app.switchToBotManager();
      expect(app.currentView()).toBe('bots');
    });

    it('switches currentView back to chat when selectConversation() is called', async () => {
      app.switchToBotManager();
      expect(app.currentView()).toBe('bots');

      await app.selectConversation('conv-1');
      expect(app.currentView()).toBe('chat');
    });

    it('switches currentView back to chat when newConversation() is called', async () => {
      app.switchToBotManager();
      expect(app.currentView()).toBe('bots');

      await app.newConversation();
      expect(app.currentView()).toBe('chat');
    });

    it('binds seedBot to authoritative defaultLocalBot and does not blindly guess bots[0]', async () => {
      await app.loadBots();

      expect(app.defaultLocalBot()?.id).toBe('bot-general');
      expect(app.seedBot()?.id).toBe('bot-general');

      const store = TestBed.inject(BotStore);
      store.bots.set([mockGkeSimulatedBot, mockLocalBot]);

      expect(app.seedBot()?.id).toBe('bot-general');
      expect(app.seedBot()?.target).toBe('LOCAL');
    });

    describe('Negative Cases: Strict Default Bot Contract & Fallback Prevention', () => {
      it('never falls back to GKE bot when list contains only a single GKE bot and defaultLocalBot is null', async () => {
        const store = TestBed.inject(BotStore);
        store.defaultLocalBot.set(null);
        store.bots.set([mockGkeSimulatedBot]);

        expect(app.defaultLocalBot()).toBeNull();
        expect(app.seedBot()).toBeNull();
        expect(app.bots().length).toBe(1);
        expect(app.bots()[0].target).toBe('GKE_REMOTE');
      });

      it('never blindly picks first LOCAL bot when list has other LOCAL bots but defaultLocalBot is null', async () => {
        const store = TestBed.inject(BotStore);
        store.defaultLocalBot.set(null);
        store.bots.set([
          { ...mockLocalBot, id: 'bot-other-local', name: 'Other Local' },
          { ...mockLocalBot, id: 'bot-third-local', name: 'Third Local' }
        ]);

        expect(app.defaultLocalBot()).toBeNull();
        expect(app.seedBot()).toBeNull();
      });

      it('strictly sets seedBot to null and refuses submitInput mutation when default query fails or returns null', async () => {
        app.currentConversationId.set(null);
        app.activeDraftBot.set(null);
        const store = TestBed.inject(BotStore);

        let mutationCalled = false;
        fetchMock.mockImplementation((url: string, opts?: any) => {
          let body = JSON.parse(opts?.body || '{}');
          if (body.query?.includes('createConversation')) {
            mutationCalled = true;
          }
          if (
            body.query?.includes('defaultLocalBot') ||
            body.operationName === 'GetDefaultLocalBot' ||
            body.query?.includes('GetBots')
          ) {
            return Promise.resolve({
              ok: true,
              status: 200,
              json: () => Promise.resolve({ data: { defaultLocalBot: null, bots: [] } })
            });
          }
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: {} }) });
        });

        await store.loadDefaultLocalBot();

        expect(store.defaultLocalBot()).toBeNull();
        expect(app.seedBot()).toBeNull();

        app.inputValue.set('Test message without default bot');
        await app.submitInput();

        expect(mutationCalled).toBe(false);
        // Guard condition blocks message dispatch when default local bot is absent
        expect(app.streamError() === '未配置預設本地助理，目前無法建立對話' || app.streamError() === null).toBe(true);
        expect(app.inputValue()).toBe('Test message without default bot');
      });

      it('authoritative defaultLocalBot is used even when absent from the current paginated bots list', async () => {
        const store = TestBed.inject(BotStore);
        store.defaultLocalBot.set(mockLocalBot);
        store.bots.set([mockGkeSimulatedBot, mockGkeDisabledBot]);

        expect(app.seedBot()?.id).toBe('bot-general');
        expect(app.seedBot()?.name).toBe('General Assistant');
        expect(app.bots().some(b => b.id === 'bot-general')).toBe(false);
      });
    });
  });
});
