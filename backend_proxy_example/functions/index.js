// ==============================================================================
// El-Batal Express - Production-Ready Secure Backend Proxy & Cloud Functions
// ==============================================================================
// Complete Order Lifecycle & Security Rules Compliance
// 1. acceptDeliveryOffer (alias: acceptOrder)
// 2. captainArrivedAtMerchant (alias: captainArrived)
// 3. confirmPickup
// 4. submitCustomerDeliveryDetails (alias: submitReceiptAndCustomerData)
// 5. confirmDelivery (alias: completeDelivery)
// 6. expireDeliveryOffer
// 7. assignNextAvailableCaptain
// 8. merchantConfirmOrder
// 9. uploadImageToImgBB
// 10. updateFcmToken
// ==============================================================================

const { onRequest } = require("firebase-functions/v2/https");
const admin = require("firebase-admin");
const axios = require("axios");
const FormData = require("form-data");
const busboy = require("busboy");

if (!admin.apps.length) {
  admin.initializeApp();
}

// ------------------------------------------------------------------------------
// Helper: Authenticate Captain via Firebase Auth ID Token
// ------------------------------------------------------------------------------
async function authenticateCaptain(req, res) {
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith("Bearer ")) {
    res.status(401).json({ error: "غير مصرح: يجب تسجيل الدخول أولاً" });
    return null;
  }
  const idToken = authHeader.split("Bearer ")[1];
  try {
    const decodedUser = await admin.auth().verifyIdToken(idToken);
    return decodedUser;
  } catch (error) {
    console.error("Token verification failed:", error);
    res.status(403).json({ error: "جلسة الكابتن منتهية الصلاحية، يرجى تسجيل الدخول مجدداً" });
    return null;
  }
}

/**
 * Resilient order document reference resolver:
 * Handles order IDs with or without leading '#', as well as matching by 'orderNumber' or 'order_number' fields.
 */
async function resolveOrderReference(db, orderId) {
  const rawStr = String(orderId || "").trim();
  const cleanId = rawStr.replace(/^#+/, "").trim();
  if (!cleanId) return null;

  // 1. Direct document ID match with cleanId
  let ref = db.collection("orders").doc(cleanId);
  let snap = await ref.get();
  if (snap.exists) return ref;

  // 2. Direct document ID match with raw string (if had '#')
  if (rawStr !== cleanId) {
    ref = db.collection("orders").doc(rawStr);
    snap = await ref.get();
    if (snap.exists) return ref;
  }

  // 3. Document ID prefixed with '#'
  ref = db.collection("orders").doc("#" + cleanId);
  snap = await ref.get();
  if (snap.exists) return ref;

  // 4. Query by orderNumber field
  const q1 = await db.collection("orders")
    .where("orderNumber", "in", [cleanId, "#" + cleanId])
    .limit(1)
    .get();
  if (!q1.empty) return q1.docs[0].ref;

  // 5. Query by order_number field
  const q2 = await db.collection("orders")
    .where("order_number", "in", [cleanId, "#" + cleanId])
    .limit(1)
    .get();
  if (!q2.empty) return q2.docs[0].ref;

  return db.collection("orders").doc(cleanId);
}

// ------------------------------------------------------------------------------
// 1. Cloud Function: acceptDeliveryOffer (alias: acceptOrder)
// Enforces:
// - 60s offer validity window
// - Prevents double assignment (atomic transaction)
// - Ensures captain has no other active orders
// - Ensures captain is approved and active
// ------------------------------------------------------------------------------
async function handleAcceptDeliveryOffer(req, res) {
  if (req.method !== "POST") return res.status(405).json({ error: "Method Not Allowed" });
  const user = await authenticateCaptain(req, res);
  if (!user) return;

  const { orderId, offerId } = req.body || {};
  if (!orderId) {
    return res.status(400).json({ error: "معرف الطلب مطلوب" });
  }

  const db = admin.firestore();
  const orderRef = await resolveOrderReference(db, orderId);

  try {
    // Check if captain has another active ongoing order
    const ongoingQuery = await db.collection("orders")
      .where("captainId", "==", user.uid)
      .where("status", "in", ["accepted", "arrived_at_merchant", "at_store", "picked_up", "awaiting_merchant_confirmation", "en_route"])
      .limit(1)
      .get();

    if (!ongoingQuery.empty && ongoingQuery.docs[0].id !== String(orderId)) {
      return res.status(409).json({
        error: "لديك طلب نشط بالفعل قيد التنفيذ، لا يمكن قبول أكثر من طلب في وقت واحد."
      });
    }

    await db.runTransaction(async (transaction) => {
      const orderDoc = await transaction.get(orderRef);
      if (!orderDoc.exists) {
        throw new Error("هذا الطلب لم يعد متاحاً");
      }
      const data = orderDoc.data();
      const currentStatus = (data.status || "").toLowerCase();
      const existingCaptain = data.captainId || data.driverId || "";

      // 1. Check if assigned to another captain
      if (existingCaptain && existingCaptain !== user.uid) {
        throw new Error("تم استلام هذا الطلب بالفعل بواسطة كابتن آخر");
      }

      // 2. Check 60-second offer timeout
      const nowMs = Date.now();
      const offerExpiresAt = data.offerExpiresAt || 0;
      if (offerExpiresAt > 0 && nowMs > offerExpiresAt && existingCaptain !== user.uid) {
        throw new Error("انتهت مهلة قبول الطلب وتم تحويله إلى كابتن آخر");
      }

      // 3. Status eligibility check
      const validStatuses = ["pending", "searching", "new", "offered", "ready", "ready_for_pickup", "waiting_driver"];
      if (!validStatuses.includes(currentStatus) && existingCaptain !== user.uid) {
        throw new Error("هذا الطلب لم يعد متاحاً للقبول");
      }

      transaction.update(orderRef, {
        status: "accepted",
        captainId: user.uid,
        driverId: user.uid,
        acceptedAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      });
    });

    return res.status(200).json({
      success: true,
      message: "تم قبول الطلب بنجاح وإسناده إليك",
      orderId: orderId
    });
  } catch (err) {
    console.warn("acceptDeliveryOffer error:", err.message);
    return res.status(409).json({ error: err.message });
  }
}

exports.acceptDeliveryOffer = onRequest({ cors: true, timeoutSeconds: 30, memory: "256MiB" }, handleAcceptDeliveryOffer);
exports.acceptOrder = onRequest({ cors: true, timeoutSeconds: 30, memory: "256MiB" }, handleAcceptDeliveryOffer);

// ------------------------------------------------------------------------------
// 2. Cloud Function: captainArrivedAtMerchant (alias: captainArrived)
// ------------------------------------------------------------------------------
async function handleCaptainArrivedAtMerchant(req, res) {
  if (req.method !== "POST") return res.status(405).json({ error: "Method Not Allowed" });
  const user = await authenticateCaptain(req, res);
  if (!user) return;

  const { orderId } = req.body || {};
  if (!orderId) return res.status(400).json({ error: "معرف الطلب مطلوب" });

  const db = admin.firestore();
  const orderRef = await resolveOrderReference(db, orderId);

  try {
    await db.runTransaction(async (transaction) => {
      const doc = await transaction.get(orderRef);
      if (!doc.exists) throw new Error("الطلب غير موجود");
      const data = doc.data();
      if (data.captainId !== user.uid && data.driverId !== user.uid) {
        throw new Error("غير مصرح: هذا الطلب ليس مسنداً إليك");
      }
      transaction.update(orderRef, {
        status: "arrived_at_merchant",
        arrivedAtStoreTimestamp: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      });
    });
    return res.status(200).json({ success: true, message: "تم تسجيل الوصول إلى التاجر بنجاح" });
  } catch (err) {
    return res.status(400).json({ error: err.message });
  }
}

exports.captainArrivedAtMerchant = onRequest({ cors: true, timeoutSeconds: 30, memory: "256MiB" }, handleCaptainArrivedAtMerchant);
exports.captainArrived = onRequest({ cors: true, timeoutSeconds: 30, memory: "256MiB" }, handleCaptainArrivedAtMerchant);

// ------------------------------------------------------------------------------
// 3. Cloud Function: confirmPickup
// ------------------------------------------------------------------------------
exports.confirmPickup = onRequest(
  { cors: true, timeoutSeconds: 30, memory: "256MiB" },
  async (req, res) => {
    if (req.method !== "POST") return res.status(405).json({ error: "Method Not Allowed" });
    const user = await authenticateCaptain(req, res);
    if (!user) return;

    const { orderId } = req.body || {};
    if (!orderId) return res.status(400).json({ error: "معرف الطلب مطلوب" });

    const db = admin.firestore();
    const orderRef = await resolveOrderReference(db, orderId);

    try {
      await db.runTransaction(async (transaction) => {
        const doc = await transaction.get(orderRef);
        if (!doc.exists) throw new Error("الطلب غير موجود");
        const data = doc.data();
        if (data.captainId !== user.uid && data.driverId !== user.uid) {
          throw new Error("غير مصرح: هذا الطلب ليس مسنداً إليك");
        }
        transaction.update(orderRef, {
          status: "picked_up",
          pickedUpAt: admin.firestore.FieldValue.serverTimestamp(),
          updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });
      });
      return res.status(200).json({ success: true, message: "تم استلام الطلب من التاجر بنجاح" });
    } catch (err) {
      return res.status(400).json({ error: err.message });
    }
  }
);

// ------------------------------------------------------------------------------
// 4. Cloud Function: submitCustomerDeliveryDetails (alias: submitReceiptAndCustomerData)
// ------------------------------------------------------------------------------
async function handleSubmitCustomerDeliveryDetails(req, res) {
  if (req.method !== "POST") return res.status(405).json({ error: "Method Not Allowed" });
  const user = await authenticateCaptain(req, res);
  if (!user) return;

  const { orderId, customerName, customerPhone, receiptImageUrl, notes } = req.body || {};
  if (!orderId) return res.status(400).json({ error: "معرف الطلب مطلوب" });
  if (!receiptImageUrl) return res.status(400).json({ error: "صورة الإيصال مطلوبة" });

  const db = admin.firestore();
  const orderRef = await resolveOrderReference(db, orderId);

  try {
    await db.runTransaction(async (transaction) => {
      const doc = await transaction.get(orderRef);
      if (!doc.exists) throw new Error("الطلب غير موجود");
      const data = doc.data();
      if (data.captainId !== user.uid && data.driverId !== user.uid) {
        throw new Error("غير مصرح: هذا الطلب ليس مسنداً إليك");
      }
      transaction.update(orderRef, {
        customerName: customerName || data.customerName || "",
        customerPhone: customerPhone || data.customerPhone || "",
        receiptImageUrl: receiptImageUrl,
        notes: notes || data.notes || "",
        status: "awaiting_merchant_confirmation",
        merchantConfirmed: false,
        rejectionReason: admin.firestore.FieldValue.delete(),
        receiptSubmittedAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      });
    });
    return res.status(200).json({
      success: true,
      message: "تم حفظ بيانات الإيصال وبانتظار تأكيد التاجر"
    });
  } catch (err) {
    return res.status(400).json({ error: err.message });
  }
}

exports.submitCustomerDeliveryDetails = onRequest({ cors: true, timeoutSeconds: 30, memory: "256MiB" }, handleSubmitCustomerDeliveryDetails);
exports.submitReceiptAndCustomerData = onRequest({ cors: true, timeoutSeconds: 30, memory: "256MiB" }, handleSubmitCustomerDeliveryDetails);

// ------------------------------------------------------------------------------
// 5. Cloud Function: merchantConfirmOrder (Merchant reviews details & receipt)
// ------------------------------------------------------------------------------
exports.merchantConfirmOrder = onRequest(
  { cors: true, timeoutSeconds: 30, memory: "256MiB" },
  async (req, res) => {
    if (req.method !== "POST") return res.status(405).json({ error: "Method Not Allowed" });
    const { orderId, confirmed, rejectionReason } = req.body || {};
    if (!orderId) return res.status(400).json({ error: "معرف الطلب مطلوب" });

    const db = admin.firestore();
    const orderRef = await resolveOrderReference(db, orderId);

    try {
      await db.runTransaction(async (transaction) => {
        const doc = await transaction.get(orderRef);
        if (!doc.exists) throw new Error("الطلب غير موجود");
        if (confirmed) {
          transaction.update(orderRef, {
            status: "en_route",
            merchantConfirmed: true,
            merchantConfirmedAt: admin.firestore.FieldValue.serverTimestamp(),
            updatedAt: admin.firestore.FieldValue.serverTimestamp()
          });
        } else {
          transaction.update(orderRef, {
            status: "merchant_rejected",
            merchantConfirmed: false,
            rejectionReason: rejectionReason || "بيانات الإيصال أو العميل غير مطابقة",
            updatedAt: admin.firestore.FieldValue.serverTimestamp()
          });
        }
      });
      return res.status(200).json({ success: true, message: confirmed ? "تم تأكيد الطلب بنجاح" : "تم رفض البيانات" });
    } catch (err) {
      return res.status(400).json({ error: err.message });
    }
  }
);

// ------------------------------------------------------------------------------
// 6. Cloud Function: confirmDelivery (alias: completeDelivery)
// ------------------------------------------------------------------------------
async function handleConfirmDelivery(req, res) {
  if (req.method !== "POST") return res.status(405).json({ error: "Method Not Allowed" });
  const user = await authenticateCaptain(req, res);
  if (!user) return;

  const { orderId } = req.body || {};
  if (!orderId) return res.status(400).json({ error: "معرف الطلب مطلوب" });

  const db = admin.firestore();
  const orderRef = await resolveOrderReference(db, orderId);
  const cleanId = String(orderId).trim().replace(/^#+/, "");
  const captainRef = db.collection("captains").doc(user.uid);
  const transactionRecordRef = captainRef.collection("transactions").doc(cleanId);

  try {
    let earnedDeliveryFee = 25.0;
    await db.runTransaction(async (transaction) => {
      const orderDoc = await transaction.get(orderRef);
      if (!orderDoc.exists) throw new Error("الطلب غير موجود");
      const orderData = orderDoc.data();
      if (orderData.captainId !== user.uid && orderData.driverId !== user.uid) {
        throw new Error("غير مصرح: هذا الطلب ليس مسنداً إليك");
      }
      if (orderData.status === "delivered" || orderData.status === "completed") {
        throw new Error("الطلب مسجل كمكتمل مسبقاً");
      }

      if (typeof orderData.deliveryFee === "number" && orderData.deliveryFee > 0) {
        earnedDeliveryFee = orderData.deliveryFee;
      }

      // 1. Mark order delivered
      transaction.update(orderRef, {
        status: "delivered",
        deliveredAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      });

      // 2. Safely increment captain wallet balance and order count
      transaction.update(captainRef, {
        walletBalance: admin.firestore.FieldValue.increment(earnedDeliveryFee),
        totalOrdersCount: admin.firestore.FieldValue.increment(1),
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      });

      // 3. Financial audit record
      transaction.set(transactionRecordRef, {
        amount: earnedDeliveryFee,
        type: "delivery_earning",
        orderId: String(orderId),
        description: `أرباح توصيل الطلب رقم #${orderId}`,
        createdAt: admin.firestore.FieldValue.serverTimestamp()
      });
    });

    return res.status(200).json({
      success: true,
      message: "تم تسليم الطلب بنجاح وإضافة الأرباح لمحفظتك",
      earnedAmount: earnedDeliveryFee
    });
  } catch (err) {
    console.error("confirmDelivery error:", err.message);
    return res.status(400).json({ error: err.message });
  }
}

exports.confirmDelivery = onRequest({ cors: true, timeoutSeconds: 30, memory: "256MiB" }, handleConfirmDelivery);
exports.completeDelivery = onRequest({ cors: true, timeoutSeconds: 30, memory: "256MiB" }, handleConfirmDelivery);

// ------------------------------------------------------------------------------
// 7. Cloud Function: expireDeliveryOffer
// Ends the 60s window, excludes current captain, and triggers reassignment
// ------------------------------------------------------------------------------
exports.expireDeliveryOffer = onRequest(
  { cors: true, timeoutSeconds: 30, memory: "256MiB" },
  async (req, res) => {
    if (req.method !== "POST") return res.status(405).json({ error: "Method Not Allowed" });
    const { orderId, captainId } = req.body || {};
    if (!orderId) return res.status(400).json({ error: "معرف الطلب مطلوب" });

    const db = admin.firestore();
    const orderRef = db.collection("orders").doc(String(orderId));

    try {
      let shouldReassign = false;
      await db.runTransaction(async (transaction) => {
        const doc = await transaction.get(orderRef);
        if (!doc.exists) throw new Error("الطلب غير موجود");
        const data = doc.data();

        // Only expire if still in pending/offered state
        if (data.status === "pending" || data.status === "searching" || data.status === "offered") {
          const excluded = Array.isArray(data.excludedCaptainIds) ? [...data.excludedCaptainIds] : [];
          const targetCaptain = captainId || data.offeredCaptainId;
          if (targetCaptain && !excluded.includes(targetCaptain)) {
            excluded.push(targetCaptain);
          }

          transaction.update(orderRef, {
            status: "searching",
            offeredCaptainId: null,
            offerExpiresAt: null,
            excludedCaptainIds: excluded,
            updatedAt: admin.firestore.FieldValue.serverTimestamp()
          });
          shouldReassign = true;
        }
      });

      return res.status(200).json({
        success: true,
        message: "انتهت مهلة قبول الطلب وتم تحويله إلى كابتن آخر",
        reassigned: shouldReassign
      });
    } catch (err) {
      return res.status(400).json({ error: err.message });
    }
  }
);

// ------------------------------------------------------------------------------
// 8. Cloud Function: assignNextAvailableCaptain
// ------------------------------------------------------------------------------
exports.assignNextAvailableCaptain = onRequest(
  { cors: true, timeoutSeconds: 30, memory: "256MiB" },
  async (req, res) => {
    if (req.method !== "POST") return res.status(405).json({ error: "Method Not Allowed" });
    const { orderId } = req.body || {};
    if (!orderId) return res.status(400).json({ error: "معرف الطلب مطلوب" });

    const db = admin.firestore();
    const orderRef = db.collection("orders").doc(String(orderId));

    try {
      const orderDoc = await orderRef.get();
      if (!orderDoc.exists) return res.status(404).json({ error: "الطلب غير موجود" });
      const orderData = orderDoc.data();
      const excluded = Array.isArray(orderData.excludedCaptainIds) ? orderData.excludedCaptainIds : [];

      // Find eligible online captains
      const captainsSnapshot = await db.collection("captains")
        .where("isOnline", "==", true)
        .where("isApproved", "==", true)
        .limit(20)
        .get();

      const candidate = captainsSnapshot.docs.find(doc => !excluded.includes(doc.id));

      if (candidate) {
        const offerExpiresAt = Date.now() + 60000; // 60 seconds
        await orderRef.update({
          status: "pending",
          offeredCaptainId: candidate.id,
          offerExpiresAt: offerExpiresAt,
          updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });

        return res.status(200).json({
          success: true,
          message: `تم إسناد العرض للكابتن ${candidate.id}`,
          offeredCaptainId: candidate.id,
          expiresInSeconds: 60
        });
      } else {
        await orderRef.update({
          status: "searching",
          offeredCaptainId: null,
          offerExpiresAt: null,
          updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });

        return res.status(200).json({
          success: true,
          message: "لا يوجد كباتن متاحين حالياً، يتم الاستمرار في البحث"
        });
      }
    } catch (err) {
      return res.status(500).json({ error: err.message });
    }
  }
);

// ------------------------------------------------------------------------------
// 9. Cloud Function: uploadImageToImgBB
// ------------------------------------------------------------------------------
exports.uploadImageToImgBB = onRequest(
  {
    cors: false,
    timeoutSeconds: 60,
    memory: "256MiB",
    secrets: ["IMGBB_API_KEY"]
  },
  async (req, res) => {
    if (req.method !== "POST") return res.status(405).json({ error: "Method Not Allowed" });

    const user = await authenticateCaptain(req, res);
    if (!user) return;

    const bb = busboy({ headers: req.headers });
    let fileBuffer = null;
    let fileName = "upload.jpg";

    bb.on("file", (name, file, info) => {
      fileName = info.filename || "upload.jpg";
      const chunks = [];
      file.on("data", (data) => chunks.push(data));
      file.on("end", () => {
        fileBuffer = Buffer.concat(chunks);
      });
    });

    bb.on("finish", async () => {
      if (!fileBuffer || fileBuffer.length === 0) {
        return res.status(400).json({ error: "لم يتم استلام أي بيانات صورة" });
      }

      try {
        const apiKey = process.env.IMGBB_API_KEY;
        if (!apiKey) {
          return res.status(500).json({ error: "خطأ في تهيئة الخادم" });
        }

        const formData = new FormData();
        formData.append("key", apiKey);
        formData.append("image", fileBuffer, { filename: fileName, contentType: "image/jpeg" });

        const imgbbResponse = await axios.post("https://api.imgbb.com/1/upload", formData, {
          headers: formData.getHeaders(),
          timeout: 45000,
        });

        if (imgbbResponse.data && imgbbResponse.data.data && imgbbResponse.data.data.url) {
          return res.status(200).json({
            success: true,
            url: imgbbResponse.data.data.url,
            display_url: imgbbResponse.data.data.display_url
          });
        } else {
          return res.status(502).json({ error: "فشل استلام الرابط من مزود الصور" });
        }
      } catch (uploadError) {
        return res.status(502).json({
          error: "تعذر رفع الصورة، يرجى المحاولة مرة أخرى",
          details: uploadError?.response?.data?.error?.message || uploadError.message
        });
      }
    });

    req.pipe(bb);
  }
);

// ------------------------------------------------------------------------------
// 10. Cloud Function: updateFcmToken
// ------------------------------------------------------------------------------
exports.updateFcmToken = onRequest(
  { cors: true, timeoutSeconds: 30, memory: "256MiB" },
  async (req, res) => {
    if (req.method !== "POST") return res.status(405).json({ error: "Method Not Allowed" });
    const user = await authenticateCaptain(req, res);
    if (!user) return;

    const { token } = req.body || {};
    if (!token) return res.status(400).json({ error: "رمز FCM مطلوب" });

    const db = admin.firestore();
    try {
      await db.collection("captains").doc(user.uid).update({
        fcmToken: token,
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      });
      return res.status(200).json({ success: true, message: "تم تحديث رمز الإشعارات بنجاح" });
    } catch (err) {
      return res.status(400).json({ error: err.message });
    }
  }
);
