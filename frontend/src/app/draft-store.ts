import { Injectable, signal } from '@angular/core';

/*
 * WHY / ARCHITECTURAL RATIONALE:
 * Draft Store Separation.
 * Draft input for new conversations is isolated from per-conversation draft inputs
 * in an app-level in-memory store.
 * Navigating between conversations or switching back to "+ New Chat" preserves the respective
 * in-progress text without cross-contamination or accidental loss, avoiding route destruction issues
 * while keeping server database free of empty/abandoned drafts.
 */
@Injectable({
  providedIn: 'root'
})
export class DraftStore {
  // Legacy single frontend draft text for "+ New Chat" (unpersisted conversation)
  private readonly newChatDraft = signal<string>('');

  // Per-bot unsaved draft inputs for unpersisted conversations, keyed by botId
  private readonly botDrafts = new Map<string, string>();

  // Per-conversation unsaved draft inputs, keyed by conversationId
  private readonly conversationDrafts = new Map<string, string>();

  getDraft(): string {
    return this.newChatDraft();
  }

  setDraft(text: string): void {
    this.newChatDraft.set(text);
  }

  clearDraft(): void {
    this.newChatDraft.set('');
  }

  getBotDraft(botId: string): string {
    return this.botDrafts.get(botId) || '';
  }

  setBotDraft(botId: string, text: string): void {
    if (text) {
      this.botDrafts.set(botId, text);
    } else {
      this.botDrafts.delete(botId);
    }
  }

  clearBotDraft(botId: string): void {
    this.botDrafts.delete(botId);
  }

  deleteBotDraft(botId: string): void {
    this.botDrafts.delete(botId);
  }

  getConversationDraft(conversationId: string): string {
    return this.conversationDrafts.get(conversationId) || '';
  }

  setConversationDraft(conversationId: string, text: string): void {
    if (text) {
      this.conversationDrafts.set(conversationId, text);
    } else {
      this.conversationDrafts.delete(conversationId);
    }
  }

  clearConversationDraft(conversationId: string): void {
    this.conversationDrafts.delete(conversationId);
  }

  deleteConversationDraft(conversationId: string): void {
    this.conversationDrafts.delete(conversationId);
  }

  resetAll(): void {
    this.newChatDraft.set('');
    this.botDrafts.clear();
    this.conversationDrafts.clear();
  }
}
