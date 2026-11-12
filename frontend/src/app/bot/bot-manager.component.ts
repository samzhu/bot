import {
  Component,
  ElementRef,
  OnInit,
  computed,
  inject,
  output,
  signal,
  viewChild
} from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  form,
  FormField,
  submit,
  required,
  maxLength,
  validate
} from '@angular/forms/signals';
import { BotStore } from './bot-store';
import { BotTarget, BotView, getBotExecutionModeDisplay } from './bot.model';

export interface BotFormData {
  name: string;
  description: string;
  instructions: string;
}

@Component({
  selector: 'app-bot-manager',
  standalone: true,
  imports: [CommonModule, FormField],
  templateUrl: './bot-manager.component.html',
  styleUrl: './bot-manager.component.css'
})
export class BotManagerComponent implements OnInit {
  readonly botStore = inject(BotStore);

  // Emits selected bot when user requests to open draft chat
  readonly startChat = output<BotView>();

  // View references for Native Dialog and Focus Management
  readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('botDialog');
  readonly createBtnRef = viewChild<ElementRef<HTMLButtonElement>>('createBtn');

  // Search, Target Filter & Pagination States
  readonly searchQuery = signal<string>('');
  readonly selectedTarget = signal<BotTarget | 'ALL'>('ALL');
  readonly currentOffset = signal<number>(0);
  readonly pageSize = 20;

  readonly currentPage = computed(() => Math.floor(this.currentOffset() / this.pageSize) + 1);
  readonly canPrevPage = computed(() => this.currentOffset() > 0);
  readonly canNextPage = computed(() => this.botStore.hasMore());

  // Dialog & Submission States
  readonly dialogMode = signal<'create' | 'edit'>('create');
  readonly editingBotId = signal<string | null>(null);
  readonly isDialogOpen = signal<boolean>(false);
  readonly isSubmitting = signal<boolean>(false);
  readonly formError = signal<string | null>(null);

  // Official Angular Signal Forms Model & Validation
  readonly botModel = signal<BotFormData>({
    name: '',
    description: '',
    instructions: ''
  });

  readonly botForm = form(this.botModel, b => {
    required(b.name, { message: '名稱為必填項目且不可為空白' });
    // Enforces trimmed length bounds 1..100 while permitting surrounding whitespace during typing
    validate(b.name, ({ value }) => {
      const val = value() || '';
      const trimmed = val.trim();
      if (!trimmed) {
        return { kind: 'required', message: '名稱為必填項目且不可為空白' };
      }
      if (trimmed.length > 100) {
        return { kind: 'maxLength', message: '名稱長度不可超過 100 字元' };
      }
      return null;
    });

    maxLength(b.description, 2000, {
      message: '用途說明長度不可超過 2,000 字元'
    });

    maxLength(b.instructions, 10000, {
      message: '待部署工作指示長度不可超過 10,000 字元'
    });
  });

  // Track the element that triggered the dialog for accessibility focus return
  private lastFocusedTrigger: HTMLElement | null = null;

  ngOnInit(): void {
    this.fetchBots().catch(err => {
      console.warn('Initial loadBots warning:', err);
    });
  }

  getModeDisplay(bot: BotView) {
    return getBotExecutionModeDisplay(bot);
  }

  isEditable(bot: BotView): boolean {
    return bot.target === 'GKE_REMOTE';
  }

  onOpenDraft(bot: BotView): void {
    this.startChat.emit(bot);
  }

  async fetchBots(): Promise<BotView[]> {
    const search = this.searchQuery().trim() || null;
    const target = this.selectedTarget() === 'ALL' ? null : (this.selectedTarget() as BotTarget);
    return this.botStore.loadBots({
      search,
      target,
      limit: this.pageSize,
      offset: this.currentOffset()
    });
  }

  onSearchInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.searchQuery.set(input.value);
    this.currentOffset.set(0);
    this.fetchBots().catch(err => console.warn('Search fetch error:', err));
  }

  onTargetChange(event: Event): void {
    const select = event.target as HTMLSelectElement;
    this.selectedTarget.set(select.value as BotTarget | 'ALL');
    this.currentOffset.set(0);
    this.fetchBots().catch(err => console.warn('Target change fetch error:', err));
  }

  prevPage(): void {
    if (!this.canPrevPage()) return;
    this.currentOffset.update(offset => Math.max(0, offset - this.pageSize));
    this.fetchBots().catch(err => console.warn('Paging fetch error:', err));
  }

  nextPage(): void {
    if (!this.canNextPage()) return;
    this.currentOffset.update(offset => offset + this.pageSize);
    this.fetchBots().catch(err => console.warn('Paging fetch error:', err));
  }

  openCreateDialog(triggerElement?: HTMLElement): void {
    this.lastFocusedTrigger = triggerElement || this.createBtnRef()?.nativeElement || null;
    this.dialogMode.set('create');
    this.editingBotId.set(null);
    this.formError.set(null);
    this.botModel.set({ name: '', description: '', instructions: '' });
    this.botForm().reset();

    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && !dialog.open) {
      this.isDialogOpen.set(true);
      if (typeof dialog.showModal === 'function') {
        dialog.showModal();
      }
    }
  }

  openEditDialog(bot: BotView, triggerElement?: HTMLElement): void {
    if (bot.target === 'LOCAL') {
      return; // LOCAL bots are strictly read-only
    }
    this.lastFocusedTrigger = triggerElement || null;
    this.dialogMode.set('edit');
    this.editingBotId.set(bot.id);
    this.formError.set(null);
    this.botModel.set({
      name: bot.name,
      description: bot.description || '',
      instructions: bot.instructions || ''
    });
    this.botForm().reset();

    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && !dialog.open) {
      this.isDialogOpen.set(true);
      if (typeof dialog.showModal === 'function') {
        dialog.showModal();
      }
    }
  }

  closeDialog(): void {
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && dialog.open) {
      if (typeof dialog.close === 'function') {
        dialog.close();
      }
    }
    this.isDialogOpen.set(false);
    this.restoreFocusToTrigger();
  }

  // Native HTML <dialog> cancel event handler (fired on Escape key)
  onDialogCancel(event: Event): void {
    // Escape closes dialog without triggering mutation
    this.isDialogOpen.set(false);
    this.restoreFocusToTrigger();
  }

  async submitBot(): Promise<void> {
    await submit(this.botForm, async () => {
      this.isSubmitting.set(true);
      this.formError.set(null);

      const formValue = this.botModel();
      // Name is trimmed; description and instructions preserve raw strings (empty string converted to null)
      // to avoid modifying user-defined prompt indentation, formatting, or multiline structure
      const name = formValue.name.trim();
      const description = formValue.description === '' ? null : formValue.description;
      const instructions = formValue.instructions === '' ? null : formValue.instructions;

      // Phase 1: Execute mutation (createBot or updateBot).
      // If mutation fails, retain form values in botModel and display error banner in dialog.
      try {
        if (this.dialogMode() === 'create') {
          await this.botStore.createBot({ name, description, instructions });
        } else {
          const id = this.editingBotId();
          if (!id) {
            throw new Error('未指定要編輯的代理識別碼');
          }
          await this.botStore.updateBot({ id, name, description, instructions });
        }
      } catch (err: any) {
        this.formError.set(
          err.message ||
            (this.dialogMode() === 'create'
              ? '建立 GKE 代理失敗，請檢查輸入內容或稍後重試'
              : '編輯 GKE 代理失敗，請檢查輸入內容或稍後重試')
        );
        return;
      } finally {
        this.isSubmitting.set(false);
      }

      // Phase 2: Mutation succeeded on server.
      // Immediately reset model/form, close dialog, and restore focus to trigger button.
      // This guarantees the user cannot resubmit and produce duplicate bots on subsequent refetch failures.
      this.botModel.set({ name: '', description: '', instructions: '' });
      this.botForm().reset();
      this.closeDialog();

      // Phase 3: Refresh bot list with reset offset and active search/target filters.
      // If refetch fails (e.g. 500), error is set on botStore.error (showing global manager error with retry),
      // and mutation is never re-sent.
      this.currentOffset.set(0);
      try {
        await this.fetchBots();
      } catch (err: any) {
        console.warn('Post-mutation list refetch failed:', err);
      }
    });
  }

  private restoreFocusToTrigger(): void {
    if (this.lastFocusedTrigger && typeof this.lastFocusedTrigger.focus === 'function') {
      setTimeout(() => {
        this.lastFocusedTrigger?.focus();
      }, 0);
    }
  }
}
