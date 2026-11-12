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

  describe('BotManagerComponent & Official Signal Forms', () => {
    let fixture: any;
    let component: BotManagerComponent;

    beforeEach(async () => {
      await TestBed.configureTestingModule({
        imports: [BotManagerComponent],
        providers: [BotStore]
      }).compileComponents();

      fixture = TestBed.createComponent(BotManagerComponent);
      component = fixture.componentInstance;
      fixture.detectChanges();
    });

    it('initializes official Signal Forms botForm with required and maxLength schema', () => {
      expect(component.botForm).toBeDefined();
      expect(component.botModel()).toEqual({
        name: '',
        description: '',
        instructions: ''
      });
      expect(component.botForm.name().invalid()).toBe(true);
    });

    it('validates whitespace-only name as invalid', () => {
      component.botModel.set({
        name: '    ',
        description: '',
        instructions: ''
      });
      expect(component.botForm.name().invalid()).toBe(true);
    });

    it('validates name with 100 characters plus leading/trailing whitespace as valid', () => {
      // Trimmed length is exactly 100, so surrounding whitespace is allowed
      component.botModel.set({
        name: '   ' + 'a'.repeat(100) + '   ',
        description: '',
        instructions: ''
      });
      expect(component.botForm.name().valid()).toBe(true);
    });

    it('validates name exceeding trimmed length 100 as invalid', () => {
      component.botModel.set({
        name: '   ' + 'a'.repeat(101) + '   ',
        description: '',
        instructions: ''
      });
      expect(component.botForm.name().invalid()).toBe(true);
    });

    it('validates description exceeding 2000 characters as invalid', () => {
      component.botModel.set({
        name: 'Valid Name',
        description: 'd'.repeat(2001),
        instructions: ''
      });
      expect(component.botForm.description().invalid()).toBe(true);
      expect(component.botForm().invalid()).toBe(true);
    });

    it('validates instructions exceeding 10000 characters as invalid', () => {
      component.botModel.set({
        name: 'Valid Name',
        description: '',
        instructions: 'i'.repeat(10001)
      });
      expect(component.botForm.instructions().invalid()).toBe(true);
      expect(component.botForm().invalid()).toBe(true);
    });

    it('validates valid input as valid form', () => {
      component.botModel.set({
        name: 'Valid GKE Agent',
        description: 'Valid description',
        instructions: 'Valid instructions'
      });
      expect(component.botForm().valid()).toBe(true);
    });

    it('opens native dialog using showModal()', () => {
      const dialogNative = document.createElement('dialog');
      dialogNative.showModal = vi.fn();
      dialogNative.close = vi.fn();
      vi.spyOn(component.dialogRef()!, 'nativeElement', 'get').mockReturnValue(
        dialogNative as any
      );

      component.openCreateDialog();
      expect(component.isDialogOpen()).toBe(true);
      expect(dialogNative.showModal).toHaveBeenCalled();
    });

    it('closes native dialog without mutation on cancel event (Escape key) and restores focus', async () => {
      const fakeTrigger = document.createElement('button');
      fakeTrigger.focus = vi.fn();
      const dialogNative = document.createElement('dialog');
      dialogNative.showModal = vi.fn();
      dialogNative.close = vi.fn();
      vi.spyOn(component.dialogRef()!, 'nativeElement', 'get').mockReturnValue(
        dialogNative as any
      );

      component.openCreateDialog(fakeTrigger);
      expect(component.isDialogOpen()).toBe(true);

      component.onDialogCancel(new Event('cancel'));
      expect(component.isDialogOpen()).toBe(false);

      await new Promise(resolve => setTimeout(resolve, 10));
      expect(fakeTrigger.focus).toHaveBeenCalled();
    });

    it('retains user input values upon server error during submitBot', async () => {
      fetchMock.mockImplementationOnce(() =>
        Promise.resolve({
          ok: true,
          status: 200,
          json: () =>
            Promise.resolve({
              errors: [{ message: 'Network timeout connecting to server' }]
            })
        })
      );

      component.botModel.set({
        name: 'My Special Agent',
        description: 'Special description',
        instructions: 'Special instructions'
      });

      await component.submitBot();

      expect(component.formError()).toBe('Network timeout connecting to server');
      expect(component.botModel().name).toBe('My Special Agent');
      expect(component.botModel().description).toBe('Special description');
      expect(component.botModel().instructions).toBe('Special instructions');
    });

    it('clears form, refetches bots, and closes dialog upon successful submitBot in create mode', async () => {
      const dialogNative = document.createElement('dialog');
      dialogNative.showModal = vi.fn();
      dialogNative.close = vi.fn();
      dialogNative.open = true;
      vi.spyOn(component.dialogRef()!, 'nativeElement', 'get').mockReturnValue(
        dialogNative as any
      );

      component.openCreateDialog();
      component.botModel.set({
        name: 'Successfully Created Agent',
        description: 'Good description',
        instructions: 'Good instructions'
      });

      await component.submitBot();

      expect(component.formError()).toBeNull();
      expect(component.botModel().name).toBe('');
      expect(dialogNative.close).toHaveBeenCalled();
    });

    it('preserves raw whitespace and multiline formatting for description and instructions upon submit', async () => {
      let capturedInput: any = null;
      fetchMock.mockImplementation((url: string, opts?: any) => {
        const body = JSON.parse(opts?.body || '{}');
        if (body.query?.includes('mutation CreateBot')) {
          capturedInput = body.variables?.input;
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                data: {
                  createBot: {
                    ...mockGkeSimulatedBot,
                    id: 'bot-raw-test',
                    name: capturedInput.name,
                    description: capturedInput.description,
                    instructions: capturedInput.instructions
                  }
                }
              })
          });
        }
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () =>
            Promise.resolve({
              data: { defaultLocalBot: mockLocalBot, bots: [mockLocalBot] }
            })
        });
      });

      component.openCreateDialog();
      const rawDesc = '  Leading spaces and \n newline in description  ';
      const rawInst = '  System instructions:\n\t- Step 1\n\t- Step 2\n  ';
      component.botModel.set({
        name: '  Trimmed Bot Name  ',
        description: rawDesc,
        instructions: rawInst
      });

      await component.submitBot();

      expect(capturedInput).not.toBeNull();
      // Name is strictly trimmed
      expect(capturedInput.name).toBe('Trimmed Bot Name');
      // Description and instructions are preserved raw without trimming
      expect(capturedInput.description).toBe(rawDesc);
      expect(capturedInput.instructions).toBe(rawInst);
    });

    describe('Edit Bot Flow & Read-only Protection', () => {
      it('opens edit dialog with prefilled data for GKE bots', () => {
        const dialogNative = document.createElement('dialog');
        dialogNative.showModal = vi.fn();
        dialogNative.close = vi.fn();
        vi.spyOn(component.dialogRef()!, 'nativeElement', 'get').mockReturnValue(
          dialogNative as any
        );

        component.openEditDialog(mockGkeSimulatedBot);

        expect(component.dialogMode()).toBe('edit');
        expect(component.editingBotId()).toBe('bot-gke-01');
        expect(component.botModel().name).toBe(mockGkeSimulatedBot.name);
        expect(component.botModel().description).toBe(mockGkeSimulatedBot.description);
        expect(component.botModel().instructions).toBe(mockGkeSimulatedBot.instructions);
        expect(component.isDialogOpen()).toBe(true);
        expect(dialogNative.showModal).toHaveBeenCalled();
      });

      it('strictly forbids opening edit dialog for LOCAL bots (read-only)', () => {
        const dialogNative = document.createElement('dialog');
        dialogNative.showModal = vi.fn();
        vi.spyOn(component.dialogRef()!, 'nativeElement', 'get').mockReturnValue(
          dialogNative as any
        );

        component.openEditDialog(mockLocalBot);

        expect(component.isDialogOpen()).toBe(false);
        expect(dialogNative.showModal).not.toHaveBeenCalled();
      });

      it('submits updateBot in edit mode and refetches authoritative list', async () => {
        const dialogNative = document.createElement('dialog');
        dialogNative.showModal = vi.fn();
        dialogNative.close = vi.fn();
        dialogNative.open = true;
        vi.spyOn(component.dialogRef()!, 'nativeElement', 'get').mockReturnValue(
          dialogNative as any
        );

        let updateCalledWith: any = null;
        fetchMock.mockImplementation((url: string, opts?: any) => {
          const body = JSON.parse(opts?.body || '{}');
          if (body.query?.includes('mutation UpdateBot')) {
            updateCalledWith = body.variables?.input;
            return Promise.resolve({
              ok: true,
              status: 200,
              json: () =>
                Promise.resolve({
                  data: {
                    updateBot: {
                      ...mockGkeSimulatedBot,
                      name: updateCalledWith.name
                    }
                  }
                })
            });
          }
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () =>
              Promise.resolve({
                data: { defaultLocalBot: mockLocalBot, bots: [mockLocalBot] }
              })
          });
        });

        component.openEditDialog(mockGkeSimulatedBot);
        component.botModel.set({
          name: '  Updated GKE Name  ',
          description: 'Updated description',
          instructions: 'Updated instructions'
        });

        await component.submitBot();

        expect(updateCalledWith).not.toBeNull();
        expect(updateCalledWith.id).toBe('bot-gke-01');
        expect(updateCalledWith.name).toBe('Updated GKE Name');
        expect(updateCalledWith.description).toBe('Updated description');
        expect(dialogNative.close).toHaveBeenCalled();
      });
    });

    describe('Draft Action & Output Contract (startChat)', () => {
      it('emits startChat output when onOpenDraft is clicked', () => {
        let emittedBot: BotView | null = null;
        component.startChat.subscribe((bot: BotView) => {
          emittedBot = bot;
        });

        component.onOpenDraft(mockGkeSimulatedBot);
        expect(emittedBot).toBe(mockGkeSimulatedBot);
      });

      it('allows opening draft even for bots with canChat: false (OFF mode)', () => {
        let emittedBot: BotView | null = null;
        component.startChat.subscribe((bot: BotView) => {
          emittedBot = bot;
        });

        // mockGkeDisabledBot has canChat: false
        expect(mockGkeDisabledBot.canChat).toBe(false);
        component.onOpenDraft(mockGkeDisabledBot);
        expect(emittedBot).toBe(mockGkeDisabledBot);
      });
    });

    describe('Search, Target Filtering, Stable Pagination & Counter-Examples', () => {
      it('triggers backend search and resets offset to 0 on search input', async () => {
        component.currentOffset.set(40);

        const fakeEvent = {
          target: { value: 'Data' }
        } as unknown as Event;

        component.onSearchInput(fakeEvent);

        expect(component.searchQuery()).toBe('Data');
        expect(component.currentOffset()).toBe(0);

        // Verify last fetch query carried search variable
        const lastCall = fetchMock.mock.calls[fetchMock.mock.calls.length - 1];
        const body = JSON.parse(lastCall[1].body);
        expect(body.variables.search).toBe('Data');
      });

      it('triggers backend target filtering and resets offset to 0 on target change', async () => {
        component.currentOffset.set(20);

        const fakeEvent = {
          target: { value: 'LOCAL' }
        } as unknown as Event;

        component.onTargetChange(fakeEvent);

        expect(component.selectedTarget()).toBe('LOCAL');
        expect(component.currentOffset()).toBe(0);

        const lastCall = fetchMock.mock.calls[fetchMock.mock.calls.length - 1];
        const body = JSON.parse(lastCall[1].body);
        expect(body.variables.target).toBe('LOCAL');
      });

      it('handles pagination navigation via prevPage and nextPage', async () => {
        component.currentOffset.set(0);
        component.botStore.hasMore.set(true);

        component.nextPage();
        expect(component.currentOffset()).toBe(20);

        component.prevPage();
        expect(component.currentOffset()).toBe(0);
      });

      it('Counter-example 1: creating GKE bot while active filter is LOCAL does not display GKE bot in LOCAL list', async () => {
        // Active filter is set to LOCAL
        component.selectedTarget.set('LOCAL');

        fetchMock.mockImplementation((url: string, opts?: any) => {
          const body = JSON.parse(opts?.body || '{}');
          if (body.query?.includes('mutation CreateBot')) {
            return Promise.resolve({
              ok: true,
              status: 200,
              json: () =>
                Promise.resolve({
                  data: {
                    createBot: {
                      ...mockGkeSimulatedBot,
                      id: 'bot-gke-new',
                      name: 'Newly Created GKE Agent',
                      target: 'GKE_REMOTE'
                    }
                  }
                })
            });
          }
          if (body.query?.includes('query GetBots')) {
            // Backend strictly enforces target filter
            const target = body.variables?.target;
            if (target === 'LOCAL') {
              return Promise.resolve({
                ok: true,
                status: 200,
                json: () =>
                  Promise.resolve({
                    data: {
                      defaultLocalBot: mockLocalBot,
                      bots: [mockLocalBot] // Only LOCAL bots returned
                    }
                  })
              });
            }
          }
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: {} }) });
        });

        component.openCreateDialog();
        component.botModel.set({
          name: 'Newly Created GKE Agent',
          description: 'GKE description',
          instructions: 'GKE instructions'
        });

        await component.submitBot();

        // The list must be strictly reconciled by fetchBots with active filter (LOCAL)
        expect(component.botStore.bots().length).toBe(1);
        expect(component.botStore.bots()[0].target).toBe('LOCAL');
        expect(component.botStore.bots().some(b => b.target === 'GKE_REMOTE')).toBe(false);
      });

      it('Counter-example 2: after edit mutation, older in-flight background query responses do not overwrite post-mutation list', async () => {
        let resolveStaleSearch: any = null;
        const staleSearchPromise = new Promise(resolve => {
          resolveStaleSearch = resolve;
        });

        // Trigger an initial search that hangs in flight
        fetchMock.mockImplementationOnce(() => staleSearchPromise);
        component.onSearchInput({ target: { value: 'OldSearchTerm' } } as any);

        // While that search is in-flight, user edits a bot and submits
        fetchMock.mockImplementation((url: string, opts?: any) => {
          const body = JSON.parse(opts?.body || '{}');
          if (body.query?.includes('mutation UpdateBot')) {
            return Promise.resolve({
              ok: true,
              status: 200,
              json: () =>
                Promise.resolve({
                  data: {
                    updateBot: {
                      ...mockGkeSimulatedBot,
                      name: 'Authoritative Edited Bot'
                    }
                  }
                })
            });
          }
          if (body.query?.includes('query GetBots')) {
            return Promise.resolve({
              ok: true,
              status: 200,
              json: () =>
                Promise.resolve({
                  data: {
                    defaultLocalBot: mockLocalBot,
                    bots: [{ ...mockGkeSimulatedBot, name: 'Authoritative Edited Bot' }]
                  }
                })
            });
          }
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: {} }) });
        });

        component.openEditDialog(mockGkeSimulatedBot);
        component.botModel.set({
          name: 'Authoritative Edited Bot',
          description: '',
          instructions: ''
        });
        await component.submitBot();

        expect(component.botStore.bots()[0].name).toBe('Authoritative Edited Bot');

        // Now the old hanging search finally resolves with stale data
        resolveStaleSearch({
          ok: true,
          status: 200,
          json: () =>
            Promise.resolve({
              data: {
                defaultLocalBot: mockLocalBot,
                bots: [{ ...mockLocalBot, name: 'Stale Outdated Result' }]
              }
            })
        });
        await new Promise(resolve => setTimeout(resolve, 20));

        // Stale result must be discarded; store remains on Authoritative Edited Bot!
        expect(component.botStore.bots()[0].name).toBe('Authoritative Edited Bot');
        expect(component.botStore.bots().some(b => b.name === 'Stale Outdated Result')).toBe(false);
      });

      it('separates mutation execution from list refetch: when create mutation succeeds but subsequent list refetch fails with 500, dialog is closed, form is cleared, error banner allows retry without resubmitting mutation', async () => {
        let createCallCount = 0;
        let getBotsCallCount = 0;

        fetchMock.mockImplementation((url: string, opts?: any) => {
          const body = JSON.parse(opts?.body || '{}');
          if (body.query?.includes('mutation CreateBot')) {
            createCallCount++;
            return Promise.resolve({
              ok: true,
              status: 200,
              json: () =>
                Promise.resolve({
                  data: {
                    createBot: {
                      ...mockGkeSimulatedBot,
                      id: 'bot-gke-500-test',
                      name: 'Resilient Bot'
                    }
                  }
                })
            });
          }
          if (body.query?.includes('query GetBots')) {
            getBotsCallCount++;
            // Post-mutation refetch fails with 500
            return Promise.resolve({
              ok: false,
              status: 500,
              json: () => Promise.resolve({ errors: [{ message: 'HTTP 500: Internal Server Error' }] })
            });
          }
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: {} }) });
        });

        component.openCreateDialog();
        component.botModel.set({
          name: 'Resilient Bot',
          description: 'Valid description',
          instructions: 'Valid instructions'
        });

        // Submit creation
        await component.submitBot();

        // 1. Mutation must have been called exactly once
        expect(createCallCount).toBe(1);

        // 2. Dialog is closed and form model is cleared so user cannot resubmit mutation
        expect(component.isDialogOpen()).toBe(false);
        expect(component.botModel().name).toBe('');
        // Form-level error banner inside dialog is null (not falsely reporting save failure)
        expect(component.formError()).toBeNull();

        // 3. Global botStore.error is set, showing manager error banner with retry option
        expect(component.botStore.error()).toContain('500');

        // 4. When user clicks retry on list error banner, it calls fetchBots() and does NOT re-send mutation
        // Configure refetch to succeed on retry
        fetchMock.mockImplementation((url: string, opts?: any) => {
          const body = JSON.parse(opts?.body || '{}');
          if (body.query?.includes('mutation CreateBot')) {
            createCallCount++;
            return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: {} }) });
          }
          if (body.query?.includes('query GetBots')) {
            return Promise.resolve({
              ok: true,
              status: 200,
              json: () =>
                Promise.resolve({
                  data: {
                    defaultLocalBot: mockLocalBot,
                    bots: [{ ...mockGkeSimulatedBot, id: 'bot-gke-500-test', name: 'Resilient Bot' }]
                  }
                })
            });
          }
          return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: {} }) });
        });

        await component.fetchBots();

        // Mutation count is STILL strictly 1: zero duplicate bot creation!
        expect(createCallCount).toBe(1);
        expect(component.botStore.error()).toBeNull();
        expect(component.botStore.bots()[0].name).toBe('Resilient Bot');
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
