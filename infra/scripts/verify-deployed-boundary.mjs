import { pathToFileURL } from "node:url";

const MAX_RESPONSE_BYTES = 64 * 1024;

function feedUrl(apiUrl) {
  const url = new URL(apiUrl);
  if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash || (url.port && url.port !== "443")) {
    throw new Error("API URL must be a credential-free HTTPS origin/path");
  }
  url.pathname = `${url.pathname.replace(/\/$/, "")}/feed`;
  return url;
}

async function readBounded(response) {
  const declared = Number(response.headers.get("content-length"));
  if (Number.isFinite(declared) && declared > MAX_RESPONSE_BYTES) throw new Error("Smoke response is too large");
  if (!response.body) return;
  const reader = response.body.getReader();
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > MAX_RESPONSE_BYTES) throw new Error("Smoke response is too large");
    }
  } finally {
    await reader.cancel().catch(() => {});
  }
}

export async function verifyDeployedBoundary(apiUrl, fetchImplementation = fetch) {
  const response = await fetchImplementation(feedUrl(apiUrl), {
    method: "GET",
    redirect: "error",
    signal: AbortSignal.timeout(10_000),
    headers: { Accept: "application/json" },
  });
  await readBounded(response);
  if (response.status !== 401) throw new Error(`Expected anonymous HTTP 401, received ${response.status}`);
}

async function main(argv) {
  if (argv.length !== 1) throw new Error("Usage: verify-deployed-boundary.mjs <api-url>");
  await verifyDeployedBoundary(argv[0]);
  process.stdout.write("Protected API boundary verified.\n");
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).catch((error) => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  });
}
