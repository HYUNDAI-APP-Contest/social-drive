const FORBIDDEN_KEYS = new Set(["lat", "lng", "latitude", "longitude", "speed", "velocity", "acceleration", "brake", "steering", "vin", "vehicleid", "deviceid"]);

const plainObject = (value) => value && typeof value === "object" && !Array.isArray(value);
const onlyKeys = (value, allowed) => Object.keys(value).every((key) => allowed.has(key));
const unit = (value) => typeof value === "number" && Number.isFinite(value) && value >= 0 && value <= 1;

function containsForbiddenKey(value) {
  if (Array.isArray(value)) return value.some(containsForbiddenKey);
  if (!plainObject(value)) return false;
  return Object.entries(value).some(([key, child]) => FORBIDDEN_KEYS.has(key.toLowerCase()) || containsForbiddenKey(child));
}

export function validateRouteDocument(text) {
  let document;
  try { document = JSON.parse(text); } catch { return { valid: false, reason: "INVALID_JSON" }; }
  if (!plainObject(document) || containsForbiddenKey(document)) return { valid: false, reason: "FORBIDDEN_RAW_DATA" };
  if (!onlyKeys(document, new Set(["schemaVersion", "path", "highlights"])) || document.schemaVersion !== 1) return { valid: false, reason: "INVALID_SCHEMA" };
  if (!Array.isArray(document.path) || document.path.length < 2 || document.path.length > 500) return { valid: false, reason: "INVALID_PATH" };
  const pathValid = document.path.every((point) => plainObject(point)
    && onlyKeys(point, new Set(["horizontal", "vertical", "progress"]))
    && unit(point.horizontal) && unit(point.vertical)
    && (point.progress === undefined || unit(point.progress)));
  if (!pathValid) return { valid: false, reason: "INVALID_PATH" };
  if (!Array.isArray(document.highlights) || document.highlights.length > 100) return { valid: false, reason: "INVALID_HIGHLIGHTS" };
  const highlightsValid = document.highlights.every((item) => plainObject(item)
    && onlyKeys(item, new Set(["progress", "title"]))
    && unit(item.progress)
    && (item.title === undefined || (typeof item.title === "string" && [...item.title].length <= 40)));
  return highlightsValid ? { valid: true } : { valid: false, reason: "INVALID_HIGHLIGHTS" };
}
