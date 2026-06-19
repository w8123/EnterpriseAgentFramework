import { Injectable, OnDestroy } from '@angular/core';
import {
  ReachAiPageActionHandler,
  ReachAiPageActionRegistration,
  ReachAiPageActionResult,
  ReachAiPageBridge,
} from './reachai-page-action.types';

@Injectable({ providedIn: 'root' })
export class ReachAiPageActionService implements ReachAiPageBridge, OnDestroy {
  private readonly handlers = new Map<string, ReachAiPageActionHandler>();
  private readonly metadata = new Map<string, Record<string, unknown>>();

  constructor() {
    window.__REACHAI_PAGE_BRIDGE__ = this;
    window.addEventListener('message', this.onMessage);
  }

  register<TArgs = unknown, TResult = unknown>(
    pageKey: string,
    actionKey: string,
    handler: ReachAiPageActionHandler<TArgs, TResult>,
    metadata: Record<string, unknown> = {},
  ): void {
    const key = this.keyOf(pageKey, actionKey);
    this.handlers.set(key, handler as ReachAiPageActionHandler);
    this.metadata.set(key, { ...metadata, pageKey, actionKey });
  }

  unregisterPage(pageKey: string): void {
    for (const key of Array.from(this.handlers.keys())) {
      if (key.startsWith(`${pageKey}::`)) {
        this.handlers.delete(key);
        this.metadata.delete(key);
      }
    }
  }

  async execute<TArgs = unknown, TResult = unknown>(
    pageKey: string,
    actionKey: string,
    args?: TArgs,
    options: { confirmed?: boolean; requestId?: string } = {},
  ): Promise<ReachAiPageActionResult<TResult>> {
    const handler = this.handlers.get(this.keyOf(pageKey, actionKey));
    if (!handler) {
      return {
        status: 'ERROR',
        message: `Page action handler not found: ${pageKey}/${actionKey}`,
        error: { code: 'HANDLER_NOT_FOUND', message: 'Page action handler not found' },
      };
    }
    try {
      return await handler(args, {
        pageKey,
        actionKey,
        confirmed: options.confirmed,
        requestId: options.requestId,
      }) as ReachAiPageActionResult<TResult>;
    } catch (error) {
      return {
        status: 'ERROR',
        message: error instanceof Error ? error.message : 'Page action failed',
        error: {
          code: 'HANDLER_ERROR',
          message: error instanceof Error ? error.message : String(error),
        },
      };
    }
  }

  list(pageKey?: string): ReachAiPageActionRegistration[] {
    return Array.from(this.metadata.values())
      .filter((item) => !pageKey || item.pageKey === pageKey)
      .map((item) => ({
        pageKey: String(item.pageKey),
        actionKey: String(item.actionKey),
        metadata: item,
      }));
  }

  ngOnDestroy(): void {
    window.removeEventListener('message', this.onMessage);
    if (window.__REACHAI_PAGE_BRIDGE__ === this) {
      delete window.__REACHAI_PAGE_BRIDGE__;
    }
  }

  private readonly onMessage = async (event: MessageEvent): Promise<void> => {
    const payload = event.data || {};
    if (payload.type !== 'reachai.pageAction.execute') return;
    const result = await this.execute(payload.pageKey, payload.actionKey, payload.args, {
      confirmed: payload.confirmed,
      requestId: payload.requestId,
    });
    const target = event.source as { postMessage: (message: unknown, targetOrigin: string) => void } | null;
    target?.postMessage({
      type: 'reachai.pageAction.result',
      requestId: payload.requestId,
      pageKey: payload.pageKey,
      actionKey: payload.actionKey,
      result,
    }, event.origin || '*');
  };

  private keyOf(pageKey: string, actionKey: string): string {
    return `${pageKey}::${actionKey}`;
  }
}
