import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ChatSearchStore } from './chat-search.store';

describe('ChatSearchStore', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [ChatSearchStore] }));
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });
  it('clears stale results while debouncing, queries the server with trimmed input and preserves intent', async () => {
    vi.useFakeTimers();
    let resolve!: (v: unknown) => void;
    const fetcher = vi.fn((_url: string, _options: RequestInit) => new Promise(r => { resolve = r; })); vi.stubGlobal('fetch', fetcher);
    const store = TestBed.inject(ChatSearchStore);
    store.openSearch(); store.changeQuery(' first ');
    expect(store.loading()).toBe(true); expect(store.results()).toEqual([]);
    await vi.advanceTimersByTimeAsync(300);
    const body = JSON.parse(fetcher.mock.calls[0][1].body as string);
    expect(body.variables).toEqual({ search: 'first', limit: 20, offset: 0 });
    store.closeSearch(); resolve({ok:true,json:async()=>({data:{conversations:[{id:'old',title:'Old'}]}})});
    await vi.runAllTimersAsync();
    expect(store.open()).toBe(false); expect(store.results()).toEqual([]);
  });
  it('closes on failure, retains query, retries read-only while closed and opens only after success', async () => {
    vi.useFakeTimers();
    const fetcher = vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({ok:true,json:async()=>({data:{conversations:[]}})});
    vi.stubGlobal('fetch', fetcher);
    const store = TestBed.inject(ChatSearchStore); store.openSearch(); store.changeQuery('test');
    await vi.advanceTimersByTimeAsync(300);
    expect(store.open()).toBe(false); expect(store.error()).toBeTruthy(); expect(store.query()).toBe('test');
    await store.retry(); expect(store.open()).toBe(true); expect(store.error()).toBeNull();
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(fetcher.mock.calls.every(call => !JSON.parse(call[1].body).query.includes('mutation'))).toBe(true);
  });
  it('normalizes blank without fetch and closes on 101 characters', () => {
    const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher);
    const store = TestBed.inject(ChatSearchStore); store.openSearch(); store.changeQuery('   ');
    expect(store.query()).toBe(''); expect(store.loading()).toBe(false); expect(fetcher).not.toHaveBeenCalled();
    store.changeQuery('x'.repeat(101)); expect(store.open()).toBe(false); expect(store.error()).toBeTruthy();
  });
  it('does not reopen or steal state when retry completes after user closes', async () => {
    vi.useFakeTimers();
    let resolve!: (value: unknown) => void;
    const fetcher = vi.fn().mockRejectedValueOnce(new Error('offline')).mockImplementationOnce(() => new Promise(r => { resolve = r; }));
    vi.stubGlobal('fetch', fetcher);
    const store = TestBed.inject(ChatSearchStore);
    store.openSearch(); store.changeQuery('same'); await vi.advanceTimersByTimeAsync(300);
    const pending = store.retry(); expect(store.open()).toBe(false);
    store.closeSearch(); resolve({ ok: true, json: async () => ({ data: { conversations: [{ id: 'stale', title: 'Stale' }] } }) });
    await pending;
    expect(store.open()).toBe(false); expect(store.results()).toEqual([]); expect(store.loading()).toBe(false);
  });
  it('discards older responses and clears the previous result before the debounce', async () => {
    vi.useFakeTimers();
    let resolve!: (value: unknown) => void;
    const fetcher = vi.fn().mockImplementationOnce(() => new Promise(r => { resolve = r; })).mockResolvedValueOnce({ ok: true, json: async () => ({ data: { conversations: [{ id: 'new', title: 'New' }] } }) });
    vi.stubGlobal('fetch', fetcher);
    const store = TestBed.inject(ChatSearchStore);
    store.openSearch(); store.changeQuery('old'); await vi.advanceTimersByTimeAsync(300);
    store.changeQuery('new'); expect(store.results()).toEqual([]); expect(store.loading()).toBe(true);
    await vi.advanceTimersByTimeAsync(300);
    resolve({ ok: true, json: async () => ({ data: { conversations: [{ id: 'old', title: 'Old' }] } }) });
    await vi.runAllTimersAsync(); expect(store.results().map(row => row.id)).toEqual(['new']);
  });
  it('invalidates a pending validation retry when the user edits the query', async () => {
    let resolve!: (value: unknown) => void;
    vi.stubGlobal('fetch', vi.fn(() => new Promise(r => { resolve = r; })));
    const store = TestBed.inject(ChatSearchStore);
    store.openSearch(); store.changeQuery('x'.repeat(101));
    store.editInvalidQuery('A'); const pending = store.retry();
    store.editInvalidQuery('B');
    resolve({ ok: true, json: async () => ({ data: { conversations: [{ id: 'a', title: 'A' }] } }) });
    await pending;
    expect(store.open()).toBe(false); expect(store.query()).toBe('B');
    expect(store.results()).toEqual([]); expect(store.loading()).toBe(false); expect(store.error()).toBeTruthy();
  });
});
