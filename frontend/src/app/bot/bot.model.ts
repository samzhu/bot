export type BotTarget = 'LOCAL' | 'GKE_REMOTE';

export type BotConnectionStatus = 'NOT_APPLICABLE' | 'NOT_CONNECTED';

export type BotExecutionMode = 'LOCAL' | 'SPRING_AI_SIMULATION' | 'UNAVAILABLE';

export interface BotView {
  id: string;
  name: string;
  description?: string | null;
  instructions?: string | null;
  target: BotTarget;
  connectionStatus: BotConnectionStatus;
  executionMode: BotExecutionMode;
  statusReason?: string | null;
  canChat: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateBotInput {
  name: string;
  description?: string | null;
  instructions?: string | null;
}

export interface UpdateBotInput {
  id: string;
  name: string;
  description?: string | null;
  instructions?: string | null;
}

export interface LoadBotsOptions {
  search?: string | null;
  target?: BotTarget | null;
  limit?: number;
  offset?: number;
}

export interface BotsQueryResult {
  bots: BotView[];
}

export interface BotQueryResult {
  bot: BotView | null;
}

export interface DefaultLocalBotQueryResult {
  defaultLocalBot: BotView | null;
}

export interface CreateBotMutationResult {
  createBot: BotView;
}

export interface UpdateBotMutationResult {
  updateBot: BotView;
}

/*
 * ARCHITECTURAL CONTEXT & MODE INTERPRETATION:
 * TODO: Future integration with Gemini Enterprise Agent Platform.
 * Current implementation uses Spring AI simulation (SPRING_AI_SIMULATION) for remote agents while GKE connection
 * remains NOT_CONNECTED (infrastructure not yet deployed). GKE container substrate direction remains preserved.
 * UI and client state interpret this mode truthfully as "本機模擬，GKE尚未串接", never faking remote cloud readiness.
 */
export function getBotExecutionModeDisplay(bot: BotView): {
  badgeText: string;
  description: string;
  isSimulated: boolean;
} {
  if (bot.target === 'LOCAL') {
    return {
      badgeText: 'LOCAL',
      description: '本地原生助理',
      isSimulated: false
    };
  }

  if (bot.executionMode === 'SPRING_AI_SIMULATION') {
    return {
      badgeText: '本機模擬，GKE尚未串接',
      description: bot.statusReason || 'Spring AI 模擬推論中（待部署工作指示作為系統指示）',
      isSimulated: true
    };
  }

  return {
    badgeText: '未連線 (停用)',
    description: bot.statusReason || 'GKE 專案與叢集尚未配置',
    isSimulated: false
  };
}
