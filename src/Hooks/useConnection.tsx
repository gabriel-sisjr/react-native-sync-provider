import { useContext } from 'react';
import { ConnectionContext } from '../contexts/SyncProvider';

export const useConnection = () => {
  const ctx = useContext(ConnectionContext);

  if (!ctx) {
    throw new Error('useConnection must be used within an ConnectionProvider.');
  }

  return ctx;
};
