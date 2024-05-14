import * as React from 'react';

import { View, Text, TouchableOpacity } from 'react-native';

import { ConnectionProvider, useConnection } from 'react-native-sync-provider';

import styles from './styles';
import offlineData from './dataMock';

const Home: React.FC = () => {
  const { netInfoState, isSync, itemsCount, storage } = useConnection();

  const addToStorage = () => storage().setItem(offlineData);

  const clearAll = () => storage().removeAll();

  return (
    <View style={styles.container}>
      <Text>
        CONNECTION: {JSON.stringify(netInfoState?.isConnected, null, 2)}
      </Text>
      <TouchableOpacity onPress={addToStorage} style={styles.button}>
        <Text style={styles.buttonText}>ADD TO STORAGE</Text>
      </TouchableOpacity>
      <TouchableOpacity onPress={clearAll} style={styles.button}>
        <Text style={styles.buttonText}>CLEAR STORAGE</Text>
      </TouchableOpacity>
      <Text>KEYS: {itemsCount}</Text>
      <Text>SYNC: {JSON.stringify(isSync)}</Text>
    </View>
  );
};

const App: React.FC = () => {
  return (
    <ConnectionProvider
      connectionProviderConfiguration={{
        baseUrl: 'https://google.com.br',
        healthEndpoint: 'https://google.com.br',
      }}
    >
      <Home />
    </ConnectionProvider>
  );
};

export default App;
