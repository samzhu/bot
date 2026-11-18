import { ChangeDetectorRef, Component, ElementRef, computed, effect, inject, input, output, signal, untracked, viewChild } from '@angular/core';
import { BudgetDenialReason } from './chat-session-registry';
import { Agent, AgentError, ChatErrorComponent, ChatInputComponent, ChatMessageComponent, ChatMessageListComponent, ChatStreamingMdComponent, ChatTypingIndicatorComponent, ChatWindowComponent, MessageTemplateDirective, markdownDocument, messageContent } from '@threadplane/chat';

/** Uses public primitives so the application owns submission and authoritative cancellation. */
@Component({
  selector: 'app-chat-surface', standalone: true,
  imports: [ChatWindowComponent, ChatErrorComponent, ChatInputComponent, ChatMessageComponent, ChatMessageListComponent, ChatStreamingMdComponent, ChatTypingIndicatorComponent, MessageTemplateDirective],
  template: `
    <chat-window>
    <div chatHeader><ng-content select="[chatHeader]" /></div>
    <div chatBody #transcript class="transcript" role="region" aria-label="Conversation" tabindex="0" (scroll)="onScroll()">
      <chat-message-list [agent]="agent()">
        <ng-template chatMessageTemplate="human" let-message>
          <chat-message role="user">{{content(message)}}</chat-message>
        </ng-template>
        <ng-template chatMessageTemplate="ai" let-message>
          <chat-message role="assistant" [message]="message" [streaming]="message.delivery.phase === 'streaming'">
            <chat-streaming-md [document]="markdown(content(message), message.delivery)" />
            @if (message.delivery.phase === 'complete' && message.delivery.outcome === 'error') { <span role="status">回覆失敗</span> }
            @else if (message.delivery.phase === 'complete' && message.delivery.outcome === 'aborted') { <span role="status">回覆已停止</span> }
            @else if (message.delivery.phase === 'complete' && message.delivery.outcome === 'interrupted') { <span role="status">回覆已中斷</span> }
            @if (content(message).trim()) {
              <button chatMessageControls type="button" aria-label="複製回覆" (click)="copy(content(message))">複製</button>
            }
          </chat-message>
        </ng-template>
      </chat-message-list>
      <chat-typing-indicator [agent]="agent()" />
    </div>
    <div chatFooter>
    <chat-error [agent]="errorAgent()" />
    @if (runCancelled()) { <p role="status">回覆已停止</p> }
    @if (copyNotice()) { <p role="status">{{copyNotice()}}</p> }
    <chat-input [agent]="composerAgent()" [submitOnEnter]="submitOnEnter()" (input)="onInput($event)" />
    </div>
    </chat-window>
  `,
  styles: [':host{display:flex;flex-direction:column;min-height:0;flex:1}[chatHeader]{min-width:0;flex:1}.transcript{flex:1;min-height:0;overflow:auto}']
})
export class ChatSurfaceComponent {
  private readonly changeDetector = inject(ChangeDetectorRef);
  readonly agent = input.required<Agent>();
  readonly draft = input('');
  readonly visible = input(true);
  readonly inputBlocked = input(false);
  readonly runUnknown = input(false);
  readonly runFailed = input(false);
  readonly runDenialReason = input<BudgetDenialReason | undefined>(undefined);
  readonly runCancelled = input(false);
  readonly submitOnEnter = input(true);
  readonly draftChanged = output<string>();
  readonly submitRequested = output<string>();
  readonly stopRequested = output<void>();
  readonly checkStatusRequested = output<void>();
  readonly composer = viewChild(ChatInputComponent);
  readonly transcript = viewChild<ElementRef<HTMLElement>>('transcript');
  private followLatest = true;
  private readingAnchor: { node: Text; offset: number; top: number } | null = null;
  private anchorAgent: Agent | null = null;
  private transcriptWidth = 0;
  private transcriptHeight = 0;
  private restoringScroll = false;
  readonly content = messageContent;
  readonly markdown = markdownDocument;
  readonly blocked = computed(() => this.inputBlocked() || !!this.agent().isInputBlocked?.());
  readonly errorAgent = computed<Agent>(() => ({
    ...this.agent(),
    error: computed(() => this.runUnknown()
      ? new AgentError({ kind: 'interrupted', message: '連線中斷，執行結果尚未確認。', retryable: false, recovery: 'check', detail: '可查詢原執行狀態。' })
      : this.runFailed() ? new AgentError({ kind: 'server', message: this.runDenialReason() === 'MONTH_CAP' ? '工作區月預算已達上限，無法開始新工作；草稿已保留。' : this.runDenialReason() === 'MONTH_UNKNOWN' ? '工作區費用待確認，無法開始新工作；草稿已保留。' : this.runDenialReason() === 'WEEK_CAP' ? '成員週額度已達上限，回覆未完成；原稿已保留。' : this.runDenialReason() === 'WEEK_UNKNOWN' ? '本週費用待確認，回覆未完成；原稿已保留，請聯絡工作區管理者。' : '執行失敗，草稿已保留。', retryable: false }) : undefined),
    checkStatus: async () => { this.checkStatusRequested.emit(); }
  }));
  readonly composerAgent = computed<Agent>(() => ({
    ...this.agent(),
    isInputBlocked: this.blocked,
    submit: async () => {
      const agent = this.agent();
      const raw = this.composer()?.messageText() ?? '';
      this.submitRequested.emit(raw);
      // ChatInput clears synchronously after submit; an unchanged parent draft
      // means the parent rejected it or restored it before Angular rendered.
      queueMicrotask(() => {
        const composer = this.composer();
        if (this.agent() === agent && this.draft() === raw && composer && !composer.messageText()) {
          this.changeDetector.detectChanges();
          composer.messageText.set(raw);
        }
      });
    },
    stop: async () => { this.stopRequested.emit(); }
  }));
  constructor() {
    // Native scroll anchoring may choose a whole long message rather than the text being read.
    // Keep a local text position across reflow; hidden or programmatic scrolls must not change intent.
    effect(onCleanup => {
      const area = this.transcript()?.nativeElement;
      if (!area || typeof ResizeObserver === 'undefined') return;
      const observer = new ResizeObserver(() => {
        if (!this.visible() || !area.clientWidth || !area.clientHeight) return;
        const width = area.clientWidth, height = area.clientHeight;
        if (!this.transcriptWidth || !this.transcriptHeight) { this.transcriptWidth = width; this.transcriptHeight = height; return; }
        if (width === this.transcriptWidth && height === this.transcriptHeight) return;
        this.transcriptWidth = width; this.transcriptHeight = height;
        this.restoringScroll = true;
        if (this.followLatest) area.scrollTop = area.scrollHeight;
        else if (this.readingAnchor?.node.isConnected && area.contains(this.readingAnchor.node)) {
          const anchor = this.readingAnchor;
          const range = document.createRange();
          range.setStart(anchor.node, Math.min(anchor.offset, anchor.node.length));
          range.collapse(true);
          const top = range.getBoundingClientRect().top - area.getBoundingClientRect().top;
          area.scrollTop += top - anchor.top;
        }
        requestAnimationFrame(() => { this.restoringScroll = false; });
      });
      observer.observe(area);
      onCleanup(() => observer.disconnect());
    });
    effect(() => {
      const agent = this.agent();
      if (this.anchorAgent !== agent) {
        this.anchorAgent = agent;
        this.readingAnchor = null;
      }
    });
    effect(() => {
      const text = this.draft();
      const composer = this.composer();
      if (composer && untracked(composer.messageText) !== text) composer.messageText.set(text);
    });
    effect(() => {
      this.agent().messages();
      const visible = this.visible();
      const area = this.transcript()?.nativeElement;
      if (visible && area && this.followLatest) requestAnimationFrame(() => { if (this.visible() && this.followLatest && area.isConnected) area.scrollTop = area.scrollHeight; });
    });
  }
  onScroll(): void {
    const area = this.transcript()?.nativeElement;
    if (!area || !this.visible() || !area.clientHeight || this.restoringScroll ||
        (this.transcriptWidth && (area.clientWidth !== this.transcriptWidth || area.clientHeight !== this.transcriptHeight))) return;
    this.followLatest = area.scrollHeight - area.scrollTop - area.clientHeight < 48;
    if (!this.followLatest) this.rememberReadingAnchor(area);
  }
  private rememberReadingAnchor(area: HTMLElement): void {
    const box = area.getBoundingClientRect();
    const target = box.top + box.height / 2;
    const walker = document.createTreeWalker(area, NodeFilter.SHOW_TEXT);
    const range = document.createRange();
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      const text = node as Text;
      if (!text.length || !text.data.trim()) continue;
      range.selectNodeContents(text);
      const bounds = range.getBoundingClientRect();
      if (bounds.bottom < target || bounds.top > box.bottom) continue;
      let low = 0, high = text.length - 1;
      while (low < high) {
        const mid = Math.floor((low + high) / 2);
        range.setStart(text, mid); range.setEnd(text, mid + 1);
        if (range.getBoundingClientRect().bottom < target) low = mid + 1; else high = mid;
      }
      range.setStart(text, low); range.collapse(true);
      this.readingAnchor = { node: text, offset: low, top: range.getBoundingClientRect().top - box.top };
      return;
    }
    this.readingAnchor = null;
  }
  onInput(event: Event): void {
    if (event.target instanceof HTMLTextAreaElement) this.draftChanged.emit(event.target.value);
  }
  async copy(content: string): Promise<void> {
    try { await navigator.clipboard.writeText(content); this.copyNotice.set('已複製回覆。'); }
    catch { this.copyNotice.set('無法複製回覆。'); this.copyError.emit(); }
  }
  readonly copyNotice = signal('');
  readonly copyError = output<void>();
}
