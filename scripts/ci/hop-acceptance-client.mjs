import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import path from "node:path";

// Test-only sequential STDIO client. stdout must contain only matching JSON-RPC responses.
export function connect(hopDir, project, configDir) {
  const win = process.platform === "win32";
  const launcher = path.join(hopDir, win ? "hop.bat" : "hop");
  if (win && [launcher, project].some((value) => /["\r\n%]/.test(value))) {
    throw new Error("Unsupported Windows acceptance path");
  }
  const child = spawn(
    win ? process.env.ComSpec || "cmd.exe" : launcher,
    win
      ? [
          "/d",
          "/s",
          "/c",
          `""${launcher}" mcp --root "${project}" --allow-execution --allow-mutation"`,
        ]
      : ["mcp", "--root", project, "--allow-execution", "--allow-mutation"],
    {
      cwd: hopDir,
      windowsHide: true,
      windowsVerbatimArguments: win,
      env: { ...process.env, HOP_CONFIG_FOLDER: configDir },
      stdio: ["pipe", "pipe", "pipe"],
    },
  );
  let id = 0;
  let pending;
  let violation;
  let stderr = "";
  let exited = false;
  const exit = new Promise((resolve) => {
    child.once("error", (error) => {
      violation = error;
      pending?.reject(error);
      resolve({ code: -1 });
    });
    child.once("close", (code) => {
      exited = true;
      pending?.reject(new Error("Hop closed before responding"));
      resolve({ code });
    });
  });
  child.stderr.setEncoding("utf8");
  child.stderr.on("data", (chunk) => {
    stderr = (stderr + chunk).slice(-64 * 1024);
  });
  createInterface({ input: child.stdout }).on("line", (line) => {
    try {
      const response = JSON.parse(line);
      if (
        !pending ||
        response.jsonrpc !== "2.0" ||
        response.id !== pending.id
      ) {
        throw new Error("Unexpected stdout message or response ID");
      }
      if (response.error)
        pending.reject(new Error(JSON.stringify(response.error)));
      else pending.resolve(response.result);
    } catch (error) {
      violation = error;
      pending?.reject(error);
      child.kill();
    }
  });
  async function request(method, params) {
    if (pending || violation || exited)
      throw violation || new Error("Client unavailable");
    const requestId = ++id;
    let timer;
    try {
      return await new Promise((resolve, reject) => {
        pending = { id: requestId, resolve, reject };
        timer = setTimeout(() => {
          child.kill();
          reject(new Error("Acceptance response timeout"));
        }, 90_000);
        child.stdin.write(
          JSON.stringify({ jsonrpc: "2.0", id: requestId, method, params }) +
            "\n",
        );
      });
    } finally {
      clearTimeout(timer);
      pending = undefined;
    }
  }
  async function close() {
    child.stdin.end();
    let timer;
    try {
      const result = await Promise.race([
        exit,
        new Promise((_, reject) => {
          timer = setTimeout(() => {
            child.kill();
            reject(new Error("EOF shutdown timeout"));
          }, 30_000);
        }),
      ]);
      if (violation || result.code !== 0)
        throw violation || new Error(`Hop exit ${result.code}: ${stderr}`);
    } finally {
      clearTimeout(timer);
    }
  }
  return {
    request,
    close,
    abort: () => child.kill(),
    diagnostics: () => stderr,
    async initialize() {
      const result = await request("initialize", {
        protocolVersion: "2025-11-25",
        capabilities: {},
        clientInfo: { name: "hop-operational-acceptance", version: "1" },
      });
      if (
        result.protocolVersion !== "2025-11-25" ||
        result.serverInfo?.name !== "hop-mcp-connector"
      ) {
        throw new Error("Unexpected protocol/server");
      }
      child.stdin.write(
        JSON.stringify({
          jsonrpc: "2.0",
          method: "notifications/initialized",
        }) + "\n",
      );
      return result;
    },
    async call(name, args = {}, expectedError = false) {
      const result = await request("tools/call", { name, arguments: args });
      if (
        Boolean(result.isError) !== expectedError ||
        (!expectedError && !result.structuredContent)
      ) {
        throw new Error(
          `${name}: unexpected result ${JSON.stringify(result).slice(0, 4000)}`,
        );
      }
      return expectedError ? result : result.structuredContent;
    },
  };
}
