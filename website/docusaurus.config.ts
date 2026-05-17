import { themes as prismThemes } from 'prism-react-renderer';
import type { Config } from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

const config: Config = {
  title: 'React Native Sync Provider',
  tagline:
    'Offline-first HTTP request queue with native persistence and true background sync for React Native — even when the app is closed.',

  url: 'https://gabriel-sisjr.github.io',
  baseUrl: '/react-native-sync-provider/',

  organizationName: 'gabriel-sisjr',
  projectName: 'react-native-sync-provider',
  trailingSlash: false,

  onBrokenLinks: 'throw',

  markdown: {
    hooks: {
      onBrokenMarkdownLinks: 'warn',
    },
  },

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  headTags: [
    {
      tagName: 'meta',
      attributes: {
        name: 'keywords',
        content:
          'react native, sync, offline-first, queue, http, background sync, nitro, nitro-module, persistence, retry, hooks, kotlin, swift, typescript, android, ios',
      },
    },
    {
      tagName: 'meta',
      attributes: {
        property: 'og:type',
        content: 'website',
      },
    },
  ],

  presets: [
    [
      'classic',
      {
        docs: {
          sidebarPath: './sidebars.ts',
          editUrl:
            'https://github.com/gabriel-sisjr/react-native-sync-provider/tree/develop/website/',
        },
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
        sitemap: {
          lastmod: 'date',
          changefreq: 'weekly',
          priority: 0.5,
        },
      } satisfies Preset.Options,
    ],
  ],

  themes: [
    [
      require.resolve('@easyops-cn/docusaurus-search-local'),
      {
        hashed: true,
        language: ['en'],
        highlightSearchTermsOnTargetPage: true,
        explicitSearchResultPath: true,
      },
    ],
  ],

  themeConfig: {
    image: 'img/social-card.svg',
    metadata: [
      {
        name: 'description',
        content:
          'React Native library that turns every HTTP request into a durable, offline-first job. Native persistence (Core Data / Room), retry with backoff, and OS-scheduled background sync via BGTaskScheduler and WorkManager.',
      },
      {
        name: 'twitter:card',
        content: 'summary_large_image',
      },
    ],
    navbar: {
      title: 'RN Sync Provider',
      items: [
        {
          type: 'docSidebar',
          sidebarId: 'docsSidebar',
          position: 'left',
          label: 'Docs',
        },
        {
          to: '/docs/api-reference/functions',
          label: 'API',
          position: 'left',
        },
        {
          href: 'https://github.com/gabriel-sisjr/react-native-sync-provider',
          label: 'GitHub',
          position: 'right',
        },
        {
          href: 'https://www.npmjs.com/package/@gabriel-sisjr/react-native-sync-provider',
          label: 'npm',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Docs',
          items: [
            {
              label: 'Getting Started',
              to: '/docs/getting-started/introduction',
            },
            { label: 'API Reference', to: '/docs/api-reference/functions' },
            { label: 'Guides', to: '/docs/guides/offline-queue' },
          ],
        },
        {
          title: 'More',
          items: [
            {
              label: 'GitHub',
              href: 'https://github.com/gabriel-sisjr/react-native-sync-provider',
            },
            {
              label: 'npm',
              href: 'https://www.npmjs.com/package/@gabriel-sisjr/react-native-sync-provider',
            },
            {
              label: 'Issues',
              href: 'https://github.com/gabriel-sisjr/react-native-sync-provider/issues',
            },
          ],
        },
      ],
      copyright: `Copyright ${new Date().getFullYear()} Gabriel Santana.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ['kotlin', 'swift', 'bash', 'json', 'markup'],
    },
    colorMode: {
      defaultMode: 'dark',
      disableSwitch: false,
      respectPrefersColorScheme: true,
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
