// Twirlly server: checks Google Play purchases and keeps the coin wallet. Only this code can write the wallet.
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { google } = require("googleapis");
const crypto = require("crypto");

initializeApp();
const db = getFirestore();

const PACKAGE = "com.example.twirlly"; // change this to your real package name before release
const COINS = {
  twirlly_coins_70: 70, twirlly_coins_350: 350, twirlly_coins_700: 700,
  twirlly_coins_1400: 1400, twirlly_coins_3500: 3500, twirlly_coins_7000: 7000,
};
const SUBS = new Set(["twirlly_premium_monthly", "twirlly_premium_yearly"]);

function needUser(req) {
  const t = req.auth && req.auth.token;
  if (!t) throw new HttpsError("unauthenticated", "Sign in first.");
  const ok = t.email_verified === true || (t.firebase && t.firebase.sign_in_provider === "google.com");
  if (!ok) throw new HttpsError("permission-denied", "Confirm your email first.");
  return req.auth.uid;
}

function publisher() {
  const auth = new google.auth.GoogleAuth({ scopes: ["https://www.googleapis.com/auth/androidpublisher"] });
  return google.androidpublisher({ version: "v3", auth });
}

exports.verifyPurchase = onCall({ enforceAppCheck: false }, async (req) => {
  const uid = needUser(req);
  const productId = String(req.data.productId || "");
  const token = String(req.data.purchaseToken || "");
  if (!token || token.length > 2000) throw new HttpsError("invalid-argument", "Missing purchase.");
  const pub = publisher();
  const key = crypto.createHash("sha256").update(token).digest("hex");
  const seen = db.collection("purchases").doc(key);

  if (COINS[productId]) {
    const r = await pub.purchases.products.get({ packageName: PACKAGE, productId, token });
    if (r.data.purchaseState !== 0) throw new HttpsError("failed-precondition", "That purchase is not paid.");
    const wallet = db.collection("users").doc(uid).collection("wallet").doc("main");
    let added = false;
    await db.runTransaction(async (t) => {
      const s = await t.get(seen);
      if (s.exists) {
        if (s.data().uid !== uid) throw new HttpsError("already-exists", "That purchase belongs to another account.");
        return;
      }
      t.set(seen, { uid, productId, at: FieldValue.serverTimestamp() });
      t.set(wallet, { coins: FieldValue.increment(COINS[productId]) }, { merge: true });
      added = true;
    });
    // consuming lets the person buy the same pack again
    if (r.data.consumptionState === 0) {
      try { await pub.purchases.products.consume({ packageName: PACKAGE, productId, token }); } catch (e) { /* already consumed */ }
    }
    return { message: added ? COINS[productId] + " coins added" : "Already added" };
  }

  if (SUBS.has(productId)) {
    const r = await pub.purchases.subscriptionsv2.get({ packageName: PACKAGE, token });
    const state = r.data.subscriptionState;
    const active = state === "SUBSCRIPTION_STATE_ACTIVE" || state === "SUBSCRIPTION_STATE_IN_GRACE_PERIOD";
    const line = (r.data.lineItems || [])[0] || {};
    const until = line.expiryTime ? Date.parse(line.expiryTime) : 0;
    if (!active) throw new HttpsError("failed-precondition", "That subscription is not active.");
    await db.runTransaction(async (t) => {
      const s = await t.get(seen);
      if (s.exists && s.data().uid !== uid) throw new HttpsError("already-exists", "That purchase belongs to another account.");
      t.set(seen, { uid, productId, at: FieldValue.serverTimestamp() });
      t.set(db.collection("users").doc(uid).collection("wallet").doc("premium"), { active: true, until, productId }, { merge: true });
    });
    if (r.data.acknowledgementState === "ACKNOWLEDGEMENT_STATE_PENDING") {
      try { await pub.purchases.subscriptions.acknowledge({ packageName: PACKAGE, subscriptionId: productId, token }); } catch (e) { /* ignore */ }
    }
    return { message: "Premium is on" };
  }
  throw new HttpsError("invalid-argument", "Unknown product.");
});

// Takes coins for a gift. Later it also credits the creator who gets the gift.
exports.spendCoins = onCall(async (req) => {
  const uid = needUser(req);
  const n = Math.floor(Number(req.data.coins));
  if (!(n > 0 && n <= 10000)) throw new HttpsError("invalid-argument", "Wrong amount.");
  const wallet = db.collection("users").doc(uid).collection("wallet").doc("main");
  await db.runTransaction(async (t) => {
    const s = await t.get(wallet);
    const have = s.exists ? (s.data().coins || 0) : 0;
    if (have < n) throw new HttpsError("failed-precondition", "Not enough coins.");
    t.set(wallet, { coins: have - n }, { merge: true });
  });
  return { ok: true };
});
