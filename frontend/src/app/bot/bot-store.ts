import { Injectable, signal } from '@angular/core';
import {
  BotView,
  BotTarget,
  CreateBotInput,
  UpdateBotInput,
  LoadBotsOptions,
  BotsQueryResult,
  DefaultLocalBotQueryResult,
  CreateBotMutationResult,
  UpdateBotMutationResult
} from './bot.model';

@Injectable({
  providedIn: 'root'
})
export class BotStore {
  readonly bots = signal<BotView[]>([]);
  readonly defaultLocalBot = signal<BotView | null>(null);
  readonly isLoading = signal<boolean>(false);
  readonly error = signal<string | null>(null);
  readonly hasMore = signal<boolean>(false);

  private graphqlEndpoint = '/graphql';
  private requestGeneration = 0;

  setGraphqlEndpoint(endpoint: string): void {
    this.graphqlEndpoint = endpoint;
  }

  async loadDefaultLocalBot(): Promise<BotView | null> {
    const query = `
      query GetDefaultLocalBot {
        defaultLocalBot {
          id
          name
          description
          instructions
          target
          connectionStatus
          executionMode
          statusReason
          canChat
          createdAt
          updatedAt
        }
      }
    `;

    try {
      const res = await fetch(this.graphqlEndpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ query })
      });

      if (!res.ok) {
        this.defaultLocalBot.set(null);
        throw new Error(`HTTP ${res.status}: Failed to fetch defaultLocalBot`);
      }

      const json = await res.json();
      if (json.errors && json.errors.length > 0) {
        console.warn('GraphQL errors fetching defaultLocalBot:', json.errors);
      }

      const defaultBot: BotView | null = json.data?.defaultLocalBot || null;
      this.defaultLocalBot.set(defaultBot);
      return defaultBot;
    } catch (err: any) {
      console.error('Failed to load default local bot:', err);
      // Strictly clean up stale default state on failure to avoid stale reference
      this.defaultLocalBot.set(null);
      return null;
    }
  }

  async loadBot(id: string): Promise<BotView | null> {
    const query = `
      query GetBot($id: ID!) {
        bot(id: $id) {
          id
          name
          description
          instructions
          target
          connectionStatus
          executionMode
          statusReason
          canChat
          createdAt
          updatedAt
        }
      }
    `;

    try {
      const res = await fetch(this.graphqlEndpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          query,
          variables: { id }
        })
      });

      if (!res.ok) {
        return null;
      }

      const json = await res.json();
      if (json.errors && json.errors.length > 0) {
        return null;
      }

      return json.data?.bot || null;
    } catch (err: any) {
      console.error(`Failed to load bot with id ${id}:`, err);
      return null;
    }
  }

  async loadBots(
    optionsOrLimit?: LoadBotsOptions | number,
    maybeOffset = 0
  ): Promise<BotView[]> {
    const currentGen = ++this.requestGeneration;
    this.isLoading.set(true);
    this.error.set(null);

    let search: string | null = null;
    let target: BotTarget | null = null;
    let pageSize = 20;
    let offset = 0;

    if (typeof optionsOrLimit === 'number') {
      pageSize = optionsOrLimit;
      offset = maybeOffset;
    } else if (optionsOrLimit && typeof optionsOrLimit === 'object') {
      search = optionsOrLimit.search ?? null;
      target = optionsOrLimit.target ?? null;
      pageSize = optionsOrLimit.limit ?? 20;
      offset = optionsOrLimit.offset ?? 0;
    }

    // Paging: fetch pageSize + 1 (e.g. 21 for 20) to determine hasMore authoritatively without extra count API.
    // Checked backend limit ceiling: safeLimit allows up to 100.
    const fetchLimit = Math.min(pageSize + 1, 100);

    const query = `
      query GetBots($search: String, $target: BotTarget, $limit: Int, $offset: Int) {
        defaultLocalBot {
          id
          name
          description
          instructions
          target
          connectionStatus
          executionMode
          statusReason
          canChat
          createdAt
          updatedAt
        }
        bots(search: $search, target: $target, limit: $limit, offset: $offset) {
          id
          name
          description
          instructions
          target
          connectionStatus
          executionMode
          statusReason
          canChat
          createdAt
          updatedAt
        }
      }
    `;

    try {
      const res = await fetch(this.graphqlEndpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          query,
          variables: {
            search: search || null,
            target: target || null,
            limit: fetchLimit,
            offset
          }
        })
      });

      if (!res.ok) {
        throw new Error(`HTTP ${res.status}: Failed to fetch bots`);
      }

      const json = await res.json();
      if (json.errors && json.errors.length > 0) {
        throw new Error(json.errors[0]?.message || 'GraphQL error fetching bots');
      }

      // Generation check: discard stale async responses
      if (currentGen !== this.requestGeneration) {
        return this.bots();
      }

      if (json.data?.defaultLocalBot !== undefined) {
        this.defaultLocalBot.set(json.data.defaultLocalBot || null);
      } else {
        // Clear default if not present in payload to prevent stale reference
        this.defaultLocalBot.set(null);
      }

      const rawList: BotView[] = json.data?.bots || [];
      const hasMore = rawList.length > pageSize;
      const displayedBots = hasMore ? rawList.slice(0, pageSize) : rawList;

      this.hasMore.set(hasMore);
      this.bots.set(displayedBots);
      return displayedBots;
    } catch (err: any) {
      if (currentGen !== this.requestGeneration) {
        return this.bots();
      }
      // Clear stale defaultLocalBot state on loadBots failure as well
      this.defaultLocalBot.set(null);
      this.error.set(err.message || 'Unknown error loading bots');
      throw err;
    } finally {
      if (currentGen === this.requestGeneration) {
        this.isLoading.set(false);
      }
    }
  }

  async createBot(input: CreateBotInput): Promise<BotView> {
    // Invalidate any in-flight background query responses
    ++this.requestGeneration;
    this.isLoading.set(true);
    this.error.set(null);

    const mutation = `
      mutation CreateBot($input: CreateBotInput!) {
        createBot(input: $input) {
          id
          name
          description
          instructions
          target
          connectionStatus
          executionMode
          statusReason
          canChat
          createdAt
          updatedAt
        }
      }
    `;

    try {
      const res = await fetch(this.graphqlEndpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          query: mutation,
          variables: { input }
        })
      });

      if (!res.ok) {
        throw new Error(`HTTP ${res.status}: Failed to create bot`);
      }

      const json = await res.json();
      if (json.errors && json.errors.length > 0) {
        const firstErr = json.errors[0];
        const msg = firstErr.message || 'Failed to create bot';
        throw new Error(msg);
      }

      const data: CreateBotMutationResult = json.data;
      const created = data.createBot;

      // Update local state: add new bot to bots list
      this.bots.update(prev => [created, ...prev.filter(b => b.id !== created.id)]);
      return created;
    } catch (err: any) {
      this.error.set(err.message || 'Unknown error creating bot');
      throw err;
    } finally {
      this.isLoading.set(false);
    }
  }

  async updateBot(input: UpdateBotInput): Promise<BotView> {
    // Invalidate any in-flight background query responses
    ++this.requestGeneration;
    this.isLoading.set(true);
    this.error.set(null);

    const mutation = `
      mutation UpdateBot($input: UpdateBotInput!) {
        updateBot(input: $input) {
          id
          name
          description
          instructions
          target
          connectionStatus
          executionMode
          statusReason
          canChat
          createdAt
          updatedAt
        }
      }
    `;

    try {
      const res = await fetch(this.graphqlEndpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          query: mutation,
          variables: { input }
        })
      });

      if (!res.ok) {
        throw new Error(`HTTP ${res.status}: Failed to update bot`);
      }

      const json = await res.json();
      if (json.errors && json.errors.length > 0) {
        const firstErr = json.errors[0];
        const msg = firstErr.message || 'Failed to update bot';
        throw new Error(msg);
      }

      const data: UpdateBotMutationResult = json.data;
      const updated = data.updateBot;

      // Update local state: replace the bot in bots list
      this.bots.update(prev => prev.map(b => (b.id === updated.id ? updated : b)));
      return updated;
    } catch (err: any) {
      this.error.set(err.message || 'Unknown error updating bot');
      throw err;
    } finally {
      this.isLoading.set(false);
    }
  }
}
