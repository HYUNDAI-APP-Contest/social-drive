import { spawn } from "node:child_process";
import path from "node:path";

export function codexLaunch(executable) {
  if (typeof executable !== "string" || !path.isAbsolute(executable) || executable.includes("\0")) {
    throw new Error("CODEX_EXECUTABLE must be an absolute executable path");
  }
  return { executable, args: ["app-server", "--listen", "stdio://"] };
}

export function run(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const { timeoutMs = 0, ...spawnOptions } = options;
    const child = spawn(command, args, { ...spawnOptions, stdio: ["ignore", "pipe", "pipe"] });
    let stdout = ""; let stderr = "";
    let timedOut = false;
    const timeout = timeoutMs > 0 ? setTimeout(() => { timedOut = true; child.kill("SIGTERM"); }, timeoutMs) : null;
    timeout?.unref();
    child.stdout.on("data", (chunk) => { stdout += chunk; });
    child.stderr.on("data", (chunk) => { stderr += chunk; });
    child.once("error", (error) => { if (timeout) clearTimeout(timeout); reject(error); });
    child.once("close", (code) => {
      if (timeout) clearTimeout(timeout);
      if (timedOut) reject(new Error(`${command} timed out after ${timeoutMs}ms`));
      else if (code === 0) resolve({ stdout, stderr });
      else reject(new Error(`${command} exited ${code}: ${stderr.slice(-4000)}`));
    });
  });
}

export function safeWorkerEnvironment() {
  const blocked = /^(GH_.*|GITHUB_.*|DISCORD_.*|AWS_.*|AZURE_.*|GOOGLE_.*|OPENAI_API_KEY|.*TOKEN.*|.*SECRET.*|.*PASSWORD.*|.*PRIVATE_KEY.*)$/i;
  return Object.fromEntries(Object.entries(process.env).filter(([key]) => !blocked.test(key)));
}

export function isolatedWorkerEnvironment() {
  const nodeBin = path.dirname(process.execPath);
  const inheritedPath = process.env.PATH ?? "/usr/bin:/bin:/usr/sbin:/sbin";
  const workerPath = [nodeBin, ...inheritedPath.split(path.delimiter)]
    .filter((value, index, values) => value && values.indexOf(value) === index)
    .join(path.delimiter);
  return {
    ...safeWorkerEnvironment(),
    PATH: workerPath,
    AWS_SHARED_CREDENTIALS_FILE: "/dev/null",
    AWS_CONFIG_FILE: "/dev/null",
    AWS_EC2_METADATA_DISABLED: "true",
  };
}

