import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { logout as apiLogout, onSessionChanged, refreshSession, setSession, type SessionUser } from '../api/client';
import { authApi } from '../api/endpoints';

type Status = 'restoring' | 'signedIn' | 'signedOut' | 'offline';

interface AuthState {
  status: Status;
  user: SessionUser | null;
  signIn: (email: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
  /** After a password change or "sign out everywhere": forget the session locally. */
  endSession: () => void;
  updateUser: (user: Partial<SessionUser>) => void;
  retryRestore: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<Status>('restoring');
  const [user, setUser] = useState<SessionUser | null>(null);

  const restore = useCallback(() => {
    setStatus('restoring');
    refreshSession()
      .then((session) => {
        setUser(session?.user ?? null);
        setStatus(session ? 'signedIn' : 'signedOut');
      })
      .catch(() => setStatus('offline'));
  }, []);

  useEffect(() => {
    // A refresh triggered by any API call keeps this state in step (e.g. session revoked elsewhere).
    onSessionChanged((session) => {
      setUser(session?.user ?? null);
      setStatus(session ? 'signedIn' : 'signedOut');
      if (!session) queryClient.clear();
    });
    restore();
  }, [queryClient, restore]);

  const signIn = useCallback(async (email: string, password: string) => {
    const session = await authApi.login(email, password);
    setSession(session);
    setUser(session.user);
    setStatus('signedIn');
  }, []);

  const endSession = useCallback(() => {
    setSession(null);
    setUser(null);
    setStatus('signedOut');
    queryClient.clear();
  }, [queryClient]);

  const signOut = useCallback(async () => {
    try {
      await apiLogout();
    } finally {
      endSession();
    }
  }, [endSession]);

  const updateUser = useCallback((changes: Partial<SessionUser>) => {
    setUser((current) => (current ? { ...current, ...changes } : current));
  }, []);

  const value = useMemo(
    () => ({ status, user, signIn, signOut, endSession, updateUser, retryRestore: restore }),
    [status, user, signIn, signOut, endSession, updateUser, restore],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used inside AuthProvider');
  return value;
}
