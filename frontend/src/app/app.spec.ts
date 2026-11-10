import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
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

    if (!navigator.clipboard) {
      Object.defineProperty(navigator, 'clipboard', {
        value: {
          writeText: vi.fn().mockResolvedValue(undefined)
        },
        configurable: true,
        writable: true
      });
    } else {
      vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue(undefined);
    }

    await TestBed.configureTestingModule({
      imports: [App],
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

  it('should copy message content to clipboard and set copied state', async () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    await fixture.whenStable();

    const writeSpy = vi.spyOn(navigator.clipboard, 'writeText');
    const testContent = 'Hello world to be copied';
    const testMsgId = 'msg-123';

    app.copyMessage(testContent, testMsgId);

    expect(writeSpy).toHaveBeenCalledWith(testContent);
    expect(app.copiedMessageId()).toBe(testMsgId);
  });
});
