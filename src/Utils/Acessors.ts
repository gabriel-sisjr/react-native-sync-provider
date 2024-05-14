import { type Dispatch } from 'react';
import { MMKV } from 'react-native-mmkv';
import { KEY_STORAGE, type StorageItem } from '../@types/ProviderTypes';

const Acessors = (
  setData: Dispatch<StorageItem[]>,
  setIsSync: Dispatch<boolean>
) => {
  const mmkvStorage = new MMKV();

  const getItem = (): StorageItem[] | null => {
    const content = mmkvStorage.getString(KEY_STORAGE);
    if (!content) {
      return null;
    }

    const parsed = JSON.parse(content) as StorageItem[];
    return parsed;
  };

  const setItem = (value: StorageItem[]): void => {
    mmkvStorage.set(KEY_STORAGE, JSON.stringify(value));
    setData(value);
    setIsSync(false);
  };

  const countItems = (): number => {
    return mmkvStorage.getAllKeys().length;
  };

  const contains = (): boolean => {
    return mmkvStorage.contains(KEY_STORAGE);
  };

  const removeItem = (): void => {
    mmkvStorage.delete(KEY_STORAGE);
    // TODO
    // const cleanedObj = Object.keys(key)
    //   .filter(objKey => objKey !== key)
    //   .reduce((newObj: T, k: string) => {
    //     newObj[k] = data[k];
    //     return newObj;
    //   }, {} as unknown as T);

    // setData(cleanedObj);
  };

  const removeAll = (): void => {
    mmkvStorage.clearAll();
    setData([] as StorageItem[]);
  };

  const storage = {
    getItem,
    setItem,
    countItems,
    contains,
    removeItem,
    removeAll,
  };

  return storage;
};

export default Acessors;
