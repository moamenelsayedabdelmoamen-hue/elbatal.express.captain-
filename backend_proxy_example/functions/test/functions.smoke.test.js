const Module = require('module');
const path = require('path').join(__dirname, '..', 'index.js');
const store = {}; // "col/id" -> data
const writes = [];
const FV = { serverTimestamp: () => '__ts__', increment: n => ({ inc: n }), delete: () => '__del__' };
function docRef(col, id) { return { _k: col + '/' + id, id, get: async () => ({ exists: (col + '/' + id) in store, id, data: () => store[col + '/' + id], ref: docRef(col, id) }) }; }
const fakeAdmin = {
  apps: [1], initializeApp() {},
  auth: () => ({ verifyIdToken: async t => { if (!t.startsWith('uid:')) throw new Error('bad'); return { uid: t.slice(4) }; } }),
  firestore: Object.assign(() => ({
    collection: c => ({
      doc: id => Object.assign(docRef(c, id), { collection: sc => ({ doc: sid => docRef(c + '/' + id + '/' + sc, sid) }) }),
      where: () => ({ where: () => ({ limit: () => ({ get: async () => ({ empty: true, docs: [] }) }) }), limit: () => ({ get: async () => ({ empty: true, docs: [] }) }) }),
    }),
    runTransaction: async fn => fn({
      get: async r => r.get(), update: (r, d) => writes.push(['update', r._k, d]), set: (r, d) => writes.push(['set', r._k, d]),
    }),
  }), { FieldValue: FV }),
};
const orig = Module._load;
Module._load = function (req, ...a) {
  if (req === 'firebase-functions/v2/https') return { onRequest: (o, h) => h || o };
  if (req === 'firebase-admin') return fakeAdmin;
  if (['axios', 'form-data', 'busboy'].includes(req)) return function () {};
  return orig.call(this, req, ...a);
};
const fns = require(path);
function call(fn, token, body) {
  return new Promise(resolve => {
    const res = { code: 200, status(c) { this.code = c; return this; }, json(b) { resolve([this.code, b.error || b.message || 'ok']); } };
    fn({ method: 'POST', headers: token ? { authorization: 'Bearer ' + token } : {}, body }, res);
  });
}
(async () => {
  store['restaurants/M1'] = {}; store['restaurants/M2'] = {};
  store['captains/C1'] = { status: 'active' }; store['captains/C2'] = { status: 'pending' }; store['captains/C3'] = { status: 'active', isBlocked: true };
  store['admins/A1'] = {};
  const ord = (o) => store['orders/O1'] = { restaurantId: 'M1', status: 'awaiting_merchant_confirmation', captainId: 'C1', ...o };
  const t = async (name, p, expectOk) => { const [c, m] = await p; const ok = c === 200; console.log((ok === expectOk ? 'PASS' : 'FAIL'), name, '->', c, m); };

  ord(); await t('merchantConfirm: no token', call(fns.merchantConfirmOrder, null, { orderId: 'O1', confirmed: true }), false);
  ord(); await t('merchantConfirm: OTHER merchant', call(fns.merchantConfirmOrder, 'uid:M2', { orderId: 'O1', confirmed: true }), false);
  ord(); await t('merchantConfirm: owning merchant', call(fns.merchantConfirmOrder, 'uid:M1', { orderId: 'O1', confirmed: true }), true);
  ord({ status: 'picked_up' }); await t('merchantConfirm: wrong state', call(fns.merchantConfirmOrder, 'uid:M1', { orderId: 'O1', confirmed: true }), false);
  ord(); await t('merchantConfirm: admin', call(fns.merchantConfirmOrder, 'uid:A1', { orderId: 'O1', confirmed: false }), true);

  ord({ status: 'searching', offeredCaptainId: 'C1', captainId: undefined });
  await t('expire: random user', call(fns.expireDeliveryOffer, 'uid:C2', { orderId: 'O1' }), false);
  await t('expire: offered captain', call(fns.expireDeliveryOffer, 'uid:C1', { orderId: 'O1' }), true);
  await t('assignNext: no token', call(fns.assignNextAvailableCaptain, null, { orderId: 'O1' }), false);
  await t('assignNext: other merchant', call(fns.assignNextAvailableCaptain, 'uid:M2', { orderId: 'O1' }), false);

  ord({ status: 'pending', captainId: undefined });
  await t('accept: pending (unapproved) captain', call(fns.acceptDeliveryOffer, 'uid:C2', { orderId: 'O1' }), false);
  await t('accept: blocked captain', call(fns.acceptDeliveryOffer, 'uid:C3', { orderId: 'O1' }), false);
  await t('accept: non-captain user', call(fns.acceptDeliveryOffer, 'uid:M1', { orderId: 'O1' }), false);
  await t('accept: approved captain', call(fns.acceptDeliveryOffer, 'uid:C1', { orderId: 'O1' }), true);
  ord({ status: 'pending', captainId: undefined, offeredCaptainId: 'C9', offerExpiresAt: Date.now() + 30000 });
  await t('accept: offer reserved for someone else', call(fns.acceptDeliveryOffer, 'uid:C1', { orderId: 'O1' }), false);

  ord({ status: 'accepted' }); await t('confirmDelivery right after accept (fee exploit)', call(fns.confirmDelivery, 'uid:C1', { orderId: 'O1' }), false);
  store['captains/C1'] = { status: 'active' };
  ord({ status: 'picked_up', deliveryFee: 99999 }); writes.length = 0;
  await t('confirmDelivery after pickup', call(fns.confirmDelivery, 'uid:C1', { orderId: 'O1' }), true);
  const inc = writes.find(w => w[1] === 'captains/C1')[2].walletBalance.inc; console.log(inc === 200 ? 'PASS' : 'FAIL', 'fee capped at', inc);
  ord({ status: 'customer_confirmed' }); await t('confirmDelivery from merchant app status customer_confirmed', call(fns.confirmDelivery, 'uid:C1', { orderId: 'O1' }), true);
})();
