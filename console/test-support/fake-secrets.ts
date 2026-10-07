// The keystore of the Fake Engine (08-api.md: Secrets, ADR-019): the aliases an Engine has read
// from its PKCS12 file, which it reads again only when an admin reloads it. The Fake holds no secret
// values at all: an entry has a fingerprint that stands for its content, so that a reload can say
// which aliases changed. The file itself is what a test says it is (operators change it with
// keytool; the Engine never writes it). The contract tests (contract/admin-contract.ts) hold it to
// the Engine.

import { answer, failure, type ApiAnswer, type FakeRoute } from './fake-api';

export interface FakeKeystoreEntry {
  alias: string;
  /** `secret`, `trusted_certificate` or `private_key`. */
  type: string;
  /** `found`, or `invalid_secret` for a value that is not printable ASCII. */
  status: string;
  /** Stands for the content of the entry: another fingerprint is another content. */
  fingerprint: string;
}

/** What the keystore file holds now: its entries, or why it cannot be read. */
export type FakeKeystoreFile = { entries: FakeKeystoreEntry[] } | { problem: string };

/** What the secrets ask of the rest of the Fake Engine: which resources refer to an alias. */
export interface SecretUsers {
  usersOf(alias: string): string[];
}

const byAlias = (a: { alias: string }, b: { alias: string }) => (a.alias < b.alias ? -1 : a.alias > b.alias ? 1 : 0);

export class FakeSecrets {
  /** Null: the Engine was started without a keystore. */
  private file: FakeKeystoreFile | null = null;
  private loaded = new Map<string, FakeKeystoreEntry>();

  constructor(private readonly users: SecretUsers) {}

  /** The Engine is started with a keystore that holds [entries]: they are read at once. */
  configure(entries: FakeKeystoreEntry[]) {
    this.file = { entries };
    this.loaded = this.read(entries);
  }

  /** The operator changes the file: the Engine does not see it until a reload. */
  writeFile(file: FakeKeystoreFile) {
    if (this.file === null) throw new Error('the Engine has no keystore configured');
    this.file = file;
  }

  /** The status of [alias] as loaded: undefined when the keystore does not have it (or there is none). */
  statusOf(alias: string): string | undefined {
    return this.loaded.get(alias)?.status;
  }

  readonly routes: FakeRoute[] = [
    { method: 'GET', pattern: /^\/api\/v1\/secrets$/, admin: true, handle: () => this.list() },
    { method: 'POST', pattern: /^\/api\/v1\/secrets\/reload$/, admin: true, handle: () => this.reload() },
  ];

  private read(entries: FakeKeystoreEntry[]) {
    return new Map(entries.map((e) => [e.alias.toLowerCase(), { ...e, alias: e.alias.toLowerCase() }]));
  }

  private notConfigured = () =>
    failure(409, 'secret_store_not_configured', 'The Engine has no keystore configured.');

  private list(): ApiAnswer {
    if (this.file === null) return this.notConfigured();
    return answer(200, {
      secrets: [...this.loaded.values()].sort(byAlias).map((e) => ({
        alias: e.alias,
        type: e.type,
        status: e.status,
        usedBy: this.users.usersOf(e.alias),
      })),
    });
  }

  /** Reads the whole file again; one that cannot be read leaves what is in memory as it was. */
  private reload(): ApiAnswer {
    if (this.file === null) return this.notConfigured();
    if ('problem' in this.file) {
      return failure(422, 'secret_store_unreadable', 'The keystore could not be read.', {
        problem: this.file.problem,
      });
    }
    const before = this.loaded;
    const after = this.read(this.file.entries);
    const aliases = new Set([...before.keys(), ...after.keys()]);
    const changed = [...aliases]
      .filter((alias) => before.get(alias)?.fingerprint !== after.get(alias)?.fingerprint)
      .sort()
      .map((alias) => ({ alias, usedBy: this.users.usersOf(alias) }));
    this.loaded = after;
    return answer(200, { aliases: after.size, changed });
  }
}
