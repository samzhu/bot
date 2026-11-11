import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { appConfig } from './app.config';
import { App } from './app';

describe('App', () => {
  beforeEach(async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({
          data: {
            bots: [{ id: 'bot-1', name: 'General Assistant', createdAt: '2026-01-01' }],
            conversations: []
          }
        })
      });
    }));

    if (typeof globalThis.ResizeObserver === 'undefined') {
      globalThis.ResizeObserver = class {
        observe() {}
        unobserve() {}
        disconnect() {}
      } as any;
    }

    if (typeof Element !== 'undefined' && !Element.prototype.scrollTo) {
      Element.prototype.scrollTo = () => {};
    }

    if (typeof window !== 'undefined' && (!window.localStorage || typeof window.localStorage.getItem !== 'function')) {
      const store: Record<string, string> = {};
      Object.defineProperty(window, 'localStorage', {
        value: {
          getItem: (key: string) => store[key] || null,
          setItem: (key: string, val: string) => { store[key] = val; },
          removeItem: (key: string) => { delete store[key]; },
          clear: () => {}
        },
        configurable: true,
        writable: true
      });
    }

    await TestBed.configureTestingModule({
      imports: [App],
      providers: [appConfig.providers]
    }).compileComponents();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it('should render workspace title', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.workspace-title')?.textContent).toContain('Herdr Bot');
  });

  it('should bind raw messages and status prompts without altering message content', async () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    await fixture.whenStable();

    app.messages.set([
      { id: 'u1', role: 'user', content: 'hello' },
      { id: 'a1', role: 'assistant', content: '**bold response**', interrupted: true }
    ]);
    fixture.detectChanges();

    // Verify messages signal retains raw content unchanged
    expect(app.messages()[1].content).toBe('**bold response**');
    expect(app.lastMessageInterrupted()).toBe(true);
    expect(app.lastMessageFailed()).toBe(false);

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('copilot-chat-view')).toBeTruthy();
    expect(compiled.querySelector('.recovering-banner')?.textContent).toContain('前次回應已中斷');
  });
});
