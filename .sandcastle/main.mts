import { run } from "@ai-hero/sandcastle";
import { bwrap } from "@ai-hero/sandcastle/sandboxes/bwrap";

import { opencodeV2 } from "./opencode-v2.ts";

const issue = Number(process.env.ISSUE ?? 0);
if (!issue) throw new Error("用法：ISSUE=14 npx tsx .sandcastle/main.mts");

await run({
  name: `issue-${issue}`,

  // Windows 上没有原生沙箱；走 WSL2 的 bwrap
  sandbox: bwrap({
    mounts: [
      {
        hostPath: "/home/xllcbc/.cache/sandcastle/m2",
        sandboxPath: "/home/xllcbc/.cache/sandcastle/m2",
      },
      {
        hostPath: "/home/xllcbc/.local/opt",
        sandboxPath: "/home/xllcbc/.local/opt",
        readonly: true,
      },
    ],
  }),

  agent: opencodeV2("opencode-go/deepseek-v4.1-flash", {
    env: { OPENCODE_API_KEY: process.env.OPENCODE_API_KEY ?? "" },
  }),

  promptFile: ".sandcastle/prompt.md",
  promptArgs: { ISSUE_NUMBER: issue },

  // 一次只做一票
  maxIterations: 1,

  // 一票一条分支：可审计、可并行、不碰你的工作区
  branchStrategy: { type: "branch", branch: `agent/issue-${issue}` },

  hooks: {
    sandbox: {
      onSandboxReady: [
        {
          command:
            "mvn -q -Dmaven.repo.local=/home/xllcbc/.cache/sandcastle/m2 " +
            "-Pno-containers -DskipTests compile",
          timeoutMs: 600_000,
        },
      ],
    },
  },
});

console.log(`跑完先审计：bash tools/check-redgreen.sh master agent/issue-${issue}`);
