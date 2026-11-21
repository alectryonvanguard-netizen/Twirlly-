import { initializeApp } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-app.js";
import { getAuth, onAuthStateChanged, GoogleAuthProvider, signInWithPopup, signInWithEmailAndPassword, createUserWithEmailAndPassword, sendEmailVerification, sendPasswordResetEmail, signOut } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-auth.js";
import { getFirestore, collection, doc, getDoc, getDocs, query, where, orderBy, limit, startAt, endAt, documentId, writeBatch, increment, serverTimestamp, getCountFromServer } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-firestore.js";

const app = initializeApp({
  apiKey: "AIzaSyDqcXzTCIvf2Y03ZQe9B7LKkewebpNgis4",
  authDomain: "twirlly-75313.firebaseapp.com",
  projectId: "twirlly-75313",
  storageBucket: "twirlly-75313.firebasestorage.app",
  messagingSenderId: "417317261167",
  appId: "1:417317261167:web:146aceecd212a90dde629a"
});
const auth = getAuth(app);
const db = getFirestore(app);

const $ = (s) => document.querySelector(s);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const safeUrl = (u) => (/^https:\/\//.test(u || "") ? u : "");
const view = $("#view");
let me = null;          // signed-in user
let myProfile = null;   // users/{uid}
let player = null;      // running post player

const isGoogle = () => me && me.providerData.some((p) => p.providerId === "google.com");
const needsVerify = () => me && !me.emailVerified && !isGoogle();
const canRead = () => me && !needsVerify();

// ---------------- small helpers ----------------
function linkify(text) {
  return esc(text).replace(/https?:\/\/[^\s<]+/g, (u) => `<a href="${u}" target="_blank" rel="noopener noreferrer">${u}</a>`);
}
const ago = (d) => {
  if (!d) return "";
  const s = (Date.now() - d.getTime()) / 1000;
  return s < 60 ? "now" : s < 3600 ? Math.floor(s / 60) + "m" : s < 86400 ? Math.floor(s / 3600) + "h" : Math.floor(s / 86400) + "d";
};
const avatar = (u, name, big) => `<span class="av" style="${u ? `background-image:url('${safeUrl(u)}')` : ""}">${u ? "" : esc((name || "?")[0].toUpperCase())}</span>`;

// ---------------- header ----------------
function renderTop() {
  $("#top").innerHTML = `
    <a href="/" data-link class="logo"><i>▶</i>Twirlly</a>
    <div class="search"><input id="q" placeholder="Search people" autocomplete="off"><div id="res" class="results" hidden></div></div>
    <span style="flex:1"></span>
    ${me ? `<a href="/u/${esc(myProfile?.username || "")}" data-link>${avatar(myProfile?.avatarUrl, myProfile?.username)}</a><button class="btn ghost small" id="out">Sign out</button>` : `<button class="btn small" id="in">Sign in</button>`}`;
  $("#in")?.addEventListener("click", authModal);
  $("#out")?.addEventListener("click", () => signOut(auth));
  let timer;
  $("#q").addEventListener("input", (e) => {
    clearTimeout(timer);
    const v = e.target.value.trim().toLowerCase().replace(/^@/, "");
    if (v.length < 2) { $("#res").hidden = true; return; }
    timer = setTimeout(async () => {
      if (!canRead()) { $("#res").hidden = false; $("#res").innerHTML = `<a>Sign in to search</a>`; return; }
      try {
        const s = await getDocs(query(collection(db, "usernames"), orderBy(documentId()), startAt(v), endAt(v + "\uf8ff"), limit(8)));
        const rows = [];
        for (const d of s.docs) {
          const u = await getDoc(doc(db, "users", d.data().uid));
          if (u.exists()) rows.push(`<a href="/u/${esc(u.data().username)}" data-link>${avatar(u.data().avatarUrl, u.data().username)}<span>${esc(u.data().displayName || u.data().username)}<br><small class="dim">@${esc(u.data().username)}</small></span></a>`);
        }
        $("#res").hidden = false;
        $("#res").innerHTML = rows.join("") || `<a>No one found</a>`;
      } catch (e) { $("#res").hidden = false; $("#res").innerHTML = `<a>Search is not available</a>`; }
    }, 300);
  });
  document.addEventListener("click", (e) => { if (!e.target.closest(".search")) $("#res").hidden = true; });
}

// ---------------- sign in ----------------
function modal(html) { const m = $("#modal"); m.innerHTML = `<div class="box">${html}</div>`; m.hidden = false; m.onclick = (e) => { if (e.target === m) m.hidden = true; }; return m; }
const closeModal = () => { $("#modal").hidden = true; };

function authModal() {
  let create = false;
  const draw = (msg = "", ok = false) => {
    const m = modal(`
      <h2 style="margin:0 0 10px">Welcome to Twirlly</h2>
      <div class="tabs"><button class="${create ? "" : "on"}" data-t="0">Sign in</button><button class="${create ? "on" : ""}" data-t="1">Create account</button></div>
      <input class="tx" id="em" type="email" placeholder="Email" autocomplete="email">
      <input class="tx" id="pw" type="password" placeholder="${create ? "Password (8+ letters and numbers)" : "Password"}" autocomplete="${create ? "new-password" : "current-password"}">
      <div class="${ok ? "ok" : "err"}">${esc(msg)}</div>
      <button class="btn" id="go" style="width:100%;margin-top:8px">${create ? "Create account" : "Sign in"}</button>
      ${create ? "" : `<button class="btn ghost small" id="fp" style="margin-top:8px">Forgot password?</button>`}
      <div class="dim" style="text-align:center;margin:10px">or</div>
      <button class="btn ghost" id="gg" style="width:100%">Continue with Google</button>`);
    m.querySelectorAll(".tabs button").forEach((b) => b.onclick = () => { create = b.dataset.t === "1"; draw(); });
    $("#gg").onclick = async () => { try { await signInWithPopup(auth, new GoogleAuthProvider()); closeModal(); } catch (e) { draw("Google sign-in did not work."); } };
    $("#fp")?.addEventListener("click", async () => {
      const em = $("#em").value.trim();
      if (!em) return draw("Enter your email first.");
      try { await sendPasswordResetEmail(auth, em); } catch (e) {}
      draw("If an account exists for this email, we sent a link to choose a new password.", true);
    });
    $("#go").onclick = async () => {
      const em = $("#em").value.trim(), pw = $("#pw").value;
      try {
        if (create) {
          if (pw.length < 8 || !/\d/.test(pw) || !/[A-Za-z]/.test(pw)) return draw("Use 8+ characters with letters and numbers.");
          const c = await createUserWithEmailAndPassword(auth, em, pw);
          await sendEmailVerification(c.user);
        } else await signInWithEmailAndPassword(auth, em, pw);
        closeModal();
      } catch (e) {
        draw(e.code === "auth/email-already-in-use" ? "This email may already have an account. Try signing in." : e.code === "auth/too-many-requests" ? "Too many tries. Please wait a few minutes." : "The email or password is not correct.");
      }
    };
  };
  draw();
}

function verifyModal() {
  modal(`<h2 style="margin:0 0 8px">Check your email</h2>
    <p class="dim">We sent a link to ${esc(me.email)}. Open it, then tap the button.</p><div id="vm" class="err"></div>
    <button class="btn" id="vd" style="width:100%">I have verified my email</button>
    <button class="btn ghost small" id="vr" style="margin-top:8px">Send the email again</button>
    <button class="btn ghost small" id="vo" style="margin:8px 0 0 8px">Use a different email</button>`);
  $("#vd").onclick = async () => { await me.reload(); if (me.emailVerified) { await me.getIdToken(true); closeModal(); await boot(me); } else $("#vm").textContent = "Not confirmed yet. Check the link and the spam folder."; };
  $("#vr").onclick = async () => { try { await sendEmailVerification(me); $("#vm").className = "ok"; $("#vm").textContent = "We sent it again."; } catch (e) { $("#vm").textContent = "Please wait a bit before sending again."; } };
  $("#vo").onclick = () => signOut(auth).then(closeModal);
}

function onboardModal() {
  modal(`<h2 style="margin:0 0 8px">Choose your name</h2><p class="dim">You can change these once every 7 days.</p>
    <input class="tx" id="dn" placeholder="Your name" maxlength="40"><input class="tx" id="un" placeholder="username (letters, numbers, _ .)" maxlength="24">
    <div id="om" class="err"></div><button class="btn" id="ok" style="width:100%">Get started</button>`);
  $("#ok").onclick = async () => {
    const un = $("#un").value.trim().toLowerCase(), dn = $("#dn").value.trim() || un;
    if (!/^[a-z0-9_.]{3,24}$/.test(un)) return ($("#om").textContent = "Username must be 3 to 24 letters, numbers, _ or .");
    try {
      const taken = await getDoc(doc(db, "usernames", un));
      if (taken.exists()) return ($("#om").textContent = "That username is already taken.");
      const b = writeBatch(db);
      b.set(doc(db, "usernames", un), { uid: me.uid });
      b.set(doc(db, "users", me.uid), { username: un, displayName: dn, bio: "", isPrivate: false, verified: false, createdAt: serverTimestamp(), updatedAt: serverTimestamp() });
      await b.commit();
      closeModal();
      await boot(me);
    } catch (e) { $("#om").textContent = "Could not save. Try another username."; }
  };
}

// ---------------- post player (plays the saved edit in the browser) ----------------
const FILTERS = ["", "grayscale(1)", "sepia(.25) saturate(1.2) hue-rotate(-10deg)", "hue-rotate(15deg) saturate(1.1)", "saturate(1.6) contrast(1.1)", "contrast(.85) brightness(1.1) saturate(.8)", "sepia(1)", "grayscale(1) contrast(1.3)", "sepia(.5) contrast(.9)", "hue-rotate(-30deg) saturate(1.3)", "hue-rotate(60deg) saturate(1.4) contrast(1.1)", "sepia(.4) saturate(1.5) hue-rotate(-20deg)", "hue-rotate(110deg) saturate(1.1)", "contrast(1.4) brightness(.9)", "sepia(.6) contrast(.95)", "hue-rotate(30deg) brightness(1.1)", "sepia(.3) saturate(1.3) brightness(1.1)", "grayscale(.8) hue-rotate(190deg) saturate(2)"];
const colorCss = (c) => "#" + ((c >>> 0) & 0xffffff).toString(16).padStart(6, "0");

function mountPlayer(box, p) {
  const clips = (p.clips || []).filter((c) => safeUrl(c.path));
  const layers = p.layers || [];
  const audio = (p.audios || []).find((a) => safeUrl(a.path));
  const durs = clips.map((c) => Math.max(300, ((c.out || 0) - (c.in || 0)) / (c.speed || 1)));
  const starts = []; let total = 0;
  durs.forEach((d) => { starts.push(total); total += d; });
  box.innerHTML = ""; box.className = "stage";
  const first = clips[0];
  const ratio = p.aspect > 0 ? p.aspect : first && first.w > 0 && first.h > 0 ? first.w / first.h : 9 / 16;
  box.style.aspectRatio = String(ratio);
  if (ratio > 0.7) box.classList.add("fit");
  if (!clips.length) { box.innerHTML = `<p class="dim" style="padding:20px">This post cannot be played here yet.</p>`; return { destroy() {} }; }
  const vid = document.createElement("video"); vid.playsInline = true; vid.muted = true; vid.style.display = "none";
  const img = document.createElement("img"); img.className = "m"; img.style.display = "none"; img.alt = "";
  box.append(vid, img);
  const els = layers.map((l) => {
    const e = document.createElement("div"); e.className = "ly";
    if (l.img && safeUrl(l.img)) { e.innerHTML = `<img src="${safeUrl(l.img)}" style="width:${(l.size || .2) * 100}vw;max-width:${(l.size || .2) * 100}%">`; }
    else {
      e.textContent = l.text || "";
      e.style.color = colorCss(l.color);
      e.style.fontFamily = ["system-ui", "Georgia, serif", "ui-monospace, monospace", "cursive"][l.font || 0] || "system-ui";
      e.style.fontWeight = l.bold ? "800" : "500";
      if (l.bg && !l.emoji) { e.style.background = "rgba(0,0,0,.6)"; e.style.padding = ".25em .6em"; e.style.borderRadius = ".4em"; }
      else if (l.st === 2) e.style.textShadow = `0 0 14px ${colorCss(l.color)}, 0 0 28px ${colorCss(l.color)}`;
      else if (l.st === 1) e.style.textShadow = "3px 3px 0 #000";
      else e.style.textShadow = "0 0 8px #000";
    }
    const tr = l.tr || {};
    e.style.left = (tr.x ?? .5) * 100 + "%"; e.style.top = (tr.y ?? .5) * 100 + "%";
    e.style.transform = `translate(-50%,-50%) rotate(${tr.r || 0}deg) scale(${tr.s ?? 1})`;
    box.append(e);
    return e;
  });
  let aud = null;
  if (audio) { aud = new Audio(safeUrl(audio.path)); aud.loop = !!audio.loop; aud.volume = Math.min(1, audio.vol ?? 1); aud.muted = true; }
  const snd = document.createElement("div"); snd.className = "snd"; snd.textContent = "🔇 Tap for sound"; box.append(snd);
  let idx = -1, t = 0, last = performance.now(), playing = true, muted = true, audStarted = false, raf = 0;
  const applyMute = () => { vid.muted = muted; if (aud) aud.muted = muted; snd.style.display = muted ? "block" : "none"; };
  function show(i, off) {
    idx = i; const c = clips[i];
    const f = (FILTERS[c.filter || 0] || "") + ` brightness(${1 + (c.br || 0)}) contrast(${c.ct || 1}) saturate(${c.sat || 1})`;
    vid.style.filter = img.style.filter = f;
    if (c.video) {
      img.style.display = "none"; vid.style.display = "block"; vid.src = safeUrl(c.path); vid.playbackRate = c.speed || 1; vid.volume = Math.min(1, c.vol ?? 1);
      vid.onloadedmetadata = () => { try { vid.currentTime = ((c.in || 0) + off * (c.speed || 1)) / 1000; } catch (e) {} if (playing) vid.play().catch(() => {}); };
    } else { vid.pause(); vid.style.display = "none"; img.style.display = "block"; img.src = safeUrl(c.path); }
  }
  function frame(now) {
    const dt = now - last; last = now;
    if (playing && !document.hidden) t += dt;
    if (t >= total) { t = 0; idx = -1; audStarted = false; if (aud) { aud.pause(); aud.currentTime = (audio.in || 0) / 1000; } }
    let i = starts.findIndex((s, k) => t >= s && t < s + durs[k]); if (i < 0) i = 0;
    if (i !== idx) show(i, t - starts[i]);
    layers.forEach((l, k) => { els[k].style.display = t >= (l.start || 0) && t <= (l.end ?? total) ? "block" : "none"; els[k].style.fontSize = box.clientWidth * (l.size || .08) + "px"; });
    if (aud && playing && !audStarted && t >= (audio.start || 0)) { audStarted = true; aud.currentTime = (audio.in || 0) / 1000; aud.play().catch(() => {}); }
    raf = requestAnimationFrame(frame);
  }
  box.onclick = () => {
    if (muted) { muted = false; applyMute(); return; }
    playing = !playing;
    if (playing) { vid.play().catch(() => {}); aud?.play().catch(() => {}); } else { vid.pause(); aud?.pause(); }
  };
  applyMute(); raf = requestAnimationFrame(frame);
  return { destroy() { cancelAnimationFrame(raf); vid.pause(); aud?.pause(); } };
}

// ---------------- pages ----------------
async function userByName(name) {
  const n = await getDoc(doc(db, "usernames", name.toLowerCase()));
  if (!n.exists()) return null;
  const u = await getDoc(doc(db, "users", n.data().uid));
  return u.exists() ? { id: u.id, ...u.data() } : null;
}

function card(d) {
  const p = d.data();
  return `<a class="card" href="/v/${d.id}" data-link><div class="cv" style="${p.cover ? `background-image:url('${safeUrl(p.cover)}')` : ""}"></div><div class="in"><b>@${esc(p.username)}</b><small>${esc((p.caption || "").slice(0, 70))}</small><br><small>❤ ${p.likesCount || 0} · 💬 ${p.commentsCount || 0}</small></div></a>`;
}

async function feedPage() {
  if (!canRead()) {
    view.innerHTML = `<div class="hero"><h1>Create. Edit. Share.</h1><p>Short videos, dramas and LIVE. Sign in to watch what people share.</p><button class="btn" id="hi">Sign in or create an account</button></div>`;
    $("#hi").onclick = authModal; return;
  }
  view.innerHTML = `<p class="dim">Loading...</p>`;
  try {
    const s = await getDocs(query(collection(db, "posts"), orderBy("createdAt", "desc"), limit(40)));
    view.innerHTML = s.empty ? `<div class="hero"><p>No posts yet. Be the first from the app!</p></div>` : `<div class="grid">${s.docs.map(card).join("")}</div>`;
  } catch (e) { view.innerHTML = `<p class="err">Could not load the feed.</p>`; }
}

async function postPage(id) {
  view.innerHTML = `<p class="dim">Loading...</p>`;
  let snap;
  try { snap = await getDoc(doc(db, "posts", id)); } catch (e) { snap = null; }
  if (!snap || !snap.exists()) { view.innerHTML = `<div class="hero"><h1>Post not found</h1><p>It may have been deleted.</p></div>`; return; }
  const p = snap.data();
  let proj = {}; try { proj = JSON.parse(p.project || "{}"); } catch (e) {}
  view.innerHTML = `<div class="post"><div><div id="stage"></div></div><div>
    <div class="row"><a href="/u/${esc(p.username)}" data-link class="row"><b>@${esc(p.username)}</b></a><span class="dim">${esc(p.seriesTitle ? "· " + p.seriesTitle + " ep " + (p.episodeNo || 1) : "")}</span></div>
    <p>${linkify(p.caption || "")}</p>
    <div class="row"><button class="btn ghost small" id="like">❤ <span id="lc">${p.likesCount || 0}</span></button><span class="dim">💬 ${p.commentsCount || 0}</span>
      <button class="btn ghost small" id="share">Copy link</button><a class="btn small" href="https://play.google.com/store/apps" rel="noopener">Get the app</a></div>
    <h3>Comments</h3><div id="cms"></div><div id="cf"></div></div></div>`;
  player = mountPlayer($("#stage"), proj);
  $("#share").onclick = () => { navigator.clipboard?.writeText(location.href); $("#share").textContent = "Copied"; };
  if (!canRead()) {
    $("#cms").innerHTML = `<p class="dim">Sign in to like and read comments.</p>`;
    $("#like").onclick = authModal; return;
  }
  const likeRef = doc(db, "posts", id, "likes", me.uid);
  let liked = (await getDoc(likeRef)).exists();
  const paint = () => ($("#like").style.background = liked ? "#ff2d87" : "");
  paint();
  $("#like").onclick = async () => {
    const b = writeBatch(db);
    if (liked) b.delete(likeRef); else b.set(likeRef, { at: serverTimestamp() });
    b.update(doc(db, "posts", id), { likesCount: increment(liked ? -1 : 1) });
    try { await b.commit(); liked = !liked; $("#lc").textContent = Number($("#lc").textContent) + (liked ? 1 : -1); paint(); } catch (e) {}
  };
  const loadComments = async () => {
    const s = await getDocs(query(collection(db, "posts", id, "comments"), orderBy("createdAt"), limit(100)));
    $("#cms").innerHTML = s.docs.map((d) => {
      const c = d.data(); const m = safeUrl(c.media); let media = "";
      if (c.media && String(c.media).startsWith("anim:")) media = `<span class="an">${esc(String(c.media).split(":")[1] || "✨")}</span>`;
      else if (m && c.mediaKind === "video") media = `<video src="${m}" autoplay muted loop playsinline></video>`;
      else if (m) media = `<img src="${m}" alt="">`;
      return `<div class="cm"><div><b>@${esc(c.username)}</b> <small class="dim">${ago(c.createdAt?.toDate())}</small><div>${linkify(c.text || "")}</div>${media}</div></div>`;
    }).join("") || `<p class="dim">No comments yet.</p>`;
  };
  await loadComments();
  $("#cf").innerHTML = myProfile ? `<input class="tx" id="ct" placeholder="Add a comment..." maxlength="1000"><button class="btn small" id="cs">Send</button>` : `<p class="dim">Choose a username to comment.</p>`;
  $("#cs")?.addEventListener("click", async () => {
    const text = $("#ct").value.trim(); if (!text) return;
    const b = writeBatch(db);
    b.set(doc(collection(db, "posts", id, "comments")), { uid: me.uid, username: myProfile.username, text, createdAt: serverTimestamp() });
    b.update(doc(db, "posts", id), { commentsCount: increment(1) });
    try { await b.commit(); $("#ct").value = ""; loadComments(); } catch (e) {}
  });
}

async function profilePage(name) {
  view.innerHTML = `<p class="dim">Loading...</p>`;
  let u = null; try { u = await userByName(name); } catch (e) {}
  if (!u) { view.innerHTML = `<div class="hero"><h1>Account not found</h1></div>`; return; }
  let followers = "", following = "", posts = "";
  const isMe = me && u.id === me.uid;
  if (canRead()) {
    try {
      followers = (await getCountFromServer(query(collection(db, "follows"), where("following", "==", u.id)))).data().count;
      following = (await getCountFromServer(query(collection(db, "follows"), where("follower", "==", u.id)))).data().count;
      const s = await getDocs(query(collection(db, "posts"), where("uid", "==", u.id), limit(60)));
      const docs = s.docs.sort((a, b) => (b.data().createdAt?.seconds || 0) - (a.data().createdAt?.seconds || 0));
      posts = docs.length ? `<div class="grid">${docs.map(card).join("")}</div>` : `<p class="dim">No posts yet.</p>`;
    } catch (e) { posts = `<p class="err">Could not load posts.</p>`; }
  } else posts = `<p class="dim">Sign in to see posts.</p>`;
  view.innerHTML = `<div class="banner" style="${u.bannerUrl ? `background-image:url('${safeUrl(u.bannerUrl)}')` : ""}"></div>
    <div class="pf">${avatar(u.avatarUrl, u.username)}<div style="flex:1"><h2 style="margin:0">${esc(u.displayName || u.username)}${u.verified ? `<span class="badge">✔</span>` : ""}</h2><span class="dim">@${esc(u.username)}</span></div>
      ${!isMe && canRead() ? `<button class="btn small" id="fl">Follow</button>` : ""}</div>
    <p style="margin:0 16px 8px">${linkify(u.bio || "")}</p>
    <p class="dim" style="margin:0 16px 16px">${followers !== "" ? `<b>${followers}</b> followers · <b>${following}</b> following` : ""}</p>${posts}`;
  if (!isMe && canRead()) {
    const ref = doc(db, "follows", me.uid + "_" + u.id);
    let on = (await getDoc(ref)).exists();
    const paint = () => { $("#fl").textContent = on ? "Following" : "Follow"; $("#fl").className = "btn small" + (on ? " ghost" : ""); };
    paint();
    $("#fl").onclick = async () => {
      try { if (on) await writeBatch(db).delete(ref).commit(); else await writeBatch(db).set(ref, { follower: me.uid, following: u.id, at: serverTimestamp() }).commit(); on = !on; paint(); } catch (e) {}
    };
  }
}

// ---------------- routing ----------------
function route() {
  player?.destroy(); player = null; window.scrollTo(0, 0);
  const parts = location.pathname.split("/").filter(Boolean);
  if (parts[0] === "v" && parts[1]) return postPage(parts[1]);
  if (parts[0] === "u" && parts[1]) return profilePage(decodeURIComponent(parts[1]));
  return feedPage();
}
document.addEventListener("click", (e) => {
  const a = e.target.closest("a[data-link]");
  if (a && a.origin === location.origin) { e.preventDefault(); history.pushState({}, "", a.pathname); route(); }
});
window.addEventListener("popstate", route);

async function boot(user) {
  me = user; myProfile = null;
  if (user && !needsVerify()) {
    try { const d = await getDoc(doc(db, "users", user.uid)); myProfile = d.exists() ? d.data() : null; } catch (e) {}
  }
  renderTop();
  if (me && needsVerify()) verifyModal();
  else if (me && !myProfile) onboardModal();
  route();
}
onAuthStateChanged(auth, boot);
if ("serviceWorker" in navigator) navigator.serviceWorker.register("/sw.js").catch(() => {});
