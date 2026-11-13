import type { SidebarsConfig } from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  docsSidebar: [
    {
      type: 'category',
      label: 'Getting Started',
      collapsed: false,
      items: [
        'getting-started/introduction',
        'getting-started/installation',
        'getting-started/quick-start',
        'getting-started/ios-setup',
        'getting-started/android-setup',
      ],
    },
    {
      type: 'category',
      label: 'Guides',
      items: [
        'guides/offline-queue',
        'guides/background-sync',
        'guides/retry-policy',
        'guides/connectivity-detection',
        'guides/error-handling',
        'guides/priority-and-ordering',
        'guides/idempotency',
        'guides/crash-recovery',
        'guides/battery-optimization',
      ],
    },
    {
      type: 'category',
      label: 'API Reference',
      items: [
        'api-reference/functions',
        'api-reference/listeners',
        {
          type: 'category',
          label: 'Hooks',
          items: [
            'api-reference/hooks/useConnection',
            'api-reference/hooks/useSyncQueue',
            'api-reference/hooks/useSyncStatus',
            'api-reference/hooks/useOfflineQueue',
            'api-reference/hooks/useSyncEvents',
            'api-reference/hooks/useSyncConfig',
            'api-reference/hooks/useAutoSync',
            'api-reference/hooks/useSyncSnapshot',
            'api-reference/hooks/useSyncContext',
          ],
        },
        'api-reference/context',
        'api-reference/types',
        'api-reference/enums',
        'api-reference/errors',
      ],
    },
    {
      type: 'category',
      label: 'Production',
      items: [
        'production/production-checklist',
        'production/android-permissions',
        'production/privacy-manifest',
        'production/platform-comparison',
        'production/ios-background-modes',
      ],
    },
    {
      type: 'category',
      label: 'Architecture',
      items: [
        'architecture/overview',
        'architecture/data-flow',
        'architecture/android-native',
        'architecture/ios-native',
      ],
    },
    {
      type: 'category',
      label: 'Migration',
      items: ['migration/index', 'migration/v0-3-0', 'migration/v0-2-0'],
    },
    {
      type: 'category',
      label: 'Development',
      items: [
        'development/contributing',
        'development/testing',
        'development/ci-cd',
        'development/publishing',
        'development/debugging',
      ],
    },
    'troubleshooting',
  ],
};

export default sidebars;
