import { Component, ElementRef, inject, output, signal, viewChild } from '@angular/core';
import { form, FormField, submit, required, maxLength, validate } from '@angular/forms/signals';
import { BotStore } from './bot-store';
import { BotView } from './bot.model';

export interface BotEditorFormData {
  name: string;
  description: string;
  instructions: string;
}

const CREATE_NOTICE = '建立後會儲存為獨立 Bot 定義；目前尚未連接正式 Agent 平台，可否對話以儲存後卡片上的狀態為準。';

function noticeFor(bot: BotView): string {
  switch (bot.executionMode) {
    case 'SPRING_AI_SIMULATION':
      return '此 Bot 目前以本機模擬執行，尚未連接正式 Agent 平台；工作指示會在模擬對話中生效。';
    case 'UNAVAILABLE':
      return '此 Bot 目前未連線且無法對話；仍可儲存名稱、用途說明與工作指示。';
    default:
      return '儲存後會更新此 Bot 定義；目前尚未連接正式 Agent 平台。';
  }
}

const EMPTY_FORM: BotEditorFormData = { name: '', description: '', instructions: '' };

/**
 * 右側貼邊的 Bot 新增／設定面板。
 * 為何沿用原生 <dialog>.showModal()：焦點陷阱、Escape、inert 背景由瀏覽器提供；
 * 靠右為應用層 CSS 佈局；ThreadPlane 的確認對話框不提供可投影的表單面板。
 */
@Component({
  selector: 'app-bot-editor',
  standalone: true,
  imports: [FormField],
  templateUrl: './bot-editor.component.html',
  styleUrl: './bot-editor.component.css'
})
export class BotEditorComponent {
  private readonly botStore = inject(BotStore);

  /** 保存成功（已關閉面板）後送出，供 manager 刷新清單；保存失敗不送出。 */
  readonly saved = output<BotView>();

  readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('editorDialog');

  readonly mode = signal<'create' | 'edit'>('create');
  readonly isOpen = signal(false);
  readonly isSubmitting = signal(false);
  readonly formError = signal<string | null>(null);
  /** 說明文字：建立前不知道 mode，只說明保存與未連接；編輯時依該 Bot 真實 executionMode 顯示。 */
  readonly notice = signal<string>(CREATE_NOTICE);

  private editingBotId: string | null = null;
  private trigger: HTMLElement | null = null;

  readonly botModel = signal<BotEditorFormData>({ ...EMPTY_FORM });

  readonly botForm = form(this.botModel, b => {
    required(b.name, { message: '名稱為必填項目且不可為空白' });
    // 以去除前後空白後的長度檢查 1..100；輸入中允許前後空白，保存時才 trim。
    validate(b.name, ({ value }) => {
      const trimmed = (value() || '').trim();
      if (!trimmed) {
        return { kind: 'required', message: '名稱為必填項目且不可為空白' };
      }
      if (trimmed.length > 100) {
        return { kind: 'maxLength', message: '名稱長度不可超過 100 字元' };
      }
      return null;
    });
    // 用途與指示以原始字串計長，不 trim，保留縮排與換行。
    maxLength(b.description, 2000, { message: '用途說明長度不可超過 2,000 字元' });
    maxLength(b.instructions, 10000, { message: '工作指示長度不可超過 10,000 字元' });
  });

  openCreate(trigger: HTMLElement): void {
    this.open('create', null, { ...EMPTY_FORM }, trigger, null);
  }

  openEdit(bot: BotView, trigger: HTMLElement): void {
    if (bot.target === 'LOCAL') {
      return; // LOCAL 預設助理唯讀，不可編輯
    }
    this.open(
      'edit',
      bot.id,
      {
        name: bot.name,
        description: bot.description ?? '',
        instructions: bot.instructions ?? ''
      },
      trigger,
      bot
    );
  }

  /** 取消／X：無 mutation。保存進行中不允許關閉（按鈕在模板同步 disabled），否則保存結果與焦點返回會失去對應的面板。 */
  close(): void {
    if (this.isSubmitting()) {
      return;
    }
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog?.open && typeof dialog.close === 'function') {
      dialog.close();
    }
    this.finishClose();
  }

  /** 原生 cancel（Escape）：保存中阻擋，否則交給瀏覽器關閉，再由 close 事件收尾。 */
  onDialogCancel(event: Event): void {
    if (this.isSubmitting()) {
      event.preventDefault();
    }
  }

  /** 原生 close 事件（Escape 或 close()）統一收尾；idempotent。 */
  onDialogClose(): void {
    this.finishClose();
  }

  async save(): Promise<void> {
    if (this.isSubmitting()) {
      return;
    }
    await submit(this.botForm, async () => {
      this.isSubmitting.set(true);
      this.formError.set(null);

      const value = this.botModel();
      // 名稱 trim；用途與指示保留原始空白／換行，空字串視為未填 (null)。
      const name = value.name.trim();
      const description = value.description === '' ? null : value.description;
      const instructions = value.instructions === '' ? null : value.instructions;
      const isCreate = this.mode() === 'create';

      let result: BotView;
      try {
        if (isCreate) {
          result = await this.botStore.createBot({ name, description, instructions });
        } else {
          if (!this.editingBotId) {
            throw new Error('未指定要設定的 Bot');
          }
          result = await this.botStore.updateBot({
            id: this.editingBotId,
            name,
            description,
            instructions
          });
        }
      } catch (err: any) {
        // 失敗：面板維持開啟、保留原始輸入、顯示錯誤；不送出 saved。
        this.formError.set(err?.message || (isCreate ? '建立 Bot 失敗，請稍後再試' : '儲存 Bot 失敗，請稍後再試'));
        this.isSubmitting.set(false);
        return;
      }

      // 保存已成功：先清空並關閉，避免後續清單刷新失敗時使用者能重送而重複建立。
      this.isSubmitting.set(false);
      this.close();
      this.saved.emit(result);
    });
  }

  private open(
    mode: 'create' | 'edit',
    botId: string | null,
    model: BotEditorFormData,
    trigger: HTMLElement,
    bot: BotView | null
  ): void {
    this.trigger = trigger;
    this.mode.set(mode);
    this.notice.set(bot ? noticeFor(bot) : CREATE_NOTICE);
    this.editingBotId = botId;
    this.formError.set(null);
    this.isSubmitting.set(false);
    this.botModel.set(model);
    this.botForm().reset();

    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && !dialog.open) {
      this.isOpen.set(true);
      if (typeof dialog.showModal === 'function') {
        dialog.showModal();
      }
    }
  }

  private finishClose(): void {
    if (!this.isOpen()) {
      return;
    }
    this.isOpen.set(false);
    this.botModel.set({ ...EMPTY_FORM });
    this.botForm().reset();
    this.formError.set(null);
    this.editingBotId = null;
    const trigger = this.trigger;
    this.trigger = null;
    if (trigger && typeof trigger.focus === 'function') {
      trigger.focus();
    }
  }
}
