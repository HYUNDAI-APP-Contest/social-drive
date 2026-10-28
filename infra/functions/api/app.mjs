const response = (statusCode, body) => ({
  statusCode,
  headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  body: JSON.stringify(body),
});

export async function handler(event) {
  const routeKey = event?.requestContext?.routeKey;
  const claims = event?.requestContext?.authorizer?.jwt?.claims ?? {};
  const userId = claims.sub;
  if (!userId) return response(401, { error: "unauthorized" });

  if (routeKey === "GET /users/me") {
    return response(200, { id: userId, email: claims.email ?? null });
  }
  if (routeKey === "GET /feed") {
    return response(200, { items: [], nextCursor: null });
  }
  return response(404, { error: "not_found" });
}

