import { beforeEach, describe, expect, test } from 'vitest';
import { MemoryStorage } from '../../test-support/memory-storage';
import { createCredentialStore, SESSION_STORAGE_KEY } from './credential-store';

// The tab's storage of the browser, and the in-memory one the multi-tab tests use for the other
// tabs: the store must behave the same on both.
describe.each([
  ['the sessionStorage of the browser', () => sessionStorage],
  ['an in-memory storage', () => new MemoryStorage()],
])('the credential store on %s', (_name, storage) => {
  let store: ReturnType<typeof createCredentialStore>;
  let backing: Storage;
  beforeEach(() => {
    backing = storage();
    backing.clear();
    store = createCredentialStore(backing);
  });

  test('has no credential at first', () => {
    expect(store.read()).toBeNull();
  });

  test('gives back the credential it was given', () => {
    store.write('opaque-credential');
    expect(store.read()).toBe('opaque-credential');
  });

  test('forgets it when cleared', () => {
    store.write('opaque-credential');
    store.clear();
    expect(store.read()).toBeNull();
  });

  test('keeps it under one key of the tab storage, and nothing else', () => {
    store.write('opaque-credential');
    expect(backing.length).toBe(1);
    expect(backing.getItem(SESSION_STORAGE_KEY)).toBe('opaque-credential');
  });
});

test('never touches localStorage', () => {
  localStorage.clear();
  const store = createCredentialStore(sessionStorage);
  store.write('opaque-credential');
  expect(localStorage.length).toBe(0);
});
