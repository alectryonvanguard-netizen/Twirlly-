// Twirlly server: checks Google Play purchases and keeps the coin wallet. Only this code can write the wallet.
const { onCall, onRequest, HttpsError } = require("firebase-functions/v2/https");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { google } = require("googleapis");
const crypto = require("crypto");

initializeApp();ondex
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


// ---------- shareable pages: link previews (WhatsApp, X, Google) and the Twirlly web app shell ----------
function esc(t) { return String(t == null ? "" : t).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])); }

function shell(host, path, title, desc, image) {
  const url = "https://" + host + path;
  return `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>${esc(title)}</title><meta name="description" content="${esc(desc)}">
<link rel="canonical" href="${esc(url)}"><meta name="theme-color" content="#0a0a0f">
<meta property="og:site_name" content="Twirlly"><meta property="og:type" content="video.other">
<meta property="og:title" content="${esc(title)}"><meta property="og:description" content="${esc(desc)}"><meta property="og:url" content="${esc(url)}">
${image ? `<meta property="og:image" content="${esc(image)}"><meta name="twitter:card" content="summary_large_image"><meta name="twitter:image" content="${esc(image)}">` : `<meta name="twitter:card" content="summary">`}
<meta name="twitter:title" content="${esc(title)}"><meta name="twitter:description" content="${esc(desc)}">
<link rel="manifest" href="/manifest.webmanifest"><link rel="icon" href="/icon-192.png"><link rel="stylesheet" href="/style.css"></head>
<body><header id="top"></header><main id="view"><noscript><p>${esc(title)}</p><p>${esc(desc)}</p></noscript></main><div id="modal" hidden></div>
<script type="module" src="/app.js"></script></body></html>`;
}

exports.share = onRequest(async (req, res) => {
  const parts = req.path.split("/").filter(Boolean);
  let title = "Twirlly", desc = "Create. Edit. Share.", image = "";
  try {
    if (parts[0] === "v" && parts[1]) {
      const d = await db.collection("posts").doc(parts[1]).get();
      if (d.exists) {
        const p = d.data();
        title = "@" + p.username + " on Twirlly";
        desc = String(p.caption || "").slice(0, 200) || "Watch on Twirlly";
        image = p.cover || "";
      }
    } else if (parts[0] === "u" && parts[1]) {
      const n = await db.collection("usernames").doc(String(parts[1]).toLowerCase()).get();
      if (n.exists) {
        const u = (await db.collection("users").doc(n.data().uid).get()).data() || {};
        title = (u.displayName || u.username) + " (@" + u.username + ") on Twirlly";
        desc = String(u.bio || "").slice(0, 200) || "See their videos on Twirlly";
        image = u.avatarUrl || "";
      }
    }
  } catch (e) { /* show the plain page */ }
  res.set("Cache-Control", "public, max-age=300, s-maxage=600");
  res.status(200).send(shell(req.hostname, req.path, title, desc, image));
});

exports.sitemap = onRequest(async (req, res) => {
  const host = req.hostname;
  let urls = ["https://" + host + "/"];
  try {
    const s = await db.collection("posts").orderBy("createdAt", "desc").limit(500).get();
    urls = urls.concat(s.docs.map((d) => "https://" + host + "/v/" + d.id));
  } catch (e) { /* only the home page */ }
  res.set("Content-Type", "application/xml");
  res.set("Cache-Control", "public, max-age=3600");
  res.status(200).send('<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">' + urls.map((u) => "<url><loc>" + esc(u) + "</loc></url>").join("") + "</urlset>");
});
