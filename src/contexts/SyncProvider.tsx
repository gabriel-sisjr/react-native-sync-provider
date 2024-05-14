import React, { createContext, useCallback, useEffect, useState } from 'react';

import NetInfo, { type NetInfoState } from '@react-native-community/netinfo';
import Acessors from '../Utils/Acessors';
import {
  type ConnectionContextData,
  type IPropsProvider,
  type StorageItem,
} from '../@types/ProviderTypes';

export const ConnectionContext = createContext<ConnectionContextData>(
  {} as ConnectionContextData
);

export const ConnectionProvider: React.FC<IPropsProvider> = ({
  children,
  connectionProviderConfiguration,
}) => {
  const [netInfoState, setNetInfoState] = useState<NetInfoState>(
    {} as NetInfoState
  );

  const [isSync, setIsSync] = useState(false);
  const [itemsCount, setItemsCount] = useState(0);
  const [data, setData] = useState<StorageItem[]>([] as StorageItem[]);

  // Acessors
  const storage = useCallback(() => {
    const acessors = Acessors(setData, setIsSync);
    setItemsCount(data.length);

    return acessors;
  }, [setData, data.length]);

  const checkEndpoint = useCallback(() => {
    const success = fetch(
      connectionProviderConfiguration?.healthEndpoint ?? 'https://google.com.br'
    )
      .then((r) => r)
      .catch((err) => err);

    return success;
  }, [connectionProviderConfiguration?.healthEndpoint]);

  const uploadItems = (storageItem: StorageItem[]) => {
    // const response = fetch(url, {
    //   method: 'POST',
    //   body: jsonData,
    //   headers: {
    //     'Content-type': 'application/json; charset=UTF-8',
    //   },
    // })
    //   .then(r => r)
    //   .catch(err => err);
    const response = new Promise((resolve) => {
      setTimeout(() => {
        resolve({ storageItem });
      }, 300);
    });

    return response;
  };

  const syncItems = useCallback(
    (netInfo: NetInfoState) => {
      if (netInfo.isConnected && netInfo.isInternetReachable) {
        if (storage().contains()) {
          // ping
          checkEndpoint().then(() => {
            // start upload
            const storageData = storage().getItem();

            if (storageData) {
              uploadItems(storageData)
                .then(() => {
                  storage().removeItem();
                  const count = storage().countItems();
                  const sync = count === 0;
                  setItemsCount(count);
                  setIsSync(sync);
                })
                .catch((err) => JSON.stringify(err, null, 2));
            }
          });
        }
      }
    },
    [storage, checkEndpoint]
  );

  const listenerCallBack = useCallback(() => {
    const unsubscribe = NetInfo.addEventListener((state) => {
      const count = storage().countItems();
      const sync = count === 0;
      setIsSync(sync);
      setItemsCount(count);

      setNetInfoState(state);
      syncItems(state);
    });

    return () => {
      unsubscribe();
    };
  }, [syncItems, storage]);

  useEffect(() => {
    listenerCallBack();
  }, [listenerCallBack, data.length, storage]);

  return (
    <ConnectionContext.Provider
      value={{ netInfoState, isSync, itemsCount, storage }}
    >
      {children}
    </ConnectionContext.Provider>
  );
};
