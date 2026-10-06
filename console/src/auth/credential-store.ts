// Where the tab keeps the credential of its session. It is the storage of the tab (sessionStorage,
// never localStorage: ADR-017), so it ends with the last tab of the browser. The credential is an
// opaque string here: what it is, is the business of the sign-in method.

export type CredentialStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

export const SESSION_STORAGE_KEY = 'runline.session';

export function createCredentialStore(storage: CredentialStorage) {
  return {
    read: (): string | null => storage.getItem(SESSION_STORAGE_KEY),
    write: (credential: string) => storage.setItem(SESSION_STORAGE_KEY, credential),
    clear: () => storage.removeItem(SESSION_STORAGE_KEY),
  };
}

export type CredentialStore = ReturnType<typeof createCredentialStore>;
