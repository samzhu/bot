import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ChatNavigationComponent } from './chat-navigation.component';
import { BotStore } from './bot/bot-store';
import { ConversationListStore } from './conversation-list-store';

const bot = (id: string) => ({ id, name: `Bot ${id}`, target: 'GKE_REMOTE', connectionStatus: 'NOT_CONNECTED', executionMode: 'UNAVAILABLE', canChat: false, createdAt: '', updatedAt: '' });
const response = (bots: unknown[]) => ({ ok: true, json: async () => ({ data: { defaultLocalBot: { ...bot('default'), target: 'LOCAL' }, bots } }) });

describe('ChatNavigationComponent', () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  beforeEach(async () => {
    fetchMock = vi.fn(async (_url: string, options: RequestInit) => {
      const body = JSON.parse(options.body as string);
      if (body.query.includes('GetBots')) {
        const { offset, limit } = body.variables;
        return response(Array.from({ length: Math.max(0, Math.min(limit, 25 - offset)) }, (_, i) => bot(String(offset + i))));
      }
      return { ok: true, json: async () => ({ data: { conversations: [] } }) };
    });
    vi.stubGlobal('fetch', fetchMock);
    await TestBed.configureTestingModule({ imports: [ChatNavigationComponent] }).compileComponents();
  });
  afterEach(() => vi.unstubAllGlobals());
  const settle = async (fixture: ReturnType<typeof TestBed.createComponent<ChatNavigationComponent>>) => {
    for (let i = 0; i < 5; i++) { await new Promise(resolve => setTimeout(resolve, 0)); fixture.detectChanges(); }
  };

  it('loads scoped remote bots, emits selection while keeping Bot mode, and refreshes the loaded range', async () => {
    const fixture = TestBed.createComponent(ChatNavigationComponent);
    const component = fixture.componentInstance;
    const parentStore = TestBed.inject(BotStore);
    const conversationStore = TestBed.inject(ConversationListStore);
    conversationStore.activeConversation.set({ id: 'deep', botId: 'b', title: 'Deep', pinned: false, createdAt: '', updatedAt: '' });
    fixture.componentRef.setInput('activeConversationId', 'deep');
    const modes: string[] = []; const selected: string[] = [];
    component.modeSelected.subscribe(value => modes.push(value));
    component.botSelected.subscribe(value => selected.push(value.id));
    fixture.detectChanges(); await settle(fixture);
    expect(fetchMock).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Deep');
    (fixture.nativeElement.querySelector('[data-testid="nav-bots"]') as HTMLButtonElement).click();
    fixture.detectChanges(); await settle(fixture);
    expect(modes).toEqual(['bots']);
    expect(parentStore.bots()).toEqual([]);
    expect(fixture.nativeElement.textContent).not.toContain('Bot default');
    expect(fixture.nativeElement.textContent).toContain('未連線 (停用)');
    (fixture.nativeElement.querySelector('[data-testid="bot-item-0"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(selected).toEqual(['0']);
    expect(fixture.nativeElement.querySelector('[data-testid="nav-bots"]').getAttribute('aria-pressed')).toBe('true');
    (fixture.nativeElement.querySelector('[data-testid="bots-more"]') as HTMLButtonElement).click();
    await settle(fixture);
    expect(fixture.nativeElement.textContent).toContain('Bot 24');
    component.refreshBots(); await settle(fixture);
    expect(fixture.nativeElement.textContent).toContain('Bot 24');
    const calls = fetchMock.mock.calls.map(c => JSON.parse((c[1] as RequestInit).body as string)).filter(b => b.query.includes('GetBots'));
    expect(calls.every(c => c.variables.target === 'GKE_REMOTE')).toBe(true);
    expect(calls.map(c => c.variables.offset)).toEqual([0, 0, 20, 0, 20]);
  });

  it('shows a retryable read error and preserves conversation selection while modes change', async () => {
    let fail = true;
    fetchMock.mockImplementation(async (_url: string, options: RequestInit) => {
      const body = JSON.parse(options.body as string);
      if (body.query.includes('GetBots')) { if (fail) { fail = false; throw Error('offline'); } return response([bot('one')]); }
      return { ok: true, json: async () => ({ data: { conversations: body.variables.pinned ? [] : [{ id: 'c1', botId: 'b', title: 'Conversation', pinned: false, createdAt: '', updatedAt: '' }] } }) };
    });
    const fixture = TestBed.createComponent(ChatNavigationComponent); const selected: string[] = [];
    fixture.componentInstance.conversationSelected.subscribe(value => selected.push(value));
    fixture.detectChanges(); await TestBed.inject(ConversationListStore).loadConversations(); await settle(fixture);
    (fixture.nativeElement.querySelector('[data-testid="nav-bots"]') as HTMLButtonElement).click(); await settle(fixture);
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeTruthy();
    (fixture.nativeElement.querySelector('[data-testid="bots-retry"]') as HTMLButtonElement).click(); await settle(fixture);
    expect(fixture.nativeElement.textContent).toContain('Bot one');
    (fixture.nativeElement.querySelector('[data-testid="nav-conversations"]') as HTMLButtonElement).click(); fixture.detectChanges();
    const button = Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>).find(b => b.textContent?.includes('Conversation'));
    expect(button).toBeTruthy(); button!.click();
    expect(selected).toEqual(['c1']);
    expect(fetchMock.mock.calls.every(c => !JSON.parse((c[1] as RequestInit).body as string).query.includes('mutation'))).toBe(true);
  });

  it('uses the native thread menu to pin and reconciles both conversation ranges', async () => {
    let pinned = false;
    let mutations = 0;
    fetchMock.mockImplementation(async (_url: string, options: RequestInit) => {
      const body = JSON.parse(options.body as string);
      const item = { id: 'c1', botId: 'b', title: 'Conversation', pinned, createdAt: '', updatedAt: '' };
      if (body.query.includes('mutation')) {
        mutations++; pinned = body.variables.pinned;
        return { ok: true, json: async () => ({ data: { setConversationPinned: { ...item, pinned } } }) };
      }
      return { ok: true, json: async () => ({ data: { conversations: body.variables.offset === 0 && body.variables.pinned === pinned ? [item] : [] } }) };
    });
    const fixture = TestBed.createComponent(ChatNavigationComponent);
    fixture.detectChanges(); await TestBed.inject(ConversationListStore).loadConversations(); await settle(fixture);
    (fixture.nativeElement.querySelector('[aria-label="More actions"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const pin = Array.from(fixture.nativeElement.querySelectorAll('[role="menuitem"]') as NodeListOf<HTMLElement>).find(item => item.textContent?.trim() === 'Pin');
    expect(pin).toBeTruthy(); pin!.click(); await settle(fixture);
    expect(mutations).toBe(1);
    const store = TestBed.inject(ConversationListStore);
    expect(store.pinnedConversations().map(item => item.id)).toEqual(['c1']);
    expect(store.unpinnedConversations()).toEqual([]);
  });

  it('ignores an older Bot response after a newer refresh', async () => {
    let resolveOld!: (value: unknown) => void;
    let count = 0;
    fetchMock.mockImplementation(async (_url: string, options: RequestInit) => {
      const body = JSON.parse(options.body as string);
      if (body.query.includes('GetBots')) {
        if (++count === 1) return new Promise(resolve => { resolveOld = resolve; });
        return response([bot('new')]);
      }
      return { ok: true, json: async () => ({ data: { conversations: [] } }) };
    });
    const fixture = TestBed.createComponent(ChatNavigationComponent);
    fixture.detectChanges(); await settle(fixture);
    (fixture.nativeElement.querySelector('[data-testid="nav-bots"]') as HTMLButtonElement).click();
    await settle(fixture);
    fixture.componentInstance.refreshBots(); await settle(fixture);
    resolveOld(response([bot('old')])); await settle(fixture);
    expect(fixture.nativeElement.textContent).toContain('Bot new');
    expect(fixture.nativeElement.textContent).not.toContain('Bot old');
  });

  it('retains each mode’s loaded range and collapsed state when switching', async () => {
    const fixture = TestBed.createComponent(ChatNavigationComponent);
    fixture.detectChanges();
    const pinnedHeading = fixture.nativeElement.querySelector('[aria-label="釘選對話"] h2 button') as HTMLButtonElement;
    pinnedHeading.click(); fixture.detectChanges();
    expect(pinnedHeading.getAttribute('aria-expanded')).toBe('false');
    (fixture.nativeElement.querySelector('[data-testid="nav-bots"]') as HTMLButtonElement).click(); await settle(fixture);
    (fixture.nativeElement.querySelector('[data-testid="bots-more"]') as HTMLButtonElement).click(); await settle(fixture);
    const botReads = fetchMock.mock.calls.length;
    (fixture.nativeElement.querySelector('[data-testid="nav-conversations"]') as HTMLButtonElement).click(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[aria-label="釘選對話"] h2 button').getAttribute('aria-expanded')).toBe('false');
    (fixture.nativeElement.querySelector('[data-testid="nav-bots"]') as HTMLButtonElement).click(); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Bot 24');
    expect(fetchMock).toHaveBeenCalledTimes(botReads);
  });

  it.each([true, false])('retries only the failed next page for pinned=%s without losing loaded items', async pinned => {
    const attempts: { pinned: boolean; offset: number; query: string }[] = [];
    fetchMock.mockImplementation(async (_url: string, options: RequestInit) => {
      const body = JSON.parse(options.body as string);
      attempts.push({ ...body.variables, query: body.query });
      if (attempts.length === 1) throw new Error('offline');
      return { ok: true, json: async () => ({ data: { conversations: [{ id: 'next', botId: 'b', title: 'Next', pinned, createdAt: '', updatedAt: '' }] } }) };
    });
    const store = TestBed.inject(ConversationListStore);
    const existing = { id: 'first', botId: 'b', title: 'First', pinned, createdAt: '', updatedAt: '' };
    (pinned ? store.pinnedConversations : store.unpinnedConversations).set([existing]);
    (pinned ? store.pinnedOffset : store.unpinnedOffset).set(1);
    (pinned ? store.hasMorePinned : store.hasMoreUnpinned).set(true);
    const fixture = TestBed.createComponent(ChatNavigationComponent);
    fixture.detectChanges();
    const section = fixture.nativeElement.querySelector(pinned ? '[aria-label="釘選對話"]' : '[aria-label="近期對話"]') as HTMLElement;
    const more = Array.from(section.querySelectorAll('button')).find(b => b.textContent?.includes('載入更多'))!;
    more.click(); await settle(fixture);
    expect(section.textContent).toContain('載入失敗');
    expect((pinned ? store.pinnedOffset : store.unpinnedOffset)()).toBe(1);
    expect((pinned ? store.pinnedConversations : store.unpinnedConversations)().map(c => c.id)).toEqual(['first']);
    (section.querySelector('[data-testid="page-retry"]') as HTMLButtonElement).click(); await settle(fixture);
    expect(section.textContent).not.toContain('載入失敗');
    expect((pinned ? store.pinnedConversations : store.unpinnedConversations)().map(c => c.id)).toEqual(['first', 'next']);
    expect(attempts.map(a => [a.pinned, a.offset])).toEqual([[pinned, 1], [pinned, 1]]);
    expect(attempts.every(a => !a.query.includes('mutation'))).toBe(true);
  });

  it('uses the native search palette and opens the selected authoritative conversation', async () => {
    fetchMock.mockImplementation(async (_url: string, options: RequestInit) => {
      const body = JSON.parse(options.body as string);
      if (body.query.includes('SearchConversations')) return { ok: true, json: async () => ({ data: { conversations: [{ id: 'remote', title: 'Remote match' }] } }) };
      return { ok: true, json: async () => ({ data: { conversations: [] } }) };
    });
    const fixture = TestBed.createComponent(ChatNavigationComponent); const selected: string[] = [];
    fixture.componentInstance.conversationSelected.subscribe(id => selected.push(id));
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('[aria-label="Search conversations"]') as HTMLButtonElement).click(); fixture.detectChanges();
    const input = fixture.nativeElement.querySelector('[role="combobox"]') as HTMLInputElement;
    expect(input.placeholder).toContain('最多20筆');
    input.value = '   '; input.dispatchEvent(new Event('input')); fixture.detectChanges();
    await new Promise(resolve => setTimeout(resolve, 0)); fixture.detectChanges();
    expect(input.value).toBe('');
    expect(fixture.nativeElement.textContent).toContain('Type to search your conversations');
    input.value = 'remote'; input.dispatchEvent(new Event('input')); fixture.detectChanges();
    await new Promise(resolve => setTimeout(resolve, 300)); await settle(fixture);
    const option = fixture.nativeElement.querySelector('[role="option"]') as HTMLElement;
    expect(option.textContent).toContain('Remote match'); option.click(); fixture.detectChanges();
    expect(selected).toEqual(['remote']);
    expect(fixture.nativeElement.querySelector('[role="combobox"]')).toBeNull();
  });
  it('opens the native drawer from a trigger and closes with Escape or backdrop', async () => {
    const trigger = document.createElement('button'); document.body.appendChild(trigger); trigger.focus();
    const fixture = TestBed.createComponent(ChatNavigationComponent); const component = fixture.componentInstance;
    component.setLayoutMode('drawer'); fixture.detectChanges();
    const drawer = fixture.nativeElement.querySelector('.drawer-focus') as HTMLElement;
    expect(drawer.hasAttribute('inert')).toBe(true);
    component.openNavigation(); fixture.detectChanges(); await new Promise(resolve => setTimeout(resolve, 0));
    expect(drawer.hasAttribute('inert')).toBe(false);
    // Initial focus is on the drawer host, outside the SDK's Escape listener.
    drawer.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); fixture.detectChanges();
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(drawer.hasAttribute('inert')).toBe(true); expect(document.activeElement).toBe(trigger);
    component.openNavigation(); fixture.detectChanges();
    const scrim = fixture.nativeElement.querySelector('chat-sidenav-scrim button') as HTMLButtonElement;
    scrim.click(); fixture.detectChanges(); expect(drawer.hasAttribute('inert')).toBe(true);
    trigger.remove();
  });
  it('closes the palette on failed search and retries the retained query before reopening', async () => {
    let searches = 0;
    fetchMock.mockImplementation(async (_url: string, options: RequestInit) => {
      const body = JSON.parse(options.body as string);
      if (body.query.includes('SearchConversations')) {
        searches++;
        if (searches === 1) throw new Error('offline');
        return { ok: true, json: async () => ({ data: { conversations: [] } }) };
      }
      return { ok: true, json: async () => ({ data: { conversations: [] } }) };
    });
    const fixture = TestBed.createComponent(ChatNavigationComponent); fixture.detectChanges();
    (fixture.nativeElement.querySelector('[aria-label="Search conversations"]') as HTMLButtonElement).click(); fixture.detectChanges();
    const input = fixture.nativeElement.querySelector('[role="combobox"]') as HTMLInputElement;
    input.value = 'query'; input.dispatchEvent(new Event('input')); fixture.detectChanges();
    await new Promise(resolve => setTimeout(resolve, 300)); await settle(fixture);
    expect(fixture.nativeElement.querySelector('[role="combobox"]')).toBeNull();
    const alert = fixture.nativeElement.querySelector('.search-alert[role="alert"]') as HTMLElement;
    expect(alert.textContent).toContain('搜尋對話失敗');
    (alert.querySelector('button') as HTMLButtonElement).click(); await settle(fixture);
    expect(searches).toBe(2);
    expect((fixture.nativeElement.querySelector('[role="combobox"]') as HTMLInputElement).value).toBe('query');
    expect(fixture.nativeElement.textContent).toContain('No conversations match');
  });
  it('keeps Tab within the palette dialog and handles Escape on its close button', async () => {
    const trigger = document.createElement('button'); document.body.appendChild(trigger); trigger.focus();
    const fixture = TestBed.createComponent(ChatNavigationComponent); document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    const button = fixture.nativeElement.querySelector('[aria-label="Search conversations"]') as HTMLButtonElement;
    button.focus(); button.click(); fixture.detectChanges(); await Promise.resolve();
    const dialog = fixture.nativeElement.querySelector('[role="dialog"]') as HTMLElement;
    const input = dialog.querySelector('input')!; const close = dialog.querySelector('[aria-label="Close"]') as HTMLButtonElement;
    close.focus(); const forward = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }); close.dispatchEvent(forward);
    expect(forward.defaultPrevented).toBe(true); expect(document.activeElement).toBe(input);
    const backward = new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true }); input.dispatchEvent(backward);
    expect(backward.defaultPrevented).toBe(true); expect(document.activeElement).toBe(close);
    close.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })); fixture.detectChanges();
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(fixture.nativeElement.querySelector('[role="dialog"]')).toBeNull(); expect(document.activeElement).toBe(button);
    fixture.nativeElement.remove(); trigger.remove();
  });
  it('edits an overlong query outside the closed palette and only reopens after a successful retry', async () => {
    const fixture = TestBed.createComponent(ChatNavigationComponent); fixture.detectChanges();
    (fixture.nativeElement.querySelector('[aria-label="Search conversations"]') as HTMLButtonElement).click(); fixture.detectChanges();
    const input = fixture.nativeElement.querySelector('[role="combobox"]') as HTMLInputElement;
    input.value = 'x'.repeat(101); input.dispatchEvent(new Event('input')); fixture.detectChanges();
    const editor = fixture.nativeElement.querySelector('.search-alert input') as HTMLInputElement;
    expect(editor.value).toBe('x'.repeat(101));
    expect(fixture.nativeElement.querySelector('[role="combobox"]')).toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('No conversations match');
    expect(fetchMock).not.toHaveBeenCalled();
    editor.value = 'valid'; editor.dispatchEvent(new Event('input')); fixture.detectChanges();
    (fixture.nativeElement.querySelector('.search-alert button') as HTMLButtonElement).click();
    await settle(fixture);
    expect((fixture.nativeElement.querySelector('[role="combobox"]') as HTMLInputElement).value).toBe('valid');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
  it('closes the mobile drawer before focusing an external search error', async () => {
    fetchMock.mockRejectedValue(new Error('offline'));
    const fixture = TestBed.createComponent(ChatNavigationComponent); const component = fixture.componentInstance;
    fixture.detectChanges(); component.setLayoutMode('drawer'); component.openNavigation(); fixture.detectChanges();
    (fixture.nativeElement.querySelector('[aria-label="Search conversations"]') as HTMLButtonElement).click(); fixture.detectChanges();
    const input = fixture.nativeElement.querySelector('[role="combobox"]') as HTMLInputElement;
    input.value = 'query'; input.dispatchEvent(new Event('input')); fixture.detectChanges();
    await new Promise(resolve => setTimeout(resolve, 300)); await settle(fixture);
    expect(component.navigationOpen()).toBe(false);
    expect(fixture.nativeElement.querySelector('.drawer-focus').hasAttribute('inert')).toBe(true);
    expect(fixture.nativeElement.querySelector('.search-alert button')).toBeTruthy();
  });
});
