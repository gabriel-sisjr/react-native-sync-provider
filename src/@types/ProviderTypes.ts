import { type NetInfoState } from '@react-native-community/netinfo';
import { type ReactNode } from 'react';

export interface StorageItem {
  data: any;
  urlEndpoint: string;
}

export interface ConnectionProviderConfiguration {
  baseUrl: string;
  healthEndpoint: string;
}

export interface IPropsProvider {
  children: ReactNode;
  connectionProviderConfiguration?: ConnectionProviderConfiguration;
}

export interface ConnectionContextData {
  netInfoState: NetInfoState | null;
  isSync: boolean;
  itemsCount: number;
  storage: () => {
    getItem: () => StorageItem[] | null;
    setItem: (value: StorageItem[]) => void;
    countItems: () => number;
    contains: () => boolean;
    removeItem: () => void;
    removeAll: () => void;
  };
}

export const KEY_STORAGE = '@@OFFLINE_STORAGE_ITEMS@@';
