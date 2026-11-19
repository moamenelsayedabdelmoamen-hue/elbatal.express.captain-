import {
  CollectionReference,
  DocumentReference,
  FieldValue,
  Firestore,
  Query,
} from "firebase-admin/firestore";
import {getFunctions} from "firebase-admin/functions";
import {getMessaging} from "firebase-admin/messaging";
import * as logger from "firebase-functions/logger";
import {
  CollectionRefLike,
  DatabaseLike,
  DocRefLike,
  DocSnapshotLike,
  OfferFcmPayload,
  QueryLike,
  QuerySnapshotLike,
  ReassignTaskPayload,
  ServerEffectsPort,
  TransactionLike,
} from "./types.js";

function wrapDocRef(ref: DocumentReference): DocRefLike {
  return {
    get id() {
      return ref.id;
    },
    get path() {
      return ref.path;
    },
    collection(collectionPath: string) {
      return wrapCollectionRef(ref.collection(collectionPath));
    },
    async get(): Promise<DocSnapshotLike> {
      const snap = await ref.get();
      return {
        id: snap.id,
        exists: snap.exists,
        ref: wrapDocRef(snap.ref),
        data: () => snap.data() as Record<string, unknown> | undefined,
      };
    },
    async set(data: Record<string, unknown>, options?: {merge?: boolean}) {
      await ref.set(data, options ? {merge: Boolean(options.merge)} : {});
    },
    async update(data: Record<string, unknown>) {
      await ref.update(data);
    },
  };
}

function wrapQuery(q: Query): QueryLike {
  const wrapper: QueryLike & {__rawQuery?: Query} = {
    __rawQuery: q,
    where(
      fieldPath: string,
      opStr: "==" | "array-contains" | "in",
      value: unknown
    ) {
      return wrapQuery(q.where(fieldPath, opStr, value));
    },
    limit(limitCount: number) {
      return wrapQuery(q.limit(limitCount));
    },
    async get(): Promise<QuerySnapshotLike> {
      const snap = await q.get();
      return {
        empty: snap.empty,
        size: snap.size,
        docs: snap.docs.map((d) => ({
          id: d.id,
          exists: d.exists,
          ref: wrapDocRef(d.ref),
          data: () => d.data() as Record<string, unknown> | undefined,
        })),
      };
    },
  };
  return wrapper;
}

function wrapCollectionRef(col: CollectionReference): CollectionRefLike {
  const baseQuery = wrapQuery(col);
  return {
    ...baseQuery,
    doc(docId?: string): DocRefLike {
      return wrapDocRef(docId ? col.doc(docId) : col.doc());
    },
    async add(data: Record<string, unknown>): Promise<DocRefLike> {
      const added = await col.add(data);
      return wrapDocRef(added);
    },
  };
}

function unwrapDocRef(ref: DocRefLike, firestore: Firestore): DocumentReference {
  return firestore.doc(ref.path);
}

/**
 * Wraps the real Firebase Admin Firestore instance in the DatabaseLike interface.
 * @param {Firestore} firestore Admin SDK Firestore instance.
 * @return {DatabaseLike} DatabaseLike adapter.
 */
export function createAdminDatabaseAdapter(firestore: Firestore): DatabaseLike {
  return {
    collection(collectionPath: string): CollectionRefLike {
      return wrapCollectionRef(firestore.collection(collectionPath));
    },
    async runTransaction<T>(fn: (tx: TransactionLike) => Promise<T>): Promise<T> {
      return firestore.runTransaction(async (adminTx) => {
        const txAdapter: TransactionLike = {
          async get(ref: DocRefLike): Promise<DocSnapshotLike> {
            const adminRef = unwrapDocRef(ref, firestore);
            const snap = await adminTx.get(adminRef);
            return {
              id: snap.id,
              exists: snap.exists,
              ref: wrapDocRef(snap.ref),
              data: () => snap.data() as Record<string, unknown> | undefined,
            };
          },
          async getQuery(q: QueryLike): Promise<QuerySnapshotLike> {
            // Execute underlying query via tx by re-building on original Query not exposed;
            // keep safe cast through internal symbol or fallback query execution.
            const adminQuery = (q as unknown as {__rawQuery?: Query}).__rawQuery;
            const snap = adminQuery ? await adminTx.get(adminQuery) : null;
            if (snap) {
              return {
                empty: snap.empty,
                size: snap.size,
                docs: snap.docs.map((d) => ({
                  id: d.id,
                  exists: d.exists,
                  ref: wrapDocRef(d.ref),
                  data: () => d.data() as Record<string, unknown> | undefined,
                })),
              };
            }
            return q.get();
          },
          set(ref: DocRefLike, data: Record<string, unknown>, options?: {merge?: boolean}) {
            const adminRef = unwrapDocRef(ref, firestore);
            adminTx.set(adminRef, data, options ? {merge: Boolean(options.merge)} : {});
          },
          update(ref: DocRefLike, data: Record<string, unknown>) {
            const adminRef = unwrapDocRef(ref, firestore);
            adminTx.update(adminRef, data);
          },
        };
        return fn(txAdapter);
      });
    },
  };
}

/**
 * Production ServerEffectsPort backed by Admin FieldValue, FCM, and Cloud Tasks.
 * @return {ServerEffectsPort} Production effects implementation.
 */
export function createAdminServerEffects(): ServerEffectsPort {
  return {
    now: () => Date.now(),
    sentinels: {
      serverTimestamp: () => FieldValue.serverTimestamp(),
      deleteField: () => FieldValue.delete(),
      increment: (value: number) => FieldValue.increment(value),
      arrayUnion: (...elements: unknown[]) => FieldValue.arrayUnion(...elements),
    },
    async sendOfferNotification(payload: OfferFcmPayload): Promise<void> {
      if (!payload.fcmToken) {
        logger.info("Skipping FCM push: captain lacks fcmToken", {
          captainUid: payload.captainUid,
          orderId: payload.orderId,
        });
        return;
      }

      try {
        await getMessaging().send({
          token: payload.fcmToken,
          android: {
            priority: "high",
            ttl: 60 * 1000,
          },
          data: {
            type: "new_order",
            orderId: payload.orderId,
            orderNumber: payload.orderNumber,
            orderCode: payload.orderCode,
            storeName: payload.storeName,
            storePhone: payload.storePhone,
            storeImage: payload.storeImage,
            storeLocationUrl: payload.storeLocationUrl,
            pickupAddress: payload.pickupAddress,
            deliveryAddress: payload.deliveryAddress,
            deliveryFee: String(payload.deliveryFee),
            distanceKm: String(payload.distanceKm),
            offerExpiresAt: String(payload.offerExpiresAtMs),
            itemsSummary: payload.itemsSummary,
            itemsCount: String(payload.itemsCount),
            orderTotalAmount: String(payload.orderTotalAmount),
            customerName: payload.customerName,
            customerPhone: payload.customerPhone,
            offerGeneration: String(payload.offerGeneration),
          },
        });
      } catch (err) {
        logger.warn("Failed to send offer FCM push to captain", {
          captainUid: payload.captainUid,
          orderId: payload.orderId,
          error: err instanceof Error ? err.message : String(err),
        });
      }
    },
    async enqueueReassignTask(
      payload: ReassignTaskPayload,
      delaySeconds: number
    ): Promise<void> {
      try {
        const queue = getFunctions().taskQueue("reassignOrderOffer");
        await queue.enqueue(payload, {
          scheduleDelaySeconds: Math.max(5, Math.min(delaySeconds, 300)),
        });
      } catch (err) {
        logger.warn("Failed to enqueue Cloud Task reassignOrderOffer", {
          orderId: payload.orderId,
          expectedCaptainId: payload.expectedCaptainId,
          error: err instanceof Error ? err.message : String(err),
        });
      }
    },
  };
}
