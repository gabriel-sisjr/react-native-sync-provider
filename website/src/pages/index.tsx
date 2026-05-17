import clsx from 'clsx';
import Link from '@docusaurus/Link';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import Layout from '@theme/Layout';
import Heading from '@theme/Heading';

const features = [
  {
    title: 'Offline-first queue',
    icon: '📥',
    description:
      'Every request is durable. Enqueue from JS and the native layer persists it before the promise resolves — replay across crashes, reboots, and offline windows.',
  },
  {
    title: 'Background sync',
    icon: '🌙',
    description:
      'OS-scheduled flushes via BGTaskScheduler on iOS and WorkManager on Android. The queue keeps draining even when the app is closed.',
  },
  {
    title: 'Native persistence',
    icon: '💾',
    description:
      'Core Data on iOS and Room on Android — not AsyncStorage. Survives JS bundle reloads, memory pressure, and force-quits.',
  },
  {
    title: 'Configurable retry',
    icon: '🔁',
    description:
      'Exponential or linear backoff with jitter, per-status retry rules, and a hard attempt budget. Sane defaults aligned with HTTP semantics.',
  },
  {
    title: 'Nitro-powered',
    icon: '⚡',
    description:
      'Built on Nitro Modules for synchronous, type-safe JS↔native calls on the New Architecture. Single TypeScript spec drives both platforms.',
  },
];

function HeroBanner() {
  const { siteConfig } = useDocusaurusContext();
  return (
    <header className={clsx('hero hero--primary')}>
      <div className="container">
        <Heading as="h1" className="hero__title">
          {siteConfig.title}
        </Heading>
        <p className="hero__subtitle">{siteConfig.tagline}</p>
        <div className="install-command">
          yarn add @gabriel-sisjr/react-native-sync-provider
          react-native-nitro-modules
        </div>
        <div className="buttons">
          <Link
            className="button button--secondary button--lg"
            to="/docs/getting-started/introduction"
          >
            Get Started
          </Link>
          <Link
            className="button button--lg hero-api-button"
            to="/docs/api-reference/functions"
          >
            API Reference
          </Link>
        </div>
        <div className="badges">
          <img
            alt="npm version"
            src="https://img.shields.io/npm/v/@gabriel-sisjr/react-native-sync-provider"
          />
          <img
            alt="license"
            src="https://img.shields.io/npm/l/@gabriel-sisjr/react-native-sync-provider"
          />
          <img
            alt="platforms"
            src="https://img.shields.io/badge/platforms-iOS%20%7C%20Android-blue"
          />
          <img
            alt="new architecture"
            src="https://img.shields.io/badge/New%20Architecture-supported-green"
          />
        </div>
      </div>
    </header>
  );
}

function Feature({
  title,
  icon,
  description,
}: {
  title: string;
  icon: string;
  description: string;
}) {
  return (
    <div className="feature">
      <div className="feature-icon">{icon}</div>
      <h3>{title}</h3>
      <p>{description}</p>
    </div>
  );
}

function FeaturesSection() {
  return (
    <section className="features">
      <div className="container">
        <div className="row">
          {features.map((props, idx) => (
            <div key={idx} className={clsx('col col--3')}>
              <Feature {...props} />
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function HooksPreview() {
  return (
    <section
      style={{ padding: '3rem 0', background: 'var(--ifm-color-emphasis-100)' }}
    >
      <div className="container">
        <div className="row">
          <div className="col col--6">
            <Heading as="h2">React Hooks API</Heading>
            <p>
              7 purpose-built hooks cover every sync use case — from
              connectivity polling and live queue inspection to event streams,
              configuration, and one-line auto-flush wiring.
            </p>
            <ul>
              <li>
                <code>useConnection</code> &mdash; Live network state
              </li>
              <li>
                <code>useSyncQueue</code> &mdash; Pending items + enqueue/remove
              </li>
              <li>
                <code>useSyncStatus</code> &mdash; Last result, in-flight flag
              </li>
              <li>
                <code>useOfflineQueue</code> &mdash; Auto-pause when offline
              </li>
              <li>
                <code>useSyncEvents</code> &mdash; Subscribe to sync events
              </li>
              <li>
                <code>useSyncConfig</code> &mdash; Read / replace config live
              </li>
              <li>
                <code>useAutoSync</code> &mdash; Flush on focus / reconnect
              </li>
            </ul>
            <Link
              className="button button--primary"
              to="/docs/api-reference/hooks/useSyncQueue"
            >
              Explore Hooks
            </Link>
          </div>
          <div className="col col--6">
            <pre
              style={{
                padding: '1.5rem',
                borderRadius: '8px',
                fontSize: '0.85rem',
                overflow: 'auto',
              }}
            >
              <code>{`import { useSyncQueue } from '@gabriel-sisjr/react-native-sync-provider';

function OutboxScreen() {
  const { items, enqueue } = useSyncQueue();

  const sendEvent = () =>
    enqueue({
      method: 'POST',
      url: 'https://api.example.com/events',
      contentType: 'application/json',
      body: JSON.stringify({ kind: 'login' }),
    });

  return (
    <View>
      <Text>Pending: {items.length}</Text>
      <Button title="Send" onPress={sendEvent} />
    </View>
  );
}`}</code>
            </pre>
          </div>
        </div>
      </div>
    </section>
  );
}

export default function Home(): React.JSX.Element {
  const { siteConfig } = useDocusaurusContext();
  return (
    <Layout title={siteConfig.title} description={siteConfig.tagline}>
      <HeroBanner />
      <main>
        <FeaturesSection />
        <HooksPreview />
      </main>
    </Layout>
  );
}
