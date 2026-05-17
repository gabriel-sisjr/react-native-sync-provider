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
      collapsed: true,
      items: [
        'guides/offline-queue',
        'guides/background-sync',
        'guides/retry-policy',
        'guides/connectivity-detection',
        'guides/error-handling',
        'guides/priority-and-ordering',
        'guides/idempotency',
      ],
    },
    {
      type: 'category',
      label: 'API Reference',
      collapsed: true,
      items: [
        'api-reference/functions',
        'api-reference/listeners',
        {
          type: 'category',
          label: 'Hooks',
          collapsed: true,
          items: [
            'api-reference/hooks/useConnection',
            'api-reference/hooks/useSyncQueue',
            'api-reference/hooks/useSyncStatus',
            'api-reference/hooks/useOfflineQueue',
            'api-reference/hooks/useSyncEvents',
            'api-reference/hooks/useSyncConfig',
            'api-reference/hooks/useAutoSync',
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
      label: 'Architecture',
      collapsed: true,
      items: [
        'architecture/overview',
        'architecture/data-flow',
        'architecture/ios-native',
        'architecture/android-native',
      ],
    },
    {
      type: 'category',
      label: 'Production',
      collapsed: true,
      items: [
        'production/production-checklist',
        'production/ios-background-modes',
        'production/android-permissions',
        'production/privacy-manifest',
      ],
    },
    {
      type: 'category',
      label: 'Migration',
      collapsed: true,
      items: ['migration/index'],
    },
    {
      type: 'category',
      label: 'Development',
      collapsed: true,
      items: [
        'development/contributing',
        'development/testing',
        'development/debugging',
      ],
    },
    'troubleshooting',
  ],
};

export default sidebars;
