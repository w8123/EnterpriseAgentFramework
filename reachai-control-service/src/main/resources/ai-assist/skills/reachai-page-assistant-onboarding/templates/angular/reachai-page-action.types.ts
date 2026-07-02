export type ReachAiPageActionStatus = 'SUCCESS' | 'WARN' | 'ERROR';

export interface ReachAiPageActionResult<T = unknown> {
  status: ReachAiPageActionStatus;
  message?: string;
  data?: T;
  error?: { code?: string; message: string };
  metadata?: Record<string, unknown>;
}

export interface ReachAiPageActionContext {
  pageKey: string;
  actionKey: string;
  confirmed?: boolean;
  requestId?: string;
}

export type ReachAiPageActionHandler<TArgs = unknown, TResult = unknown> = (
  args: TArgs,
  context: ReachAiPageActionContext,
) => ReachAiPageActionResult<TResult> | Promise<ReachAiPageActionResult<TResult>>;

export interface ReachAiPageActionRegistration {
  pageKey: string;
  actionKey: string;
  metadata?: Record<string, unknown>;
}

export interface ReachAiPageBridge {
  register<TArgs = unknown, TResult = unknown>(
    pageKey: string,
    actionKey: string,
    handler: ReachAiPageActionHandler<TArgs, TResult>,
    metadata?: Record<string, unknown>,
  ): void;
  unregisterPage(pageKey: string): void;
  execute<TArgs = unknown, TResult = unknown>(
    pageKey: string,
    actionKey: string,
    args?: TArgs,
    options?: { confirmed?: boolean; requestId?: string },
  ): Promise<ReachAiPageActionResult<TResult>>;
  list(pageKey?: string): ReachAiPageActionRegistration[];
}

declare global {
  interface Window {
    __REACHAI_PAGE_BRIDGE__?: ReachAiPageBridge;
  }
}

