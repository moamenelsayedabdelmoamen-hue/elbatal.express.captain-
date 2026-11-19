import {
  CaptainCandidate,
  DatabaseLike,
  DomainHttpsError,
  OfferFcmPayload,
  ReassignTaskPayload,
  ServerEffectsPort,
  TransactionLike,
} from "./types.js";
import {getCairoTimeContext, isCaptainInActiveShift} from "./shifts.js";
import {
  assertCaptainOperationalForOrder,
  assertMerchantAndSubscriptionValid,
  extractCoordinates,
  extractOrderCaptainUid,
  haversineDistanceKm,
  isCaptainCandidateBaseEligible,
  isEligibleCaptainIdentity,
  isOrderStatusBlockingActiveTrip,
  isOrderStatusOpenForAccept,
  isTruthyFlag,
  parseTimestampMs,
  readNormalizedOrderStatus,
  readNumber,
  readString,
  readStringList,
  requireValidOrderId,
  resolveEffectiveOrderStatus,
} from "./validators.js";

export const OFFER_WINDOW_MS = 60_000;
export const OFFER_WINDOW_SECONDS = 60;
const ACTIVE_CAPTAIN_FIELDS = [
  "captainId",
  "driverId",
  "assignedCaptainId",
  "captainUid",
];

export interface AcceptOrderResult {
  success: boolean;
  alreadyAccepted: boolean;
  orderId: string;
  status: string;
  captainId: string;
  message: string;
}

export interface SimpleLifecycleResult {
  success: boolean;
  alreadyUpdated: boolean;
  orderId: string;
  status: string;
  message: string;
}

export interface ConfirmDeliveryResult {
  success: boolean;
  alreadyCompleted: boolean;
  orderId: string;
  status: string;
  earnedAmount: number;
  grossDeliveryFee: number;
  platformCommission: number;
  dayKey: string;
  message: string;
}

export interface DispatchOutcome {
  status:
    | "offered"
    | "reassigned"
    | "no_captains_available"
    | "ignored_already_active"
    | "ignored_stale"
    | "rescheduled_early";
  orderId: string;
  offeredCaptainId?: string;
  offerGeneration?: number;
  offerExpiresAtMs?: number;
}

/**
 * Core server-authoritative service orchestrating El-Batal Express captain
 * assignment, order lifecycle transitions, financial settlement, and 60-second
 * offer timeouts/reassignment.
 */
export class OrderServerService {
  private readonly db: DatabaseLike;
  private readonly effects: ServerEffectsPort;

  /**
   * Constructs an OrderServerService bound to a Firestore-compatible database.
   * @param {DatabaseLike} db Database adapter.
   * @param {ServerEffectsPort} effects Clock, FieldValue sentinels, FCM & Task adapter.
   */
  constructor(db: DatabaseLike, effects: ServerEffectsPort) {
    this.db = db;
    this.effects = effects;
  }

  /**
   * 1. Atomic transactional acceptance of an available or offered delivery order.
   * Rejects unauthenticated, unregistered, suspended, offline, out-of-shift,
   * stale-GPS, already-occupied captains, expired offers, and invalid merchant subscriptions.
   * @param {string | undefined} authUid Authenticated Firebase UID from context.auth.
   * @param {Record<string, unknown>} data Callable payload containing orderId.
   * @return {Promise<AcceptOrderResult>} Acceptance result.
   */
  async acceptOrder(
    authUid: string | undefined,
    data: Record<string, unknown>
  ): Promise<AcceptOrderResult> {
    const uid = requireAuthUid(authUid);
    const orderId = requireValidOrderId(data?.orderId ?? data?.rawOrderId);
    const nowMs = this.effects.now();

    const captainRef = this.db.collection("captains").doc(uid);
    const preCaptainSnap = await captainRef.get();
    if (!preCaptainSnap.exists) {
      throw new DomainHttpsError(
        "permission-denied",
        "هذا الحساب غير مسجل ككابتن في النظام."
      );
    }

    const preCaptainData = preCaptainSnap.data() ?? {};
    assertCaptainOperationalForOrder(preCaptainData, nowMs);

    const inShift = await isCaptainInActiveShift(
      this.db,
      uid,
      preCaptainData,
      nowMs
    );
    if (!inShift) {
      throw new DomainHttpsError(
        "failed-precondition",
        "عفواً، أنت خارج ساعات العمل الرسمية (الشفت) ولا يمكنك قبول طلبات حالياً."
      );
    }

    const orderRef = this.db.collection("orders").doc(orderId);
    const s = this.effects.sentinels;

    return this.db.runTransaction(async (tx) => {
      const [orderSnap, captainSnap, conflictId] = await Promise.all([
        tx.get(orderRef),
        tx.get(captainRef),
        this.findActiveConflictInTx(tx, uid, orderId),
      ]);

      if (!captainSnap.exists) {
        throw new DomainHttpsError(
          "permission-denied",
          "هذا الحساب غير مسجل ككابتن في النظام."
        );
      }
      const captainData = captainSnap.data() ?? {};
      assertCaptainOperationalForOrder(captainData, nowMs);

      if (!orderSnap.exists) {
        throw new DomainHttpsError(
          "not-found",
          "الطلب غير موجود أو تم حذفه."
        );
      }

      const order = orderSnap.data() ?? {};
      const status = readNormalizedOrderStatus(order) || "pending";
      const assignedUid = extractOrderCaptainUid(order);

      // Idempotent retry by the same captain who already owns the accepted order
      if (
        assignedUid === uid &&
        (status === "accepted" || status === "assigned")
      ) {
        return {
          success: true,
          alreadyAccepted: true,
          orderId,
          status: "accepted",
          captainId: uid,
          message: "تم قبول هذا الطلب مسبقاً باسمك.",
        };
      }

      if (assignedUid && assignedUid !== uid) {
        throw new DomainHttpsError(
          "already-exists",
          "عذراً، تم قبول هذا الطلب بالفعل بواسطة كابتن آخر."
        );
      }

      if (!isOrderStatusOpenForAccept(status)) {
        throw new DomainHttpsError(
          "failed-precondition",
          "عذراً، هذا الطلب ملغي أو لم يعد متاحاً للقبول."
        );
      }

      const excludedIds = new Set([
        ...readStringList(order.excludedCaptainIds),
        ...readStringList(order.timedOutCaptainIds),
      ]);
      if (excludedIds.has(uid)) {
        throw new DomainHttpsError(
          "failed-precondition",
          "انتهت مهلة قبول هذا الطلب لك وتم تحويله لكابتن آخر."
        );
      }

      const offeredUid = readString(order, "offeredCaptainId");
      const offerExpiresAtMs = parseTimestampMs(
        order.offerExpiresAt ?? order.offer_expires_at
      );

      if (offeredUid && offeredUid !== uid) {
        if (offerExpiresAtMs === 0 || nowMs < offerExpiresAtMs) {
          throw new DomainHttpsError(
            "failed-precondition",
            "هذا الطلب معروض حالياً لكابتن آخر ولم يتحول إليك."
          );
        }
      }

      if (
        offeredUid === uid &&
        offerExpiresAtMs > 0 &&
        nowMs > offerExpiresAtMs
      ) {
        throw new DomainHttpsError(
          "failed-precondition",
          "انتهت مهلة الـ 60 ثانية لقبول هذا الطلب وتم تحويله لكابتن آخر."
        );
      }

      if (conflictId) {
        throw new DomainHttpsError(
          "failed-precondition",
          "لديك طلب نشط بالفعل قيد التنفيذ، لا يمكن قبول أكثر من طلب في وقت واحد."
        );
      }

      // Verify merchant & active subscription inside transaction
      const merchantId = readString(
        order,
        "merchantId",
        "restaurantId",
        "storeId",
        "vendorId",
        "ownerId"
      );
      if (merchantId) {
        const [merchantSnap, restaurantSnap, subSnap] = await Promise.all([
          tx.get(this.db.collection("merchants").doc(merchantId)),
          tx.get(this.db.collection("restaurants").doc(merchantId)),
          tx.get(this.db.collection("subscriptions").doc(merchantId)),
        ]);
        const mData = merchantSnap.exists
          ? merchantSnap.data()
          : restaurantSnap.exists
            ? restaurantSnap.data()
            : undefined;
        const sData = subSnap.exists ? subSnap.data() : undefined;
        assertMerchantAndSubscriptionValid(merchantId, mData, sData, nowMs);
      }

      const captainName = readString(captainData, "fullName", "name");
      const captainPhone = readString(captainData, "phone", "phoneNumber");
      const createdAtMs = parseTimestampMs(
        order.createdAt ?? order.created_at ?? order.timestamp
      );
      const searchSeconds =
        createdAtMs > 0 && nowMs >= createdAtMs
          ? Math.max(1, Math.round((nowMs - createdAtMs) / 1000))
          : 0;

      const orderUpdate: Record<string, unknown> = {
        status: "accepted",
        dispatchStatus: "assigned",
        captainId: uid,
        driverId: uid,
        assignedCaptainId: uid,
        captainUid: uid,
        captainName,
        captainPhone,
        offeredCaptainId: null,
        offerExpiresAt: null,
        eligibleCaptainIds: s.deleteField(),
        acceptedAt: s.serverTimestamp(),
        assignedAt: s.serverTimestamp(),
        updatedAt: s.serverTimestamp(),
      };
      if (searchSeconds > 0) {
        orderUpdate.captainSearchSeconds = searchSeconds;
      }

      tx.update(orderRef, orderUpdate);
      tx.update(captainRef, {
        presenceStatus: "on_trip",
        currentOrderId: orderId,
        updatedAt: s.serverTimestamp(),
      });

      return {
        success: true,
        alreadyAccepted: false,
        orderId,
        status: "accepted",
        captainId: uid,
        message: "تم قبول الطلب بنجاح",
      };
    });
  }

  /**
   * 2. Captain marks arrival at merchant store.
   * @param {string | undefined} authUid Authenticated Firebase UID.
   * @param {Record<string, unknown>} data Callable payload containing orderId.
   * @return {Promise<SimpleLifecycleResult>} Transition result.
   */
  async captainArrivedAtMerchant(
    authUid: string | undefined,
    data: Record<string, unknown>
  ): Promise<SimpleLifecycleResult> {
    const uid = requireAuthUid(authUid);
    const orderId = requireValidOrderId(data?.orderId ?? data?.rawOrderId);
    const orderRef = this.db.collection("orders").doc(orderId);
    const s = this.effects.sentinels;

    return this.db.runTransaction(async (tx) => {
      const orderSnap = await tx.get(orderRef);
      if (!orderSnap.exists) {
        throw new DomainHttpsError(
          "not-found",
          "الطلب غير موجود أو تم حذفه."
        );
      }

      const order = orderSnap.data() ?? {};
      const assignedUid = extractOrderCaptainUid(order);
      if (!assignedUid || assignedUid !== uid) {
        throw new DomainHttpsError(
          "permission-denied",
          "غير مصرح: هذا الطلب غير مسند إلى حسابك."
        );
      }

      const status = readNormalizedOrderStatus(order);
      if (
        status === "arrived_at_merchant" ||
        status === "at_store" ||
        status === "arrived" ||
        status === "arrived_store"
      ) {
        return {
          success: true,
          alreadyUpdated: true,
          orderId,
          status: "arrived_at_merchant",
          message: "تم تسجيل الوصول إلى التاجر مسبقاً.",
        };
      }

      if (status !== "accepted" && status !== "assigned") {
        throw new DomainHttpsError(
          "failed-precondition",
          "حالة الطلب الحالية لا تسمح بتسجيل الوصول إلى التاجر."
        );
      }

      tx.update(orderRef, {
        status: "arrived_at_merchant",
        captainId: uid,
        driverId: uid,
        arrivedAtMerchantAt: s.serverTimestamp(),
        arrivedAtStoreTimestamp: s.serverTimestamp(),
        updatedAt: s.serverTimestamp(),
      });

      return {
        success: true,
        alreadyUpdated: false,
        orderId,
        status: "arrived_at_merchant",
        message: "تم تسجيل الوصول إلى التاجر بنجاح",
      };
    });
  }

  /**
   * 3. Captain confirms picking up order from merchant.
   * @param {string | undefined} authUid Authenticated Firebase UID.
   * @param {Record<string, unknown>} data Callable payload containing orderId.
   * @return {Promise<SimpleLifecycleResult>} Transition result.
   */
  async confirmPickup(
    authUid: string | undefined,
    data: Record<string, unknown>
  ): Promise<SimpleLifecycleResult> {
    const uid = requireAuthUid(authUid);
    const orderId = requireValidOrderId(data?.orderId ?? data?.rawOrderId);
    const orderRef = this.db.collection("orders").doc(orderId);
    const s = this.effects.sentinels;

    return this.db.runTransaction(async (tx) => {
      const orderSnap = await tx.get(orderRef);
      if (!orderSnap.exists) {
        throw new DomainHttpsError(
          "not-found",
          "الطلب غير موجود أو تم حذفه."
        );
      }

      const order = orderSnap.data() ?? {};
      const assignedUid = extractOrderCaptainUid(order);
      if (!assignedUid || assignedUid !== uid) {
        throw new DomainHttpsError(
          "permission-denied",
          "غير مصرح: هذا الطلب غير مسند إلى حسابك."
        );
      }

      const status = readNormalizedOrderStatus(order);
      if (status === "picked_up") {
        return {
          success: true,
          alreadyUpdated: true,
          orderId,
          status: "picked_up",
          message: "تم تأكيد استلام الطلب من التاجر مسبقاً.",
        };
      }

      if (
        status !== "arrived_at_merchant" &&
        status !== "at_store" &&
        status !== "arrived" &&
        status !== "arrived_store"
      ) {
        throw new DomainHttpsError(
          "failed-precondition",
          "يجب تسجيل الوصول إلى التاجر أولاً قبل تأكيد استلام الطلب."
        );
      }

      tx.update(orderRef, {
        status: "picked_up",
        captainId: uid,
        driverId: uid,
        pickedUpAt: s.serverTimestamp(),
        updatedAt: s.serverTimestamp(),
      });

      return {
        success: true,
        alreadyUpdated: false,
        orderId,
        status: "picked_up",
        message: "تم تأكيد استلام الطلب من التاجر بنجاح",
      };
    });
  }

  /**
   * 4. Captain submits customer details and Storage receipt path for merchant confirmation.
   * @param {string | undefined} authUid Authenticated Firebase UID.
   * @param {Record<string, unknown>} data Customer & receipt fields.
   * @return {Promise<SimpleLifecycleResult>} Submission result.
   */
  async submitCustomerDeliveryDetails(
    authUid: string | undefined,
    data: Record<string, unknown>
  ): Promise<SimpleLifecycleResult> {
    const uid = requireAuthUid(authUid);
    const orderId = requireValidOrderId(data?.orderId ?? data?.rawOrderId);
    const receiptImagePath = readString(
      data,
      "receiptImagePath",
      "receiptImageUrl"
    );
    const customerName = readString(data, "customerName");
    const customerPhone = readString(data, "customerPhone");
    const notes = typeof data?.notes === "string" ? data.notes.trim() : "";

    const expectedPrefix = `orders/${orderId}/receipts/`;
    if (!receiptImagePath || !receiptImagePath.startsWith(expectedPrefix)) {
      throw new DomainHttpsError(
        "invalid-argument",
        "مسار صورة الإيصال غير صالح أو لا يطابق مسار الطلب المعتمد."
      );
    }

    const orderRef = this.db.collection("orders").doc(orderId);
    const s = this.effects.sentinels;

    return this.db.runTransaction(async (tx) => {
      const orderSnap = await tx.get(orderRef);
      if (!orderSnap.exists) {
        throw new DomainHttpsError(
          "not-found",
          "الطلب غير موجود أو تم حذفه."
        );
      }

      const order = orderSnap.data() ?? {};
      const assignedUid = extractOrderCaptainUid(order);
      if (!assignedUid || assignedUid !== uid) {
        throw new DomainHttpsError(
          "permission-denied",
          "غير مصرح: لا يمكنك تعديل بيانات طلب غير مسند إليك."
        );
      }

      const status = readNormalizedOrderStatus(order);
      const allowedStatuses = new Set([
        "picked_up",
        "order_data_entered",
        "data_entered",
        "merchant_rejected",
        "awaiting_merchant_confirmation",
        "awaiting_confirmation",
      ]);

      if (!allowedStatuses.has(status)) {
        throw new DomainHttpsError(
          "failed-precondition",
          "حالة الطلب الحالية لا تسمح برفع بيانات العميل والإيصال."
        );
      }

      const updateFields: Record<string, unknown> = {
        receiptImagePath,
        receiptImageUrl: receiptImagePath,
        orderImageUrl: receiptImagePath,
        receiptUploadedBy: uid,
        receiptUploadedAt: s.serverTimestamp(),
        receiptSubmittedAt: s.serverTimestamp(),
        notes,
        status: "awaiting_merchant_confirmation",
        merchantConfirmed: false,
        rejectionReason: s.deleteField(),
        updatedAt: s.serverTimestamp(),
      };
      if (customerName) updateFields.customerName = customerName;
      if (customerPhone) updateFields.customerPhone = customerPhone;

      tx.update(orderRef, updateFields);

      return {
        success: true,
        alreadyUpdated: false,
        orderId,
        status: "awaiting_merchant_confirmation",
        message: "تم إرسال بيانات العميل والإيصال وبانتظار تأكيد التاجر",
      };
    });
  }

  /**
   * 5. Atomic idempotent completion of delivery and server-side financial settlement.
   * Delivery earnings & commissions are calculated strictly from server order/captain fields,
   * never trusting client-submitted amounts. Uses deterministic ledger doc ID `delivery_{orderId}`
   * to guarantee duplicate calls never double-credit walletBalance or daily_wallets.
   * @param {string | undefined} authUid Authenticated Firebase UID.
   * @param {Record<string, unknown>} data Callable payload containing orderId.
   * @return {Promise<ConfirmDeliveryResult>} Financial settlement result.
   */
  async confirmDelivery(
    authUid: string | undefined,
    data: Record<string, unknown>
  ): Promise<ConfirmDeliveryResult> {
    const uid = requireAuthUid(authUid);
    const orderId = requireValidOrderId(data?.orderId ?? data?.rawOrderId);
    const nowMs = this.effects.now();
    const dayKey = getCairoTimeContext(nowMs).dayKey;

    const orderRef = this.db.collection("orders").doc(orderId);
    const captainRef = this.db.collection("captains").doc(uid);
    const txDocRef = captainRef
      .collection("transactions")
      .doc(`delivery_${orderId}`);
    const dailyWalletRef = captainRef.collection("daily_wallets").doc(dayKey);
    const s = this.effects.sentinels;

    return this.db.runTransaction(async (tx) => {
      const [orderSnap, captainSnap, existingLedgerSnap] = await Promise.all([
        tx.get(orderRef),
        tx.get(captainRef),
        tx.get(txDocRef),
      ]);

      if (!orderSnap.exists) {
        throw new DomainHttpsError(
          "not-found",
          "الطلب غير موجود أو تم حذفه."
        );
      }
      if (!captainSnap.exists) {
        throw new DomainHttpsError(
          "permission-denied",
          "هذا الحساب غير مسجل ككابتن في النظام."
        );
      }

      const order = orderSnap.data() ?? {};
      const captain = captainSnap.data() ?? {};
      const assignedUid = extractOrderCaptainUid(order);
      if (!assignedUid || assignedUid !== uid) {
        throw new DomainHttpsError(
          "permission-denied",
          "غير مصرح: هذا الطلب غير مسند إلى حسابك."
        );
      }

      const grossFee =
        readNumber(order, "deliveryFee", "delivery_fee", "shippingFee", "fee") ??
        0;
      const effectiveStatus = resolveEffectiveOrderStatus(order);

      // Idempotency guard: if transaction ledger or delivered status already exists,
      // return recorded values without incrementing balances or counters a second time.
      if (
        existingLedgerSnap.exists ||
        effectiveStatus === "delivered" ||
        effectiveStatus === "completed" ||
        effectiveStatus === "done"
      ) {
        const ledger = existingLedgerSnap.data() ?? {};
        const recordedAmount =
          readNumber(ledger, "amount") ??
          readNumber(order, "captainEarning") ??
          grossFee;
        const recordedGross =
          readNumber(ledger, "grossDeliveryFee") ?? grossFee;
        const recordedCommission =
          readNumber(ledger, "platformCommission") ??
          readNumber(order, "platformCommission") ??
          0;
        const recordedDay = readString(ledger, "dayKey") || dayKey;

        return {
          success: true,
          alreadyCompleted: true,
          orderId,
          status: "delivered",
          earnedAmount: recordedAmount,
          grossDeliveryFee: recordedGross,
          platformCommission: recordedCommission,
          dayKey: recordedDay,
          message: "تم تأكيد تسليم هذا الطلب وتسجيل أرباحه مسبقاً.",
        };
      }

      const isEnRouteState =
        effectiveStatus === "en_route" ||
        effectiveStatus === "out_for_delivery" ||
        effectiveStatus === "delivering" ||
        effectiveStatus === "on_the_way";

      if (!isEnRouteState) {
        throw new DomainHttpsError(
          "failed-precondition",
          "لا يمكن تأكيد التسليم قبل اعتماد التاجر لبيانات الإيصال وتحول الطلب إلى حالة التوصيل."
        );
      }

      if (grossFee <= 0) {
        throw new DomainHttpsError(
          "failed-precondition",
          "قيمة توصيل الطلب على الخادم غير صالحة لإتمام التسوية المالية."
        );
      }

      const rawRate = readNumber(captain, "commissionRate") ?? 0;
      const commissionRate = rawRate > 0 && rawRate < 1 ? rawRate : 0;
      const platformCommission =
        Math.round(grossFee * commissionRate * 100) / 100;
      const earnedAmount =
        Math.round((grossFee - platformCommission) * 100) / 100;

      const orderNumber = readString(order, "orderNumber", "order_number") || orderId;
      const merchantId = readString(
        order,
        "merchantId",
        "restaurantId",
        "storeId"
      );

      tx.update(orderRef, {
        status: "delivered",
        dispatchStatus: "delivered",
        merchantConfirmed: true,
        deliveredAt: s.serverTimestamp(),
        completedAt: s.serverTimestamp(),
        updatedAt: s.serverTimestamp(),
        deliveryFee: grossFee,
        captainEarning: earnedAmount,
        platformCommission,
      });

      tx.set(txDocRef, {
        orderId,
        orderNumber,
        merchantId,
        captainId: uid,
        type: "delivery_earning",
        grossDeliveryFee: grossFee,
        platformCommission,
        amount: earnedAmount,
        currency: "EGP",
        dayKey,
        createdAt: s.serverTimestamp(),
      });

      tx.update(captainRef, {
        walletBalance: s.increment(earnedAmount),
        totalOrdersCount: s.increment(1),
        presenceStatus: "available",
        currentOrderId: null,
        updatedAt: s.serverTimestamp(),
      });

      tx.set(
        dailyWalletRef,
        {
          dayKey,
          captainId: uid,
          totalEarnings: s.increment(earnedAmount),
          deliveredOrdersCount: s.increment(1),
          updatedAt: s.serverTimestamp(),
        },
        {merge: true}
      );

      return {
        success: true,
        alreadyCompleted: false,
        orderId,
        status: "delivered",
        earnedAmount,
        grossDeliveryFee: grossFee,
        platformCommission,
        dayKey,
        message: "تم تأكيد تسليم الطلب وإضافة الأرباح لمحفظتك بنجاح",
      };
    });
  }

  /**
   * 6. Server-verified offer expiry / decline callable.
   * For reason="expired", refuses execution unless server clock confirms now >= offerExpiresAt
   * so client devices cannot manipulate timers or prematurely drop offers.
   * @param {string | undefined} authUid Authenticated Firebase UID.
   * @param {Record<string, unknown>} data Callable payload with orderId & reason.
   * @return {Promise<DispatchOutcome>} Reassignment outcome.
   */
  async expireDeliveryOffer(
    authUid: string | undefined,
    data: Record<string, unknown>
  ): Promise<DispatchOutcome> {
    const uid = requireAuthUid(authUid);
    const orderId = requireValidOrderId(data?.orderId ?? data?.rawOrderId);
    const reason = readString(data, "reason").toLowerCase() || "expired";
    const nowMs = this.effects.now();

    const preOrderSnap = await this.db.collection("orders").doc(orderId).get();
    if (!preOrderSnap.exists) {
      return {status: "ignored_stale", orderId};
    }

    const preOrder = preOrderSnap.data() ?? {};
    const status = readNormalizedOrderStatus(preOrder) || "pending";
    const assignedUid = extractOrderCaptainUid(preOrder);
    if (assignedUid || !isOrderStatusOpenForAccept(status)) {
      return {status: "ignored_stale", orderId};
    }

    const offeredUid = readString(preOrder, "offeredCaptainId");
    if (offeredUid && offeredUid !== uid) {
      return {status: "ignored_stale", orderId};
    }

    const offerExpiresAtMs = parseTimestampMs(
      preOrder.offerExpiresAt ?? preOrder.offer_expires_at
    );
    if (reason !== "declined" && offerExpiresAtMs > 0 && nowMs < offerExpiresAtMs) {
      throw new DomainHttpsError(
        "failed-precondition",
        "لم تنتهِ مهلة قبول الطلب على الخادم بعد."
      );
    }

    const expectedGen = Number(preOrder.offerGeneration ?? 0) || 0;
    return this.executeOfferRotation(
      orderId,
      uid,
      expectedGen,
      reason === "declined" ? "declined" : "expired",
      false
    );
  }

  /**
   * 7. Dispatches a newly created or re-queued order to the nearest eligible captain
   * with a server-authored 60-second offer deadline and Cloud Task watchdog.
   * @param {string} orderId Order document ID.
   * @return {Promise<DispatchOutcome>} Dispatch outcome.
   */
  async dispatchNewOrSearchingOrder(orderId: string): Promise<DispatchOutcome> {
    const orderRef = this.db.collection("orders").doc(orderId);
    const preSnap = await orderRef.get();
    if (!preSnap.exists) {
      return {status: "ignored_stale", orderId};
    }

    const preOrder = preSnap.data() ?? {};
    const nowMs = this.effects.now();
    const status = readNormalizedOrderStatus(preOrder) || "pending";
    const assignedUid = extractOrderCaptainUid(preOrder);

    if (assignedUid || !isOrderStatusOpenForAccept(status)) {
      return {status: "ignored_already_active", orderId};
    }

    const existingOfferedUid = readString(preOrder, "offeredCaptainId");
    const existingExpiryMs = parseTimestampMs(preOrder.offerExpiresAt);
    if (existingOfferedUid && existingExpiryMs > nowMs) {
      return {
        status: "ignored_already_active",
        orderId,
        offeredCaptainId: existingOfferedUid,
        offerExpiresAtMs: existingExpiryMs,
      };
    }

    // Validate merchant & active subscription before searching/offering
    const merchantId = readString(
      preOrder,
      "merchantId",
      "restaurantId",
      "storeId",
      "vendorId",
      "ownerId"
    );
    if (merchantId) {
      const [mSnap, rSnap, sSnap] = await Promise.all([
        this.db.collection("merchants").doc(merchantId).get(),
        this.db.collection("restaurants").doc(merchantId).get(),
        this.db.collection("subscriptions").doc(merchantId).get(),
      ]);
      const mData = mSnap.exists
        ? mSnap.data()
        : rSnap.exists
          ? rSnap.data()
          : undefined;
      assertMerchantAndSubscriptionValid(
        merchantId,
        mData,
        sSnap.exists ? sSnap.data() : undefined,
        nowMs
      );
    }

    const excludedSet = new Set([
      ...readStringList(preOrder.excludedCaptainIds),
      ...readStringList(preOrder.timedOutCaptainIds),
    ]);
    const candidates = await this.findNearestEligibleCaptains(
      preOrder,
      excludedSet,
      nowMs
    );
    const topCandidate = candidates[0] ?? null;
    const eligibleIds = candidates.map((c) => c.uid);
    const s = this.effects.sentinels;

    let selectedOfferPayload: OfferFcmPayload | null = null;
    let taskPayload: ReassignTaskPayload | null = null;

    const outcome = await this.db.runTransaction(async (tx) => {
      const snap = await tx.get(orderRef);
      if (!snap.exists) {
        return {status: "ignored_stale", orderId} as DispatchOutcome;
      }
      const latest = snap.data() ?? {};
      const latestStatus = readNormalizedOrderStatus(latest) || "pending";
      if (extractOrderCaptainUid(latest) || !isOrderStatusOpenForAccept(latestStatus)) {
        return {status: "ignored_already_active", orderId} as DispatchOutcome;
      }
      const curOffered = readString(latest, "offeredCaptainId");
      const curExpiry = parseTimestampMs(latest.offerExpiresAt);
      if (curOffered && curExpiry > nowMs) {
        return {status: "ignored_already_active", orderId} as DispatchOutcome;
      }

      if (!topCandidate) {
        tx.update(orderRef, {
          status: "searching",
          dispatchStatus: "no_captains_available",
          offeredCaptainId: null,
          offeredCaptainName: null,
          offerExpiresAt: null,
          eligibleCaptainIds: [],
          updatedAt: s.serverTimestamp(),
        });
        return {status: "no_captains_available", orderId} as DispatchOutcome;
      }

      const nextGen = (Number(latest.offerGeneration ?? 0) || 0) + 1;
      const offerExpiresAtMs = nowMs + OFFER_WINDOW_MS;

      tx.update(orderRef, {
        status: "pending",
        dispatchStatus: "offered",
        offeredCaptainId: topCandidate.uid,
        offeredCaptainName: topCandidate.name,
        offerSentAt: s.serverTimestamp(),
        offerExpiresAt: offerExpiresAtMs,
        offerGeneration: nextGen,
        eligibleCaptainIds: eligibleIds,
        updatedAt: s.serverTimestamp(),
      });

      selectedOfferPayload = this.buildOfferFcmPayload(
        orderId,
        latest,
        topCandidate,
        offerExpiresAtMs,
        nextGen
      );
      taskPayload = {
        orderId,
        expectedCaptainId: topCandidate.uid,
        expectedOfferGeneration: nextGen,
      };

      return {
        status: "offered",
        orderId,
        offeredCaptainId: topCandidate.uid,
        offerGeneration: nextGen,
        offerExpiresAtMs,
      } as DispatchOutcome;
    });

    if (outcome.status === "no_captains_available") {
      await this.createAdminNoCaptainNotification(
        orderId,
        preOrder,
        "لا يوجد كباتن متاحون حالياً (متصلين وداخل الشفت وبموقع حديث)"
      );
    } else if (selectedOfferPayload && taskPayload) {
      await this.effects.sendOfferNotification(selectedOfferPayload);
      await this.effects.enqueueReassignTask(taskPayload, OFFER_WINDOW_SECONDS);
    }

    return outcome;
  }

  /**
   * 8. Authoritative Cloud Task handler executed 60 seconds after an offer was sent.
   * Exits cleanly if order was already accepted, cancelled, reassigned, or superseded.
   * @param {ReassignTaskPayload} payload Task payload scheduled when offer started.
   * @return {Promise<DispatchOutcome>} Reassignment outcome.
   */
  async handleReassignOfferTask(
    payload: ReassignTaskPayload
  ): Promise<DispatchOutcome> {
    const orderId = requireValidOrderId(payload?.orderId);
    const expectedCaptainId =
      typeof payload?.expectedCaptainId === "string"
        ? payload.expectedCaptainId.trim()
        : "";
    const expectedGen = Number(payload?.expectedOfferGeneration ?? 0) || 0;

    if (!expectedCaptainId) {
      return {status: "ignored_stale", orderId};
    }

    return this.executeOfferRotation(
      orderId,
      expectedCaptainId,
      expectedGen,
      "expired",
      true
    );
  }

  /**
   * 9. Read-only phone lookup for Captain login/reset flows (eliminates need for client direct query).
   * @param {Record<string, unknown>} data Payload containing phone and optional phone variants.
   * @return {Promise<{found: boolean, email: string | null}>} Email resolution result.
   */
  async resolveCaptainEmailByPhone(
    data: Record<string, unknown>
  ): Promise<{found: boolean; email: string | null}> {
    const rawPhone = readString(data, "phone", "phoneNumber");
    const rawVariants = readStringList(data?.variants);
    const candidateStrings = Array.from(
      new Set([rawPhone, ...rawVariants].filter((v) => v.length > 0))
    ).slice(0, 10);

    if (candidateStrings.length === 0) {
      throw new DomainHttpsError(
        "invalid-argument",
        "رقم الهاتف مطلوب للبحث عن الحساب."
      );
    }

    const numericCandidates = Array.from(
      new Set(
        candidateStrings
          .map((s) => s.replace(/\D/g, ""))
          .flatMap((digits) => {
            const vals = [digits, digits.replace(/^0+/, ""), digits.replace(/^20/, "")];
            return vals
              .map((v) => Number(v))
              .filter((n) => Number.isSafeInteger(n) && n > 0);
          })
      )
    ).slice(0, 10);

    const searchFields = ["phone", "phoneNumber", "mobile", "phone_number"];
    for (const field of searchFields) {
      const strSnap = await this.db
        .collection("captains")
        .where(field, "in", candidateStrings)
        .limit(5)
        .get();
      for (const doc of strSnap.docs) {
        const d = doc.data();
        if (isEligibleCaptainIdentity(d)) {
          const email = readString(d, "email");
          if (email.includes("@")) {
            return {found: true, email};
          }
        }
      }

      if (numericCandidates.length > 0) {
        const numSnap = await this.db
          .collection("captains")
          .where(field, "in", numericCandidates)
          .limit(5)
          .get();
        for (const doc of numSnap.docs) {
          const d = doc.data();
          if (isEligibleCaptainIdentity(d)) {
            const email = readString(d, "email");
            if (email.includes("@")) {
              return {found: true, email};
            }
          }
        }
      }
    }

    return {found: false, email: null};
  }

  private async executeOfferRotation(
    orderId: string,
    timedOutCaptainId: string,
    expectedOfferGeneration: number,
    rotationReason: "expired" | "declined",
    enforceOfferGenerationMatch: boolean
  ): Promise<DispatchOutcome> {
    const orderRef = this.db.collection("orders").doc(orderId);
    const preSnap = await orderRef.get();
    if (!preSnap.exists) {
      return {status: "ignored_stale", orderId};
    }

    const preOrder = preSnap.data() ?? {};
    const nowMs = this.effects.now();
    const preStatus = readNormalizedOrderStatus(preOrder) || "pending";
    if (
      extractOrderCaptainUid(preOrder) ||
      !isOrderStatusOpenForAccept(preStatus)
    ) {
      return {status: "ignored_stale", orderId};
    }

    const excludedSet = new Set([
      ...readStringList(preOrder.excludedCaptainIds),
      ...readStringList(preOrder.timedOutCaptainIds),
      timedOutCaptainId,
    ]);
    const candidates = await this.findNearestEligibleCaptains(
      preOrder,
      excludedSet,
      nowMs
    );
    const nextCandidate = candidates[0] ?? null;
    const eligibleIds = candidates.map((c) => c.uid);
    const s = this.effects.sentinels;

    let offerFcm: OfferFcmPayload | null = null;
    let nextTask: ReassignTaskPayload | null = null;
    let rescheduleRemainingSec: number | null = null;

    const outcome = await this.db.runTransaction(async (tx) => {
      const snap = await tx.get(orderRef);
      if (!snap.exists) {
        return {status: "ignored_stale", orderId} as DispatchOutcome;
      }

      const latest = snap.data() ?? {};
      const status = readNormalizedOrderStatus(latest) || "pending";
      const assignedUid = extractOrderCaptainUid(latest);

      // Ignore stale task if order was already accepted, cancelled, or delivered
      if (assignedUid || !isOrderStatusOpenForAccept(status)) {
        return {status: "ignored_stale", orderId} as DispatchOutcome;
      }

      const currentOffered = readString(latest, "offeredCaptainId");
      const currentGen = Number(latest.offerGeneration ?? 0) || 0;
      if (currentOffered !== timedOutCaptainId) {
        return {status: "ignored_stale", orderId} as DispatchOutcome;
      }
      if (enforceOfferGenerationMatch && currentGen !== expectedOfferGeneration) {
        return {status: "ignored_stale", orderId} as DispatchOutcome;
      }

      const currentExpiryMs = parseTimestampMs(
        latest.offerExpiresAt ?? latest.offer_expires_at
      );
      if (
        rotationReason === "expired" &&
        currentExpiryMs > nowMs + 1000
      ) {
        rescheduleRemainingSec = Math.max(
          2,
          Math.ceil((currentExpiryMs - nowMs) / 1000)
        );
        return {status: "rescheduled_early", orderId} as DispatchOutcome;
      }

      if (!nextCandidate) {
        tx.update(orderRef, {
          status: "searching",
          dispatchStatus: "no_captains_available",
          offeredCaptainId: null,
          offeredCaptainName: null,
          offerExpiresAt: null,
          eligibleCaptainIds: [],
          excludedCaptainIds: s.arrayUnion(timedOutCaptainId),
          timedOutCaptainIds:
            rotationReason === "expired"
              ? s.arrayUnion(timedOutCaptainId)
              : latest.timedOutCaptainIds ?? [],
          updatedAt: s.serverTimestamp(),
        });
        return {status: "no_captains_available", orderId} as DispatchOutcome;
      }

      const nextGen = currentGen + 1;
      const offerExpiresAtMs = nowMs + OFFER_WINDOW_MS;

      const updateFields: Record<string, unknown> = {
        status: "pending",
        dispatchStatus: "offered",
        offeredCaptainId: nextCandidate.uid,
        offeredCaptainName: nextCandidate.name,
        offerSentAt: s.serverTimestamp(),
        offerExpiresAt: offerExpiresAtMs,
        offerGeneration: nextGen,
        eligibleCaptainIds: eligibleIds,
        excludedCaptainIds: s.arrayUnion(timedOutCaptainId),
        updatedAt: s.serverTimestamp(),
      };
      if (rotationReason === "expired") {
        updateFields.timedOutCaptainIds = s.arrayUnion(timedOutCaptainId);
      }

      tx.update(orderRef, updateFields);

      offerFcm = this.buildOfferFcmPayload(
        orderId,
        latest,
        nextCandidate,
        offerExpiresAtMs,
        nextGen
      );
      nextTask = {
        orderId,
        expectedCaptainId: nextCandidate.uid,
        expectedOfferGeneration: nextGen,
      };

      return {
        status: "reassigned",
        orderId,
        offeredCaptainId: nextCandidate.uid,
        offerGeneration: nextGen,
        offerExpiresAtMs,
      } as DispatchOutcome;
    });

    if (outcome.status === "rescheduled_early" && rescheduleRemainingSec) {
      await this.effects.enqueueReassignTask(
        {
          orderId,
          expectedCaptainId: timedOutCaptainId,
          expectedOfferGeneration,
        },
        rescheduleRemainingSec
      );
      return outcome;
    }

    if (outcome.status === "no_captains_available") {
      await this.createAdminNoCaptainNotification(
        orderId,
        preOrder,
        "انتهت مهلة قبول الطلب لجميع الكباتن المتاحين ولم يتم قبوله"
      );
    } else if (offerFcm && nextTask) {
      await this.effects.sendOfferNotification(offerFcm);
      await this.effects.enqueueReassignTask(nextTask, OFFER_WINDOW_SECONDS);
    }

    return outcome;
  }

  private async findNearestEligibleCaptains(
    orderData: Record<string, unknown>,
    excludedIds: Set<string>,
    nowMs: number
  ): Promise<CaptainCandidate[]> {
    const onlineSnap = await this.db
      .collection("captains")
      .where("isOnline", "==", true)
      .limit(100)
      .get();

    const origin = extractCoordinates(orderData) ?? {lat: 30.0444, lng: 31.2357};
    const eligible: CaptainCandidate[] = [];

    for (const doc of onlineSnap.docs) {
      const uid = doc.id;
      if (excludedIds.has(uid)) continue;

      const data = doc.data() ?? {};
      if (!isCaptainCandidateBaseEligible(data, nowMs)) continue;

      const inShift = await isCaptainInActiveShift(this.db, uid, data, nowMs);
      if (!inShift) continue;

      const hasConflict = await this.hasActiveConflictOutsideTx(uid);
      if (hasConflict) continue;

      const coords = extractCoordinates(data);
      if (!coords) continue;

      const distanceKm = haversineDistanceKm(
        origin.lat,
        origin.lng,
        coords.lat,
        coords.lng
      );

      eligible.push({
        uid,
        name: readString(data, "fullName", "name") || "كابتن",
        phone: readString(data, "phone", "phoneNumber"),
        fcmToken: readString(data, "fcmToken"),
        lat: coords.lat,
        lng: coords.lng,
        distanceKm: Math.round(distanceKm * 100) / 100,
      });
    }

    eligible.sort((a, b) => a.distanceKm - b.distanceKm);
    return eligible;
  }

  private async hasActiveConflictOutsideTx(captainUid: string): Promise<boolean> {
    for (const field of ACTIVE_CAPTAIN_FIELDS) {
      const snap = await this.db
        .collection("orders")
        .where(field, "==", captainUid)
        .limit(10)
        .get();
      for (const doc of snap.docs) {
        const status = resolveEffectiveOrderStatus(doc.data() ?? {});
        if (isOrderStatusBlockingActiveTrip(status)) {
          return true;
        }
      }
    }
    return false;
  }

  private async findActiveConflictInTx(
    tx: TransactionLike,
    captainUid: string,
    targetOrderId: string
  ): Promise<string | null> {
    for (const field of ACTIVE_CAPTAIN_FIELDS) {
      const q = this.db
        .collection("orders")
        .where(field, "==", captainUid)
        .limit(10);
      const snap = await tx.getQuery(q);
      for (const doc of snap.docs) {
        if (doc.id === targetOrderId) continue;
        const status = resolveEffectiveOrderStatus(doc.data() ?? {});
        if (isOrderStatusBlockingActiveTrip(status)) {
          return doc.id;
        }
      }
    }
    return null;
  }

  private buildOfferFcmPayload(
    orderId: string,
    order: Record<string, unknown>,
    candidate: CaptainCandidate,
    offerExpiresAtMs: number,
    offerGeneration: number
  ): OfferFcmPayload {
    const itemsArr = Array.isArray(order.items) ? order.items : [];
    const itemsSummary =
      readString(order, "itemsSummary", "description") || "طلب توصيل جديد";
    return {
      captainUid: candidate.uid,
      fcmToken: candidate.fcmToken,
      orderId,
      orderNumber: readString(order, "orderNumber", "order_number") || orderId,
      orderCode: readString(order, "orderCode", "order_code"),
      storeName:
        readString(
          order,
          "storeName",
          "restaurantName",
          "merchantName",
          "vendorName"
        ) || "مطعم مسجل",
      storePhone: readString(order, "storePhone", "merchantPhone"),
      storeImage: readString(order, "storeImage", "merchantLogo", "storeLogo"),
      storeLocationUrl: readString(order, "storeLocationUrl", "locationUrl"),
      pickupAddress:
        readString(order, "pickupAddress", "storeAddress", "merchantAddress") ||
        "موقع التاجر",
      deliveryAddress:
        readString(
          order,
          "deliveryAddress",
          "customerAddress",
          "dropoffAddress"
        ) || "عنوان العميل",
      deliveryFee:
        readNumber(order, "deliveryFee", "delivery_fee", "shippingFee") ?? 25.0,
      distanceKm: candidate.distanceKm,
      offerExpiresAtMs,
      itemsSummary,
      itemsCount:
        readNumber(order, "itemsCount") ??
        (itemsArr.length > 0 ? itemsArr.length : 1),
      orderTotalAmount:
        readNumber(order, "orderTotalAmount", "totalAmount", "total") ?? 0.0,
      customerName: readString(order, "customerName") || "عميل البطل",
      customerPhone: readString(order, "customerPhone"),
      offerGeneration,
    };
  }

  private async createAdminNoCaptainNotification(
    orderId: string,
    order: Record<string, unknown>,
    reason: string
  ): Promise<void> {
    const orderNumber = readString(order, "orderNumber", "order_number") || orderId;
    const restaurantName =
      readString(order, "merchantName", "restaurantName", "storeName") ||
      "مطعم غير محدد";
    const s = this.effects.sentinels;

    await this.db.collection("notifications").add({
      type: "captain_assignment_failed",
      targetRole: "admin",
      title: `تعذر تعيين كابتن للطلب #${orderNumber}`,
      message: `${reason} — ${restaurantName}`,
      orderId,
      orderNumber,
      restaurantName,
      reason,
      isRead: false,
      createdAt: s.serverTimestamp(),
    });
  }
}

function requireAuthUid(authUid: string | undefined): string {
  const uid = typeof authUid === "string" ? authUid.trim() : "";
  if (!uid) {
    throw new DomainHttpsError(
      "unauthenticated",
      "يجب تسجيل الدخول أولاً لتنفيذ هذه العملية."
    );
  }
  return uid;
}

export function isNonEmptyTruthy(v: unknown): boolean {
  return isTruthyFlag(v);
}
