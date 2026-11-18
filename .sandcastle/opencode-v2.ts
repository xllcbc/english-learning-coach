import {
  opencode,
  type AgentProvider,
  type BindMountSandboxHandle,
  type ExecResult,
  type OpenCodeOptions,
} from "@ai-hero/sandcastle";
import { mkdir } from "node:fs/promises";
import { dirname, posix } from "node:path";

type OpenCodeAgent = ReturnType<typeof opencode>;
type OpenCodeStorage = NonNullable<AgentProvider["sessionStorage"]>;
type CaptureArgs = Parameters<OpenCodeStorage["captureToHost"]>[0];
type ResumeArgs = Parameters<OpenCodeStorage["resumeIntoSandbox"]>[0];

const shellEscape = (value: string) => `'${value.replaceAll("'", "'\\''")}'`;

const failureReason = (result: ExecResult) =>
  result.stderr.trim() || `opencode 退出码 ${result.exitCode}`;

const sandboxSessionPath = (
  handle: BindMountSandboxHandle,
  exportDir: string | undefined,
  sessionId: string,
) => posix.join(exportDir ?? handle.sandboxTmpdir ?? "/tmp", `${sessionId}.json`);

function hostSessionPath(
  storage: OpenCodeStorage,
  hostCwd: string,
  sessionId: string,
): string {
  const hostPath = storage.hostSessionFilePath(hostCwd, sessionId);
  if (!hostPath) {
    throw new Error(`无法确定 session 的 host 存档路径（id=${sessionId}）`);
  }
  return hostPath;
}

async function requireOpencodeV2(
  handle: BindMountSandboxHandle,
  cwd: string,
): Promise<void> {
  const result = await handle.exec("opencode --version", { cwd });
  const output = `${result.stdout}\n${result.stderr}`.trim();
  const match = output.match(/(\d+)\./);
  const major = match ? Number(match[1]) : Number.NaN;

  if (result.exitCode !== 0 || !Number.isInteger(major) || major < 2) {
    throw new Error(
      `不支持的 opencode 版本 ${output || "<empty>"}：本地适配层只支持 v2（需要 opencode session export/import）`,
    );
  }
}

function makeCaptureToHost(storage: OpenCodeStorage, exportDir?: string) {
  return async ({
    hostCwd,
    sandboxCwd,
    sessionId,
    handle,
  }: CaptureArgs): Promise<void> => {
    await requireOpencodeV2(handle, sandboxCwd);

    try {
      const sandboxPath = sandboxSessionPath(handle, exportDir, sessionId);
      const command = `opencode session export ${shellEscape(sessionId)} --standalone > ${shellEscape(sandboxPath)}`;
      const result = await handle.exec(command, { cwd: sandboxCwd });

      if (result.exitCode !== 0) {
        throw new Error(
          `\`${command}\` exited with code ${result.exitCode}: ${failureReason(result)}`,
        );
      }

      const hostPath = hostSessionPath(storage, hostCwd, sessionId);
      await mkdir(dirname(hostPath), { recursive: true });
      await handle.copyFileOut(sandboxPath, hostPath);
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error);
      throw new Error(
        `session 未保存（id=${sessionId}），本次 run 不能 resume；原因：${reason}`,
      );
    }
  };
}

function makeResumeIntoSandbox(storage: OpenCodeStorage, exportDir?: string) {
  return async ({
    hostCwd,
    sandboxCwd,
    sessionId,
    handle,
  }: ResumeArgs): Promise<void> => {
    await requireOpencodeV2(handle, sandboxCwd);

    const hostPath = hostSessionPath(storage, hostCwd, sessionId);
    const sandboxPath = sandboxSessionPath(handle, exportDir, sessionId);
    await handle.exec(`mkdir -p ${shellEscape(posix.dirname(sandboxPath))}`);
    await handle.copyFileIn(hostPath, sandboxPath);

    const result = await handle.exec(
      `opencode session import ${shellEscape(sandboxPath)} --standalone`,
      { cwd: sandboxCwd },
    );
    if (result.exitCode !== 0) {
      throw new Error(
        `session 导入失败（id=${sessionId}）；原因：${failureReason(result)}`,
      );
    }
  };
}

/**
 * opencode() with the session commands OpenCode v2 actually supports.
 *
 * Upstream sandcastle 0.14.0 still calls the v1 top-level `export` / `import`.
 * This wrapper only swaps those two commands; run, stream parsing, permissions
 * and session-id extraction stay upstream.
 */
export function opencodeV2(
  model: string,
  options: Omit<OpenCodeOptions, "captureSessions"> = {},
): OpenCodeAgent {
  const base = opencode(model, { ...options, captureSessions: true });
  const storage = base.sessionStorage;
  const exportDir = options.sessionStorage?.sandboxExportDir;

  return {
    ...base,
    sessionStorage: {
      ...storage,
      captureToHost: makeCaptureToHost(storage, exportDir),
      resumeIntoSandbox: makeResumeIntoSandbox(storage, exportDir),
    },
  };
}
