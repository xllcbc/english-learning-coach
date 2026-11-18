import assert from "node:assert/strict";
import { mkdtemp, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import type { BindMountSandboxHandle } from "@ai-hero/sandcastle";

import { opencodeV2 } from "./opencode-v2.ts";

type ExecCall = { command: string; cwd?: string };

function makeHandle(options: { version?: string; exportExitCode?: number } = {}) {
  const execCalls: ExecCall[] = [];
  const copiesOut: Array<[string, string]> = [];
  const copiesIn: Array<[string, string]> = [];

  const handle: BindMountSandboxHandle = {
    worktreePath: "/sandbox/worktree",
    sandboxTmpdir: "/tmp",
    async exec(command, opts) {
      execCalls.push({ command, cwd: opts?.cwd });
      if (command === "opencode --version") {
        return {
          stdout: options.version ?? "2.3.0\n",
          stderr: "",
          exitCode: 0,
        };
      }
      if (command.startsWith("opencode session export")) {
        return {
          stdout: "",
          stderr: options.exportExitCode ? "export failed" : "",
          exitCode: options.exportExitCode ?? 0,
        };
      }
      return { stdout: "", stderr: "", exitCode: 0 };
    },
    async copyFileIn(hostPath, sandboxPath) {
      copiesIn.push([hostPath, sandboxPath]);
    },
    async copyFileOut(sandboxPath, hostPath) {
      copiesOut.push([sandboxPath, hostPath]);
    },
    async close() {},
  };

  return { handle, execCalls, copiesOut, copiesIn };
}

function transferArgs(handle: BindMountSandboxHandle, sessionId: string) {
  return {
    hostCwd: "/host/repo",
    sandboxCwd: "/sandbox/worktree",
    sessionId,
    handle,
  };
}

function commands(fake: ReturnType<typeof makeHandle>) {
  return fake.execCalls.map((call) => call.command).join("\n");
}

const hostRoot = await mkdtemp(join(tmpdir(), "sandcastle-adapter-"));
const hostStore = join(hostRoot, "nested", "sandcastle-sessions");
const agent = opencodeV2("test/model", {
  sessionStorage: { hostSessionsDir: hostStore },
});

assert.equal(
  agent.captureSessions,
  true,
  "opencodeV2 must force session capture on",
);

// capture: v2 export command, then copy to the host store
{
  const fake = makeHandle();
  await agent.sessionStorage.captureToHost(
    transferArgs(fake.handle, "ses_test"),
  );

  assert.deepEqual(fake.execCalls[0], {
    command: "opencode --version",
    cwd: "/sandbox/worktree",
  });
  assert.ok(
    fake.execCalls.some(
      ({ command }) =>
        command ===
        "opencode session export 'ses_test' --standalone > '/tmp/ses_test.json'",
    ),
    `unexpected export command:\n${commands(fake)}`,
  );
  assert.deepEqual(fake.copiesOut, [
    ["/tmp/ses_test.json", join(hostStore, "ses_test.json")],
  ]);
  await stat(hostStore);
}

// resume: copy the host JSON in, then import it
{
  const fake = makeHandle();
  await agent.sessionStorage.resumeIntoSandbox(
    transferArgs(fake.handle, "ses_test"),
  );

  assert.ok(
    fake.execCalls.some(({ command }) => command === "mkdir -p '/tmp'"),
    `missing mkdir:\n${commands(fake)}`,
  );
  assert.deepEqual(fake.copiesIn, [
    [join(hostStore, "ses_test.json"), "/tmp/ses_test.json"],
  ]);
  assert.ok(
    fake.execCalls.some(
      ({ command }) =>
        command ===
        "opencode session import '/tmp/ses_test.json' --standalone",
    ),
    `unexpected import command:\n${commands(fake)}`,
  );
}

// v1 is rejected before touching the session store
{
  const fake = makeHandle({ version: "1.9.0\n" });
  await assert.rejects(
    agent.sessionStorage.captureToHost(transferArgs(fake.handle, "ses_v1")),
    /只支持 v2/,
  );
}

// export failure is wrapped with the "cannot resume" wording
{
  const fake = makeHandle({ exportExitCode: 1 });
  await assert.rejects(
    agent.sessionStorage.captureToHost(transferArgs(fake.handle, "ses_fail")),
    /不能 resume/,
  );
}

// host write failure is wrapped too, not only non-zero exports
{
  const fake = makeHandle();
  fake.handle.copyFileOut = async () => {
    throw new Error("disk full");
  };
  await assert.rejects(
    agent.sessionStorage.captureToHost(
      transferArgs(fake.handle, "ses_write_fail"),
    ),
    /不能 resume/,
  );
}

await rm(hostRoot, { recursive: true, force: true });
console.log("opencode-v2 adapter: all checks passed");
