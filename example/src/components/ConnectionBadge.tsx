import React from 'react';
import { Text, View } from 'react-native';
import {
  ConnectionStatus,
  useConnection,
} from '@gabriel-sisjr/react-native-sync-provider';

import styles from '../styles';

/**
 * Compact indicator that mirrors the live network connectivity reported by
 * the native layer. Renders a colored dot and a label that switches between
 * "Online", "Offline", "Metered" and "Unknown".
 *
 * Subscribes through `useConnection` so the badge re-renders on every
 * `CONNECTION_CHANGED` event without polling.
 */
function ConnectionBadgeBase(): React.ReactElement {
  const { status, type } = useConnection();

  let containerStyle = styles.badgeUnknown;
  let textStyle = styles.badgeUnknownText;
  let dotStyle = styles.badgeUnknownDot;
  let label = 'Unknown';

  switch (status) {
    case ConnectionStatus.CONNECTED:
      containerStyle = styles.badgeOnline;
      textStyle = styles.badgeOnlineText;
      dotStyle = styles.badgeOnlineDot;
      label = 'Online';
      break;
    case ConnectionStatus.METERED:
      containerStyle = styles.badgeMetered;
      textStyle = styles.badgeMeteredText;
      dotStyle = styles.badgeMeteredDot;
      label = 'Metered';
      break;
    case ConnectionStatus.DISCONNECTED:
      containerStyle = styles.badgeOffline;
      textStyle = styles.badgeOfflineText;
      dotStyle = styles.badgeOfflineDot;
      label = 'Offline';
      break;
    case ConnectionStatus.UNKNOWN:
    default:
      break;
  }

  return (
    <View style={[styles.badge, containerStyle]}>
      <View style={[styles.badgeDot, dotStyle]} />
      <Text style={[styles.badgeText, textStyle]}>
        {label} · {type}
      </Text>
    </View>
  );
}

export const ConnectionBadge = React.memo(ConnectionBadgeBase);
