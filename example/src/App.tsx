import { Text, View, StyleSheet } from 'react-native';
import { isNativeModuleAvailable } from '@gabriel-sisjr/react-native-sync-provider';

const nativeReady = isNativeModuleAvailable();

export default function App() {
  return (
    <View style={styles.container}>
      <Text>
        SyncProvider native module: {nativeReady ? 'ready' : 'unavailable'}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
