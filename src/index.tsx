// import { NativeModules, Platform } from 'react-native';

// const LINKING_ERROR =
//   `The package 'react-native-sync-provider' doesn't seem to be linked. Make sure: \n\n` +
//   Platform.select({ ios: "- You have run 'pod install'\n", default: '' }) +
//   '- You rebuilt the app after installing the package\n' +
//   '- You are not using Expo Go\n';

// const SyncProvider = NativeModules.SyncProvider
//   ? NativeModules.SyncProvider
//   : new Proxy(
//       {},
//       {
//         get() {
//           throw new Error(LINKING_ERROR);
//         },
//       }
//     );

// export function multiply(a: number, b: number): Promise<number> {
//   return SyncProvider.multiply(a, b);
// }

export { useConnection } from './Hooks/useConnection';
export { ConnectionProvider } from './contexts/SyncProvider';
export { type StorageItem } from './@types/ProviderTypes';
