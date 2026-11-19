const { test, describe, before, after, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require('@firebase/rules-unit-testing');

const {
  doc,
  getDoc,
  setDoc,
  updateDoc,
  deleteDoc,
  collection,
  getDocs,
  query,
  where,
} = require('@firebase/firestore');

const {
  ref,
  uploadBytes,
  getBytes,
  deleteObject,
} = require('@firebase/storage');

const PROJECT_ID = 'el-batal-express-956ae';
const BUCKET_URL = `gs://${PROJECT_ID}.firebasestorage.app`;

// Valid minimal JPEG payload bytes for Storage MIME/size checks
const SAMPLE_JPEG_BYTES = Uint8Array.from([
  0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46,
  0x49, 0x46, 0x00, 0x01, 0x01, 0x01, 0x00, 0x48,
  0x00, 0x48, 0x00, 0x00, 0xff, 0xd9,
]);

let testEnv;

async function seedBaseEcosystem() {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminDb = ctx.firestore();

    // Provision Admin account
    await setDoc(doc(adminDb, 'admins', 'admin_1'), {
      role: 'admin',
      email: 'admin@elbatal.com',
    });

  // Provision two canonical Merchant accounts
await setDoc(doc(adminDb, 'merchants', 'merchant_1'), {
  uid: 'merchant_1',
  accountType: 'merchant',
  role: 'merchant',
  name: 'مطعم البطل الأول',
  email: 'merchant1@elbatal.com',
  phone: '01000000001',
  address: 'مدينة نصر',
  status: 'active',
  isOpen: true,
});

await setDoc(doc(adminDb, 'merchants', 'merchant_2'), {
  uid: 'merchant_2',
  accountType: 'merchant',
  role: 'merchant',
  name: 'مطعم الشرق الثاني',
  email: 'merchant2@elbatal.com',
  phone: '01000000002',
  address: 'المعادي',
  status: 'active',
  isOpen: true,
});

    // Provision two distinct Captain accounts (captain_1 actively linked to merchant_1; captain_2 unlinked)
    await setDoc(doc(adminDb, 'captains', 'captain_1'), {
      uid: 'captain_1',
      name: 'كابتن أحمد',
      fullName: 'كابتن أحمد',
      phone: '01011111111',
      email: 'captain1@elbatal.com',
      accountType: 'captain',
      role: 'captain',
      status: 'active',
      presenceStatus: 'on_trip',
      walletBalance: 120.0,
      rating: 4.9,
      totalOrdersCount: 15,
      activeMerchantId: 'merchant_1',
      profileImagePath: 'captains/captain_1/profile/profile.jpg',
      idFrontImagePath: 'captains/captain_1/documents/id_front.jpg',
      idBackImagePath: 'captains/captain_1/documents/id_back.jpg',
      idDocumentsComplete: true,
    });

    await setDoc(doc(adminDb, 'captains', 'captain_2'), {
      uid: 'captain_2',
      name: 'كابتن محمد',
      fullName: 'كابتن محمد',
      phone: '01022222222',
      email: 'captain2@elbatal.com',
      accountType: 'captain',
      role: 'captain',
      status: 'active',
      presenceStatus: 'available',
      walletBalance: 50.0,
      rating: 5.0,
      totalOrdersCount: 4,
      profileImagePath: 'captains/captain_2/profile/profile.jpg',
      idFrontImagePath: 'captains/captain_2/documents/id_front.jpg',
      idBackImagePath: 'captains/captain_2/documents/id_back.jpg',
      idDocumentsComplete: true,
    });

    // Provision regular customer account
    await setDoc(doc(adminDb, 'users', 'user_1'), {
      name: 'عميل تجريبي',
      phone: '01199999999',
      role: 'customer',
    });

    // Provision Orders across various states
    await setDoc(doc(adminDb, 'orders', 'order_pending_open'), {
      orderNumber: 'EXP-100',
      status: 'pending',
      captainId: '',
      offeredCaptainId: 'captain_1',
      eligibleCaptainIds: ['captain_1', 'captain_2'],
      merchantId: 'merchant_1',
      customerId: 'user_1',
      deliveryFee: 30,
    });

    await setDoc(doc(adminDb, 'orders', 'order_picked_up_c1'), {
      orderNumber: 'EXP-101',
      status: 'picked_up',
      captainId: 'captain_1',
      merchantId: 'merchant_1',
      customerId: 'user_1',
      deliveryFee: 35,
    });

    await setDoc(doc(adminDb, 'orders', 'order_rejected_c1'), {
      orderNumber: 'EXP-102',
      status: 'merchant_rejected',
      captainId: 'captain_1',
      merchantId: 'merchant_1',
      customerId: 'user_1',
      deliveryFee: 35,
    });

    await setDoc(doc(adminDb, 'orders', 'order_closed_delivered'), {
      orderNumber: 'EXP-103',
      status: 'delivered',
      captainId: 'captain_1',
      merchantId: 'merchant_1',
      customerId: 'user_1',
      deliveryFee: 40,
      receiptImagePath: 'orders/order_closed_delivered/receipts/orig.jpg',
    });

    await setDoc(doc(adminDb, 'orders', 'order_closed_cancelled'), {
      orderNumber: 'EXP-104',
      status: 'cancelled',
      captainId: 'captain_1',
      merchantId: 'merchant_1',
      customerId: 'user_1',
      deliveryFee: 25,
    });

    await setDoc(doc(adminDb, 'orders', 'order_unknown_status'), {
      orderNumber: 'EXP-105',
      status: 'mystery_custom_state',
      captainId: 'captain_1',
      merchantId: 'merchant_1',
      customerId: 'user_1',
      deliveryFee: 25,
    });

    await setDoc(doc(adminDb, 'orders', 'order_en_route_c1'), {
      orderNumber: 'EXP-106',
      status: 'en_route',
      captainId: 'captain_1',
      merchantId: 'merchant_1',
      customerId: 'user_1',
      deliveryFee: 30,
    });

    await setDoc(doc(adminDb, 'orders', 'order_c2_m2'), {
      orderNumber: 'EXP-201',
      status: 'picked_up',
      captainId: 'captain_2',
      merchantId: 'merchant_2',
      customerId: 'user_other',
      deliveryFee: 45,
    });

    // Seed private Storage objects (profile photos, National ID cards, and an order receipt)
    const adminStorage = ctx.storage(BUCKET_URL);
    await uploadBytes(
      ref(adminStorage, 'captains/captain_1/profile/profile.jpg'),
      SAMPLE_JPEG_BYTES,
      { contentType: 'image/jpeg' }
    );
    await uploadBytes(
      ref(adminStorage, 'captains/captain_1/documents/id_front.jpg'),
      SAMPLE_JPEG_BYTES,
      { contentType: 'image/jpeg' }
    );
    await uploadBytes(
      ref(adminStorage, 'captains/captain_1/documents/id_back.jpg'),
      SAMPLE_JPEG_BYTES,
      { contentType: 'image/jpeg' }
    );
    await uploadBytes(
      ref(adminStorage, 'captains/captain_2/documents/id_front.jpg'),
      SAMPLE_JPEG_BYTES,
      { contentType: 'image/jpeg' }
    );
    await uploadBytes(
      ref(adminStorage, 'captains/captain_2/documents/id_back.jpg'),
      SAMPLE_JPEG_BYTES,
      { contentType: 'image/jpeg' }
    );
    await uploadBytes(
      ref(adminStorage, 'orders/order_picked_up_c1/receipts/receipt_c1.jpg'),
      SAMPLE_JPEG_BYTES,
      {
        contentType: 'image/jpeg',
        customMetadata: { orderId: 'order_picked_up_c1', captainId: 'captain_1' },
      }
    );
  });
}

describe('El-Batal Express Real Firebase Emulator Security Audit (Firestore + Storage)', () => {
  before(async () => {
    testEnv = await initializeTestEnvironment({
      projectId: PROJECT_ID,
      firestore: {
        host: '127.0.0.1',
        port: 8085,
        rules: fs.readFileSync(path.join(__dirname, 'firestore.rules'), 'utf8'),
      },
      storage: {
        host: '127.0.0.1',
        port: 9199,
        rules: fs.readFileSync(path.join(__dirname, 'storage.rules'), 'utf8'),
      },
    });
  });

  beforeEach(async () => {
    await testEnv.clearFirestore();
    await testEnv.clearStorage();
    await seedBaseEcosystem();
  });

  after(async () => {
    if (testEnv) {
      await testEnv.cleanup();
    }
  });

  test('1. Unauthenticated public access is strictly denied across Firestore and Storage', async () => {
    const anonCtx = testEnv.unauthenticatedContext();
    const anonDb = anonCtx.firestore();
    const anonStorage = anonCtx.storage(BUCKET_URL);

    await assertFails(getDoc(doc(anonDb, 'captains', 'captain_1')));
    await assertFails(getDocs(collection(anonDb, 'captains')));
    await assertFails(getDoc(doc(anonDb, 'orders', 'order_pending_open')));
    await assertFails(getDocs(collection(anonDb, 'orders')));
    await assertFails(getDoc(doc(anonDb, 'restaurants', 'merchant_1')));

    await assertFails(getBytes(ref(anonStorage, 'captains/captain_1/profile/profile.jpg')));
    await assertFails(getBytes(ref(anonStorage, 'captains/captain_1/documents/id_front.jpg')));
    await assertFails(getBytes(ref(anonStorage, 'orders/order_picked_up_c1/receipts/receipt_c1.jpg')));
  });

  test('2. Two Captains isolation: each accesses own ID cards & profile, CANNOT read or overwrite another captain ID card', async () => {
    const cap1Ctx = testEnv.authenticatedContext('captain_1');
    const cap2Ctx = testEnv.authenticatedContext('captain_2');

    const cap1Storage = cap1Ctx.storage(BUCKET_URL);
    const cap2Storage = cap2Ctx.storage(BUCKET_URL);
    const cap1Db = cap1Ctx.firestore();

    // Captain 1 reads & replaces own profile & ID cards -> ALLOWED
    await assertSucceeds(getBytes(ref(cap1Storage, 'captains/captain_1/profile/profile.jpg')));
    await assertSucceeds(getBytes(ref(cap1Storage, 'captains/captain_1/documents/id_front.jpg')));
    await assertSucceeds(getBytes(ref(cap1Storage, 'captains/captain_1/documents/id_back.jpg')));
    await assertSucceeds(
      uploadBytes(ref(cap1Storage, 'captains/captain_1/documents/id_front.jpg'), SAMPLE_JPEG_BYTES, {
        contentType: 'image/jpeg',
      })
    );

    // Captain 1 attempts to read or overwrite Captain 2's National ID front/back -> DENIED
    await assertFails(getBytes(ref(cap1Storage, 'captains/captain_2/documents/id_front.jpg')));
    await assertFails(getBytes(ref(cap1Storage, 'captains/captain_2/documents/id_back.jpg')));
    await assertFails(
      uploadBytes(ref(cap1Storage, 'captains/captain_2/documents/id_front.jpg'), SAMPLE_JPEG_BYTES, {
        contentType: 'image/jpeg',
      })
    );

    // Captain 2 attempts to read Captain 1's National ID cards or profile -> DENIED
    await assertFails(getBytes(ref(cap2Storage, 'captains/captain_1/documents/id_front.jpg')));
    await assertFails(getBytes(ref(cap2Storage, 'captains/captain_1/documents/id_back.jpg')));
    await assertFails(getBytes(ref(cap2Storage, 'captains/captain_1/profile/profile.jpg')));

    // Captain 1 cannot delete own ID card directly (admin/server only)
    await assertFails(deleteObject(ref(cap1Storage, 'captains/captain_1/documents/id_front.jpg')));

    // Firestore cross-captain profile read/update is ALSO blocked
    await assertSucceeds(getDoc(doc(cap1Db, 'captains', 'captain_1')));
    await assertFails(getDoc(doc(cap1Db, 'captains', 'captain_2')));
  });

  test('3. Order receipt uploads enforce EXPLICIT status allowlist and reject closed, unknown, or other-captain orders', async () => {
    const cap1Storage = testEnv.authenticatedContext('captain_1').storage(BUCKET_URL);
    const cap2Storage = testEnv.authenticatedContext('captain_2').storage(BUCKET_URL);

    // ALLOWED: status == 'picked_up' and assigned to captain_1 with matching metadata
    await assertSucceeds(
      uploadBytes(ref(cap1Storage, 'orders/order_picked_up_c1/receipts/new_1.jpg'), SAMPLE_JPEG_BYTES, {
        contentType: 'image/jpeg',
        customMetadata: { orderId: 'order_picked_up_c1', captainId: 'captain_1' },
      })
    );

    // ALLOWED: status == 'merchant_rejected' (re-uploading clearer receipt)
    await assertSucceeds(
      uploadBytes(ref(cap1Storage, 'orders/order_rejected_c1/receipts/retry_1.jpg'), SAMPLE_JPEG_BYTES, {
        contentType: 'image/jpeg',
        customMetadata: { orderId: 'order_rejected_c1', captainId: 'captain_1' },
      })
    );

    // DENIED: Closed order ('delivered')
    await assertFails(
      uploadBytes(ref(cap1Storage, 'orders/order_closed_delivered/receipts/hack.jpg'), SAMPLE_JPEG_BYTES, {
        contentType: 'image/jpeg',
        customMetadata: { orderId: 'order_closed_delivered', captainId: 'captain_1' },
      })
    );

    // DENIED: Closed order ('cancelled')
    await assertFails(
      uploadBytes(ref(cap1Storage, 'orders/order_closed_cancelled/receipts/hack.jpg'), SAMPLE_JPEG_BYTES, {
        contentType: 'image/jpeg',
        customMetadata: { orderId: 'order_closed_cancelled', captainId: 'captain_1' },
      })
    );

    // DENIED: Unknown / arbitrary status ('mystery_custom_state')
    await assertFails(
      uploadBytes(ref(cap1Storage, 'orders/order_unknown_status/receipts/hack.jpg'), SAMPLE_JPEG_BYTES, {
        contentType: 'image/jpeg',
        customMetadata: { orderId: 'order_unknown_status', captainId: 'captain_1' },
      })
    );

    // DENIED: Non-allowlisted status ('en_route' after merchant already confirmed, or 'pending')
    await assertFails(
      uploadBytes(ref(cap1Storage, 'orders/order_en_route_c1/receipts/hack.jpg'), SAMPLE_JPEG_BYTES, {
        contentType: 'image/jpeg',
        customMetadata: { orderId: 'order_en_route_c1', captainId: 'captain_1' },
      })
    );
    await assertFails(
      uploadBytes(ref(cap1Storage, 'orders/order_pending_open/receipts/hack.jpg'), SAMPLE_JPEG_BYTES, {
        contentType: 'image/jpeg',
        customMetadata: { orderId: 'order_pending_open', captainId: 'captain_1' },
      })
    );

    // DENIED: Captain 2 attempting to upload or read receipt on Captain 1's order
    await assertFails(
      uploadBytes(ref(cap2Storage, 'orders/order_picked_up_c1/receipts/spoof.jpg'), SAMPLE_JPEG_BYTES, {
        contentType: 'image/jpeg',
        customMetadata: { orderId: 'order_picked_up_c1', captainId: 'captain_2' },
      })
    );
    await assertFails(getBytes(ref(cap2Storage, 'orders/order_picked_up_c1/receipts/receipt_c1.jpg')));
  });

  test('4. Merchant permissions: reads ONLY own order receipt, ZERO access to Captain ID cards, scoped Captain profile photo', async () => {
    const merch1Storage = testEnv.authenticatedContext('merchant_1').storage(BUCKET_URL);
    const merch2Storage = testEnv.authenticatedContext('merchant_2').storage(BUCKET_URL);
    const merch1Db = testEnv.authenticatedContext('merchant_1').firestore();
    const merch2Db = testEnv.authenticatedContext('merchant_2').firestore();

    // Merchant 1 CAN read receipt of order_picked_up_c1 (merchantId == 'merchant_1')
    await assertSucceeds(getBytes(ref(merch1Storage, 'orders/order_picked_up_c1/receipts/receipt_c1.jpg')));

    // Merchant 2 CANNOT read receipt of Merchant 1's order
    await assertFails(getBytes(ref(merch2Storage, 'orders/order_picked_up_c1/receipts/receipt_c1.jpg')));

    // Merchant 1 CANNOT upload/overwrite order receipts
    await assertFails(
      uploadBytes(ref(merch1Storage, 'orders/order_picked_up_c1/receipts/m_write.jpg'), SAMPLE_JPEG_BYTES, {
        contentType: 'image/jpeg',
        customMetadata: { orderId: 'order_picked_up_c1', captainId: 'merchant_1' },
      })
    );

    // STRICT PRIVACY: Merchant 1 & Merchant 2 CANNOT read Captain National ID cards under ANY condition!
    await assertFails(getBytes(ref(merch1Storage, 'captains/captain_1/documents/id_front.jpg')));
    await assertFails(getBytes(ref(merch1Storage, 'captains/captain_1/documents/id_back.jpg')));
    await assertFails(getBytes(ref(merch2Storage, 'captains/captain_2/documents/id_front.jpg')));

    // SCOPED PROFILE PHOTO:
    // Merchant 1 is actively linked to captain_1 (activeMerchantId == 'merchant_1') -> ALLOWED
    await assertSucceeds(getBytes(ref(merch1Storage, 'captains/captain_1/profile/profile.jpg')));
    // Merchant 2 is NOT linked to captain_1 -> DENIED (prevents merchants scraping all captain photos)
    await assertFails(getBytes(ref(merch2Storage, 'captains/captain_1/profile/profile.jpg')));

    // Firestore orders isolation for merchants
    await assertSucceeds(getDoc(doc(merch1Db, 'orders', 'order_picked_up_c1')));
    await assertFails(getDoc(doc(merch2Db, 'orders', 'order_picked_up_c1')));
  });

  test('5. Firestore financial, subscription, restaurant, and order write protections', async () => {
    const cap1Db = testEnv.authenticatedContext('captain_1').firestore();
    const user1Db = testEnv.authenticatedContext('user_1').firestore();
    const merch1Db = testEnv.authenticatedContext('merchant_1').firestore();

    // Captain 1 CAN update safe operational/presence/image-path fields on own doc
    await assertSucceeds(
      updateDoc(doc(cap1Db, 'captains', 'captain_1'), {
        presenceStatus: 'available',
        isOnline: true,
        profileImagePath: 'captains/captain_1/profile/profile.jpg',
      })
    );

    // Captain 1 CANNOT tamper with walletBalance, subscriptions, rating, status, role, or activeMerchantId
    await assertFails(updateDoc(doc(cap1Db, 'captains', 'captain_1'), { walletBalance: 99999 }));
    await assertFails(updateDoc(doc(cap1Db, 'captains', 'captain_1'), { subscriptionStatus: 'premium' }));
    await assertFails(updateDoc(doc(cap1Db, 'captains', 'captain_1'), { status: 'active', isApproved: true }));
    await assertFails(updateDoc(doc(cap1Db, 'captains', 'captain_1'), { rating: 5.0, totalOrdersCount: 500 }));
    await assertFails(updateDoc(doc(cap1Db, 'captains', 'captain_1'), { activeMerchantId: 'merchant_2' }));

    // Captain 1 CANNOT write to financial subcollections (daily_wallets / transactions / subscriptions)
    await assertFails(
      setDoc(doc(cap1Db, 'captains', 'captain_1', 'daily_wallets', '2026-11-10'), {
        walletBalance: 5000,
      })
    );
    await assertFails(
      setDoc(doc(cap1Db, 'captains', 'captain_1', 'transactions', 'tx_fake'), {
        amount: 500,
      })
    );

    // Captain 1 CAN run scoped queries for eligible pending orders and own assigned orders,
    // CANNOT read another captain's order, and CANNOT mutate ANY order directly in Firestore!
    const pendingSnap = await assertSucceeds(
      getDocs(
        query(
          collection(cap1Db, 'orders'),
          where('status', '==', 'pending'),
          where('eligibleCaptainIds', 'array-contains', 'captain_1')
        )
      )
    );
    assert.equal(pendingSnap.size, 1);

    const ownOrdersSnap = await assertSucceeds(
      getDocs(query(collection(cap1Db, 'orders'), where('captainId', '==', 'captain_1')))
    );
    assert.equal(ownOrdersSnap.size, 6);

    await assertFails(getDoc(doc(cap1Db, 'orders', 'order_c2_m2')));
    await assertFails(updateDoc(doc(cap1Db, 'orders', 'order_pending_open'), { status: 'accepted', captainId: 'captain_1' }));
    await assertFails(updateDoc(doc(cap1Db, 'orders', 'order_picked_up_c1'), { status: 'arrived_at_merchant' }));
    await assertFails(updateDoc(doc(cap1Db, 'orders', 'order_picked_up_c1'), { status: 'delivered', deliveryFee: 9999 }));
    await assertFails(setDoc(doc(cap1Db, 'orders', 'forged_order'), { status: 'pending', merchantId: 'merchant_1' }));
    await assertFails(deleteDoc(doc(cap1Db, 'orders', 'order_picked_up_c1')));

    // Regular users and captains CANNOT create/modify/delete restaurants, merchants, or legacy drivers
    await assertFails(updateDoc(doc(user1Db, 'restaurants', 'merchant_1'), { name: 'Hacked' }));
    await assertFails(updateDoc(doc(cap1Db, 'restaurants', 'merchant_1'), { isOpen: false }));
    await assertFails(setDoc(doc(user1Db, 'restaurants', 'fake_rest'), { name: 'Fake' }));
    await assertFails(setDoc(doc(cap1Db, 'merchants', 'captain_1'), { name: 'Spoof Merchant' }));
    await assertFails(setDoc(doc(cap1Db, 'drivers', 'captain_1'), { uid: 'captain_1', accountType: 'captain' }));

    // Merchant CANNOT read captain PII docs or register themselves in captains
    await assertFails(getDoc(doc(merch1Db, 'captains', 'captain_1')));
    await assertFails(setDoc(doc(merch1Db, 'captains', 'merchant_1'), { uid: 'merchant_1', accountType: 'captain', status: 'pending' }));

    // Captain CANNOT mutate accountType or claim emailVerified=true without verified token
    await assertFails(updateDoc(doc(cap1Db, 'captains', 'captain_1'), { accountType: 'merchant' }));
    await assertFails(updateDoc(doc(cap1Db, 'captains', 'captain_1'), { emailVerified: true }));

    // Merchant 1 CAN update allowed store info on their own restaurant, CANNOT update Merchant 2's restaurant
    await assertSucceeds(updateDoc(doc(merch1Db, 'restaurants', 'merchant_1'), { isOpen: false }));
    await assertFails(updateDoc(doc(merch1Db, 'restaurants', 'merchant_2'), { isOpen: false }));

    // Nobody can self-grant Admin privileges
    await assertFails(setDoc(doc(cap1Db, 'admins', 'captain_1'), { role: 'admin' }));
    await assertFails(setDoc(doc(user1Db, 'admins', 'user_1'), { role: 'admin' }));
  });

  test('6. Admin account has verified management access across Storage and Firestore', async () => {
    const adminCtx = testEnv.authenticatedContext('admin_1');
    const adminDb = adminCtx.firestore();
    const adminStorage = adminCtx.storage(BUCKET_URL);

    // Admin can review Captain National IDs, profile photos, and order receipts
    await assertSucceeds(getBytes(ref(adminStorage, 'captains/captain_1/documents/id_front.jpg')));
    await assertSucceeds(getBytes(ref(adminStorage, 'captains/captain_2/documents/id_back.jpg')));
    await assertSucceeds(getBytes(ref(adminStorage, 'orders/order_picked_up_c1/receipts/receipt_c1.jpg')));

    // Admin can manage captain approval/wallet, orders, and restaurants
    await assertSucceeds(
      updateDoc(doc(adminDb, 'captains', 'captain_1'), {
        walletBalance: 250.0,
        status: 'active',
      })
    );
    await assertSucceeds(
      updateDoc(doc(adminDb, 'orders', 'order_picked_up_c1'), {
        status: 'en_route',
        merchantConfirmed: true,
      })
    );
    await assertSucceeds(
      setDoc(doc(adminDb, 'restaurants', 'merchant_3'), {
        name: 'مطعم جديد معتمد',
        isOpen: true,
      })
    );
  });
});
