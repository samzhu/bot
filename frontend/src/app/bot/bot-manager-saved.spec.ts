import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { BotManagerComponent } from './bot-manager.component';
import { BotEditorComponent } from './bot-editor.component';
import type { BotView } from './bot.model';

describe('Bot manager saved event', () => {
  afterEach(() => vi.unstubAllGlobals());
  it('emits before list refresh and retries only reads after refresh failure', async () => {
    const events: string[] = [];
    const saved: BotView = { id: 'b1', name: 'Bot', target: 'GKE_REMOTE', connectionStatus: 'NOT_CONNECTED', executionMode: 'UNAVAILABLE', canChat: false, createdAt: '', updatedAt: '' };
    vi.stubGlobal('fetch', vi.fn(async (_url: string, opts: RequestInit) => {
      const query = JSON.parse(opts.body as string).query as string;
      events.push(query.includes('mutation') ? 'mutation' : 'read');
      if (events.filter(e => e === 'read').length === 2) throw new Error('offline');
      return { ok: true, json: async () => ({ data: { bots: [], defaultLocalBot: null } }) };
    }));
    await TestBed.configureTestingModule({ imports: [BotManagerComponent] }).compileComponents();
    const fixture = TestBed.createComponent(BotManagerComponent);
    fixture.componentInstance.saved.subscribe(value => { expect(value).toEqual(saved); events.push('saved'); });
    fixture.detectChanges();
    await new Promise(resolve => setTimeout(resolve, 0));
    fixture.debugElement.query(By.directive(BotEditorComponent)).triggerEventHandler('saved', saved);
    await new Promise(resolve => setTimeout(resolve, 0)); fixture.detectChanges();
    expect(events).toEqual(['read', 'saved', 'read']);
    expect(fixture.nativeElement.querySelector('[data-testid="bot-store-error"]')).toBeTruthy();
    (fixture.nativeElement.querySelector('[data-testid="bot-store-error"] button') as HTMLButtonElement).click();
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(events).toEqual(['read', 'saved', 'read', 'read']);
  });
});
