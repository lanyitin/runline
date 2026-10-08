// The keystore of the Fake Engine (08-api.md: Secrets, ADR-019): the aliases an Engine has read
// from its PKCS12 file, which it reads again only when an admin reloads it. The Fake holds no secret
// values at all: an entry has a fingerprint that stands for its content, so that a reload can say
// which aliases changed. The file itself is what a test says it is (operators change it with
// keytool; the Engine never writes it). The contract tests (contract/admin-contract.ts) hold it to
// the Engine.

import { answer, failure, type ApiAnswer, type FakeRoute } from './fake-api';

/** What may be shown of a certificate of an entry (WI-52): never anything of a key. */
export interface FakeCertificate {
  subject: string;
  notAfter: string;
  daysLeft: number;
  /** SHA-256, as `keytool -list` prints it. */
  fingerprint: string;
  /** `valid`, `expiring` or `expired`. */
  expiry: string;
}

export interface FakeKeystoreEntry {
  alias: string;
  /** `secret`, `trusted_certificate` or `private_key`. */
  type: string;
  /** `found`, `invalid_secret` for a value that is not printable ASCII, `invalid_key` for a key the keystore's password does not open. */
  status: string;
  /** Stands for the content of the entry: another fingerprint is another content. */
  fingerprint: string;
  /** The certificate of a certificate entry, the chain of a private key entry; made up when not given. */
  certificates?: FakeCertificate[];
}

/** What the keystore file holds now: its entries, or why it cannot be read. */
export type FakeKeystoreFile = { entries: FakeKeystoreEntry[] } | { problem: string };

/** What the secrets ask of the rest of the Fake Engine: which resources refer to an alias. */
export interface SecretUsers {
  usersOf(alias: string): string[];
}

/** A certificate for an entry a test gave none: a year left, its fingerprint from the entry's. */
function madeUp(entry: FakeKeystoreEntry): FakeCertificate {
  const hex = [...`${entry.fingerprint}${'0'.repeat(64)}`.slice(0, 64)]
    .map((c) => (/[0-9a-f]/i.test(c) ? c.toUpperCase() : '0'))
    .join('');
  return {
    subject: `CN=${entry.alias}`,
    notAfter: new Date(Date.now() + 365 * 86_400_000).toISOString().replace(/\.\d+Z$/, 'Z'),
    daysLeft: 364,
    fingerprint: hex.match(/../g)!.join(':'),
    expiry: 'valid',
  };
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

  /** The kind of entry of [alias] as loaded: undefined when the keystore does not have it. */
  typeOf(alias: string): string | undefined {
    return this.loaded.get(alias)?.type;
  }

  /** The certificates of the entry of [alias] as loaded; none for a secret or no entry. */
  certificatesOf(alias: string): FakeCertificate[] {
    return this.loaded.get(alias)?.certificates ?? [];
  }

  readonly routes: FakeRoute[] = [
    { method: 'GET', pattern: /^\/api\/v1\/secrets$/, admin: true, handle: () => this.list() },
    { method: 'POST', pattern: /^\/api\/v1\/secrets\/reload$/, admin: true, handle: () => this.reload() },
  ];

  private read(entries: FakeKeystoreEntry[]) {
    return new Map(
      entries.map((e) => [
        e.alias.toLowerCase(),
        {
          ...e,
          alias: e.alias.toLowerCase(),
          certificates: e.type === 'secret' ? undefined : (e.certificates ?? [madeUp(e)]),
        },
      ]),
    );
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
        ...(e.certificates === undefined ? {} : { certificates: e.certificates }),
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
