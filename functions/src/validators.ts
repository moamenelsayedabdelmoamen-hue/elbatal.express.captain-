import {DomainHttpsError} from "./types.js";

export const MAX_LOCATION_AGE_MS = 30 * 60 * 1000; // 30 minutes

const DISALLOWED_CAPTAIN_STATUSES = new Set([
  "banned",
  "suspended",
  "disabled",
  "blocked",
  "pending",
  "inactive",
  "rejected",
]);

const DISALLOWED_ORDER_STATUSES = new Set([
  "cancelled",
  "canceled",
  "rejected",
  "declined",
  "expired",
  "delivered",
  "completed",
  "done",
]);

const AVAILABLE_ORDER_STATUSES = new Set([
  "pending",
  "searching",
  "offered",
  "new",
  "ready",
  "ready_for_pickup",
  "waiting_driver",
]);

export const ACTIVE_ORDER_STATUSES = new Set([
  "accepted",
  "assigned",
  "arrived_at_merchant",
  "at_store",
  "arrived",
  "arrived_store",
  "picked_up",
  "order_data_entered",
  "data_entered",
  "awaiting_merchant_confirmation",
  "awaiting_confirmation",
  "merchant_rejected",
  "en_route",
  "out_for_delivery",
  "delivering",
  "on_the_way",
]);

/**
 * Reads the first non-empty string among candidate keys.
 * @param {Record<string, unknown> | undefined} data Document fields.
 * @param {string[]} keys Candidate field names.
 * @return {string} Trimmed string or empty string.
 */
export function readString(
  data: Record<string, unknown> | undefined,
  ...keys: string[]
): string {
  if (!data) return "";
  for (const k of keys) {
    const val = data[k];
    if (typeof val === "string" && val.trim().length > 0) {
      return val.trim();
    }
    if (typeof val === "number" && Number.isFinite(val)) {
      return String(val);
    }
  }
  return "";
}

/**
 * Reads a finite number among candidate keys.
 * @param {Record<string, unknown> | undefined} data Document fields.
 * @param {string[]} keys Candidate field names.
 * @return {number | null} Finite number or null.
 */
export function readNumber(
  data: Record<string, unknown> | undefined,
  ...keys: string[]
): number | null {
  if (!data) return null;
  for (const k of keys) {
    const raw = data[k];
    if (typeof raw === "number" && Number.isFinite(raw)) {
      return raw;
    }
    if (typeof raw === "string" && raw.trim().length > 0) {
      const parsed = Number(raw.trim());
      if (Number.isFinite(parsed)) return parsed;
    }
  }
  return null;
}

/**
 * Parses a Firestore Timestamp, Date, ISO string, or epoch ms/s number into ms.
 * @param {unknown} raw Timestamp candidate value.
 * @return {number} Epoch milliseconds or 0 if invalid.
 */
export function parseTimestampMs(raw: unknown): number {
  if (raw === null || raw === undefined) return 0;
  if (typeof raw === "number" && Number.isFinite(raw) && raw > 0) {
    return raw < 1e12 ? Math.round(raw * 1000) : Math.round(raw);
  }
  if (raw instanceof Date) {
    const ms = raw.getTime();
    return Number.isFinite(ms) ? ms : 0;
  }
  if (typeof raw === "string" && raw.trim().length > 0) {
    const num = Number(raw.trim());
    if (Number.isFinite(num) && num > 0) {
      return num < 1e12 ? Math.round(num * 1000) : Math.round(num);
    }
    const parsed = Date.parse(raw.trim());
    return Number.isFinite(parsed) ? parsed : 0;
  }
  if (typeof raw === "object") {
    const obj = raw as Record<string, unknown>;
    if (typeof obj.toMillis === "function") {
      try {
        const ms = (obj.toMillis as () => number)();
        return Number.isFinite(ms) ? ms : 0;
      } catch {
        return 0;
      }
    }
    const secs = obj.seconds ?? obj._seconds;
    if (typeof secs === "number" && Number.isFinite(secs)) {
      return Math.round(secs * 1000);
    }
  }
  return 0;
}

/**
 * Checks whether a value inside an assignment field is a placeholder/empty value.
 * @param {string} val Raw assignment field value.
 * @return {boolean} True if unassigned.
 */
export function isPlaceholderOrUnassigned(val: string): boolean {
  const s = val.trim().toLowerCase();
  return !s || s === "null" || s === "undefined" || s === "none" || s === "n/a";
}

/**
 * Validates that a Firestore document under captains/{uid} belongs to an eligible captain
 * while preserving legacy accounts where `accountType` was absent and `role == "captain"`.
 * @param {Record<string, unknown> | undefined} data Captain document data.
 * @return {boolean} True when eligible captain identity.
 */
export function isEligibleCaptainIdentity(
  data: Record<string, unknown> | undefined
): boolean {
  if (!data) return false;
  const accountType = readString(data, "accountType").toLowerCase();
  const role = readString(data, "role").toLowerCase();
  if (accountType) {
    if (accountType !== "captain") return false;
    if (role && role !== "captain" && role !== "driver") return false;
    return true;
  }
  return role === "captain";
}

/**
 * Extracts latitude and longitude from flat or nested captain/merchant coordinate fields.
 * @param {Record<string, unknown> | undefined} data Document fields.
 * @return {{lat: number, lng: number} | null} Coordinates or null if missing/zero.
 */
export function extractCoordinates(
  data: Record<string, unknown> | undefined
): {lat: number; lng: number} | null {
  if (!data) return null;

  let lat = readNumber(
    data,
    "latitude",
    "lat",
    "currentLat",
    "pickupLat",
    "storeLat",
    "merchantLat",
    "restaurantLat"
  );
  let lng = readNumber(
    data,
    "longitude",
    "lng",
    "currentLng",
    "pickupLng",
    "storeLng",
    "merchantLng",
    "restaurantLng"
  );

  if (lat === null || lng === null) {
    for (const nestedKey of ["location", "coordinates", "coords"]) {
      const nested = data[nestedKey];
      if (nested && typeof nested === "object") {
        const obj = nested as Record<string, unknown>;
        const nLat = readNumber(obj, "latitude", "_latitude", "lat");
        const nLng = readNumber(obj, "longitude", "_longitude", "lng");
        if (nLat !== null && nLng !== null) {
          lat = nLat;
          lng = nLng;
          break;
        }
      }
    }
  }

  if (
    lat === null ||
    lng === null ||
    !Number.isFinite(lat) ||
    !Number.isFinite(lng) ||
    lat < -90 ||
    lat > 90 ||
    lng < -180 ||
    lng > 180 ||
    (lat === 0 && lng === 0)
  ) {
    return null;
  }

  return {lat, lng};
}

/**
 * Computes Haversine distance in kilometers between two coordinates.
 * @param {number} lat1 Origin latitude.
 * @param {number} lng1 Origin longitude.
 * @param {number} lat2 Destination latitude.
 * @param {number} lng2 Destination longitude.
 * @return {number} Distance in kilometers.
 */
export function haversineDistanceKm(
  lat1: number,
  lng1: number,
  lat2: number,
  lng2: number
): number {
  const toRad = (deg: number) => (deg * Math.PI) / 180;
  const R = 6371.0;
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(toRad(lat1)) *
      Math.cos(toRad(lat2)) *
      Math.sin(dLng / 2) *
      Math.sin(dLng / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return R * c;
}

/**
 * Validates captain account approval, online state, and fresh GPS coordinates.
 * @param {Record<string, unknown> | undefined} data Captain document data.
 * @param {number} nowMs Current epoch milliseconds.
 * @return {{lat: number, lng: number}} Verified captain coordinates.
 */
export function assertCaptainOperationalForOrder(
  data: Record<string, unknown> | undefined,
  nowMs: number
): {lat: number; lng: number} {
  if (!data || !isEligibleCaptainIdentity(data)) {
    throw new DomainHttpsError(
      "permission-denied",
      "هذا الحساب غير مسجل ككابتن في النظام."
    );
  }

  const status = readString(data, "status").toLowerCase();
  const isBlocked = data.isBlocked === true;
  const isSuspended = data.isSuspended === true;
  const canReceive = data.canReceiveOrders;
  const isReceiving = data.isReceivingOrders;
  const receiveOrders = data.receiveOrders;
  const receptionDisabled = data.orderReceptionDisabled === true;

  if (
    DISALLOWED_CAPTAIN_STATUSES.has(status) ||
    isBlocked ||
    isSuspended ||
    canReceive === false ||
    isReceiving === false ||
    receiveOrders === false ||
    receptionDisabled
  ) {
    throw new DomainHttpsError(
      "permission-denied",
      "حسابك غير مفعل أو موقوف حالياً، لا يمكنك قبول الطلبات."
    );
  }

  const presenceStatus = readString(data, "presenceStatus").toLowerCase();
  if (data.isOnline !== true || presenceStatus === "offline") {
    throw new DomainHttpsError(
      "failed-precondition",
      "لا يمكنك قبول أو استلام طلبات وأنت في وضع غير متصل."
    );
  }

  const coords = extractCoordinates(data);
  if (!coords) {
    throw new DomainHttpsError(
      "failed-precondition",
      "يرجى تفعيل الموقع (GPS) وإرسال إحداثيات صالحة قبل قبول الطلب."
    );
  }

  const lastLocMs = Math.max(
    parseTimestampMs(data.lastLocationTimestamp),
    parseTimestampMs(data.lastLocationUpdate)
  );
  if (lastLocMs <= 0 || nowMs - lastLocMs > MAX_LOCATION_AGE_MS) {
    throw new DomainHttpsError(
      "failed-precondition",
      "انتهت صلاحية آخر تحديث لموقعك (GPS). يرجى تحديث موقعك وإعادة المحاولة."
    );
  }

  return coords;
}

/**
 * Evaluates whether a captain document satisfies all non-shift dispatch criteria.
 * @param {Record<string, unknown> | undefined} data Captain document data.
 * @param {number} nowMs Current epoch milliseconds.
 * @return {boolean} True when candidate is online, active, and has fresh GPS.
 */
export function isCaptainCandidateBaseEligible(
  data: Record<string, unknown> | undefined,
  nowMs: number
): boolean {
  try {
    assertCaptainOperationalForOrder(data, nowMs);
    return true;
  } catch {
    return false;
  }
}

/**
 * Extracts normalized assignee UID from an order document.
 * @param {Record<string, unknown>} order Order document fields.
 * @return {string} Assigned captain UID or empty string.
 */
export function extractOrderCaptainUid(order: Record<string, unknown>): string {
  const raw = readString(
    order,
    "captainId",
    "captain_id",
    "driverId",
    "driver_id",
    "assignedCaptainId",
    "assignedDriverId",
    "captainUid",
    "driverUid"
  );
  return isPlaceholderOrUnassigned(raw) ? "" : raw;
}

/**
 * Extracts array of unique string IDs from an array field.
 * @param {unknown} raw Field value.
 * @return {string[]} Unique trimmed non-empty strings.
 */
export function readStringList(raw: unknown): string[] {
  if (!Array.isArray(raw)) return [];
  const seen = new Set<string>();
  for (const item of raw) {
    const s = typeof item === "string" ? item.trim() : "";
    if (s.length > 0) seen.add(s);
  }
  return Array.from(seen);
}

/**
 * Normalizes order status string safely.
 * @param {Record<string, unknown>} order Order document fields.
 * @return {string} Lowercase normalized status string.
 */
export function readNormalizedOrderStatus(order: Record<string, unknown>): string {
  return readString(order, "status", "order_status", "orderStatus", "state")
    .toLowerCase();
}

/**
 * Resolves effective normalized status accounting for merchantConfirmed flag.
 * @param {Record<string, unknown>} order Order document fields.
 * @return {string} Canonical lowercase status key.
 */
export function resolveEffectiveOrderStatus(order: Record<string, unknown>): string {
  const raw = readNormalizedOrderStatus(order) || "pending";
  const confirmed = isTruthyFlag(order.merchantConfirmed);
  if (raw === "awaiting_merchant_confirmation" && confirmed) {
    return "en_route";
  }
  return raw;
}

/**
 * Checks whether a dynamic Firestore value represents boolean true.
 * @param {unknown} val Candidate value.
 * @return {boolean} True when truthy.
 */
export function isTruthyFlag(val: unknown): boolean {
  if (val === true) return true;
  if (typeof val === "number") return val === 1;
  if (typeof val === "string") {
    const s = val.trim().toLowerCase();
    return s === "true" || s === "1" || s === "yes";
  }
  return false;
}

/**
 * Checks whether an order status is still open for captain acceptance.
 * @param {string} status Normalized status string.
 * @return {boolean} True when status is open/pending.
 */
export function isOrderStatusOpenForAccept(status: string): boolean {
  return !DISALLOWED_ORDER_STATUSES.has(status) && AVAILABLE_ORDER_STATUSES.has(status);
}

/**
 * Checks whether an order status counts as an active ongoing trip blocking concurrent orders.
 * @param {string} status Normalized status string.
 * @return {boolean} True if active trip.
 */
export function isOrderStatusBlockingActiveTrip(status: string): boolean {
  return ACTIVE_ORDER_STATUSES.has(status);
}

/**
 * Sanitizes and validates an incoming orderId parameter from callable payloads.
 * @param {unknown} raw Raw orderId payload value.
 * @return {string} Cleaned order document ID.
 */
export function requireValidOrderId(raw: unknown): string {
  if (typeof raw !== "string") {
    throw new DomainHttpsError("invalid-argument", "معرّف الطلب غير صالح.");
  }
  const clean = raw.trim().replace(/^#+/, "").trim();
  if (!clean || clean.length > 128 || clean.includes("/")) {
    throw new DomainHttpsError("invalid-argument", "معرّف الطلب غير صالح.");
  }
  return clean;
}

/**
 * Validates merchant account status and active non-expired subscription.
 * @param {string} merchantId Merchant document ID.
 * @param {Record<string, unknown> | undefined} merchantData Merchant document data.
 * @param {Record<string, unknown> | undefined} subData Subscription document data.
 * @param {number} nowMs Current epoch milliseconds.
 */
export function assertMerchantAndSubscriptionValid(
  merchantId: string,
  merchantData: Record<string, unknown> | undefined,
  subData: Record<string, unknown> | undefined,
  nowMs: number
): void {
  if (!merchantId) return;

  if (merchantData) {
    const mStatus = readString(merchantData, "status").toLowerCase();
    if (
      merchantData.isBlocked === true ||
      merchantData.isSuspended === true ||
      ["banned", "suspended", "disabled", "blocked", "inactive"].includes(mStatus)
    ) {
      throw new DomainHttpsError(
        "failed-precondition",
        "حساب التاجر موقوف حالياً ولا يمكن قبول هذا الطلب."
      );
    }

    const embeddedSubStatus = readString(
      merchantData,
      "subscriptionStatus"
    ).toLowerCase();
    if (
      merchantData.isSubscribed === false ||
      ["expired", "suspended", "cancelled", "canceled", "inactive"].includes(
        embeddedSubStatus
      )
    ) {
      throw new DomainHttpsError(
        "failed-precondition",
        "اشتراك التاجر منتهي أو غير نشط حالياً."
      );
    }

    const mSubEnd = Math.max(
      parseTimestampMs(merchantData.subscriptionEndDate),
      parseTimestampMs(merchantData.subscriptionExpiresAt)
    );
    if (mSubEnd > 0 && nowMs > endOfDayMs(mSubEnd)) {
      throw new DomainHttpsError(
        "failed-precondition",
        "انتهت صلاحية اشتراك التاجر ولا يمكن قبول الطلب."
      );
    }
  }

  if (subData) {
    const sStatus = readString(subData, "status").toLowerCase();
    if (
      sStatus &&
      !["active", "approved", "trial", "نشط", "ساري"].includes(sStatus)
    ) {
      throw new DomainHttpsError(
        "failed-precondition",
        "اشتراك التاجر غير مفعل حالياً."
      );
    }
    const endMs = Math.max(
      parseTimestampMs(subData.endDate),
      parseTimestampMs(subData.expiryDate),
      parseTimestampMs(subData.expiresAt),
      parseTimestampMs(subData.subscriptionEndDate)
    );
    if (endMs > 0 && nowMs > endOfDayMs(endMs)) {
      throw new DomainHttpsError(
        "failed-precondition",
        "انتهى اشتراك التاجر ولا يمكن إتمام هذا الطلب."
      );
    }
  }
}

function endOfDayMs(tsMs: number): number {
  const d = new Date(tsMs);
  // If parsed from YYYY-MM-DD (00:00:00 UTC), allow until end of that day UTC+2
  if (
    d.getUTCHours() === 0 &&
    d.getUTCMinutes() === 0 &&
    d.getUTCSeconds() === 0
  ) {
    return tsMs + 24 * 60 * 60 * 1000 - 1;
  }
  return tsMs;
}
