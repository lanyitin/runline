// Zip files for the tests: a writer that stores the entries as they are (no compression: the bytes
// are the bytes of the files) and a reader of the central directory for what the writer made. The
// Fake Engine reads what a test uploads with it, and the tests build the jars they upload with it
// (the real Engine reads such a file as it reads any zip).

import { crc32 } from 'node:zlib';

const text = (value: string | Uint8Array): Uint8Array =>
  typeof value === 'string' ? new TextEncoder().encode(value) : value;

/** A zip holding [entries] (name to content), stored uncompressed. */
export function storedZip(entries: Record<string, string | Uint8Array>): Uint8Array {
  const parts: Uint8Array[] = [];
  const central: Uint8Array[] = [];
  let offset = 0;
  for (const [name, content] of Object.entries(entries)) {
    const data = text(content);
    const nameBytes = text(name);
    const crc = crc32(data);

    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4); // version needed
    local.writeUInt16LE(0x0800, 6); // names are UTF-8
    local.writeUInt16LE(0, 8); // stored
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBytes.length, 26);
    parts.push(local, nameBytes, data);

    const entry = Buffer.alloc(46);
    entry.writeUInt32LE(0x02014b50, 0);
    entry.writeUInt16LE(20, 4);
    entry.writeUInt16LE(20, 6);
    entry.writeUInt16LE(0x0800, 8);
    entry.writeUInt16LE(0, 10);
    entry.writeUInt32LE(crc, 16);
    entry.writeUInt32LE(data.length, 20);
    entry.writeUInt32LE(data.length, 24);
    entry.writeUInt16LE(nameBytes.length, 28);
    entry.writeUInt32LE(offset, 42);
    central.push(entry, nameBytes);

    offset += local.length + nameBytes.length + data.length;
  }
  const directory = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(Object.keys(entries).length, 8);
  end.writeUInt16LE(Object.keys(entries).length, 10);
  end.writeUInt32LE(directory.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...parts, directory, end]);
}

/** The entries of [bytes] (name to content), or null when it is not a zip file `storedZip` makes. */
export function readZip(bytes: Uint8Array): Map<string, Uint8Array> | null {
  const buffer = Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let end = -1;
  for (let i = buffer.length - 22; i >= Math.max(0, buffer.length - 22 - 65_535); i -= 1) {
    if (buffer.readUInt32LE(i) === 0x06054b50) {
      end = i;
      break;
    }
  }
  if (end < 0) return null;
  const count = buffer.readUInt16LE(end + 10);
  let at = buffer.readUInt32LE(end + 16);
  const entries = new Map<string, Uint8Array>();
  try {
    for (let n = 0; n < count; n += 1) {
      if (buffer.readUInt32LE(at) !== 0x02014b50) return null;
      const method = buffer.readUInt16LE(at + 10);
      const size = buffer.readUInt32LE(at + 20);
      const nameLength = buffer.readUInt16LE(at + 28);
      const extraLength = buffer.readUInt16LE(at + 30);
      const commentLength = buffer.readUInt16LE(at + 32);
      const local = buffer.readUInt32LE(at + 42);
      const name = buffer.toString('utf8', at + 46, at + 46 + nameLength);
      const start = local + 30 + buffer.readUInt16LE(local + 26) + buffer.readUInt16LE(local + 28);
      const raw = buffer.subarray(start, start + size);
      if (method !== 0) return null; // only what storedZip writes
      entries.set(name, raw);
      at += 46 + nameLength + extraLength + commentLength;
    }
  } catch {
    return null;
  }
  return entries;
}

/**
 * [bytes] with [tag] added to the comment of the zip: the same entries, other bytes, so a new
 * version for the Engine (its identity is the hash of the bytes). A jar may have a comment, and
 * neither the Engine nor a reader of the entries sees it.
 */
export function uniqueJar(bytes: Uint8Array, tag: string): Uint8Array {
  const buffer = Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let end = -1;
  for (let i = buffer.length - 22; i >= Math.max(0, buffer.length - 22 - 65_535); i -= 1) {
    // The end record whose comment reaches the end of the file is the one.
    if (buffer.readUInt32LE(i) === 0x06054b50 && i + 22 + buffer.readUInt16LE(i + 20) === buffer.length) {
      end = i;
      break;
    }
  }
  if (end < 0) throw new Error('not a zip file');
  const comment = Buffer.concat([buffer.subarray(end + 22), Buffer.from(`${tag};`, 'utf8')]);
  const record = Buffer.from(buffer.subarray(end, end + 22));
  record.writeUInt16LE(comment.length, 20);
  return Buffer.concat([buffer.subarray(0, end), record, comment]);
}
