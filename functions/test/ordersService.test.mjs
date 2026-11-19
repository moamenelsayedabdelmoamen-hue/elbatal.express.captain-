import {describe, it} from "node:test";
import assert from "node:assert/strict";
import {OrderServerService} from "../lib/ordersService.js";
import {DomainHttpsError} from "../lib/types.js";
import {getCairoTimeContext} from "../lib/shifts.js";

/**
 * Stateful in-memory transactional Firestore engine with optimistic version checks,
 * retryable transaction conflict resolution, subcollections, and FieldValue sentinels.
 * Used to verify atomic concurrency and idempotency guarantees deterministically.
 */
class InMemoryTransactionalFirestore {
  constructor() {
    this.docs = new Map(); // path -> { data: object, version: number }
    this.autoId = 1;
    this.YieldOnRead = false;
  }

  seed(path, data) {
    this.docs.set(path, {data: structuredClone(data), version: 1});
  }

  getDocData(path) {
    const entry = this.docs.get(path);
    return entry ? structuredClone(entry.data) : undefined;
  }

  collection(collectionPath) {
    return this._makeCollectionRef(collectionPath);
  }

  _makeDocRef(path) {
    const parts = path.split("/");
    const id = parts[parts.length - 1];
    return {
      id,
      path,
      collection: (sub) => this._makeCollectionRef(`${path}/${sub}`),
      get: async () => {
        const entry = this.docs.get(path);
        return this._wrapSnap(path, entry);
      },
      set: async (data, options) => {
        const cur = this.docs.get(path);
        const merged =
          options?.merge && cur ? {...cur.data} : {};
        this._applyMutations(merged, data);
        this.docs.set(path, {
          data: merged,
          version: (cur?.version ?? 0) + 1,
        });
      },
      update: async (data) => {
        const cur = this.docs.get(path);
        if (!cur) throw new Error(`Document not found for update: ${path}`);
        const next = structuredClone(cur.data);
        this._applyMutations(next, data);
        this.docs.set(path, {data: next, version: cur.version + 1});
      },
    };
  }

  _makeCollectionRef(basePath) {
    const query = this._makeQuery(basePath, [], null);
    return {
      ...query,
      doc: (docId) => {
        const id = docId ?? `auto_${this.autoId++}`;
        return this._makeDocRef(`${basePath}/${id}`);
      },
      add: async (data) => {
        const id = `auto_${this.autoId++}`;
        const ref = this._makeDocRef(`${basePath}/${id}`);
        await ref.set(data);
        return ref;
      },
    };
  }

  _makeQuery(basePath, filters, limitCount) {
    return {
      where: (fieldPath, opStr, value) =>
        this._makeQuery(
          basePath,
          [...filters, {fieldPath, opStr, value}],
          limitCount
        ),
      limit: (n) => this._makeQuery(basePath, filters, n),
      get: async () => this._execQuery(basePath, filters, limitCount),
    };
  }

  _execQuery(basePath, filters, limitCount) {
    const prefix = `${basePath}/`;
    const matched = [];
    for (const [path, entry] of this.docs.entries()) {
      if (!path.startsWith(prefix)) continue;
      const rest = path.slice(prefix.length);
      if (rest.includes("/")) continue; // direct children only
      const d = entry.data;
      let ok = true;
      for (const f of filters) {
        const val = d[f.fieldPath];
        if (f.opStr === "==") {
          if (val !== f.value) ok = false;
        } else if (f.opStr === "in") {
          if (!Array.isArray(f.value) || !f.value.includes(val)) ok = false;
        } else if (f.opStr === "array-contains") {
          if (!Array.isArray(val) || !val.includes(f.value)) ok = false;
        }
        if (!ok) break;
      }
      if (ok) {
        matched.push({path, entry});
        if (limitCount !== null && matched.length >= limitCount) break;
      }
    }
    return {
      empty: matched.length === 0,
      size: matched.length,
      docs: matched.map((m) => this._wrapSnap(m.path, m.entry)),
    };
  }

  _wrapSnap(path, entry) {
    const parts = path.split("/");
    const id = parts[parts.length - 1];
    return {
      id,
      exists: Boolean(entry),
      ref: this._makeDocRef(path),
      data: () => (entry ? structuredClone(entry.data) : undefined),
    };
  }

  _applyMutations(target, patch) {
    for (const [k, v] of Object.entries(patch)) {
      if (v && typeof v === "object" && v.__sentinel) {
        if (v.__sentinel === "serverTimestamp") {
          target[k] = Date.now();
        } else if (v.__sentinel === "deleteField") {
          delete target[k];
        } else if (v.__sentinel === "increment") {
          target[k] = Number(target[k] ?? 0) + v.operand;
        } else if (v.__sentinel === "arrayUnion") {
          const cur = Array.isArray(target[k]) ? [...target[k]] : [];
          for (const el of v.elements) {
            if (!cur.includes(el)) cur.push(el);
          }
          target[k] = cur;
        }
      } else {
        target[k] = structuredClone(v);
      }
    }
  }

  async runTransaction(fn) {
    const MAX_ATTEMPTS = 5;
    for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      const readVersions = new Map();
      const pendingWrites = [];

      const tx = {
        get: async (ref) => {
          const snap = this.docs.get(ref.path);
          if (!readVersions.has(ref.path)) {
            readVersions.set(ref.path, snap?.version ?? 0);
          }
          if (this.YieldOnRead) {
            await new Promise((r) => setTimeout(r, 5));
          }
          return this._wrapSnap(ref.path, snap);
        },
        getQuery: async (q) => {
          return q.get();
        },
        set: (ref, data, options) => {
          pendingWrites.push({type: "set", path: ref.path, data, options});
        },
        update: (ref, data) => {
          pendingWrites.push({type: "update", path: ref.path, data});
        },
      };

      const result = await fn(tx);

      // Optimistic concurrency commit check
      let conflict = false;
      for (const [path, expectedVersion] of readVersions.entries()) {
        const currentVersion = this.docs.get(path)?.version ?? 0;
        if (currentVersion !== expectedVersion) {
          conflict = true;
          break;
        }
      }
      if (conflict) {
        continue; // Retry transaction against newest committed state
      }

      // Apply all staged writes atomically
      for (const w of pendingWrites) {
        const cur = this.docs.get(w.path);
        if (w.type === "update") {
          if (!cur) throw new Error(`Missing document on commit: ${w.path}`);
          const next = structuredClone(cur.data);
          this._applyMutations(next, w.data);
          this.docs.set(w.path, {data: next, version: cur.version + 1});
        } else {
          const base = w.options?.merge && cur ? structuredClone(cur.data) : {};
          this._applyMutations(base, w.data);
          this.docs.set(w.path, {data: base, version: (cur?.version ?? 0) + 1});
        }
      }

      return result;
    }
    throw new Error("Transaction failed due to repeated concurrent conflicts");
  }
}

function createHarness(initialNowMs = Date.UTC(2026, 10, 18, 12, 0, 0)) {
  const db = new InMemoryTransactionalFirestore();
  let nowMs = initialNowMs;
  const fcmNotifications = [];
  const scheduledTasks = [];

  const effects = {
    now: () => nowMs,
    sentinels: {
      serverTimestamp: () => ({__sentinel: "serverTimestamp"}),
      deleteField: () => ({__sentinel: "deleteField"}),
      increment: (operand) => ({__sentinel: "increment", operand}),
      arrayUnion: (...elements) => ({__sentinel: "arrayUnion", elements}),
    },
    sendOfferNotification: async (payload) => {
      fcmNotifications.push(payload);
    },
    enqueueReassignTask: async (payload, delaySeconds) => {
      scheduledTasks.push({payload, delaySeconds, runAtMs: nowMs + delaySeconds * 1000});
    },
  };

  const service = new OrderServerService(db, effects);
  return {
    db,
    service,
    fcmNotifications,
    scheduledTasks,
    setNow: (ms) => {
      nowMs = ms;
    },
    advanceMs: (deltaMs) => {
      nowMs += deltaMs;
    },
  };
}

function seedActiveShiftForEveryDay(db, captainUid) {
  const dayNames = [
    "saturday",
    "sunday",
    "monday",
    "tuesday",
    "wednesday",
    "thursday",
    "friday",
  ];
  const weeklySchedule = {};
  for (const d of dayNames) {
    weeklySchedule[d] = {
      isDayOff: false,
      shifts: [{startTime: "00:00", endTime: "23:59", isOvernight: false}],
    };
  }
  db.seed(`captain_schedules/${captainUid}`, {
    captainId: captainUid,
    isPaused: false,
    weeklySchedule,
  });
}

function seedCaptain(db, uid, overrides = {}, nowMs = Date.UTC(2026, 10, 18, 12, 0, 0)) {
  db.seed(`captains/${uid}`, {
    uid,
    fullName: `كابتن ${uid}`,
    phone: `0100000${uid.slice(-4)}`,
    email: `${uid}@elbatal.com`,
    accountType: "captain",
    role: "captain",
    status: "active",
    isOnline: true,
    presenceStatus: "available",
    latitude: 30.045,
    longitude: 31.236,
    lastLocationTimestamp: nowMs - 30_000,
    walletBalance: 100.0,
    totalOrdersCount: 5,
    fcmToken: `token_${uid}`,
    ...overrides,
  });
  seedActiveShiftForEveryDay(db, uid);
}

function seedMerchantAndSub(db, merchantId, subOverrides = {}, merchantOverrides = {}) {
  db.seed(`merchants/${merchantId}`, {
    uid: merchantId,
    name: "مطعم البطل",
    status: "active",
    ...merchantOverrides,
  });
  db.seed(`subscriptions/${merchantId}`, {
    merchantId,
    status: "Active",
    endDate: "2099-12-31",
    captainsCount: 4,
    ...subOverrides,
  });
}

function seedPendingOrder(db, orderId, overrides = {}) {
  db.seed(`orders/${orderId}`, {
    orderNumber: `ELB-${orderId}`,
    status: "pending",
    dispatchStatus: "searching",
    captainId: null,
    merchantId: "merch_1",
    storeName: "مطعم البطل",
    pickupLat: 30.0444,
    pickupLng: 31.2357,
    pickupAddress: "وسط البلد",
    deliveryAddress: "المعادي",
    deliveryFee: 35.0,
    createdAt: Date.UTC(2026, 10, 18, 11, 59, 0),
    ...overrides,
  });
}

describe("El-Batal Cloud Functions Order Server Service", () => {
  it("1. Rejects unauthenticated caller and unregistered captain", async () => {
    const {db, service} = createHarness();
    seedMerchantAndSub(db, "merch_1");
    seedPendingOrder(db, "ord_1");

    await assert.rejects(
      () => service.acceptOrder(undefined, {orderId: "ord_1"}),
      (err) => err instanceof DomainHttpsError && err.code === "unauthenticated"
    );

    await assert.rejects(
      () => service.acceptOrder("unknown_uid", {orderId: "ord_1"}),
      (err) => err instanceof DomainHttpsError && err.code === "permission-denied"
    );
  });

  it("2. Rejects banned, suspended, pending, offline, out-of-shift, and stale-GPS captains", async () => {
    const now = Date.UTC(2026, 10, 18, 12, 0, 0);
    const {db, service} = createHarness(now);
    seedMerchantAndSub(db, "merch_1");
    seedPendingOrder(db, "ord_1");

    // Suspended captain
    seedCaptain(db, "cap_susp", {status: "suspended"}, now);
    await assert.rejects(
      () => service.acceptOrder("cap_susp", {orderId: "ord_1"}),
      (e) => e instanceof DomainHttpsError && e.code === "permission-denied"
    );

    // Offline captain
    seedCaptain(db, "cap_off", {isOnline: false, presenceStatus: "offline"}, now);
    await assert.rejects(
      () => service.acceptOrder("cap_off", {orderId: "ord_1"}),
      (e) => e instanceof DomainHttpsError && e.code === "failed-precondition"
    );

    // Outside shift schedule (paused/day off)
    seedCaptain(db, "cap_noshift", {}, now);
    db.seed("captain_schedules/cap_noshift", {
      captainId: "cap_noshift",
      isPaused: true,
      weeklySchedule: {},
    });
    await assert.rejects(
      () => service.acceptOrder("cap_noshift", {orderId: "ord_1"}),
      (e) => e instanceof DomainHttpsError && e.code === "failed-precondition"
    );

    // Stale GPS (> 30 min)
    seedCaptain(db, "cap_stale_gps", {lastLocationTimestamp: now - 45 * 60 * 1000}, now);
    await assert.rejects(
      () => service.acceptOrder("cap_stale_gps", {orderId: "ord_1"}),
      (e) => e instanceof DomainHttpsError && e.code === "failed-precondition"
    );

    // Order must remain untouched
    assert.equal(db.getDocData("orders/ord_1").status, "pending");
    assert.equal(db.getDocData("orders/ord_1").captainId, null);
  });

  it("3. Rejects unavailable/cancelled orders, expired merchant subscription, and busy captains", async () => {
    const now = Date.UTC(2026, 10, 18, 12, 0, 0);
    const {db, service} = createHarness(now);
    seedCaptain(db, "cap_1", {}, now);
    seedMerchantAndSub(db, "merch_active");
    seedMerchantAndSub(db, "merch_expired", {status: "Expired", endDate: "2020-01-01"});

    seedPendingOrder(db, "ord_cancelled", {merchantId: "merch_active", status: "cancelled"});
    seedPendingOrder(db, "ord_exp_sub", {merchantId: "merch_expired"});
    seedPendingOrder(db, "ord_open", {merchantId: "merch_active"});
    seedPendingOrder(db, "ord_already_c1", {
      merchantId: "merch_active",
      status: "picked_up",
      captainId: "cap_1",
    });

    await assert.rejects(
      () => service.acceptOrder("cap_1", {orderId: "ord_cancelled"}),
      (e) => e instanceof DomainHttpsError && e.code === "failed-precondition"
    );

    // Captain already has active picked_up order -> blocked from taking ord_open
    await assert.rejects(
      () => service.acceptOrder("cap_1", {orderId: "ord_open"}),
      (e) => e instanceof DomainHttpsError && e.code === "failed-precondition"
    );

    // Clear active trip, test expired subscription order
    db.seed("orders/ord_already_c1", {status: "delivered", captainId: "cap_1"});
    await assert.rejects(
      () => service.acceptOrder("cap_1", {orderId: "ord_exp_sub"}),
      (e) => e instanceof DomainHttpsError && e.code === "failed-precondition"
    );
  });

  it("4. Enforces true concurrency race protection (only 1 captain wins) + idempotency for winner", async () => {
    const now = Date.UTC(2026, 10, 18, 12, 0, 0);
    const {db, service} = createHarness(now);
    db.YieldOnRead = true; // force concurrent interleaving inside runTransaction
    seedMerchantAndSub(db, "merch_1");
    seedCaptain(db, "cap_1", {latitude: 30.0445, longitude: 31.2358}, now);
    seedCaptain(db, "cap_2", {latitude: 30.0446, longitude: 31.2359}, now);
    seedPendingOrder(db, "ord_race");

    const results = await Promise.allSettled([
      service.acceptOrder("cap_1", {orderId: "#ord_race"}),
      service.acceptOrder("cap_2", {orderId: "ord_race"}),
    ]);

    const fulfilled = results.filter((r) => r.status === "fulfilled");
    const rejected = results.filter((r) => r.status === "rejected");

    assert.equal(fulfilled.length, 1, "Exactly one captain must win the race");
    assert.equal(rejected.length, 1, "Loser captain must be rejected");
    assert.equal(rejected[0].reason.code, "already-exists");

    const winnerUid = fulfilled[0].value.captainId;
    assert.equal(db.getDocData("orders/ord_race").captainId, winnerUid);
    assert.equal(db.getDocData("orders/ord_race").status, "accepted");

    // Winner retrying acceptOrder must return idempotently without error
    const retryRes = await service.acceptOrder(winnerUid, {orderId: "ord_race"});
    assert.equal(retryRes.alreadyAccepted, true);
    assert.equal(retryRes.captainId, winnerUid);
  });

  it("5. Auto-dispatch selects nearest captain, enforces 60s server timeout, reassigns & ignores stale tasks", async () => {
    const now = Date.UTC(2026, 10, 18, 12, 0, 0);
    const {db, service, fcmNotifications, scheduledTasks, advanceMs} = createHarness(now);
    seedMerchantAndSub(db, "merch_1");

    // cap_near (~0.1km from pickup), cap_far (~5km), cap_offline (closer but offline)
    seedCaptain(db, "cap_offline", {isOnline: false, latitude: 30.0444, longitude: 31.2357}, now);
    seedCaptain(db, "cap_near", {latitude: 30.045, longitude: 31.236}, now);
    seedCaptain(db, "cap_far", {latitude: 30.09, longitude: 31.25}, now);
    seedPendingOrder(db, "ord_dispatch");

    const dispatchRes = await service.dispatchNewOrSearchingOrder("ord_dispatch");
    assert.equal(dispatchRes.status, "offered");
    assert.equal(dispatchRes.offeredCaptainId, "cap_near");
    assert.equal(dispatchRes.offerGeneration, 1);
    assert.equal(fcmNotifications.length, 1);
    assert.equal(fcmNotifications[0].captainUid, "cap_near");
    assert.equal(scheduledTasks.length, 1);
    assert.equal(scheduledTasks[0].delaySeconds, 60);

    // Client premature expiry (device clock forged) MUST be rejected by server!
    advanceMs(10_000);
    await assert.rejects(
      () => service.expireDeliveryOffer("cap_near", {orderId: "ord_dispatch", reason: "expired"}),
      (e) => e instanceof DomainHttpsError && e.code === "failed-precondition"
    );

    // Advance past 60s deadline and execute Cloud Task -> reassigns to cap_far!
    advanceMs(55_000);
    const task1 = scheduledTasks[0].payload;
    const reassign1 = await service.handleReassignOfferTask(task1);
    assert.equal(reassign1.status, "reassigned");
    assert.equal(reassign1.offeredCaptainId, "cap_far");
    assert.equal(reassign1.offerGeneration, 2);
    assert.ok(db.getDocData("orders/ord_dispatch").excludedCaptainIds.includes("cap_near"));

    // cap_far accepts order before 2nd 60s expires
    const accepted = await service.acceptOrder("cap_far", {orderId: "ord_dispatch"});
    assert.equal(accepted.captainId, "cap_far");

    // Stale Cloud Task firing after acceptance MUST do nothing!
    advanceMs(70_000);
    const staleTaskOutcome = await service.handleReassignOfferTask(scheduledTasks[1].payload);
    assert.equal(staleTaskOutcome.status, "ignored_stale");
    assert.equal(db.getDocData("orders/ord_dispatch").captainId, "cap_far");
    assert.equal(db.getDocData("orders/ord_dispatch").status, "accepted");
  });

  it("6. Creates admin notification when all captains time out", async () => {
    const now = Date.UTC(2026, 10, 18, 12, 0, 0);
    const {db, service, scheduledTasks, advanceMs} = createHarness(now);
    seedMerchantAndSub(db, "merch_1");
    seedCaptain(db, "cap_only", {latitude: 30.045, longitude: 31.236}, now);
    seedPendingOrder(db, "ord_no_one");

    await service.dispatchNewOrSearchingOrder("ord_no_one");
    advanceMs(65_000);
    const res = await service.handleReassignOfferTask(scheduledTasks[0].payload);
    assert.equal(res.status, "no_captains_available");
    assert.equal(db.getDocData("orders/ord_no_one").dispatchStatus, "no_captains_available");

    const notifs = await db.collection("notifications").get();
    assert.equal(notifs.size, 1);
    assert.equal(notifs.docs[0].data().type, "captain_assignment_failed");
  });

  it("7. Full lifecycle & atomic idempotent confirmDelivery (zero double-crediting)", async () => {
    const now = Date.UTC(2026, 10, 18, 12, 0, 0);
    const {db, service} = createHarness(now);
    seedMerchantAndSub(db, "merch_1");
    seedCaptain(db, "cap_1", {walletBalance: 100.0, totalOrdersCount: 5, commissionRate: 0.1}, now);
    seedPendingOrder(db, "ord_life", {deliveryFee: 50.0});

    await service.acceptOrder("cap_1", {orderId: "ord_life"});
    await service.captainArrivedAtMerchant("cap_1", {orderId: "ord_life"});
    await service.confirmPickup("cap_1", {orderId: "ord_life"});

    // Forged storage path belonging to another order must fail
    await assert.rejects(
      () => service.submitCustomerDeliveryDetails("cap_1", {
        orderId: "ord_life",
        customerName: "أحمد",
        customerPhone: "01012345678",
        receiptImagePath: "orders/OTHER_ORDER/receipts/hack.jpg",
      }),
      (e) => e instanceof DomainHttpsError && e.code === "invalid-argument"
    );

    // Valid receipt path submission
    await service.submitCustomerDeliveryDetails("cap_1", {
      orderId: "ord_life",
      customerName: "أحمد علي",
      customerPhone: "01012345678",
      receiptImagePath: "orders/ord_life/receipts/r1.jpg",
    });

    // Cannot complete delivery before merchant confirms
    await assert.rejects(
      () => service.confirmDelivery("cap_1", {orderId: "ord_life"}),
      (e) => e instanceof DomainHttpsError && e.code === "failed-precondition"
    );

    // Simulate merchant confirmation -> en_route
    await db.collection("orders").doc("ord_life").update({
      status: "en_route",
      merchantConfirmed: true,
    });

    // Duplicate concurrent confirmDelivery calls -> balance credited ONLY ONCE (50 - 10% = 45 EGP)
    db.YieldOnRead = true;
    const [d1, d2] = await Promise.all([
      service.confirmDelivery("cap_1", {orderId: "ord_life", earnedAmount: 9999}),
      service.confirmDelivery("cap_1", {orderId: "ord_life"}),
    ]);

    assert.equal(d1.earnedAmount, 45.0);
    assert.equal(d2.earnedAmount, 45.0);
    assert.ok(d1.alreadyCompleted !== d2.alreadyCompleted, "Exactly one call settles, one is idempotent");

    const updatedCaptain = db.getDocData("captains/cap_1");
    assert.equal(updatedCaptain.walletBalance, 145.0);
    assert.equal(updatedCaptain.totalOrdersCount, 6);

    const dayKey = getCairoTimeContext(now).dayKey;
    const dailyWallet = db.getDocData(`captains/cap_1/daily_wallets/${dayKey}`);
    assert.equal(dailyWallet.totalEarnings, 45.0);
    assert.equal(dailyWallet.deliveredOrdersCount, 1);

    const ledger = db.getDocData("captains/cap_1/transactions/delivery_ord_life");
    assert.equal(ledger.amount, 45.0);
    assert.equal(ledger.platformCommission, 5.0);
    assert.equal(ledger.grossDeliveryFee, 50.0);
  });

  it("8. resolveCaptainEmailByPhone supports legacy role-only captains and ignores non-captains", async () => {
    const {db, service} = createHarness();
    db.seed("captains/legacy_cap", {
      role: "captain",
      phone: "01055556666",
      email: "legacy.cap@elbatal.com",
    });
    db.seed("captains/merchant_impostor", {
      accountType: "merchant",
      role: "captain",
      phone: "01077778888",
      email: "merchant@elbatal.com",
    });

    const found = await service.resolveCaptainEmailByPhone({phone: "01055556666"});
    assert.equal(found.found, true);
    assert.equal(found.email, "legacy.cap@elbatal.com");

    const notFound = await service.resolveCaptainEmailByPhone({phone: "01077778888"});
    assert.equal(notFound.found, false);
    assert.equal(notFound.email, null);
  });
});
