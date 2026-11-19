import {DatabaseLike} from "./types.js";

const CAIRO_TIME_ZONE = "Africa/Cairo";

const WEEKDAY_MAP: Record<string, string> = {
  sat: "saturday",
  sun: "sunday",
  mon: "monday",
  tue: "tuesday",
  wed: "wednesday",
  thu: "thursday",
  fri: "friday",
};

const WEEKDAY_ORDER = [
  "sunday",
  "monday",
  "tuesday",
  "wednesday",
  "thursday",
  "friday",
  "saturday",
];

export interface CairoTimeContext {
  dayKey: string;
  weekdayKey: string;
  prevWeekdayKey: string;
  minutesSinceMidnight: number;
}

interface ParsedShiftWindow {
  dayOfWeek?: number;
  dayKey?: string;
  isDayOff: boolean;
  startTime: string;
  endTime: string;
}

/**
 * Computes current Cairo date key, weekday key, and minutes since midnight.
 * @param {number} nowMs Epoch milliseconds.
 * @return {CairoTimeContext} Cairo time context.
 */
export function getCairoTimeContext(nowMs: number): CairoTimeContext {
  const formatter = new Intl.DateTimeFormat("en-US", {
    timeZone: CAIRO_TIME_ZONE,
    weekday: "short",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });

  const parts = formatter.formatToParts(new Date(nowMs));
  const lookup: Record<string, string> = {};
  for (const part of parts) {
    lookup[part.type] = part.value;
  }

  const rawWeekday = (lookup.weekday ?? "sun").slice(0, 3).toLowerCase();
  const weekdayKey = WEEKDAY_MAP[rawWeekday] ?? "sunday";
  const idx = WEEKDAY_ORDER.indexOf(weekdayKey);
  const prevIdx = (idx + 6) % 7;
  const prevWeekdayKey = WEEKDAY_ORDER[prevIdx];

  const year = lookup.year ?? "2026";
  const month = lookup.month ?? "01";
  const day = lookup.day ?? "01";
  const hourRaw = Number(lookup.hour ?? "0");
  const hour = hourRaw === 24 ? 0 : hourRaw;
  const minute = Number(lookup.minute ?? "0");

  return {
    dayKey: `${year}-${month}-${day}`,
    weekdayKey,
    prevWeekdayKey,
    minutesSinceMidnight: hour * 60 + minute,
  };
}

function parseMinutes(timeStr: unknown): number | null {
  if (typeof timeStr !== "string") return null;
  const match = timeStr.trim().match(/^(\d{1,2}):(\d{2})$/);
  if (!match) return null;
  const h = Number(match[1]);
  const m = Number(match[2]);
  if (!Number.isInteger(h) || !Number.isInteger(m)) return null;
  if (h < 0 || h > 23 || m < 0 || m > 59) return null;
  return h * 60 + m;
}

function mapDayNumberToKey(dayOfWeek: number): string {
  // Convention used across Android ShiftEntity: 1 = Saturday .. 7 = Friday
  const mapping: Record<number, string> = {
    1: "saturday",
    2: "sunday",
    3: "monday",
    4: "tuesday",
    5: "wednesday",
    6: "thursday",
    7: "friday",
  };
  return mapping[dayOfWeek] ?? "saturday";
}

function resolveDayKey(entry: Record<string, unknown>): string | null {
  if (typeof entry.dayKey === "string" && entry.dayKey.trim()) {
    return entry.dayKey.trim().toLowerCase();
  }
  const dayNameRaw = entry.dayName ?? entry.day ?? entry.weekday;
  if (typeof dayNameRaw === "string" && dayNameRaw.trim()) {
    const clean = dayNameRaw.trim().toLowerCase();
    for (const day of WEEKDAY_ORDER) {
      if (clean === day || clean.startsWith(day.slice(0, 3))) {
        return day;
      }
    }
  }
  const dayNum = Number(entry.dayOfWeek ?? entry.dayIndex ?? entry.dayNumber);
  if (Number.isInteger(dayNum) && dayNum >= 1 && dayNum <= 7) {
    return mapDayNumberToKey(dayNum);
  }
  return null;
}

function isShiftListActive(
  shiftsRaw: unknown[],
  currentDayKey: string,
  prevDayKey: string,
  currentMins: number
): boolean {
  const parsed: ParsedShiftWindow[] = [];
  for (const item of shiftsRaw) {
    if (!item || typeof item !== "object") continue;
    const obj = item as Record<string, unknown>;
    const dayKey = resolveDayKey(obj);
    const startTime = String(obj.startTime ?? obj.start ?? "09:00");
    const endTime = String(obj.endTime ?? obj.end ?? "17:00");
    const isDayOff = obj.isDayOff === true || obj.enabled === false;
    parsed.push({dayKey: dayKey ?? undefined, isDayOff, startTime, endTime});
  }

  // Check current day shifts
  for (const win of parsed) {
    if (win.dayKey && win.dayKey !== currentDayKey) continue;
    if (win.isDayOff) continue;
    const startM = parseMinutes(win.startTime);
    const endM = parseMinutes(win.endTime);
    if (startM === null || endM === null) continue;
    if (endM <= startM) {
      if (currentMins >= startM) return true;
    } else if (currentMins >= startM && currentMins < endM) {
      return true;
    }
  }

  // Check overnight spillover from yesterday's shifts
  for (const win of parsed) {
    if (win.dayKey !== prevDayKey) continue;
    if (win.isDayOff) continue;
    const startM = parseMinutes(win.startTime);
    const endM = parseMinutes(win.endTime);
    if (startM === null || endM === null) continue;
    if (endM <= startM && currentMins < endM) {
      return true;
    }
  }

  return false;
}

/**
 * Evaluates whether a schedule document places the captain inside an active
 * shift at the specified timestamp in Africa/Cairo timezone.
 * @param {Record<string, unknown>} scheduleDoc Schedule data document.
 * @param {number} nowMs Epoch milliseconds.
 * @return {{hasSchedule: boolean, isActive: boolean}} Evaluation outcome.
 */
export function evaluateScheduleDocAtTime(
  scheduleDoc: Record<string, unknown> | undefined,
  nowMs: number
): {hasSchedule: boolean; isActive: boolean} {
  if (!scheduleDoc) {
    return {hasSchedule: false, isActive: false};
  }

  if (scheduleDoc.isPaused === true) {
    return {hasSchedule: true, isActive: false};
  }

  const ctx = getCairoTimeContext(nowMs);
  const weekly = scheduleDoc.weeklySchedule;

  if (weekly && typeof weekly === "object" && !Array.isArray(weekly)) {
    const weekMap = weekly as Record<string, unknown>;
    const hasDays = Object.keys(weekMap).length > 0;
    if (hasDays) {
      // Check previous day overnight spillovers first
      const prevEntry = weekMap[ctx.prevWeekdayKey] as
        | Record<string, unknown>
        | undefined;
      if (prevEntry && prevEntry.isDayOff !== true && Array.isArray(prevEntry.shifts)) {
        for (const s of prevEntry.shifts) {
          if (!s || typeof s !== "object") continue;
          const slot = s as Record<string, unknown>;
          const startM = parseMinutes(slot.startTime ?? slot.start);
          const endM = parseMinutes(slot.endTime ?? slot.end);
          if (startM !== null && endM !== null && endM <= startM) {
            if (ctx.minutesSinceMidnight < endM) {
              return {hasSchedule: true, isActive: true};
            }
          }
        }
      }

      // Check current day shifts
      const currEntry = weekMap[ctx.weekdayKey] as
        | Record<string, unknown>
        | undefined;
      if (currEntry && currEntry.isDayOff !== true && Array.isArray(currEntry.shifts)) {
        for (const s of currEntry.shifts) {
          if (!s || typeof s !== "object") continue;
          const slot = s as Record<string, unknown>;
          const startM = parseMinutes(slot.startTime ?? slot.start);
          const endM = parseMinutes(slot.endTime ?? slot.end);
          if (startM === null || endM === null) continue;
          if (endM <= startM) {
            if (ctx.minutesSinceMidnight >= startM) {
              return {hasSchedule: true, isActive: true};
            }
          } else if (
            ctx.minutesSinceMidnight >= startM &&
            ctx.minutesSinceMidnight < endM
          ) {
            return {hasSchedule: true, isActive: true};
          }
        }
      }

      return {hasSchedule: true, isActive: false};
    }
  }

  const shiftsArray = Array.isArray(scheduleDoc.shifts)
    ? scheduleDoc.shifts
    : Array.isArray(scheduleDoc.schedule)
      ? scheduleDoc.schedule
      : null;

  if (shiftsArray && shiftsArray.length > 0) {
    return {
      hasSchedule: true,
      isActive: isShiftListActive(
        shiftsArray,
        ctx.weekdayKey,
        ctx.prevWeekdayKey,
        ctx.minutesSinceMidnight
      ),
    };
  }

  return {hasSchedule: false, isActive: false};
}

/**
 * Checks whether the captain is currently inside an active shift using
 * captain_schedules/{uid}, embedded captain doc schedule, or subcollections.
 * @param {DatabaseLike} db Firestore database interface.
 * @param {string} captainUid Captain Firebase UID.
 * @param {Record<string, unknown>} captainData Captain document fields.
 * @param {number} nowMs Current epoch milliseconds.
 * @return {Promise<boolean>} True if captain has an active shift right now.
 */
export async function isCaptainInActiveShift(
  db: DatabaseLike,
  captainUid: string,
  captainData: Record<string, unknown>,
  nowMs: number
): Promise<boolean> {
  // 1. Primary source: captain_schedules/{uid}
  const directSchedule = await db
    .collection("captain_schedules")
    .doc(captainUid)
    .get();
  if (directSchedule.exists) {
    const evalRes = evaluateScheduleDocAtTime(directSchedule.data(), nowMs);
    if (evalRes.hasSchedule) {
      return evalRes.isActive;
    }
    // Check subcollection captain_schedules/{uid}/shifts
    const subSnap = await db
      .collection("captain_schedules")
      .doc(captainUid)
      .collection("shifts")
      .get();
    if (!subSnap.empty) {
      const ctx = getCairoTimeContext(nowMs);
      return isShiftListActive(
        subSnap.docs.map((d) => d.data() ?? {}),
        ctx.weekdayKey,
        ctx.prevWeekdayKey,
        ctx.minutesSinceMidnight
      );
    }
  }

  // 2. Query captain_schedules where captainId == uid
  const querySnap = await db
    .collection("captain_schedules")
    .where("captainId", "==", captainUid)
    .limit(5)
    .get();
  if (!querySnap.empty) {
    for (const doc of querySnap.docs) {
      const evalRes = evaluateScheduleDocAtTime(doc.data(), nowMs);
      if (evalRes.hasSchedule) {
        if (evalRes.isActive) return true;
        return false;
      }
    }
  }

  // 3. Fallback: embedded schedule inside captains/{uid}
  const embeddedEval = evaluateScheduleDocAtTime(captainData, nowMs);
  if (embeddedEval.hasSchedule) {
    return embeddedEval.isActive;
  }

  // No active schedule configured -> captain cannot receive/accept orders
  return false;
}
