export type CallableErrorCode =
  | "unauthenticated"
  | "permission-denied"
  | "not-found"
  | "failed-precondition"
  | "already-exists"
  | "invalid-argument"
  | "internal";

/**
 * Domain error mapped directly to Firebase Functions HttpsError codes.
 */
export class DomainHttpsError extends Error {
  public readonly code: CallableErrorCode;

  /**
   * Constructs a structured callable domain error.
   * @param {CallableErrorCode} code Firebase callable error code.
   * @param {string} message Localized Arabic error message.
   */
  constructor(code: CallableErrorCode, message: string) {
    super(message);
    this.name = "DomainHttpsError";
    this.code = code;
  }
}

export type SentinelValue =
  | {readonly __sentinel: "serverTimestamp"}
  | {readonly __sentinel: "deleteField"}
  | {readonly __sentinel: "increment"; readonly operand: number}
  | {readonly __sentinel: "arrayUnion"; readonly elements: unknown[]};

export interface SentinelFactory {
  serverTimestamp(): unknown;
  deleteField(): unknown;
  increment(value: number): unknown;
  arrayUnion(...elements: unknown[]): unknown;
}

export interface DocSnapshotLike {
  readonly id: string;
  readonly exists: boolean;
  readonly ref: DocRefLike;
  data(): Record<string, unknown> | undefined;
}

export interface DocRefLike {
  readonly id: string;
  readonly path: string;
  collection(collectionPath: string): CollectionRefLike;
  get(): Promise<DocSnapshotLike>;
  set(data: Record<string, unknown>, options?: {merge?: boolean}): Promise<void>;
  update(data: Record<string, unknown>): Promise<void>;
}

export interface QuerySnapshotLike {
  readonly docs: DocSnapshotLike[];
  readonly empty: boolean;
  readonly size: number;
}

export interface QueryLike {
  where(
    fieldPath: string,
    opStr: "==" | "array-contains" | "in",
    value: unknown
  ): QueryLike;
  limit(limitCount: number): QueryLike;
  get(): Promise<QuerySnapshotLike>;
}

export interface CollectionRefLike extends QueryLike {
  doc(docId?: string): DocRefLike;
  add(data: Record<string, unknown>): Promise<DocRefLike>;
}

export interface TransactionLike {
  get(ref: DocRefLike): Promise<DocSnapshotLike>;
  getQuery(query: QueryLike): Promise<QuerySnapshotLike>;
  set(
    ref: DocRefLike,
    data: Record<string, unknown>,
    options?: {merge?: boolean}
  ): void;
  update(ref: DocRefLike, data: Record<string, unknown>): void;
}

export interface DatabaseLike {
  collection(collectionPath: string): CollectionRefLike;
  runTransaction<T>(fn: (tx: TransactionLike) => Promise<T>): Promise<T>;
}

export interface OfferFcmPayload {
  captainUid: string;
  fcmToken: string;
  orderId: string;
  orderNumber: string;
  orderCode: string;
  storeName: string;
  storePhone: string;
  storeImage: string;
  storeLocationUrl: string;
  pickupAddress: string;
  deliveryAddress: string;
  deliveryFee: number;
  distanceKm: number;
  offerExpiresAtMs: number;
  itemsSummary: string;
  itemsCount: number;
  orderTotalAmount: number;
  customerName: string;
  customerPhone: string;
  offerGeneration: number;
}

export interface ReassignTaskPayload {
  orderId: string;
  expectedCaptainId: string;
  expectedOfferGeneration: number;
}

export interface ServerEffectsPort {
  now(): number;
  sentinels: SentinelFactory;
  sendOfferNotification(payload: OfferFcmPayload): Promise<void>;
  enqueueReassignTask(
    payload: ReassignTaskPayload,
    delaySeconds: number
  ): Promise<void>;
}

export interface CaptainCandidate {
  uid: string;
  name: string;
  phone: string;
  fcmToken: string;
  lat: number;
  lng: number;
  distanceKm: number;
}
