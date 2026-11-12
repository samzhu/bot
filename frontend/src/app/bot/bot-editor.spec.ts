import { describe, it, expect, beforeEach, vi } from 'vitest';
import { TestBed, ComponentFixture } from '@angular/core/testing';
import { BotStore } from './bot-store';
import { BotEditorComponent } from './bot-editor.component';
import { BotView } from './bot.model';

describe('BotEditorComponent (Issue #21 right-side panel)', () => {
  const gkeBot: BotView = {
    id: 'bot-1',
    name: '資料助理',
    description: '  line1\n  line2  ',
    instructions: '  keep\n\tindent  ',
    target: 'GKE_REMOTE',
    connectionStatus: 'NOT_CONNECTED',
    executionMode: 'SPRING_AI_SIMULATION',
    statusReason: null,
    canChat: true,
    createdAt: '2026-11-12T00:00:00Z',
    updatedAt: '2026-11-12T00:00:00Z'
  };
  const localBot: BotView = { ...gkeBot, id: 'local', target: 'LOCAL', executionMode: 'LOCAL' };

  let fixture: ComponentFixture<BotEditorComponent>;
  let component: BotEditorComponent;
  let store: { createBot: ReturnType<typeof vi.fn>; updateBot: ReturnType<typeof vi.fn> };
  let dialog: HTMLDialogElement;
  let trigger: HTMLButtonElement;

  beforeEach(() => {
    // Fake store：不發出任何網路請求，也不觸及模型。
    store = {
      createBot: vi.fn().mockResolvedValue(gkeBot),
      updateBot: vi.fn().mockResolvedValue(gkeBot)
    };
    TestBed.configureTestingModule({
      imports: [BotEditorComponent],
      providers: [{ provide: BotStore, useValue: store }]
    });
    fixture = TestBed.createComponent(BotEditorComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    dialog = fixture.nativeElement.querySelector('dialog');
    // jsdom 沒有完整 dialog 行為，補最小可觀察模擬。
    dialog.showModal = vi.fn(() => dialog.setAttribute('open', ''));
    dialog.close = vi.fn(() => {
      dialog.removeAttribute('open');
      dialog.dispatchEvent(new Event('close'));
    });
    trigger = document.createElement('button');
    document.body.appendChild(trigger);
  });

  const q = <T extends HTMLElement>(id: string) =>
    fixture.nativeElement.querySelector(`[data-testid="${id}"]`) as T;

  const type = (id: string, value: string) => {
    const el = q<HTMLInputElement>(id);
    el.value = value;
    el.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  };

  it('openCreate shows product title and empty fields in modal', () => {
    component.openCreate(trigger);
    fixture.detectChanges();
    expect(dialog.showModal).toHaveBeenCalled();
    expect(q('bot-editor-title').textContent).toContain('新增 Bot');
    expect(q<HTMLInputElement>('bot-name-input').value).toBe('');
    expect(q<HTMLInputElement>('bot-name-input').hasAttribute('autofocus')).toBe(true);
    expect(fixture.nativeElement.querySelector('label[for="bot-name-input"]')).toBeTruthy();
    expect(dialog.getAttribute('aria-labelledby')).toBe('bot-editor-title');
  });

  it('openEdit prefills raw values; LOCAL is read-only', () => {
    component.openEdit(localBot, trigger);
    expect(dialog.showModal).not.toHaveBeenCalled();

    component.openEdit(gkeBot, trigger);
    fixture.detectChanges();
    expect(q('bot-editor-title').textContent).toContain('設定 Bot');
    expect(q<HTMLInputElement>('bot-name-input').value).toBe('資料助理');
    expect(q<HTMLTextAreaElement>('bot-desc-input').value).toBe(gkeBot.description);
    expect(q<HTMLTextAreaElement>('bot-instructions-input').value).toBe(gkeBot.instructions);
  });

  it('cancel / X close without mutation and return focus to trigger', () => {
    for (const id of ['cancel-dialog-btn', 'dialog-close-x-btn']) {
      component.openCreate(trigger);
      fixture.detectChanges();
      q(id).click();
      expect(component.isOpen()).toBe(false);
      expect(document.activeElement).toBe(trigger);
    }
    expect(store.createBot).not.toHaveBeenCalled();
    expect(store.updateBot).not.toHaveBeenCalled();
  });

  it('Escape (native cancel then close) returns focus without mutation; backdrop click does not close', () => {
    component.openCreate(trigger);
    fixture.detectChanges();
    dialog.click(); // 點到 ::backdrop 時 target 為 dialog 本身
    expect(component.isOpen()).toBe(true);

    const cancel = new Event('cancel', { cancelable: true });
    dialog.dispatchEvent(cancel);
    expect(cancel.defaultPrevented).toBe(false);
    dialog.dispatchEvent(new Event('close'));
    expect(component.isOpen()).toBe(false);
    expect(document.activeElement).toBe(trigger);
    expect(store.createBot).not.toHaveBeenCalled();
  });

  it('rejects whitespace-only and >100 trimmed names; accepts 100 chars with surrounding spaces', async () => {
    component.openCreate(trigger);
    fixture.detectChanges();

    type('bot-name-input', '   ');
    expect(component.botForm.name().invalid()).toBe(true);
    type('bot-name-input', 'a'.repeat(101));
    expect(component.botForm.name().invalid()).toBe(true);

    type('bot-name-input', `  ${'a'.repeat(100)}  `);
    expect(component.botForm.name().invalid()).toBe(false);
    await component.save();
    expect(store.createBot).toHaveBeenCalledWith({
      name: 'a'.repeat(100),
      description: null,
      instructions: null
    });
  });

  it('enforces raw description <=2000 and instructions <=10000', () => {
    component.openCreate(trigger);
    fixture.detectChanges();
    type('bot-name-input', 'ok');
    type('bot-desc-input', 'd'.repeat(2000));
    type('bot-instructions-input', 'i'.repeat(10000));
    expect(component.botForm().invalid()).toBe(false);
    type('bot-desc-input', 'd'.repeat(2001));
    expect(component.botForm.description().invalid()).toBe(true);
    type('bot-desc-input', 'd');
    type('bot-instructions-input', 'i'.repeat(10001));
    expect(component.botForm.instructions().invalid()).toBe(true);
  });

  it('create preserves raw whitespace/newlines of description and instructions, closes, then emits saved', async () => {
    const order: string[] = [];
    const savedSpy = vi.fn(() => order.push(`saved:focus=${document.activeElement === trigger}`));
    component.saved.subscribe(savedSpy);
    component.openCreate(trigger);
    fixture.detectChanges();
    type('bot-name-input', ' New ');
    type('bot-desc-input', ' a\n  b ');
    type('bot-instructions-input', '\n  x\n');
    await component.save();
    expect(store.createBot).toHaveBeenCalledWith({
      name: 'New',
      description: ' a\n  b ',
      instructions: '\n  x\n'
    });
    expect(component.isOpen()).toBe(false);
    expect(savedSpy).toHaveBeenCalledWith(gkeBot);
    expect(order).toEqual(['saved:focus=true']); // 先關閉並返回焦點，再 emit
  });

  it('edit calls updateBot with id and emits saved', async () => {
    const savedSpy = vi.fn();
    component.saved.subscribe(savedSpy);
    component.openEdit(gkeBot, trigger);
    fixture.detectChanges();
    type('bot-name-input', '新名稱');
    await component.save();
    expect(store.updateBot).toHaveBeenCalledWith({
      id: 'bot-1',
      name: '新名稱',
      description: gkeBot.description,
      instructions: gkeBot.instructions
    });
    expect(store.createBot).not.toHaveBeenCalled();
    expect(savedSpy).toHaveBeenCalledTimes(1);
  });

  it('failure keeps panel open with raw input and error, emits nothing; retry allowed', async () => {
    store.createBot.mockRejectedValueOnce(new Error('伺服器拒絕'));
    const savedSpy = vi.fn();
    component.saved.subscribe(savedSpy);
    component.openCreate(trigger);
    fixture.detectChanges();
    type('bot-name-input', ' keep ');
    type('bot-desc-input', ' raw\n');
    await component.save();
    fixture.detectChanges();

    expect(component.isOpen()).toBe(true);
    expect(savedSpy).not.toHaveBeenCalled();
    expect(q('form-error-banner').textContent).toContain('伺服器拒絕');
    expect(q<HTMLInputElement>('bot-name-input').value).toBe(' keep ');
    expect(q<HTMLTextAreaElement>('bot-desc-input').value).toBe(' raw\n');
    expect(q<HTMLButtonElement>('submit-bot-btn').disabled).toBe(false);

    await component.save();
    expect(store.createBot).toHaveBeenCalledTimes(2);
    expect(savedSpy).toHaveBeenCalledTimes(1);
  });

  it('blocks double submit and close while saving', async () => {
    let resolve!: (b: BotView) => void;
    store.createBot.mockReturnValueOnce(new Promise<BotView>(r => (resolve = r)));
    component.openCreate(trigger);
    fixture.detectChanges();
    type('bot-name-input', 'x');
    const first = component.save();
    await Promise.resolve();
    await component.save();
    fixture.detectChanges();
    expect(q<HTMLButtonElement>('cancel-dialog-btn').disabled).toBe(true);
    expect(q<HTMLButtonElement>('dialog-close-x-btn').disabled).toBe(true);
    expect(q<HTMLButtonElement>('submit-bot-btn').disabled).toBe(true);
    component.close();
    expect(component.isOpen()).toBe(true);
    const cancel = new Event('cancel', { cancelable: true });
    dialog.dispatchEvent(cancel);
    expect(cancel.defaultPrevented).toBe(true);
    resolve(gkeBot);
    await first;
    expect(store.createBot).toHaveBeenCalledTimes(1);
    expect(component.isOpen()).toBe(false);
    fixture.detectChanges();
    expect(q<HTMLButtonElement>('cancel-dialog-btn').disabled).toBe(false);
  });

  it('notice never invents execution mode: create is neutral, edit follows the bot mode', () => {
    component.openCreate(trigger);
    fixture.detectChanges();
    const createText = q('bot-editor-notice').textContent!;
    expect(createText).not.toContain('本機模擬執行');
    expect(createText).toContain('尚未連接正式 Agent 平台');
    component.close();

    component.openEdit(gkeBot, trigger);
    fixture.detectChanges();
    expect(q('bot-editor-notice').textContent).toContain('本機模擬執行');
    component.close();

    const off: BotView = { ...gkeBot, executionMode: 'UNAVAILABLE', canChat: false };
    component.openEdit(off, trigger);
    fixture.detectChanges();
    const offText = q('bot-editor-notice').textContent!;
    expect(offText).toContain('無法對話');
    expect(offText).not.toContain('模擬');
    expect(offText).not.toContain('執行，');
  });

  it('reopening after cancel does not leak previous input or error', () => {
    component.openCreate(trigger);
    fixture.detectChanges();
    type('bot-name-input', 'leftover');
    component.close();
    component.openCreate(trigger);
    fixture.detectChanges();
    expect(q<HTMLInputElement>('bot-name-input').value).toBe('');
    expect(fixture.nativeElement.querySelector('[data-testid="form-error-banner"]')).toBeNull();
  });
});
