import fs from "node:fs";
import { pathToFileURL } from "node:url";

export function planReviewedPullRequest({ pulls, refs, repository, branch, expected, inMain = false }) {
  const pages = Array.isArray(pulls) ? pulls : [];
  const flattened = pages.flatMap((page) => (Array.isArray(page) ? page : [page])).filter(Boolean);
  const official = flattened.filter((pull) => pull?.head?.repo?.full_name === repository);
  const exact = official.filter((pull) => pull?.head?.sha === expected && pull?.base?.ref === "main");
  const merged = exact.find((pull) => pull.merged_at);
  if (inMain || merged) return { action: "merged", branch, url: merged?.html_url ?? "" };

  const open = exact.find((pull) => pull.state === "open");
  if (open) return { action: "existing", branch: open.head.ref, url: open.html_url };

  const closed = exact.some((pull) => pull.state === "closed" && !pull.merged_at);
  const base = closed ? `${branch}-review-${expected.slice(0, 12)}` : branch;
  for (let index = 1; index <= 10; index += 1) {
    const suffix = index === 1 ? "" : `-${index}`;
    const candidate = `${base}${suffix}`;
    const pullCollision = official.some((pull) => pull?.head?.ref === candidate);
    const remoteSha = refs[candidate];
    if (!pullCollision && (!remoteSha || remoteSha === expected)) {
      return { action: "create", branch: candidate, url: "" };
    }
  }
  throw new Error(`No collision-free review branch for ${branch}`);
}

function parseArgs(argv) {
  const values = {};
  for (let index = 0; index < argv.length; index += 2) values[argv[index].replace(/^--/, "")] = argv[index + 1];
  return values;
}

function parseRefs(text) {
  const refs = {};
  for (const line of text.split("\n")) {
    const [sha, ref] = line.trim().split(/\s+/);
    const prefix = "refs/heads/";
    if (sha && ref?.startsWith(prefix)) refs[ref.slice(prefix.length)] = sha;
  }
  return refs;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const args = parseArgs(process.argv.slice(2));
  const plan = planReviewedPullRequest({
    pulls: JSON.parse(fs.readFileSync(args.pulls, "utf8")),
    refs: parseRefs(fs.readFileSync(args.refs, "utf8")),
    repository: args.repository,
    branch: args.branch,
    expected: args.expected,
    inMain: args["in-main"] === "true",
  });
  process.stdout.write(`${plan.action}\t${plan.branch}\t${plan.url}\n`);
}
