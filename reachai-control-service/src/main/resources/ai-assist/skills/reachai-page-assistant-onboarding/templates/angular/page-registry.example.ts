import { ReachAiPageActionService } from './reachai-page-action.service';

export const reachAiPageKey = 'replace.with.pageKey';

export function registerReachAiPageActions(
  bridge: ReachAiPageActionService,
  page: {
    getPageState: () => unknown;
    setFilters: (filters: Record<string, unknown>) => void;
    search: () => Promise<unknown> | unknown;
    reset: () => Promise<unknown> | unknown;
    readTable: () => unknown;
    openRowAction?: (args: Record<string, unknown>) => Promise<unknown> | unknown;
  },
): void {
  bridge.register(reachAiPageKey, 'getPageState', async () => ({
    status: 'SUCCESS',
    data: page.getPageState(),
    metadata: { mode: 'readonly', riskLevel: 'LOW' },
  }));

  bridge.register(reachAiPageKey, 'setFilters', async (args) => {
    page.setFilters((args || {}) as Record<string, unknown>);
    return { status: 'SUCCESS', data: page.getPageState(), metadata: { riskLevel: 'LOW' } };
  });

  bridge.register(reachAiPageKey, 'search', async () => {
    const data = await page.search();
    return { status: 'SUCCESS', data, metadata: { riskLevel: 'LOW' } };
  });

  bridge.register(reachAiPageKey, 'reset', async () => {
    const data = await page.reset();
    return { status: 'SUCCESS', data, metadata: { riskLevel: 'LOW' } };
  });

  bridge.register(reachAiPageKey, 'readTable', async () => ({
    status: 'SUCCESS',
    data: page.readTable(),
    metadata: { mode: 'readonly', riskLevel: 'LOW' },
  }));

  if (page.openRowAction) {
    bridge.register(reachAiPageKey, 'openRowAction', async (args, context) => {
      if (!context.confirmed) {
        return {
          status: 'WARN',
          message: 'openRowAction requires user confirmation',
          metadata: { riskLevel: 'HIGH', confirmRequired: true },
        };
      }
      const data = await page.openRowAction?.((args || {}) as Record<string, unknown>);
      return { status: 'SUCCESS', data, metadata: { riskLevel: 'HIGH', confirmRequired: true } };
    }, { riskLevel: 'HIGH', confirmRequired: true });
  }
}

