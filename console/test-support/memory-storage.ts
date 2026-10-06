// The Web Storage of one tab, for tests that need several tabs in one process (jsdom has only one
// `sessionStorage`). It has no I/O: it is the same object model as the browser's, and
// src/auth/credential-store.test.ts holds it to the browser's own storage.

export class MemoryStorage implements Storage {
  private readonly items = new Map<string, string>();

  get length() {
    return this.items.size;
  }
  clear() {
    this.items.clear();
  }
  getItem(key: string) {
    return this.items.get(key) ?? null;
  }
  key(index: number) {
    return [...this.items.keys()][index] ?? null;
  }
  removeItem(key: string) {
    this.items.delete(key);
  }
  setItem(key: string, value: string) {
    this.items.set(key, String(value));
  }
  [name: string]: unknown;
}
