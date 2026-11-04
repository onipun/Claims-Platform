import type { ClaimType, Line, Policy, SubmitRequest } from './claimant.types';
import { CLAIM_TYPES_BY_LINE } from './claimant.labels';

/** The flat value of the report-a-claim form. Everything is a string or boolean, as typed by the user. */
export interface ClaimFormValue {
  claimType: ClaimType | '';
  incidentDate: string; // yyyy-MM-dd
  incidentTime: string; // HH:mm
  description: string;
  contactPhone: string;
  estimate: string;
  lateReason: string;
  vehicleRegistration: string;
  driverName: string;
  location: string;
  hasThirdParty: boolean;
  tpName: string;
  tpPhone: string;
  tpInsurer: string;
  tpVehicle: string;
  injury: boolean;
  injuryDescription: string;
  policeReport: string;
  address: string;
  cause: string;
  occupancy: string;
}

export const EMPTY_CLAIM_FORM: ClaimFormValue = {
  claimType: '', incidentDate: '', incidentTime: '', description: '', contactPhone: '', estimate: '', lateReason: '',
  vehicleRegistration: '', driverName: '', location: '', hasThirdParty: false, tpName: '', tpPhone: '', tpInsurer: '', tpVehicle: '',
  injury: false, injuryDescription: '', policeReport: '', address: '', cause: '', occupancy: '',
};

export type FieldErrors = Partial<Record<keyof ClaimFormValue, string>>;

/** Server-side limits (SubmitClaimRequest, MotorDetails, PropertyDetails). */
const MAX = {
  description: 4000, contactPhone: 32, lateReason: 1000, vehicleRegistration: 32, driverName: 120, location: 200, tpName: 120,
  tpPhone: 32, tpInsurer: 120, tpVehicle: 32, injuryDescription: 2000, policeReport: 64, address: 500, cause: 200,
  occupancy: 120,
} as const;

const ZERO_DECIMAL = new Set(['IDR', 'JPY', 'KRW', 'VND']);
/** A device clock can differ from the market's by up to 14 hours; the server applies the exact market-zone rule. */
const FUTURE_TOLERANCE_MS = 14 * 3600 * 1000;

export const isThirdPartyShown = (v: Pick<ClaimFormValue, 'claimType' | 'hasThirdParty'>): boolean =>
  v.claimType === 'THIRD_PARTY_DAMAGE' || v.hasThirdParty;

/**
 * Mirrors the submission rules of docs/domain/LIFECYCLE.md on the client so the user sees problems before the round trip.
 * The server remains the authority and its field errors are shown too. `now` is injectable for tests.
 */
export function validateClaim(v: ClaimFormValue, policy: Policy | null, now: Date = new Date()): FieldErrors {
  const e: FieldErrors = {};
  const line: Line | undefined = policy?.line;

  if (!v.claimType) e.claimType = 'Choose what happened.';
  else if (line && !CLAIM_TYPES_BY_LINE[line].includes(v.claimType)) e.claimType = 'This type of claim is not available for this policy.';

  if (!v.incidentDate) e.incidentDate = 'Enter the date of the incident.';
  if (!v.incidentTime) e.incidentTime = 'Enter the time of the incident.';
  if (v.incidentDate && v.incidentTime) {
    const when = new Date(`${v.incidentDate}T${v.incidentTime}:00`);
    if (Number.isNaN(when.getTime())) e.incidentDate = 'Enter a valid date and time.';
    else if (when.getTime() > now.getTime() + FUTURE_TOLERANCE_MS) e.incidentDate = 'The incident cannot be in the future.';
  }
  if (v.incidentDate && !e.incidentDate && policy) {
    if (policy.effectiveFrom && v.incidentDate < policy.effectiveFrom) e.incidentDate = `This is before your policy started on ${policy.effectiveFrom}.`;
    else if (policy.effectiveTo && v.incidentDate > policy.effectiveTo) e.incidentDate = `This is after your policy ended on ${policy.effectiveTo}.`;
  }

  const est = v.estimate.trim();
  const scale = ZERO_DECIMAL.has((policy?.currency ?? '').toUpperCase()) ? 0 : 2;
  if (!est) e.estimate = 'Enter your estimate of the loss. Enter 0 if you do not know.';
  else if (!new RegExp(`^\\d{1,15}${scale ? `(\\.\\d{1,${scale}})?` : ''}$`).test(est)) {
    e.estimate = scale ? 'Enter an amount in numbers, with at most 2 decimals, such as 1250.50.' : 'Enter a whole amount in numbers.';
  }

  const max = (k: keyof typeof MAX, label: string) => {
    if (v[k].length > MAX[k]) e[k] = `${label} must be at most ${MAX[k]} characters.`;
  };
  max('description', 'The description');
  max('contactPhone', 'The phone number');
  max('lateReason', 'The reason');

  if (line === 'MOTOR') {
    if (!v.vehicleRegistration.trim()) e.vehicleRegistration = 'Enter the vehicle registration number.';
    else max('vehicleRegistration', 'The registration number');
    if (!v.driverName.trim()) e.driverName = 'Enter the name of the driver.';
    else max('driverName', 'The name');
    if (!v.location.trim()) e.location = 'Enter where the accident happened.';
    else max('location', 'The location');
    max('policeReport', 'The police report reference');
    if (v.injury) max('injuryDescription', 'The description');
    if (isThirdPartyShown(v)) {
      max('tpName', 'The name');
      max('tpPhone', 'The phone number');
      max('tpInsurer', 'The insurer');
      max('tpVehicle', 'The vehicle number');
    }
  } else if (line === 'PROPERTY') {
    if (!v.address.trim()) e.address = 'Enter the address of the loss.';
    else max('address', 'The address');
    if (!v.cause.trim()) e.cause = 'Enter the cause of the loss.';
    else max('cause', 'The cause');
    max('occupancy', 'The occupancy');
  }
  return e;
}

const opt = (s: string): string | undefined => {
  const t = s.trim();
  return t ? t : undefined;
};

/** Builds the API body from a validated form. Only the details for the policy's line are sent. */
export function buildRequest(v: ClaimFormValue, policy: Policy): SubmitRequest {
  const line = policy.line as Line;
  const body: SubmitRequest = {
    policyId: policy.id as string,
    line,
    claimType: v.claimType as ClaimType,
    incidentAtLocal: `${v.incidentDate}T${v.incidentTime}:00`,
    estimate: v.estimate.trim(),
    contactPhone: opt(v.contactPhone),
    description: opt(v.description),
    lateReason: opt(v.lateReason),
  };
  if (line === 'MOTOR') {
    const tp = isThirdPartyShown(v)
      ? { name: opt(v.tpName), phone: opt(v.tpPhone), insurer: opt(v.tpInsurer), vehicleNumber: opt(v.tpVehicle) }
      : undefined;
    body.motor = {
      vehicleRegistration: v.vehicleRegistration.trim(),
      driverName: v.driverName.trim(),
      location: v.location.trim(),
      thirdParty: tp && Object.values(tp).some((x) => x !== undefined) ? tp : undefined,
      injury: v.injury,
      injuryDescription: v.injury ? opt(v.injuryDescription) : undefined,
      policeReport: opt(v.policeReport),
    };
  } else {
    body.property = { address: v.address.trim(), cause: v.cause.trim(), occupancy: opt(v.occupancy) };
  }
  return body;
}

const SERVER_FIELD: Record<string, keyof ClaimFormValue> = {
  claimType: 'claimType', incidentAtLocal: 'incidentDate', estimate: 'estimate', description: 'description',
  contactPhone: 'contactPhone', lateReason: 'lateReason', vehicleRegistration: 'vehicleRegistration',
  driverName: 'driverName', location: 'location', policeReport: 'policeReport', injuryDescription: 'injuryDescription', address: 'address',
  cause: 'cause', occupancy: 'occupancy', 'thirdParty.name': 'tpName', 'thirdParty.phone': 'tpPhone',
  'thirdParty.insurer': 'tpInsurer', 'thirdParty.vehicleNumber': 'tpVehicle',
};

/** Maps a server field path (`motor.thirdParty.name`, `lossDetails.cause`, `incidentAtLocal`) to a form control, or null. */
export function serverFieldToControl(field: string): keyof ClaimFormValue | null {
  const bare = field.replace(/^(motor|property|lossDetails)\./, '');
  return SERVER_FIELD[bare] ?? null;
}

/** Order the fields appear in the form, so the first error can be focused. */
export const FIELD_ORDER: readonly (keyof ClaimFormValue)[] = [
  'claimType', 'incidentDate', 'incidentTime', 'vehicleRegistration', 'driverName', 'location', 'address', 'cause', 'estimate',
];

/**
 * One Idempotency-Key per form attempt. The key is kept for as long as the request body is unchanged, so a retry
 * after a timeout or a double-click re-sends the same key and cannot create a second claim. An edit changes the
 * body, which takes a new key (the server rejects a reused key with a different body); a success clears it.
 */
export class IdempotencyGuard {
  private key: string | null = null;
  private fingerprint: string | null = null;

  constructor(private readonly generate: () => string = newKey) {}

  keyFor(body: unknown): string {
    const fp = JSON.stringify(body);
    if (this.key === null || fp !== this.fingerprint) {
      this.key = this.generate();
      this.fingerprint = fp;
    }
    return this.key;
  }

  /** After success, or when the server says the key was used for a different body. */
  reset(): void {
    this.key = null;
    this.fingerprint = null;
  }
}

export function newKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID();
  return `k-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}
