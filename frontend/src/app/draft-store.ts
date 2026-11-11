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
  // Single frontend draft text for "+ New Chat" (unpersisted conversation)
  private readonly newChatDraft = signal<string>('');

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
    this.conversationDrafts.clear();
  }
}
