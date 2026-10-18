// The sync command (deploy/wiki-mirror/sync.mjs) against real Git: a bare repository stands for the Wiki
// repository and the command runs as a separate process, configured by environment variables as in the
// workflow. What is not covered here is the Forgejo side (authentication and how the Wiki shows the
// files); that is the real run of the workflow.
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, chmodSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const syncScript = join(dirname(fileURLToPath(import.meta.url)), "sync.mjs");
const repoUrl = "https://forge.example/acme/proj";
let scratch;
let counter = 0;

before(() => {
  scratch = mkdtempSync(join(tmpdir(), "sync-test-"));
});
after(() => rmSync(scratch, { recursive: true, force: true }));

const git = (cwd, ...args) =>
  execFileSync("git", ["-c", "commit.gpgsign=false", ...args], { cwd, encoding: "utf8" }).trim();
const fresh = (name) => {
  const dir = join(scratch, `${name}-${counter++}`);
  mkdirSync(dir, { recursive: true });
  return dir;
};

// A bare repository standing for the Wiki repository, optionally holding a main branch.
const wikiRemote = (files = null) => {
  const bare = fresh("wiki");
  git(bare, "init", "-q", "--bare", "-b", "main");
  if (files) {
    const seed = fresh("seed");
    git(seed, "init", "-q", "-b", "main");
    for (const [name, content] of Object.entries(files)) writeFileSync(join(seed, name), content);
    git(seed, "add", "-A");
    git(seed, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "seed");
    git(seed, "push", "-q", bare, "main");
  }
  return bare;
};
const checkout = (files) => {
  const root = fresh("checkout");
  for (const [path, content] of Object.entries(files)) {
    mkdirSync(dirname(join(root, path)), { recursive: true });
    writeFileSync(join(root, path), content);
  }
  return root;
};
const stable = {
  "docs/stable/alpha/README.md": "# Alpha\nsee [r](01-requirements.md)\n",
  "docs/stable/alpha/01-requirements.md": "# Req\n",
  "docs/stable/dev.md": "# Dev\n",
};
const sync = (root, remote, env = {}) =>
  spawnSync("node", [syncScript], {
    cwd: root,
    encoding: "utf8",
    env: {
      PATH: process.env.PATH,
      HOME: process.env.HOME,
      MIRROR_REPO_URL: repoUrl,
      WIKI_REMOTE_URL: remote,
      ...env,
    },
  });
const filesOnMain = (bare) => {
  const names = git(bare, "ls-tree", "-r", "--name-only", "main").split("\n").filter(Boolean);
  const show = (n) => execFileSync("git", ["show", `main:${n}`], { cwd: bare, encoding: "utf8" });
  return Object.fromEntries(names.map((n) => [n, show(n)]));
};
const commitCount = (bare) => Number(git(bare, "rev-list", "--count", "main"));

test("an empty Wiki receives the mirror as it is built", () => {
  const remote = wikiRemote();
  const r = sync(checkout(stable), remote);
  assert.equal(r.status, 0, r.stderr);
  const files = filesOnMain(remote);
  assert.deepEqual(Object.keys(files).sort(), [
    "Home.md",
    "_Sidebar.md",
    "alpha%2F01-requirements.-.md",
    "alpha%2FREADME.md",
    "dev.md",
  ]);
  assert.ok(files["alpha%2FREADME.md"].includes(`[r](${repoUrl}/wiki/alpha/01-requirements)`));
});

test("a second sync of the same content makes no new commit", () => {
  const remote = wikiRemote();
  const root = checkout(stable);
  assert.equal(sync(root, remote).status, 0);
  const before = commitCount(remote);
  const again = sync(root, remote);
  assert.equal(again.status, 0, again.stderr);
  assert.equal(commitCount(remote), before);
});

test("what is in the Wiki and not in stable is removed", () => {
  const remote = wikiRemote({ "stray.md": "typed by hand\n", "alpha%2Fgone.-.md": "old\n" });
  assert.equal(sync(checkout(stable), remote).status, 0);
  const names = Object.keys(filesOnMain(remote));
  assert.ok(!names.includes("stray.md"));
  assert.ok(!names.includes("alpha%2Fgone.-.md"));
});

test("a document deleted from stable disappears from the Wiki on the next sync", () => {
  const remote = wikiRemote();
  assert.equal(sync(checkout(stable), remote).status, 0);
  const { "docs/stable/dev.md": _deleted, ...rest } = stable;
  assert.equal(sync(checkout(rest), remote).status, 0);
  assert.ok(!("dev.md" in filesOnMain(remote)));
});

test("other branches of the Wiki repository are left alone", () => {
  const remote = wikiRemote({ "x.md": "x\n" });
  git(remote, "branch", "other", "main");
  const otherBefore = git(remote, "rev-parse", "other");
  assert.equal(sync(checkout(stable), remote).status, 0);
  assert.equal(git(remote, "rev-parse", "other"), otherBefore);
});

test("the branch to write is configurable and defaults to main", () => {
  const remote = wikiRemote();
  assert.equal(sync(checkout(stable), remote, { WIKI_BRANCH: "mirror-check" }).status, 0);
  assert.equal(git(remote, "ls-tree", "--name-only", "mirror-check").includes("Home.md"), true);
  assert.equal(git(remote, "branch", "--list", "main"), "");
});

test("a failed build leaves the Wiki as it was and fails the command", () => {
  const remote = wikiRemote({ "kept.md": "kept\n" });
  const r = sync(checkout({ "README.md": "no stable here\n" }), remote);
  assert.notEqual(r.status, 0);
  assert.deepEqual(filesOnMain(remote), { "kept.md": "kept\n" });
});

test("a push the Wiki repository rejects fails the command and leaves the Wiki as it was", () => {
  const remote = wikiRemote({ "kept.md": "kept\n" });
  const hook = join(remote, "hooks", "pre-receive");
  writeFileSync(hook, "#!/bin/sh\necho rejected-by-test >&2\nexit 1\n");
  chmodSync(hook, 0o755);
  const r = sync(checkout(stable), remote);
  assert.notEqual(r.status, 0);
  assert.deepEqual(filesOnMain(remote), { "kept.md": "kept\n" });
});

test("a Wiki that cannot be reached fails the command", () => {
  const r = sync(checkout(stable), join(scratch, "no-such-repository"));
  assert.notEqual(r.status, 0);
});

test("a missing setting fails at once and names the variable", () => {
  const r = spawnSync("node", [syncScript], {
    cwd: checkout(stable),
    encoding: "utf8",
    env: { PATH: process.env.PATH, HOME: process.env.HOME },
  });
  assert.notEqual(r.status, 0);
  assert.match(r.stderr, /MIRROR_REPO_URL/);
});

test("an https Wiki address without a token fails at once and names the variable", () => {
  const r = sync(checkout(stable), "https://forge.example/acme/proj.wiki.git");
  assert.notEqual(r.status, 0);
  assert.match(r.stderr, /WIKI_TOKEN/);
});

test("the token appears in neither the output nor the Wiki repository", () => {
  const token = "s3cr3t-token-value";
  // An unreachable https address makes git fail while the token is configured.
  const failed = sync(checkout(stable), "https://127.0.0.1:1/acme/proj.wiki.git", { WIKI_TOKEN: token });
  assert.notEqual(failed.status, 0);
  assert.ok(!(failed.stdout + failed.stderr).includes(token));
  assert.ok(!(failed.stdout + failed.stderr).includes(Buffer.from(`x:${token}`).toString("base64")));
});

test("the work directory is removed after a run", () => {
  const work = fresh("tmp");
  const remote = wikiRemote();
  assert.equal(sync(checkout(stable), remote, { TMPDIR: work }).status, 0);
  assert.ok(existsSync(work));
  assert.deepEqual(execFileSync("ls", [work], { encoding: "utf8" }).trim(), "");
});
