// Mirrors docs/stable of the checkout in the current directory into the Wiki repository of the project
// (docs/stable/wiki-mirror/work-items/2-sync-stable-to-wiki.md, ADR 1 and 6). One way only: the Wiki is
// never read back except to get the branch the new commit is built on. The mirror is built completely
// before the Wiki is touched, and the Wiki is changed by one push of one commit, so a failure at any step
// leaves the Wiki as it was. A run whose mirror equals the Wiki's branch makes no commit.
//
//   node deploy/wiki-mirror/sync.mjs
//
//   MIRROR_REPO_URL   the repository's URL, e.g. https://forgejo.dev.lan/runline/runline (required)
//   WIKI_TOKEN        token that may push to the Wiki; sent as an HTTP header, never put on a command line
//                     (required when the Wiki address is http or https)
//   WIKI_REMOTE_URL   the Wiki repository (default: MIRROR_REPO_URL + ".wiki.git")
//   WIKI_BRANCH       the branch of the Wiki repository to write (default: main)
//   GITHUB_SHA        the commit mirrored, named in the commit message when set
import { execFileSync } from "node:child_process";
import { mkdtempSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { buildMirror, verifyMirror } from "./mirror.mjs";

const fail = (message) => {
  throw new Error(message);
};

const readConfig = (env) => {
  const repoUrl = (env.MIRROR_REPO_URL ?? "").replace(/\/+$/, "");
  if (!repoUrl) fail("MIRROR_REPO_URL is not set");
  const remote = env.WIKI_REMOTE_URL || `${repoUrl}.wiki.git`;
  const token = env.WIKI_TOKEN ?? "";
  if (/^https?:\/\//.test(remote) && !token) fail("WIKI_TOKEN is not set");
  return { repoUrl, remote, token, branch: env.WIKI_BRANCH || "main", sha: env.GITHUB_SHA ?? "" };
};

// Runs git in `cwd`; the token travels in the environment as an HTTP header, and is removed from
// anything git says before it is shown.
const gitRunner = (cwd, token) => {
  const env = { ...process.env, GIT_TERMINAL_PROMPT: "0" };
  const secrets = [];
  if (token) {
    const basic = Buffer.from(`forgejo-actions:${token}`).toString("base64");
    Object.assign(env, {
      GIT_CONFIG_COUNT: "1",
      GIT_CONFIG_KEY_0: "http.extraheader",
      GIT_CONFIG_VALUE_0: `Authorization: Basic ${basic}`,
    });
    secrets.push(token, basic);
  }
  const redact = (text) => secrets.reduce((t, s) => t.replaceAll(s, "***"), text);
  return (args, { allowStatus = [] } = {}) => {
    try {
      return execFileSync("git", args, { cwd, env, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
    } catch (error) {
      if (allowStatus.includes(error.status)) return null;
      throw new Error(`git ${args[0]} failed (${error.status}): ${redact(String(error.stderr || error.message))}`);
    }
  };
};

const syncWiki = ({ files, remote, token, branch, message }) => {
  const work = mkdtempSync(join(tmpdir(), "wiki-mirror-"));
  try {
    const git = gitRunner(work, token);
    git(["init", "-q", "-b", branch]);
    git(["config", "core.autocrlf", "false"]);
    git(["config", "commit.gpgsign", "false"]);
    git(["remote", "add", "origin", remote]);
    // Exit status 2 is "no such branch" (an empty Wiki); anything else that fails is an error.
    if (git(["ls-remote", "--exit-code", "--heads", "origin", branch], { allowStatus: [2] }) !== null) {
      git(["fetch", "-q", "--depth", "1", "origin", branch]);
      git(["reset", "-q", "--hard", "FETCH_HEAD"]);
      for (const entry of readdirSync(work)) {
        if (entry !== ".git") rmSync(join(work, entry), { recursive: true, force: true });
      }
    }
    for (const [name, content] of files) writeFileSync(join(work, name), content);
    git(["add", "-A"]);
    if (git(["diff", "--cached", "--quiet"], { allowStatus: [1] }) !== null) {
      console.log("The Wiki already holds this mirror; nothing to push.");
      return;
    }
    git(["-c", "user.name=runline wiki mirror", "-c", "user.email=noreply@invalid", "commit", "-q", "-m", message]);
    git(["push", "-q", "origin", `HEAD:refs/heads/${branch}`]);
    console.log(`Pushed the mirror (${files.size} pages) to branch ${branch}.`);
  } finally {
    rmSync(work, { recursive: true, force: true });
  }
};

const main = () => {
  const config = readConfig(process.env);
  const mirror = buildMirror({ repoRoot: process.cwd(), repoUrl: config.repoUrl, ref: "main" });
  verifyMirror(mirror, config.repoUrl);
  console.log(`Mirror built: ${mirror.files.size} pages.`);
  if (mirror.dropped.length > 0) {
    console.log(`${mirror.dropped.length} links lead nowhere and were kept as text only:`);
    for (const { source, dest } of mirror.dropped) console.log(`  ${source}: ${dest}`);
  }
  syncWiki({
    files: mirror.files,
    remote: config.remote,
    token: config.token,
    branch: config.branch,
    message: `docs: mirror docs/stable${config.sha ? ` at ${config.sha}` : ""}`,
  });
};

try {
  main();
} catch (error) {
  console.error(`wiki-mirror: ${error.message}`);
  process.exit(1);
}
