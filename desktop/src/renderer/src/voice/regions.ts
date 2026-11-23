// Cloudflare data centres by IATA code, for "Voice connected · Mumbai · 28 ms". The relay tells us
// where the voice chat runs (hello.server) and which data centre we came in through (hello.edge).
// Only the places voice is likely to run in or pass through for our people; anything else shows its code.

const CITIES: Record<string, string> = {
  // India
  BOM: 'Mumbai',
  DEL: 'New Delhi',
  MAA: 'Chennai',
  BLR: 'Bengaluru',
  HYD: 'Hyderabad',
  CCU: 'Kolkata',
  AMD: 'Ahmedabad',
  COK: 'Kochi',
  NAG: 'Nagpur',
  PAT: 'Patna',
  IXC: 'Chandigarh',
  BBI: 'Bhubaneswar',
  // Nearby
  CMB: 'Colombo',
  DAC: 'Dhaka',
  KTM: 'Kathmandu',
  KHI: 'Karachi',
  DXB: 'Dubai',
  MCT: 'Muscat',
  DOH: 'Doha',
  // Rest of Asia and Oceania
  SIN: 'Singapore',
  KUL: 'Kuala Lumpur',
  BKK: 'Bangkok',
  CGK: 'Jakarta',
  MNL: 'Manila',
  HKG: 'Hong Kong',
  TPE: 'Taipei',
  NRT: 'Tokyo',
  KIX: 'Osaka',
  ICN: 'Seoul',
  SYD: 'Sydney',
  MEL: 'Melbourne',
  // Europe and the Americas
  LHR: 'London',
  AMS: 'Amsterdam',
  FRA: 'Frankfurt',
  CDG: 'Paris',
  MAD: 'Madrid',
  ARN: 'Stockholm',
  IAD: 'Ashburn',
  EWR: 'Newark',
  ORD: 'Chicago',
  DFW: 'Dallas',
  LAX: 'Los Angeles',
  SJC: 'San Jose',
  SEA: 'Seattle',
  YYZ: 'Toronto',
  GRU: 'São Paulo'
}

/** "Mumbai" for BOM; the code itself when we don't know it; null for null. */
export function cityOf(colo: string | null): string | null {
  if (!colo) return null
  return CITIES[colo] ?? colo
}

/** Discord-style signal: under 80 ms is good, under 200 ms is fine, above that is poor. */
export function pingQuality(ms: number | null): 'good' | 'ok' | 'poor' | null {
  if (ms === null) return null
  return ms < 80 ? 'good' : ms < 200 ? 'ok' : 'poor'
}

/**
 * The last few round trips, smoothed so the number doesn't jump around: the median of up to the
 * last five samples, rounded to whole milliseconds.
 */
export function smoothPing(samples: readonly number[]): number | null {
  const recent = samples.slice(-5).filter((n) => Number.isFinite(n) && n >= 0)
  if (recent.length === 0) return null
  const sorted = [...recent].sort((a, b) => a - b)
  return Math.round(sorted[Math.floor(sorted.length / 2)])
}
