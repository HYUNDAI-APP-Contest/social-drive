import { spawn } from "node:child_process";

export function run(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { ...options, stdio: ["ignore", "pipe", "pipe"] });
    let stdout = ""; let stderr = "";
    child.stdout.on("data", (chunk) => { stdout += chunk; });
    child.stderr.on("data", (chunk) => { stderr += chunk; });
    child.once("error", reject);
    child.once("close", (code) => code === 0 ? resolve({ stdout, stderr }) : reject(new Error(`${command} exited ${code}: ${stderr.slice(-4000)}`)));
  });
}

export function safeWorkerEnvironment() {
  const blocked = /^(GH_.*|GITHUB_.*|DISCORD_.*|AWS_.*|AZURE_.*|GOOGLE_.*|OPENAI_API_KEY|.*TOKEN.*|.*SECRET.*|.*PASSWORD.*|.*PRIVATE_KEY.*)$/i;
  return Object.fromEntries(Object.entries(process.env).filter(([key]) => !blocked.test(key)));
}

