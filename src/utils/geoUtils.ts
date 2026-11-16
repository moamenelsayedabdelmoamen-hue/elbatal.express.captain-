/**
 * Utilities for geolocation calculations and coordinate handling
 * for El Batal Express Live Map dashboard.
 */

export interface Coordinates {
  lat: number;
  lng: number;
}

function isValidCoordPair(lat?: number, lng?: number): boolean {
  return (
    lat !== undefined &&
    lng !== undefined &&
    !isNaN(lat) &&
    !isNaN(lng) &&
    !(lat === 0 && lng === 0) &&
    lat >= -90 &&
    lat <= 90 &&
    lng >= -180 &&
    lng <= 180
  );
}

/**
 * Extracts latitude and longitude from a Google Maps / OpenStreetMap URL or plain "lat, lng" string.
 */
export function extractCoordinatesFromText(input?: string | null): Coordinates | null {
  if (!input || typeof input !== 'string') return null;
  const trimmed = input.trim();
  if (!trimmed) return null;

  let decoded = trimmed;
  try {
    decoded = decodeURIComponent(trimmed);
  } catch {
    decoded = trimmed;
  }

  const patterns: RegExp[] = [
    // Google Maps embed / place URLs: !3d30.0444!4d31.2357
    /!3d(-?\d{1,2}\.\d+)!4d(-?\d{1,3}\.\d+)/i,
    // Google Maps @lat,lng
    /@(-?\d{1,2}\.\d+),\s*(-?\d{1,3}\.\d+)/,
    // Query params: q=lat,lng | query=lat,lng | ll=lat,lng | center=lat,lng | destination=lat,lng | sll=lat,lng
    /[?&](?:q|query|ll|sll|center|destination|daddr|origin)=(?:loc:)?(-?\d{1,2}\.\d+)[,\s+]+(-?\d{1,3}\.\d+)/i,
    // Path segment: /search/lat,lng or /dir//lat,lng or /place/lat,lng
    /\/(?:search|place|dir\/[^/]*)\/(-?\d{1,2}\.\d+),\s*(-?\d{1,3}\.\d+)/i,
    // OpenStreetMap query params: mlat=..&mlon=..
    /[?&]mlat=(-?\d{1,2}\.\d+)&mlon=(-?\d{1,3}\.\d+)/i,
    // OpenStreetMap hash: #map=zoom/lat/lng
    /#map=\d+\/(-?\d{1,2}\.\d+)\/(-?\d{1,3}\.\d+)/i,
    // geo:lat,lng
    /^geo:(-?\d{1,2}\.\d+),\s*(-?\d{1,3}\.\d+)/i,
    // Plain "lat, lng"
    /^\s*(-?\d{1,2}\.\d+)\s*[,;\s]\s*(-?\d{1,3}\.\d+)\s*$/,
  ];

  for (const regex of patterns) {
    const match = decoded.match(regex);
    if (match) {
      const lat = parseFloat(match[1]);
      const lng = parseFloat(match[2]);
      if (isValidCoordPair(lat, lng)) {
        return { lat, lng };
      }
    }
  }

  return null;
}

/**
 * Extracts a merchant / location URL from various Firestore fields.
 * If no explicit URL exists, optionally generates a Google Maps URL from valid coordinates.
 */
export function extractLocationUrl(raw: any, fallbackCoords?: Coordinates | null): string {
  if (raw && typeof raw === 'object') {
    const candidateFields = [
      raw.locationUrl,
      raw.location_url,
      raw.locationLink,
      raw.location_link,
      raw.mapUrl,
      raw.map_url,
      raw.mapLink,
      raw.map_link,
      raw.googleMapsUrl,
      raw.google_maps_url,
      raw.googleMapUrl,
      raw.google_map_url,
      raw.mapsUrl,
      raw.maps_url,
      raw.mapsLink,
      raw.maps_link,
      raw.gpsUrl,
      raw.gps_url,
      raw.branchLocationUrl,
      raw.coordinatesUrl,
      raw.addressLink,
      raw.addressUrl,
      typeof raw.location === 'string' ? raw.location : undefined,
      typeof raw.address === 'string' && raw.address.trim().startsWith('http') ? raw.address : undefined,
    ];

    for (const val of candidateFields) {
      if (typeof val === 'string') {
        const s = val.trim();
        if (s && (s.startsWith('http://') || s.startsWith('https://') || s.startsWith('geo:'))) {
          return s;
        }
      }
    }
  }

  if (fallbackCoords && isValidCoordPair(fallbackCoords.lat, fallbackCoords.lng)) {
    return `https://www.google.com/maps?q=${fallbackCoords.lat},${fallbackCoords.lng}`;
  }

  return '';
}

/**
 * Extracts valid latitude and longitude from various Firestore data structures.
 * Supports:
 * - Firestore GeoPoint ({ latitude, longitude } or { _lat, _long })
 * - Nested objects: item.location, item.coordinates, item.coords, item.position
 * - Top level: item.lat & item.lng, item.latitude & item.longitude (numbers or numeric strings)
 * - Google Maps / OpenStreetMap URLs or "lat, lng" strings in locationUrl / mapUrl / location
 */
export function extractCoordinates(raw: any): Coordinates | null {
  if (!raw) return null;

  if (typeof raw === 'string') {
    return extractCoordinatesFromText(raw);
  }

  // Direct target or nested location
  const loc = raw.location || raw.coordinates || raw.coords || raw.position || raw.geo || raw;

  let lat: number | undefined;
  let lng: number | undefined;

  if (typeof loc === 'string') {
    const parsedFromLocStr = extractCoordinatesFromText(loc);
    if (parsedFromLocStr) return parsedFromLocStr;
  }

  // Firestore GeoPoint instance or duck-typing
  if (loc && typeof loc === 'object') {
    if (loc.latitude !== undefined && loc.longitude !== undefined) {
      lat = Number(loc.latitude);
      lng = Number(loc.longitude);
    } else if (loc.lat !== undefined && loc.lng !== undefined) {
      lat = Number(loc.lat);
      lng = Number(loc.lng);
    } else if (loc._lat !== undefined && loc._long !== undefined) {
      lat = Number(loc._lat);
      lng = Number(loc._long);
    } else if (Array.isArray(loc) && loc.length >= 2) {
      lat = Number(loc[0]);
      lng = Number(loc[1]);
    }
  }

  // Fallback to top-level latitude/longitude
  if (!isValidCoordPair(lat, lng)) {
    if (raw.lat !== undefined && raw.lng !== undefined) {
      lat = Number(raw.lat);
      lng = Number(raw.lng);
    } else if (raw.latitude !== undefined && raw.longitude !== undefined) {
      lat = Number(raw.latitude);
      lng = Number(raw.longitude);
    }
  }

  if (isValidCoordPair(lat, lng)) {
    return { lat: lat!, lng: lng! };
  }

  // Fallback to parsing coordinates from location URL / map link fields
  const urlCandidates = [
    raw.locationUrl,
    raw.location_url,
    raw.locationLink,
    raw.location_link,
    raw.mapUrl,
    raw.map_url,
    raw.mapLink,
    raw.map_link,
    raw.googleMapsUrl,
    raw.google_maps_url,
    raw.googleMapUrl,
    raw.google_map_url,
    raw.mapsUrl,
    raw.maps_url,
    raw.mapsLink,
    raw.maps_link,
    raw.gpsUrl,
    raw.gps_url,
    raw.branchLocationUrl,
    raw.coordinatesUrl,
    raw.addressLink,
    raw.addressUrl,
    raw.address,
  ];

  for (const candidate of urlCandidates) {
    if (typeof candidate === 'string') {
      const parsed = extractCoordinatesFromText(candidate);
      if (parsed) return parsed;
    }
  }

  return null;
}

/**
 * Calculates straight-line geographical distance between two points in kilometers
 * using the Haversine formula.
 */
export function haversineDistanceKm(
  lat1: number,
  lon1: number,
  lat2: number,
  lon2: number
): number {
  const R = 6371; // Earth's radius in kilometers
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLon = ((lon2 - lon1) * Math.PI) / 180;

  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos((lat1 * Math.PI) / 180) *
      Math.cos((lat2 * Math.PI) / 180) *
      Math.sin(dLon / 2) *
      Math.sin(dLon / 2);

  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  const d = R * c;

  return Math.round(d * 100) / 100; // 2 decimal places
}

/**
 * Formats a distance in kilometers to a human-friendly Arabic string.
 * Example: 0.8 كم or 850 م
 */
export function formatDistanceArabic(km: number): string {
  if (km < 1) {
    const meters = Math.round(km * 1000);
    return `${meters} م`;
  }
  return `${km.toFixed(1)} كم`;
}

/**
 * Evaluates whether a location timestamp is fresh based on a threshold (default: 3 minutes).
 */
export function isLocationFresh(
  rawTimestamp: any,
  thresholdMinutes: number = 3
): {
  isFresh: boolean;
  minutesAgo: number;
  formattedTime: string;
} {
  if (!rawTimestamp) {
    return {
      isFresh: false,
      minutesAgo: 9999,
      formattedTime: 'غير متوفر',
    };
  }

  let date: Date;

  if (typeof rawTimestamp === 'object' && typeof rawTimestamp.toDate === 'function') {
    date = rawTimestamp.toDate();
  } else if (rawTimestamp instanceof Date) {
    date = rawTimestamp;
  } else if (typeof rawTimestamp === 'number') {
    const ms = rawTimestamp < 10000000000 ? rawTimestamp * 1000 : rawTimestamp;
    date = new Date(ms);
  } else if (typeof rawTimestamp === 'string') {
    date = new Date(rawTimestamp);
  } else {
    return {
      isFresh: false,
      minutesAgo: 9999,
      formattedTime: 'غير معروف',
    };
  }

  const now = Date.now();
  const diffMs = Math.max(0, now - date.getTime());
  const minutesAgo = Math.floor(diffMs / 60000);
  const secondsAgo = Math.floor(diffMs / 1000);

  let formattedTime = '';
  if (secondsAgo < 60) {
    formattedTime = 'منذ ثوانٍ';
  } else if (minutesAgo < 60) {
    formattedTime = `منذ ${minutesAgo} دقيقة`;
  } else {
    const hoursAgo = Math.floor(minutesAgo / 60);
    formattedTime = `منذ ${hoursAgo} ساعة`;
  }

  return {
    isFresh: minutesAgo <= thresholdMinutes,
    minutesAgo,
    formattedTime,
  };
}
