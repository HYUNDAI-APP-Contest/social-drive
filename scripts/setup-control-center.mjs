#!/usr/bin/env node
import fs from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { loadConfig } from "../orchestrator/src/config.mjs";
import { GitHub } from "../orchestrator/src/github.mjs";
import { CONTROL_CENTER_SCHEMA } from "../orchestrator/src/control-center.mjs";
import { ProjectAdmin, controlCenterPlan } from "../orchestrator/src/project-admin.mjs";

export async function updateEnvFile(file, values) {
  const source = await fs.readFile(file, "utf8");
  let next = source;
  for (const [key, value] of Object.entries(values)) {
    if (!/^[A-Z0-9_]+$/.test(key) || /[\r\n]/.test(value)) throw new Error("Unsafe environment value");
    const line = `${key}=${value}`;
    const pattern = new RegExp(`^${key}=.*$`, "m");
    next = pattern.test(next) ? next.replace(pattern, line) : `${next.replace(/\n*$/, "\n")}${line}\n`;
  }
  const temporary = `${file}.tmp-${process.pid}`;
  await fs.writeFile(temporary, next, { mode: 0o600 });
  await fs.rename(temporary, file);
  await fs.chmod(file, 0o600);
}

const printActions = (actions) => {
  if (!actions.length) {
    console.log("✓ Control center schema is up to date.");
    return;
  }
  for (const action of actions) {
    const subject = action.field?.name ?? action.view?.name ?? action.label?.name ?? action.title ?? action.projectId ?? "";
    console.log(`- ${action.type}: ${subject}`);
  }
};

export async function main(argv = process.argv.slice(2)) {
  const modes = argv.filter((argument) => ["--dry-run", "--check", "--apply"].includes(argument));
  if (modes.length > 1) throw new Error("Choose exactly one of --dry-run, --check, or --apply");
  const mode = modes[0] ?? "--dry-run";
  const writeEnv = argv.includes("--write-env");
  if (writeEnv && mode !== "--apply") throw new Error("--write-env is only valid with --apply");
  const config = loadConfig({ requireSecrets: false });

  if (mode === "--dry-run") {
    console.log(`Dry run: ${CONTROL_CENTER_SCHEMA.title}`);
    console.log("No network request or mutation was performed.");
    printActions(controlCenterPlan({ project: null, labels: [] }));
    console.log("The apply mode owns project privacy, fields, options, saved views, and managed labels.");
  } else {
    if (!config.githubToken) throw new Error("GITHUB_TOKEN is required for --check or --apply");
    const github = new GitHub(config);
    const admin = new ProjectAdmin({ github, owner: config.githubOwner, repo: config.githubRepo });
    if (mode === "--check") {
      const current = await admin.inspect();
      const actions = controlCenterPlan(current);
      printActions(actions);
      if (actions.length) process.exitCode = 2;
      else if (current.project) console.log(`Project: ${current.project.url}`);
    } else {
      const result = await admin.apply();
      printActions(result.remaining);
      if (result.remaining.length) process.exitCode = 2;
      else if (writeEnv) {
        await updateEnvFile(path.resolve(".env"), {
          GITHUB_PROJECT_ID: result.current.project.id,
          GITHUB_PROJECT_URL: result.current.project.url,
        });
        console.log(`Project configured and saved: ${result.current.project.url}`);
      } else {
        console.log(`GITHUB_PROJECT_ID=${result.current.project.id}`);
        console.log(`GITHUB_PROJECT_URL=${result.current.project.url}`);
        console.log("Copy these non-secret values to .env, then restart the orchestrator.");
      }
    }
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    console.error(error instanceof Error ? error.message : "Control center setup failed");
    process.exitCode = 1;
  });
}
