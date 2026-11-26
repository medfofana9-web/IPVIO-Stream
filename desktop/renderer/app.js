// Fof TV for Mac – the TV app's account, profiles, catalog, addons, playlist and player in a desktop window.
'use strict';

// ------------------------------------------------------------------ account service (same as the TV app)
const SB = 'https://msooylkizqvdrgxrdjww.supabase.co';
const ANON = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im1zb295bGtpenF2ZHJneHJkand3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTQ3MTAzMTksImV4cCI6MjExMDI4NjMxOX0.PBF00osXJngq78z74Q2jVKFkc5yvJ41z9d7E0jJjryo';
const CM = 'https://v3-cinemeta.strem.io';
// The iPhone app runs these same screens in a native iOS shell (mobile/, Capacitor). There the shell fetches IPTV and
// addon data natively (no CORS or http limits) and the native video player (AVPlayer) plays HLS and mp4, with AirPlay.
const NATIVE = !!(window.Capacitor && window.Capacitor.isNativePlatform && window.Capacitor.isNativePlatform());
if (NATIVE) { window.Hls = undefined; window.mpegts = undefined; }   // their segment loaders would bypass the native fetch
const VERSION = (window.fof && window.fof.version) || window.FOF_IOS_VERSION || '0.1';

const $ = (s, el = document) => el.querySelector(s);
const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const store = {
  get(k, d) { try { const v = localStorage.getItem('fof.' + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
  set(k, v) { try { localStorage.setItem('fof.' + k, JSON.stringify(v)); } catch (e) {} },
  del(k) { localStorage.removeItem('fof.' + k); }
};
function toast(msg, ms = 3500) { const t = $('#toast'); t.textContent = msg; t.classList.remove('hidden'); clearTimeout(toast.h); toast.h = setTimeout(() => t.classList.add('hidden'), ms); }

let session = store.get('session', null);

async function auth(path, body) {
  const r = await fetch(SB + path, { method: 'POST', headers: { apikey: ANON, 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error_description || j.msg || j.message || ('Sign-in failed (' + r.status + ')'));
  session = { access: j.access_token, refresh: j.refresh_token, exp: Date.now() + (j.expires_in || 3600) * 1000, email: j.user && j.user.email };
  store.set('session', session);
  return session;
}
async function token() {
  if (!session) throw new Error('signed out');
  if (Date.now() > session.exp - 60000) await auth('/auth/v1/token?grant_type=refresh_token', { refresh_token: session.refresh });
  return session.access;
}
async function sb(path, opts = {}) {
  const t = await token();
  const r = await fetch(SB + path, {
    method: opts.method || 'GET',
    headers: Object.assign({ apikey: ANON, Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' }, opts.headers || {}),
    body: opts.body ? JSON.stringify(opts.body) : undefined
  });
  if (r.status === 401) { signOut(); throw new Error('signed out'); }
  const txt = await r.text();
  if (!r.ok) throw new Error('HTTP ' + r.status + ' ' + txt.slice(0, 120));
  return txt ? JSON.parse(txt) : null;
}
const rpc = (name, args = {}) => sb('/rest/v1/rpc/' + name, { method: 'POST', body: args });
function signOut() { session = null; store.del('session'); store.del('profile'); location.reload(); }

function deviceId() {
  let id = store.get('device', null);
  if (!id) { id = crypto.randomUUID(); store.set('device', id); }
  return id;
}

// ------------------------------------------------------------------ account data: profiles, secrets, Continue Watching
const acct = { profiles: [], pins: {}, addons: [], playlist: '', profile: null, watch: [] };

async function loadAccount() {
  await rpc('fof_register_device', { p_id: deviceId(), p_name: NATIVE ? 'iPhone' : WEB ? 'Phone' : 'Mac', p_kind: NATIVE ? 'iPhone app' : WEB ? 'Web app' : 'Mac app', p_version: VERSION }).catch(() => {});
  acct.profiles = await sb('/rest/v1/fof_profiles?select=id,slot,name,avatar,photo&deleted=eq.false&order=slot');
  const ps = await rpc('fof_pin_status').catch(() => []);
  acct.pins = {}; (ps || []).forEach(p => acct.pins[p.profile_id] = p);
  const sec = await rpc('fof_secrets_get').catch(() => []);
  (sec || []).forEach(s => {
    if (s.name === 'addons') { try { acct.addons = JSON.parse(s.value || '[]').filter(a => /^https?:/.test(a)); } catch (e) {} }
    if (s.name === 'playlist') acct.playlist = s.value || '';
  });
  // Your IPTV's movie and series list is loaded in the background, so the first Play does not wait for it
  setTimeout(() => providerIndex().catch(() => {}), 4000);
}

// The TV keeps each profile's data as Android preference files inside fof_profile_data ({files: {store: {key: ["s", "<json>"]}}}).
// The Mac reads Continue Watching and My List from it and writes back the same way the TV does: a change is saved only
// if nobody saved in between (otherwise it reads again and retries), and every add/remove carries an edit marker so the
// TV's merge keeps it.
const strOf = (files, store, key) => { const v = files && files[store] && files[store][key]; return v && v[0] === 's' ? v[1] : null; };
const setStr = (files, store, key, val) => { (files[store] = files[store] || {})[key] = ['s', val]; };
function readProfile(files) {
  acct.watch = []; acct.mylist = [];
  try { const w = strOf(files, 'ipvio_watch', 'e'); if (w) acct.watch = JSON.parse(w).map(o => ({ id: o.i, title: o.t, url: o.u, pos: o.p, dur: o.d, ts: o.ts, s: o.s || 0, e: o.e || 0, next: !!o.n })); } catch (e) {}
  acct.watch.sort((a, b) => (b.ts || 0) - (a.ts || 0));
  try { acct.mylist = (strOf(files, 'ipvio_lib', 'watch') || '').split('\n').filter(Boolean); } catch (e) {}
}
async function loadWatch(pid) {
  try {
    const rows = await sb('/rest/v1/fof_profile_data?select=data&profile_id=eq.' + pid);
    readProfile(rows && rows[0] && rows[0].data && rows[0].data.files);
  } catch (e) { console.warn('profile data', e); }
}
async function saveProfile(mutate) {
  const pid = acct.profile && acct.profile.id; if (!pid) return false;
  for (let tries = 0; tries < 4; tries++) {
    const rows = await sb('/rest/v1/fof_profile_data?select=data,updated_at&profile_id=eq.' + pid);
    if (!rows || !rows[0]) return false;                       // the TV has not saved this profile yet
    const data = rows[0].data || {}; data.files = data.files || {};
    mutate(data.files);
    const out = await sb('/rest/v1/fof_profile_data?profile_id=eq.' + pid + '&updated_at=eq.' + encodeURIComponent(rows[0].updated_at),
      { method: 'PATCH', body: { data }, headers: { Prefer: 'return=representation' } });
    if (out && out.length) { readProfile(data.files); return true; }
    await new Promise(r => setTimeout(r, 400 + tries * 300));  // someone saved first: read again
  }
  return false;
}
const editMark = (files, store, key, id, present) => {
  let e = {}; try { e = JSON.parse(strOf(files, store, key) || '{}'); } catch (x) {}
  e[id] = { p: present, t: Date.now() };
  const keys = Object.keys(e); if (keys.length > 2000) keys.sort((a, b) => e[a].t - e[b].t).slice(0, keys.length - 2000).forEach(k => delete e[k]);
  setStr(files, store, key, JSON.stringify(e));
};
function toggleMyList(id) {
  const on = !acct.mylist.includes(id);
  acct.mylist = on ? [id, ...acct.mylist] : acct.mylist.filter(x => x !== id);
  return saveProfile(files => {
    const l = (strOf(files, 'ipvio_lib', 'watch') || '').split('\n').filter(x => x && x !== id);
    if (on) l.unshift(id);
    setStr(files, 'ipvio_lib', 'watch', l.slice(0, 2000).join('\n'));
    editMark(files, 'ipvio_lib', 'watch_edits', id, on);
  }).then(ok => { if (!ok) toast('My List could not be saved to your account.'); return on; });
}
// Resume point of what is playing (same entry the TV writes); a finished movie leaves Continue Watching
let lastSave = 0;
function saveResume(force, posS, durS) {   // posS/durS: from the iPhone's VLC player (the web player is idle then)
  const it = cur, D = durS != null ? durS : V.duration, P = posS != null ? posS : V.currentTime;
  if (!it || it.live || !it.req || !isFinite(D) || D < 60) return;
  presenceBeat(it, P, D);
  const now = Date.now(); if (!force && now - lastSave < 30000) return; lastSave = now;
  const pos = Math.round(P * 1000), dur = Math.round(D * 1000);
  if (pos < 20000) return;
  const id = 'stremio:' + it.req.id, done = pos > dur * 0.93;
  const title = it.req.name + (it.req.type === 'series' ? ` S${it.req.season}E${it.req.episode}` : '');
  saveProfile(files => {
    let list = []; try { list = JSON.parse(strOf(files, 'ipvio_watch', 'e') || '[]'); } catch (e) {}
    list = list.filter(o => o.i !== id);
    if (!(done && it.req.type !== 'series')) list.unshift({ i: id, t: title, u: it.url, p: done ? 0 : pos, d: dur, ts: now, s: it.req.season || 0, e: done ? (it.req.episode || 0) + 1 : (it.req.episode || 0), n: done });
    setStr(files, 'ipvio_watch', 'e', JSON.stringify(list.slice(0, 60)));
    editMark(files, 'ipvio_watch', 'edits', id, !(done && it.req.type !== 'series'));
  }).catch(() => {});
}

const AV_COLORS = ['#E60023', '#2563EB', '#16A34A', '#D97706', '#7C3AED', '#DB2777', '#0891B2', '#65A30D'];
function avatarHtml(p, i) {
  const img = p.photo && p.photo.length > 50 ? (p.photo.startsWith('data:') ? p.photo : 'data:image/jpeg;base64,' + p.photo)
    : (p.avatar && /^https?:/.test(p.avatar) ? p.avatar : null);
  const bg = AV_COLORS[(p.slot != null ? p.slot : i) % AV_COLORS.length];
  return img ? `<img src="${esc(img)}">` : `<span style="background:${bg};width:100%;height:100%;display:flex;align-items:center;justify-content:center">${esc((p.name || '?').trim().charAt(0).toUpperCase())}</span>`;
}

// ------------------------------------------------------------------ screens: sign in, who's watching, PIN
function showSignIn(err) {
  $('#app').innerHTML = `<div class="center"><div class="wordmark">FOF TV</div>
    <form class="signin" id="f"><h1>Sign In</h1>
      <input class="field" id="em" type="email" placeholder="Email" autocomplete="username" required>
      <input class="field" id="pw" type="password" placeholder="Password" autocomplete="current-password" required>
      <button class="btn-red" type="submit">Sign In</button>
      <div class="err" id="er">${esc(err || '')}</div>
      <div class="note">Use the same Fof TV account as your TV. Your profiles, addons, playlist and Continue Watching come with it.</div>
    </form></div>`;
  if (!NATIVE) $('#em').focus();   // on the iPhone the keyboard would cover the screen before anything is tapped
  $('#f').onsubmit = async e => {
    e.preventDefault(); $('#er').textContent = '';
    try { await auth('/auth/v1/token?grant_type=password', { email: $('#em').value.trim(), password: $('#pw').value }); await start(); }
    catch (x) { $('#er').textContent = x.message; }
  };
}

function showWho() {
  // Welcome (same as the TV): round profile pictures over a dim wall of pictures from the catalog, and under each
  // profile what it was last watching
  const lockOf = p => acct.pins[p.id] && acct.pins[p.id].pin_set;
  $('#app').innerHTML = `<div class="center who wv"><div class="wwall" id="wwall"></div><div class="wshade"></div><div class="wordmark">FOF TV</div><h1>Who's watching?</h1>
    <div class="tiles">${acct.profiles.map((p, i) => `<button class="tile" data-i="${i}"><div class="av">${avatarHtml(p, i)}</div><span class="wn">${esc(p.name || 'Profile')}</span>${lockOf(p) ? '<div class="lock">&#x1F512; PIN</div>' : ''}<div class="wc" data-p="${esc(p.id)}"></div></button>`).join('')}</div>
    <button class="wso" id="so">Sign out</button></div>`;
  document.querySelectorAll('.tile').forEach(b => b.onclick = () => pickProfile(acct.profiles[+b.dataset.i]));
  $('#so').onclick = signOut;
  const f = $('.tile'); if (f) f.focus();
  catalog('movie').then(l => {
    const w = $('#wwall'); if (!w) return;
    w.innerHTML = l.filter(m => ttOf(m.id)).slice(0, 24).map(m => `<div style="background-image:url('${esc(bgOf(ttOf(m.id)))}')"></div>`).join('');
  }).catch(() => {});
  const ids = acct.profiles.map(p => p.id).filter(Boolean);
  if (ids.length) sb('/rest/v1/fof_profile_data?select=profile_id,w:data->files->ipvio_watch->e&profile_id=in.(' + ids.join(',') + ')').then(rows => {
    for (const r of rows || []) {
      try {
        const v = r.w && r.w[0] === 's' ? JSON.parse(r.w[1]) : [];
        const last = v.sort((x, y) => (y.ts || 0) - (x.ts || 0))[0]; if (!last) continue;
        const el = document.querySelector(`.wc[data-p="${r.profile_id}"]`); if (!el) continue;
        el.textContent = 'Continue: ' + String(last.t || '').replace(/\s*S\d+\s*E\d+\s*$/i, '') + (last.s || last.e ? ` · S${last.s} E${last.e}` : '');
      } catch (e) {}
    }
  }).catch(() => {});
}

async function pickProfile(p) {
  const st = acct.pins[p.id];
  if (st && st.pin_set && !st.unlocked) return askPin(p);
  await openProfile(p);
}

function askPin(p, err, len = 4) {
  // Same PIN as on the TV (checked by the account). 4 boxes like the TV; checked as soon as the 4th digit is in.
  // A longer PIN (5 or 6 digits, possible on the TV) gets its boxes with "My PIN is longer".
  $('#app').innerHTML = `<div class="center"><div class="wordmark">FOF TV</div>
    <div style="text-align:center;display:flex;flex-direction:column;align-items:center"><div style="color:var(--grey);font-size:18px">Profile lock is on.</div>
    <h1 style="font-weight:500;font-size:40px;margin:10px 0 26px">Enter your PIN to watch as ${esc(p.name)}</h1>
    <div class="pin">${Array.from({ length: len }, (_, i) => `<input type="password" maxlength="1" inputmode="numeric" data-i="${i}">`).join('')}</div>
    <div class="err">${esc(err || '')}</div>
    ${len < 6 ? '<button id="longer" style="color:var(--grey);font-size:14px;margin-top:6px">My PIN is longer</button>' : ''}
    <button class="btn-outline" id="bk">Back</button></div></div>`;
  const ins = [...document.querySelectorAll('.pin input')];
  ins[0].focus();
  let busy = false;
  const submit = async () => {
    const pin = ins.map(i => i.value).join('');
    if (pin.length < len || busy) return;
    busy = true;
    try {
      let r = await rpc('fof_verify_pin', { p_profile: p.id, p_pin: pin });
      if (r && r.err === 'signed_out') {
        // This Mac was not registered with the account yet: register, then try once more
        await rpc('fof_register_device', { p_id: deviceId(), p_name: NATIVE ? 'iPhone' : WEB ? 'Phone' : 'Mac', p_kind: NATIVE ? 'iPhone app' : WEB ? 'Web app' : 'Mac app', p_version: VERSION }).catch(() => {});
        r = await rpc('fof_verify_pin', { p_profile: p.id, p_pin: pin });
      }
      if (r && r.ok) { (acct.pins[p.id] = acct.pins[p.id] || {}).unlocked = true; return openProfile(p); }
      const e = !r ? 'The account did not answer.'
        : r.err === 'wrong' ? `Wrong PIN. ${r.left != null ? r.left + ' tries left.' : ''}`
        : r.err === 'locked' ? `Too many tries. Wait ${Math.ceil((r.wait_s || 60) / 60)} min.`
        : r.err === 'no_pin' ? 'This profile has no PIN.'
        : r.err === 'signed_out' ? 'This Mac is not allowed on the account yet. Sign out and sign in again.'
        : 'This profile could not be opened.';
      if (r && r.err === 'no_pin') return openProfile(p);
      askPin(p, e, len);
    } catch (x) { askPin(p, 'No connection to the account.', len); }
  };
  ins.forEach((inp, i) => {
    inp.addEventListener('keydown', e => {
      if (e.key === 'Enter') return submit();
      if (e.key === 'Backspace' && !inp.value && i > 0) { ins[i - 1].focus(); ins[i - 1].value = ''; e.preventDefault(); }
    });
    inp.addEventListener('input', () => {
      inp.value = inp.value.replace(/\D/g, '').slice(-1);
      if (inp.value && i < len - 1) ins[i + 1].focus();
      if (ins.every(x => x.value)) submit();
    });
  });
  const lg = $('#longer'); if (lg) lg.onclick = () => askPin(p, '', len + 1);
  $('#bk').onclick = showWho;
}

async function openProfile(p) {
  acct.profile = p; store.set('profile', p.id);
  await loadWatch(p.id);
  renderShell('home');
}

// ------------------------------------------------------------------ catalog (Cinemeta, like the TV app without a TMDB key)
const cache = new Map();
async function getJson(url, ttl = 30 * 60 * 1000) {
  const c = cache.get(url); if (c && Date.now() - c.t < ttl) return c.v;
  // No referrer and no cookies: ESPN refuses requests that name the app's local page (403)
  const r = await fetch(url, { credentials: 'omit', referrerPolicy: 'no-referrer' }); if (!r.ok) throw new Error('HTTP ' + r.status);
  const v = await r.json(); cache.set(url, { t: Date.now(), v }); return v;
}
const catalog = (type, extra) => getJson(`${CM}/catalog/${type}/top${extra ? '/' + extra : ''}.json`).then(j => j.metas || []).catch(() => []);
const metaOf = (type, id) => getJson(`${CM}/meta/${type}/${id}.json`).then(j => j.meta).catch(() => null);
const posterOf = m => m.poster || (m.id && m.id.startsWith('tt') ? `https://images.metahub.space/poster/medium/${m.id}/img` : '');
const bgOf = id => `https://images.metahub.space/background/large/${id}/img`;
const logoOf = id => `https://images.metahub.space/logo/medium/${id}/img`;
const ttOf = id => (String(id || '').replace(/^stremio:/, '').match(/^tt\d+/) || [null])[0];

// ------------------------------------------------------------------ shell: left rail like the TV
const ICONS = {
  search: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>',
  home: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 11 12 4l9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z"/></svg>',
  shows: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="6" width="18" height="13" rx="2"/><path d="m8 2 4 4 4-4"/></svg>',
  movies: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M7 3v18M17 3v18M3 8h4M3 16h4M17 8h4M17 16h4"/></svg>',
  sports: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/></svg>',
  mylist: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 5v14M5 12h14"/></svg>',
  calendar: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M3 10h18M8 3v4M16 3v4"/></svg>',
  live: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="2"/><path d="M16.2 7.8a6 6 0 0 1 0 8.4M7.8 16.2a6 6 0 0 1 0-8.4M19 5a10 10 0 0 1 0 14M5 19A10 10 0 0 1 5 5"/></svg>',
  downloads: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 4v11M7 10l5 5 5-5M5 20h14"/></svg>'
};
// The same screens run as the phone web app (no Mac shell there)
const WEB = !window.fof && !NATIVE;
const HERE = NATIVE ? 'iPhone' : WEB ? 'phone' : 'Mac';
const DL = window.fof && window.fof.downloads;
const NAV = [['search', 'Search'], ['home', 'Home'], ['shows', 'TV Shows'], ['movies', 'Movies'], ['sports', 'Sports'], ['calendar', 'Calendar'], ['live', 'Live TV'], ['mylist', 'My List'], ...(DL ? [['downloads', 'Downloads']] : [])];
let current = 'home';
const navStack = [];

function renderShell(tab) {
  const p = acct.profile;
  $('#app').innerHTML = `<div class="shell"><nav class="rail"><img class="mark" src="mark.png">
    ${NAV.map(([k, l]) => `<a data-k="${k}" class="${k === tab ? 'on' : ''}">${ICONS[k]}<span>${l}</span></a>`).join('')}
    <a class="shr" id="shr"><svg viewBox="0 0 24 24" width="22" height="22"><path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z" fill="none" stroke="currentColor" stroke-width="2"/><circle cx="12" cy="12" r="3" fill="currentColor"/></svg><span></span></a>
    <a class="me" id="me" title="Switch profile"><div class="av">${avatarHtml(p, 0)}</div><span>${esc(p.name)}</span></a></nav>
    <main class="page" id="page"></main></div>`;
  document.querySelectorAll('.rail a[data-k]').forEach(a => a.onclick = () => go(a.dataset.k));
  $('#me').onclick = () => { acct.profile = null; store.del('profile'); showWho(); };
  const shr = () => { const on = sharing(), a = $('#shr'); a.classList.toggle('off', !on); a.title = on ? 'Sharing what you watch with your other profiles. Click to stop.' : 'Not sharing what you watch. Click to share.'; $('span', a).textContent = on ? 'Sharing' : 'Private'; };
  shr(); $('#shr').onclick = () => { const on = !sharing(); store.set(shareKey(), on); if (!on && acct.profile) sb('/rest/v1/fof_presence?profile_id=eq.' + acct.profile.id, { method: 'DELETE' }).catch(() => {}); shr(); toast(on ? 'Others on your account can see what you watch' : 'What you watch is private now'); };
  castButton();
  go(tab);
}
function go(tab, arg) {
  current = tab; setGlow(null);
  document.querySelectorAll('.rail a[data-k]').forEach(a => a.classList.toggle('on', a.dataset.k === tab));
  const pg = $('#page'); pg.scrollTop = 0;
  if (tab === 'home') renderHome(pg);
  else if (tab === 'movies') renderCategory(pg, 'movie');
  else if (tab === 'shows') renderCategory(pg, 'series');
  else if (tab === 'live') renderLive(pg);
  else if (tab === 'sports') renderSports(pg);
  else if (tab === 'calendar') renderCalendar(pg);
  else if (tab === 'mylist') renderMyList(pg);
  else if (tab === 'search') renderSearch(pg);
  else if (tab === 'downloads') renderDownloads(pg);
  else if (tab === 'detail') renderDetail(pg, arg);
}
function openTitle(type, id, play) { navStack.push(current); go('detail', { type, id, play: !!play }); }

// ------------------------------------------------------------------ home / Movies / TV Shows
function cardHtml(m, type, opts = {}) {
  const tt = ttOf(m.id);
  if (!opts.poster) {
    const bg = m.background || (tt ? bgOf(tt) : posterOf(m));
    return `<div class="card nv" data-type="${esc(type || m.type)}" data-id="${esc(m.id)}" title="${esc(m.name)}" tabindex="0">${bg ? `<img loading="lazy" src="${esc(bg)}" onerror="this.remove()">` : ''}<i></i>${tt ? `<img class="nl" loading="lazy" src="${esc(logoOf(tt))}" onload="this.nextElementSibling.remove()" onerror="this.remove()">` : ''}<b class="nt">${esc(m.name)}</b></div>`;
  }
  const img = posterOf(m);
  return `<div class="card" data-type="${esc(type || m.type)}" data-id="${esc(m.id)}" title="${esc(m.name)}">
    <div class="ph">${esc(m.name)}</div>${img ? `<img loading="lazy" src="${esc(img)}" onerror="this.remove()">` : ''}${opts.top ? '<div class="tag10">TOP<br>10</div>' : ''}</div>`;
}
function rowHtml(title, inner, more) {
  return `<section class="row"><h2>${esc(title)}</h2>
    <div class="strip"><button class="arrow l">&#x2039;</button><div class="track">${inner}${more ? `<div class="card nv seeall" data-more="${esc(more)}" tabindex="0">See all</div>` : ''}</div><button class="arrow r">&#x203A;</button></div></section>`;
}
function wireRows(root) {
  root.querySelectorAll('.strip').forEach(s => {
    const t = $('.track', s);
    $('.arrow.l', s).onclick = () => t.scrollBy({ left: -t.clientWidth * 0.85 });
    $('.arrow.r', s).onclick = () => t.scrollBy({ left: t.clientWidth * 0.85 });
  });
  root.querySelectorAll('[data-id]').forEach(c => {
    if (!c.onclick) c.onclick = () => openTitle(c.dataset.type, c.dataset.id);
    // A movie you rest on for a moment: its sources are looked up already, so Play starts at once
    if (c.dataset.type === 'movie' && /^tt\d+$/.test(c.dataset.id || '') && acct.addons.length && !WEB) {
      c.addEventListener('mouseenter', () => { clearTimeout(c.pf); c.pf = setTimeout(() => streamsFor({ type: 'movie', id: c.dataset.id }).catch(() => {}), 700); });
      c.addEventListener('mouseleave', () => clearTimeout(c.pf));
    }
  });
}

function heroHtml(m, type, idx, total) {
  const tt = ttOf(m.id);
  return `<div class="hero"><div class="bg" style="background-image:url('${esc(m.background || (tt ? bgOf(tt) : ''))}')"></div><div class="shade"></div>
    <div class="info"><div class="kicker"><b>F</b>${type === 'series' ? 'SERIES' : 'FILM'}</div>
      ${tt ? `<img class="logo" src="${esc(logoOf(tt))}" onerror="this.outerHTML='<h1 class=htitle>${esc(m.name).replace(/'/g, '&#39;')}</h1>'">` : `<h1 class="htitle">${esc(m.name)}</h1>`}
      <div class="meta">${m.imdbRating ? `<span class="match">${matchPct(m)}% Match</span>` : ''}<span>${esc(m.releaseInfo || m.year || '')}</span>${(m.genres || []).slice(0, 2).map(g => `<span>${esc(g)}</span>`).join('')}<span class="q">HD</span></div>
      <p class="desc">${esc(m.description || '')}</p>
      <div class="btns"><button class="btn primary" id="hplay">&#x25B6;&nbsp; Play</button><button class="btn" id="hinfo">&#x24D8;&nbsp; More Info</button></div>
    </div>${total > 1 ? `<div class="dots">${Array.from({ length: total }, (_, i) => `<i class="${i === idx ? 'on' : ''}"></i>`).join('')}</div>` : ''}</div>`;
}
// Netflix: after a few seconds on the big picture the trailer fades in over it, muted, and loops
function trailerIn(hero, type, id, onPlay) {
  clearTimeout(trailerIn.t); trailerIn.on = false;
  // Only on the new app shell (0.3+): it sends the referrer YouTube needs; the old shell would show YouTube's error page
  if (!DL || store.get('trailers', true) === false || !hero) return;
  trailerIn.t = setTimeout(async () => {
    const m = await metaOf(type, id);
    const k = m && m.trailers && m.trailers[0] && String(m.trailers[0].source || '').replace(/[^\w-]/g, '');
    if (!k || !document.body.contains(hero) || !$('#player').classList.contains('hidden')) return;
    const f = document.createElement('iframe');
    f.className = 'trailer'; f.allow = 'autoplay; encrypted-media';
    f.src = `https://www.youtube-nocookie.com/embed/${k}?autoplay=1&mute=1&controls=0&loop=1&playlist=${k}&modestbranding=1&playsinline=1&rel=0&iv_load_policy=3&disablekb=1&fs=0`;
    $('.bg', hero).after(f);
    trailerIn.on = true; if (onPlay) onPlay();
    setTimeout(() => f.classList.add('on'), 1800);
  }, 4000);
}
function matchPct(m) { const r = parseFloat(m.imdbRating); return isNaN(r) ? 90 : Math.max(40, Math.min(99, Math.round(r * 9 + 12))); }

function continueRow() {
  if (!acct.watch.length) return '';
  return rowHtml('Continue Watching for ' + acct.profile.name, acct.watch.slice(0, 20).map((w, i) => {
    const tt = ttOf(w.id); const pr = w.dur > 0 && !w.next ? Math.min(100, Math.round(w.pos * 100 / w.dur)) : 0;
    const sub = w.s || w.e ? `S${w.s} E${w.e}${w.next ? ' · Up next' : ''}` : (w.dur ? Math.max(1, Math.round((w.dur - w.pos) / 60000)) + ' min left' : '');
    return `<div class="card wide" data-w="${i}"><div class="ph">${esc(w.title)}</div>${tt ? `<img loading="lazy" src="${esc(bgOf(tt))}" onerror="if(!this.dataset.p){this.dataset.p=1;this.src='${esc(posterOfId(tt))}'}else this.remove()">` : ''}
      <div class="cap">${esc(w.title.replace(/\s*S\d+\s*E\d+\s*$/i, ''))}<div>${esc(sub)}</div></div>${pr ? `<div class="prog"><i style="width:${pr}%"></i></div>` : ''}</div>`;
  }).join(''));
}
const posterOfId = tt => `https://images.metahub.space/poster/medium/${tt}/img`;
function wireContinue(root) {
  root.querySelectorAll('[data-w]').forEach(c => c.onclick = () => {
    const w = acct.watch[+c.dataset.w]; const tt = ttOf(w.id);
    if (tt) openTitle(w.s || w.e ? 'series' : 'movie', tt);
    else if (w.url) play({ url: w.url, title: w.title, start: w.pos });
  });
}

function handoff(pg) {
  if (handoff.done) return; handoff.done = true;
  const w = acct.watch[0]; const tt = w && ttOf(w.id);
  if (!w || !tt || w.next || !w.ts || Date.now() - w.ts > 60 * 60 * 1000 || (w.dur && w.pos > w.dur * 0.93)) return;
  const b = document.createElement('div'); b.className = 'handoff';
  const left = w.dur ? Math.max(1, Math.round((w.dur - w.pos) / 60000)) + ' min left' : '';
  b.innerHTML = `<img src="${esc(bgOf(tt))}" onerror="this.remove()"><div class="hx"><small>KEEP WATCHING ON THIS ${HERE.toUpperCase()}</small><b>${esc(w.title)}</b><span>${esc(left)}</span></div><button class="btn primary">&#x25B6;&nbsp; Resume</button><button class="x" title="Close">&#x2715;</button>`;
  document.body.appendChild(b);
  setTimeout(() => b.classList.add('on'), 50);
  const close = () => { b.classList.remove('on'); setTimeout(() => b.remove(), 300); };
  $('.x', b).onclick = close; setTimeout(close, 20000);
  $('.btn', b).onclick = () => { close(); const series = w.s || w.e; watch({ type: series ? 'series' : 'movie', id: tt, name: w.title.replace(/\s*S\d+\s*E\d+\s*$/i, ''), season: w.s, episode: w.e, start: w.pos }); };
}

// Shows you are watching that got an episode in the last 7 days you have not seen yet (newest first). Each new episode
// is announced once as a Mac notification.
async function newEpisodes() {
  if (newEpisodes.p && Date.now() - newEpisodes.t < 30 * 60 * 1000) return newEpisodes.p;
  newEpisodes.t = Date.now();
  newEpisodes.p = (async () => {
    const seen = new Map();
    acct.watch.forEach(w => { const tt = ttOf(w.id); if (tt && (w.s || w.e) && !seen.has(tt)) seen.set(tt, w); });
    const week = Date.now() - 7 * 864e5;
    const out = (await Promise.all([...seen].slice(0, 15).map(async ([tt, w]) => {
      const m = await metaOf('series', tt); if (!m) return null;
      const fresh = (m.videos || []).filter(v => v.season > 0 && v.released && Date.parse(v.released) > week && Date.parse(v.released) <= Date.now()
        && (v.season > (w.s || 0) || (v.season === (w.s || 0) && v.episode > (w.e || 0))));
      if (!fresh.length) return null;
      const v = fresh.sort((a, b) => Date.parse(b.released) - Date.parse(a.released))[0];
      return { meta: Object.assign({}, m, { type: 'series', id: tt }), ep: v, at: Date.parse(v.released) };
    }))).filter(Boolean).sort((a, b) => b.at - a.at);
    const told = store.get('newEpTold', []);
    out.forEach(x => {
      const k = `${x.meta.id}:${x.ep.season}:${x.ep.episode}`;
      if (told.includes(k) || !('Notification' in window)) return;
      told.push(k);
      try { const n = new Notification(`New episode: ${x.meta.name}`, { body: `S${x.ep.season} E${x.ep.episode}${x.ep.name ? ' · ' + x.ep.name : ''}`, silent: false }); n.onclick = () => openTitle('series', x.meta.id); } catch (e) {}
    });
    store.set('newEpTold', told.slice(-300));
    return out;
  })().catch(() => []);
  return newEpisodes.p;
}
// Checked every hour too, so the notification comes while the app is open
setInterval(() => { if (acct.watch.length) newEpisodes(); }, 60 * 60 * 1000);

// ------------------------------------------------------------------ Watching now: what the other profiles are playing (table fof_presence)
let presAt = 0;
const shareKey = () => 'shareWatching:' + (acct.profile ? acct.profile.id : '');
const sharing = () => store.get(shareKey(), true);
function presenceBeat(it, P, D) {
  const now = Date.now(); if (now - presAt < 25000 || !acct.profile || !sharing() || P < 5 || P > D * 0.95) return; presAt = now;
  const tt = it.req && ttOf('stremio:' + it.req.id);
  sb('/rest/v1/fof_presence?on_conflict=profile_id', { method: 'POST', headers: { Prefer: 'resolution=merge-duplicates,return=minimal' }, body: {
    profile_id: acct.profile.id, name: String(acct.profile.name || '').slice(0, 40), item_id: ('stremio:' + it.req.id).slice(0, 300), title: String(it.req.name || '').slice(0, 200),
    season: it.req.season || 0, ep: it.req.episode || 0, pos_ms: Math.round(P * 1000), dur_ms: Math.round(D * 1000), img: tt ? bgOf(tt) : '',
    device: NATIVE ? 'iPhone' : 'Mac', updated_at: new Date().toISOString() } }).catch(() => {});
}
async function presenceOthers() {
  if (!acct.profile) return [];
  const since = new Date(Date.now() - 75000).toISOString();
  try { return ((await sb('/rest/v1/fof_presence?select=*&updated_at=gt.' + encodeURIComponent(since) + '&order=updated_at.desc')) || []).filter(r => r.profile_id !== acct.profile.id); }
  catch (e) { return []; }
}
async function drawWatchNow() {
  const box = $('#watchnow'); if (!box) return;
  const w = (await presenceOthers())[0];
  if (!$('#watchnow')) return;
  if (!w) { box.innerHTML = ''; return; }
  const tt = ttOf(w.item_id), img = w.img || (tt ? bgOf(tt) : '');
  const left = w.dur_ms > w.pos_ms ? Math.round((w.dur_ms - w.pos_ms) / 60000) : 0, pr = w.dur_ms ? Math.min(100, Math.round(w.pos_ms * 100 / w.dur_ms)) : 0;
  const ini = esc(String(w.name || '?').slice(0, 1).toUpperCase());
  box.innerHTML = `<div class="wnb"><span class="wnav">${ini}</span><div class="wnart" style="${img ? `background-image:url('${esc(img)}')` : ''}"><i style="width:${pr}%"></i></div>
    <div class="wntx"><small><b class="wndot"></b>${esc(w.name)} is watching now${w.device ? ' · ' + esc(w.device) : ''}</small><h3>${esc(w.title)}</h3><span>${[w.season || w.ep ? `S${w.season} E${w.ep}` : '', left ? left + ' min left' : ''].filter(Boolean).join(' · ')}</span></div>
    ${tt ? `<button class="btn primary" id="wnjoin">&#x25B6;&nbsp; Join ${esc(w.name)}</button>` : '<span class="wnna">Opens on the TV</span>'}</div>`;
  const j = $('#wnjoin'); if (j) j.onclick = () => openTitle(w.season || w.ep ? 'series' : 'movie', tt);
}
(function wnStyle() {
  const st = document.createElement('style');
  st.textContent = `#watchnow:empty{display:none}.wnb{display:flex;align-items:center;gap:22px;margin:14px 48px 6px;padding:16px 22px;border-radius:18px;background:rgba(20,26,38,.8);border:1px solid #ffffff14}
.wnav{width:64px;height:64px;border-radius:50%;background:#d0527a;border:3px solid #fff;display:flex;align-items:center;justify-content:center;font-size:26px;font-weight:900;flex:none}
.wnart{width:190px;height:107px;border-radius:12px;background:#2a2440 center/cover;position:relative;overflow:hidden;flex:none}.wnart i{position:absolute;left:0;bottom:0;height:4px;background:#E50914}
.wntx{flex:1;min-width:0}.wntx small{color:#ccc;font-size:14px;display:flex;align-items:center;gap:8px}.wndot{width:10px;height:10px;border-radius:50%;background:#E50914;display:inline-block}
@media (max-width:700px){.rail .shr{display:none!important}}.rail .shr{color:#9a9a9a}.rail .shr.off{color:#555}.rail .shr.off svg{opacity:.5}
.wntx h3{margin:3px 0;font-size:28px;font-weight:900;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.wntx span{color:#bbb;font-size:15px}.wnna{color:#999;font-size:14px}
@media (max-width:600px){.wnb{margin:10px 14px;gap:12px;padding:12px;flex-wrap:wrap}.wnav{width:44px;height:44px;font-size:18px}.wnart{width:120px;height:68px}.wntx h3{font-size:19px}.wnb .btn{width:100%}}`;
  document.head.appendChild(st);
})();

async function renderHome(pg) {
  pg.innerHTML = '<div class="spin"></div>';
  const [mv, sr] = await Promise.all([catalog('movie'), catalog('series')]);
  if (current !== 'home') return;
  const featured = [...mv.slice(0, 5), ...sr.slice(0, 3)].filter(m => m.id && m.id.startsWith('tt'));
  let idx = 0;
  const top = [...mv.slice(0, 5), ...sr.slice(0, 5)].sort(() => 0.5 - Math.random()).slice(0, 10);
  const genreRows = [['Action', 'movie', 'Action'], ['Laugh Out Loud', 'movie', 'Comedy'], ['Binge-worthy Crime', 'series', 'Crime'], ['Sci-Fi & Fantasy', 'movie', 'Sci-Fi'], ['Edge-of-Your-Seat Thrillers', 'movie', 'Thriller'], ['Family Movie Night', 'movie', 'Family'], ['Drama Series', 'series', 'Drama'], ['Animation', 'movie', 'Animation']];
  pg.innerHTML = `${bbHtml()}<div id="watchnow"></div>${continueRow()}
    ${rowHtml('Top Movies & Shows Today', top.map(m => cardHtml(m, m.type)).join(''))}
    ${rowHtml('Popular Movies', mv.slice(0, 30).map(m => cardHtml(m, 'movie')).join(''), 'movie')}
    ${rowHtml('Popular TV Shows', sr.slice(0, 30).map(m => cardHtml(m, 'series')).join(''), 'series')}
    <div id="mylistrow"></div><div id="newrow"></div><div id="genres"></div><div style="height:60px"></div>`;
  newEpisodes().then(l => { if (l.length && $('#newrow')) { $('#newrow').innerHTML = rowHtml('New Episodes This Week', l.map(x => cardHtml(x.meta, 'series')).join('')); wireRows($('#newrow')); } });
  if (acct.mylist.length) myListItems(30).then(l => { if (l.length && $('#mylistrow')) { $('#mylistrow').innerHTML = rowHtml('My List', l.map(m => cardHtml(m, m.type)).join('')); wireRows($('#mylistrow')); } });
  bbWire(pg, featured[0]);
  drawWatchNow(); clearInterval(renderHome.wn); renderHome.wn = setInterval(() => { if (current === 'home') drawWatchNow(); else clearInterval(renderHome.wn); }, 30000);
  const drawHero = () => {
    const m = featured[idx]; if (!m || !$('#herobox')) return;
    $('#herobox').innerHTML = heroHtml(m, m.type, idx, featured.length);
    $('#hplay').onclick = () => openTitle(m.type, m.id, true);
    $('#hinfo').onclick = () => openTitle(m.type, m.id);
    trailerIn($('#herobox .hero'), m.type, m.id);
  };
  drawHero();
  clearInterval(renderHome.t);
  // The slideshow waits while a trailer runs and the big picture is in view
  renderHome.t = setInterval(() => {
    if (current !== 'home' || !$('#herobox')) return clearInterval(renderHome.t);
    if (trailerIn.on && $('#page').scrollTop < 300) return;
    idx = (idx + 1) % featured.length; drawHero();
  }, 9000);
  handoff(pg);
  wireRows(pg); wireContinue(pg);
  pg.querySelectorAll('[data-more]').forEach(s => s.onclick = () => go(s.dataset.more === 'movie' ? 'movies' : 'shows'));
  for (const [title, type, g] of genreRows) {
    const l = await catalog(type, 'genre=' + encodeURIComponent(g));
    if (current !== 'home' || !$('#genres')) return;
    if (l.length) { $('#genres').insertAdjacentHTML('beforeend', rowHtml(title, l.slice(0, 30).map(m => cardHtml(m, type)).join(''))); wireRows($('#genres')); }
  }
}

async function myListItems(max) {
  const ids = acct.mylist.map(ttOf).filter(Boolean).slice(0, max);
  const metas = await Promise.all(ids.map(async tt => (await metaOf('series', tt)) || (await metaOf('movie', tt))));
  return metas.filter(Boolean);
}
async function renderMyList(pg) {
  pg.innerHTML = '<div class="search"><h1 style="font-size:40px;margin:0 0 24px">My List</h1><div class="grid" id="ml"><div class="spin" style="grid-column:1/-1"></div></div></div>';
  const l = await myListItems(120);
  if (current !== 'mylist' || !$('#ml')) return;
  $('#ml').innerHTML = l.length ? l.map(m => cardHtml(m, m.type, { poster: true })).join('') : '<div class="empty" style="grid-column:1/-1;padding:20px 0">Nothing in My List yet. Add titles with the + My List button on a title page (here or on the TV).</div>';
  wireRows($('#ml'));
}

async function renderCategory(pg, type) {
  const name = type === 'movie' ? 'Movies' : 'TV Shows';
  pg.innerHTML = '<div class="spin"></div>';
  const top = await catalog(type);
  if (current !== (type === 'movie' ? 'movies' : 'shows')) return;
  const m = top.find(x => x.id && x.id.startsWith('tt')) || top[0];
  const genres = type === 'movie' ? ['Action', 'Comedy', 'Drama', 'Thriller', 'Horror', 'Sci-Fi', 'Romance', 'Animation', 'Documentary', 'Family', 'Crime', 'Adventure']
    : ['Drama', 'Comedy', 'Crime', 'Action', 'Sci-Fi', 'Reality-TV', 'Animation', 'Documentary', 'Mystery', 'Fantasy'];
  pg.innerHTML = `${bbHtml()}<div class="nvgen"><span class="nvname">${name}</span>${genres.map(g => `<button data-g="${g}">${g}</button>`).join('')}</div>
    ${rowHtml(`Top ${name} Today`, top.slice(0, 10).map(x => cardHtml(x, type)).join(''))}
    ${rowHtml('Popular ' + name, top.slice(10, 40).map(x => cardHtml(x, type)).join(''))}<div id="genres"></div><div style="height:60px"></div>`;
  bbWire(pg, m);
  wireRows(pg);
  pg.querySelectorAll('.nvgen [data-g]').forEach(b => b.onclick = async () => {
    const g = b.dataset.g; pg.querySelectorAll('.nvgen [data-g]').forEach(x => x.classList.toggle('on', x === b));
    const l = await catalog(type, 'genre=' + encodeURIComponent(g));
    $('#genres').innerHTML = `<section class="search" style="padding-top:20px"><div class="grid"><h4>${esc(name)} · ${esc(g)}</h4>${l.map(x => cardHtml(x, type, { poster: true })).join('')}</div></section>`;
    wireRows($('#genres')); $('#genres').scrollIntoView({ behavior: 'smooth' });
  });
  for (const g of genres.slice(0, 8)) {
    const l = await catalog(type, 'genre=' + encodeURIComponent(g));
    if (!$('#genres') || current !== (type === 'movie' ? 'movies' : 'shows')) return;
    if (l.length) { $('#genres').insertAdjacentHTML('beforeend', rowHtml(g, l.slice(0, 30).map(x => cardHtml(x, type)).join(''))); wireRows($('#genres')); }
  }
}

// ------------------------------------------------------------------ title page
async function renderDetail(pg, { type, id, play }) {
  pg.innerHTML = '<div class="spin"></div>';
  const m = await metaOf(type, id);
  if (!m) { pg.innerHTML = '<div class="empty">This title could not be loaded.</div>'; return; }
  const seasons = type === 'series' ? [...new Set((m.videos || []).map(v => v.season).filter(s => s > 0))].sort((a, b) => a - b) : [];
  const resume = acct.watch.find(w => ttOf(w.id) === id);
  let season = resume && resume.s && seasons.includes(resume.s) ? resume.s : seasons[0];
  const runtime = m.runtime ? esc(m.runtime) : '';
  pg.innerHTML = `<div class="detail"><div class="hero"><div class="bg" style="background-image:url('${esc(m.background || bgOf(id))}')"></div><div class="shade"></div>
    <div class="info"><button class="btn" id="back" style="margin-bottom:22px;height:40px;padding:0 16px">&#x2190; Back</button>
      <div class="kicker"><b>F</b>${type === 'series' ? 'SERIES' : 'FILM'}</div>
      <img class="logo" src="${esc(m.logo || logoOf(id))}" onerror="this.outerHTML='<h1 class=htitle>${esc(m.name).replace(/'/g, '&#39;')}</h1>'">
      <div class="meta">${m.imdbRating ? `<span class="match">${matchPct(m)}% Match</span>` : ''}<span>${esc(m.releaseInfo || m.year || '')}</span>${runtime ? `<span>${runtime}</span>` : ''}${seasons.length ? `<span>${seasons.length} Season${seasons.length > 1 ? 's' : ''}</span>` : ''}${m.imdbRating ? `<span>IMDb ${esc(m.imdbRating)}</span>` : ''}<span class="q">HD</span></div>
      <p class="desc">${esc(m.description || '')}</p>
      <div class="btns"><button class="btn primary" id="dplay">&#x25B6;&nbsp; ${resume ? (resume.s ? `Resume S${resume.s} E${resume.e}` : 'Resume') : 'Play'}</button><button class="btn" id="mlb">${acct.mylist.includes('stremio:' + id) ? '&#x2713;&nbsp; My List' : '+&nbsp; My List'}</button>${m.trailers && m.trailers.length ? '<button class="btn" id="trl">Trailer</button>' : ''}</div>
      <div class="cast">${(m.genres || []).length ? `<b>Genres:</b> ${esc(m.genres.join(', '))}<br>` : ''}${(m.cast || []).length ? `<b>Cast:</b> ${esc(m.cast.slice(0, 6).join(', '))}` : ''}</div>
    </div></div>
    ${type === 'series' ? `<div class="section"><h3>Episodes <span id="sn"></span></h3><div class="eps"><div class="seasons" id="seasons"></div><div class="eplist" id="eplist"></div></div></div>` : ''}
    <div id="more"></div><div style="height:60px"></div></div>`;
  $('#back').onclick = () => go(navStack.pop() || 'home');
  trailerIn($('.detail .hero', pg), type, id);
  // Instant Play: look up the sources of what Play would start (the movie, or the episode to resume / the first one)
  if (acct.addons.length) {
    const ep = type === 'series' ? (resume && resume.s ? { season: resume.s, episode: resume.e } : (m.videos || []).filter(v => v.season === season).sort((a, b) => a.episode - b.episode)[0]) : null;
    if (type !== 'series' || ep) setTimeout(() => streamsFor({ type, id, season: ep && ep.season, episode: ep && ep.episode }).catch(() => {}), 300);
  }
  $('#mlb').onclick = async () => { const on = await toggleMyList('stremio:' + id); $('#mlb').innerHTML = on ? '&#x2713;&nbsp; My List' : '+&nbsp; My List'; toast(on ? 'Added to My List' : 'Removed from My List', 1800); };
  if (m.trailers && m.trailers.length) $('#trl').onclick = () => window.open('https://www.youtube.com/watch?v=' + m.trailers[0].source);
  const epsOf = s => (m.videos || []).filter(v => v.season === s).sort((a, b) => a.episode - b.episode);
  const drawSeason = () => {
    $('#sn').textContent = 'Season ' + season;
    $('#seasons').innerHTML = seasons.map(s => `<button data-s="${s}" class="${s === season ? 'on' : ''}">Season ${s}${s === season ? `<small>${epsOf(s).length} episodes</small>` : ''}</button>`).join('');
    $('#eplist').innerHTML = epsOf(season).map(v => {
      const isRes = resume && resume.s === v.season && resume.e === v.episode;
      const pr = isRes && resume.dur ? Math.round(resume.pos * 100 / resume.dur) : 0;
      return `<div class="ep" data-e="${v.episode}"><div class="n">${v.episode}</div><div class="still">${v.thumbnail ? `<img loading="lazy" src="${esc(v.thumbnail)}" onerror="this.remove()">` : ''}${pr ? `<div class="prog" style="position:absolute;left:0;right:0;bottom:0;height:4px;background:rgba(128,128,128,.6)"><i style="display:block;height:100%;width:${pr}%;background:var(--red)"></i></div>` : ''}</div>
        <div style="flex:1"><div class="t">${esc(v.name || v.title || 'Episode ' + v.episode)}<span>${v.released ? esc(v.released.slice(0, 10)) : ''}</span></div><p>${esc(v.overview || v.description || '')}</p></div></div>`;
    }).join('');
    document.querySelectorAll('#seasons button').forEach(b => b.onclick = () => { season = +b.dataset.s; drawSeason(); });
    document.querySelectorAll('#eplist .ep').forEach(e => e.onclick = () => watch({ type, id, name: m.name, season, episode: +e.dataset.e, start: resume && resume.s === season && resume.e === +e.dataset.e ? resume.pos : 0 }));
  };
  if (type === 'series' && seasons.length) drawSeason();
  $('#dplay').onclick = () => {
    if (type === 'series') { const s = resume && resume.s ? resume.s : season; const e = resume && resume.e ? resume.e : (epsOf(s)[0] || {}).episode || 1; watch({ type, id, name: m.name, season: s, episode: e, start: resume && !resume.next ? resume.pos : 0 }); }
    else watch({ type, id, name: m.name, start: resume ? resume.pos : 0 });
  };
  if (play) $('#dplay').click();
  // You Might Also Like: same first genre
  const g = (m.genres || [])[0];
  if (g) catalog(type, 'genre=' + encodeURIComponent(g)).then(l => {
    if (!$('#more')) return;
    $('#more').innerHTML = rowHtml('You Might Also Like', l.filter(x => x.id !== id).slice(0, 24).map(x => cardHtml(x, type)).join(''));
    wireRows($('#more'));
  });
}

// ------------------------------------------------------------------ Your IPTV as a source for movies and shows (Xtream login)
// The provider's movie and series names are loaded once a day; a title matches only when the cleaned names are equal and
// the year fits (within one), the same rule the TV uses, so a wrong film never starts
const PV_ADDON = 'Your IPTV';
const pv = { idx: null, t: 0, p: null };
const PV_LANG = /^\s*(?:[\[|(]\s*)?([A-Za-z]{2,3})(?:\s*[\]|)])?\s*[-:|•]\s+/;
const pvKey = n => foldName(String(n || '').replace(PV_LANG, '').replace(/[(\[]\s*(?:19|20)\d{2}\s*[)\]]/g, ' ')
  .replace(/\b(4k|uhd|fhd|hd|sd|hevc|h\.?265|x265|multi|vostfr|dual|1080p|720p|2160p)\b/gi, ' ').replace(/&/g, ' and '));
const pvYear = (name, f) => +((String(name).match(/[(\[]\s*((?:19|20)\d{2})\s*[)\]]/) || [])[1] || (String(f || '').match(/\b(?:19|20)\d{2}\b/) || [])[0] || 0) || null;
function providerIndex() {
  const xt = !WEB && xtreamOf(acct.playlist); if (!xt) return Promise.resolve(null);
  const who = xt.base + '|' + xt.user;
  if (pv.idx && pv.who === who && Date.now() - pv.t < 24 * 3600 * 1000) return Promise.resolve(pv.idx);
  if (pv.p && pv.who === who) return pv.p;
  pv.who = who;
  const api = `${xt.base}/player_api.php?username=${encodeURIComponent(xt.user)}&password=${encodeURIComponent(xt.pass)}`;
  const get = a => fetch(api + '&action=' + a, { referrerPolicy: 'no-referrer' }).then(r => r.json()).catch(() => []);
  pv.p = Promise.all([get('get_vod_streams'), get('get_series')]).then(([mv, sr]) => {
    const m = new Map(), add = (k, e) => { if (!m.has(k)) m.set(k, []); m.get(k).push(e); };
    (Array.isArray(mv) ? mv : []).forEach(e => e.name && e.stream_id && add('m:' + pvKey(e.name), { id: e.stream_id, ext: e.container_extension || 'mp4', name: e.name, year: pvYear(e.name, e.year || e.releaseDate || e.release_date) }));
    (Array.isArray(sr) ? sr : []).forEach(e => e.name && e.series_id && add('s:' + pvKey(e.name), { id: e.series_id, name: e.name, year: pvYear(e.name, e.year || e.releaseDate || e.release_date) }));
    if (m.size) { pv.idx = m; pv.t = Date.now(); } pv.p = null; return pv.idx;
  });
  return pv.p;
}
function pvPick(c, year) {
  const other = year && c.some(x => x.year && Math.abs(x.year - year) > 1);
  const ok = c.filter(x => !year || (!x.year && !other) || (x.year && Math.abs(x.year - year) <= 1));
  const en = x => { const l = (x.name.match(PV_LANG) || [])[1]; return !l || /^(en|us|uk|eng)$/i.test(l) ? 0 : 1; };
  return ok.sort((a, b) => en(a) - en(b) || (a.year === year ? 0 : 1) - (b.year === year ? 0 : 1))[0] || null;
}
async function providerSource(req) {
  const xt = !WEB && xtreamOf(acct.playlist); const tt = ttOf(req.id); if (!xt || !tt) return null;
  const series = req.type === 'series';
  const [idx, m] = await Promise.all([providerIndex(), metaOf(series ? 'series' : 'movie', tt)]);
  if (!idx || !m || !m.name) return null;
  const year = pvYear('', m.year || m.releaseInfo);
  const hit = pvPick(idx.get((series ? 's:' : 'm:') + pvKey(m.name)) || [], year); if (!hit) return null;
  const base = { addon: PV_ADDON, addonName: PV_ADDON, quality: '', size: '', risky: false, title: '' };
  if (!series) return Object.assign(base, { url: `${xt.base}/movie/${xt.user}/${xt.pass}/${hit.id}.${hit.ext}`, name: PV_ADDON + ' · ' + hit.name, file: hit.name });
  const info = await getJson(`${xt.base}/player_api.php?username=${encodeURIComponent(xt.user)}&password=${encodeURIComponent(xt.pass)}&action=get_series_info&series_id=${hit.id}`).catch(() => null);
  const ep = info && info.episodes && (info.episodes[req.season] || info.episodes[String(req.season)] || []).find(e => +e.episode_num === +req.episode);
  if (!ep) return null;
  const label = `${hit.name} S${req.season}E${req.episode}`;
  return Object.assign(base, { url: `${xt.base}/series/${xt.user}/${xt.pass}/${ep.id}.${ep.container_extension || 'mp4'}`, name: PV_ADDON + ' · ' + label, file: label });
}

// ------------------------------------------------------------------ addon streams (account addons, like the TV's Sources)
// Asked ahead when a title page opens, so Play has its sources at once (kept 10 minutes, one lookup at a time)
const streamCache = new Map();
function streamsFor(req) {
  const k = [req.type, req.id, req.season || 0, req.episode || 0, acct.addons.join(';')].join('|');
  const c = streamCache.get(k);
  if (c && Date.now() - c.t < 10 * 60 * 1000) return c.p;
  const q = streamsNow(req);
  const p = q.then(l => { if (!l.length && !q.full.done) q.full.then(f => { if (!f.length) streamCache.delete(k); }); return l; });
  streamCache.set(k, { t: Date.now(), p });
  // When every addon has answered, later requests (the Sources panel) get the complete list
  q.full.then(f => { q.full.done = true; const e = streamCache.get(k); if (e && e.p === p && f.length) e.p = Promise.resolve(f); else if (!f.length) streamCache.delete(k); });
  return p;
}
// The source that played last time for a title or episode goes first (same addon and file)
const cidOf = r => r.type === 'series' ? `${r.id}:${r.season}:${r.episode}` : r.id;
const srcKey = s => (s.addonName || '') + '|' + (s.file || s.title || '').slice(0, 80);
function rememberSource(req, s) { if (!req || !s) return; const m = store.get('goodSrc', {}); m[cidOf(req)] = srcKey(s); const ks = Object.keys(m); if (ks.length > 300) delete m[ks[0]]; store.set('goodSrc', m); }
// Quick answer: the source that worked last time at once, a source the Mac plays well after 1.5 s, or anything at 6 s; the list is used;
// slower addons keep coming in the background and complete the cached list for "Sources"
function streamsNow(req) {
  const { type, id, season, episode } = req;
  const sid = type === 'series' ? `${id}:${season}:${episode}` : id;
  const all = [], t0 = Date.now();
  const good = store.get('goodSrc', {})[cidOf(req)];
  // DragonStreams is the main source for movies and shows, then Your IPTV, then every other addon
  const isDragon = s => s.addon !== PV_ADDON && /dragon/i.test((s.addon || '') + ' ' + (s.name || ''));
  const rank = s => (isDragon(s) ? 1000 : s.addon === PV_ADDON ? 500 : 0) + (good && srcKey(s) === good ? 100 : 0) + (s.risky ? 0 : 10) + ({ '4K': 3, '1080p': 4, '720p': 2, 'SD': 1 }[s.quality] || 0) + (/\.mp4\b|mp4\)/i.test(s.name + s.title) ? 1 : 0);
  const sorted = () => all.slice().sort((a, b) => rank(b) - rank(a));
  const tidy = s => {
    const t = (s.name + ' ' + s.title);
    s.quality = /2160|\b4k\b|uhd/i.test(t) ? '4K' : /1080/.test(t) ? '1080p' : /720/.test(t) ? '720p' : /480|sd\b/i.test(t) ? 'SD' : '';
    s.size = (t.match(/(\d+(?:[.,]\d+)?)\s?(GB|MB)/i) || [''])[0].replace(',', '.');
    s.file = (s.title.split('\n').map(x => x.trim()).find(x => /\.(mkv|mp4|avi|ts|m4v|webm)\b/i.test(x)) || s.title.split('\n')[0] || '').replace(/^[^\w\[]+/, '').slice(0, 140);
    // The Mac plays H.264 MP4/MKV well; HEVC/x265, AV1 and some MKV audio often not
    s.risky = NATIVE ? false : /hevc|x265|h\.?265|\bav1\b|dolby ?vision|\bdv\b|truehd|atmos|dts/i.test(t);
    s.addonName = s.name.split(/\s{2,}|\|/)[0].trim().slice(0, 40);
    return s;
  };
  let done = false, resolve;
  const quick = new Promise(r => resolve = r);
  const finish = () => { if (!done) { done = true; resolve(sorted()); } };
  const known = () => good && all.some(s => srcKey(s) === good);
  const ready = () => all.some(s => !s.risky && s.quality && s.quality !== 'SD');
  // Start order: DragonStreams the moment it answers; when it has nothing, Your IPTV; only then other addons.
  // Every addon is asked at the same time, so nothing waits on a slower one in turn.
  const dragonBases = acct.addons.filter(b => /dragon/i.test(b));
  let dragonLeft = dragonBases.length, iptvDone = !xtreamOf(acct.playlist) || WEB;
  const hasDragon = () => all.some(isDragon), hasIptv = () => all.some(s => s.addon === PV_ADDON);
  const check = () => {
    if (done) return;
    const el = Date.now() - t0;
    if (hasDragon()) return finish();                              // DragonStreams answered: start now
    if (dragonLeft > 0 && el < 8000) return;                       // still waiting for DragonStreams (8 s at most)
    if (hasIptv()) return finish();                                // no DragonStreams copy: your IPTV
    if (!iptvDone && el < 8000) return;                            // IPTV lookup still running
    if (known() || ready() || (el > 3000 && all.length)) finish(); // other addons
  };
  const tick = setInterval(check, 150);
  providerSource(req).then(s => { if (s) all.push(s); }).catch(() => {}).finally(() => { iptvDone = true; check(); });
  const full = Promise.all(acct.addons.map(async base => {
    try {
      const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), 15000);
      const r = await fetch(`${base.replace(/\/manifest\.json$/, '').replace(/\/$/, '')}/stream/${type}/${encodeURIComponent(sid)}.json`, { signal: ctl.signal, credentials: 'omit', referrerPolicy: 'no-referrer' });
      clearTimeout(t);
      const j = await r.json();
      const addon = base.replace(/^https?:\/\//, '').split('/')[0];
      (j.streams || []).forEach(s => { if (s.url && /^https?:/.test(s.url)) all.push(tidy({ url: s.url, name: (s.name || addon).replace(/\n/g, ' '), title: s.title || s.description || '', addon, headers: s.behaviorHints && s.behaviorHints.proxyHeaders && s.behaviorHints.proxyHeaders.request })); });
    } catch (e) {}
    if (dragonBases.includes(base)) { dragonLeft--; check(); }
  }).concat([new Promise(r => { const w = setInterval(() => { if (iptvDone) { clearInterval(w); r(); } }, 150); })])).then(() => { clearInterval(tick); finish(); return sorted(); });
  quick.full = full;
  return quick;
}

async function sources(req) {
  const bg = document.createElement('div'); bg.className = 'sheet-bg';
  const label = req.type === 'series' ? `${req.name} · S${req.season} E${req.episode}` : req.name;
  const skel = '<div class="skel"></div>'.repeat(6);
  bg.innerHTML = `<div class="sheet"><button class="sx" title="Close">&#x2715;</button><small class="kick">Sources</small><h2>${esc(label)}</h2><div class="sub">Looking…</div>${skel}</div>`;
  document.body.appendChild(bg);
  requestAnimationFrame(() => bg.classList.add('on'));
  const shut = () => { bg.remove(); document.removeEventListener('keydown', close); };
  bg.onclick = e => { if (e.target === bg || e.target.closest('.sx')) shut(); };
  const close = e => { if (e.key === 'Escape') shut(); };
  document.addEventListener('keydown', close);
  if (!acct.addons.length) { $('.sheet', bg).innerHTML = `<button class="sx" title="Close">&#x2715;</button><small class="kick">Sources</small><h2>No addons yet</h2><div class="sub">Add Stremio addons on the TV (Settings › Stremio addons). They come to this ${HERE} through your account.</div>`; return; }
  const list = await streamsFor(req);
  if (!document.body.contains(bg)) return;
  if (!list.length) { $('.sheet', bg).innerHTML = `<button class="sx" title="Close">&#x2715;</button><small class="kick">Sources</small><h2>${esc(label)}</h2><div class="sub">Nothing playable for this right now.</div>`; return; }
  $('.sheet', bg).innerHTML = `<button class="sx" title="Close">&#x2715;</button><small class="kick">Sources</small><h2>${esc(label)}</h2><div class="sub">${list.length} available · best first</div>${list.map((s, i) => `<div class="src" data-i="${i}"><span class="q">${esc(s.quality || '—')}</span><div class="nm2"><b>${esc(s.addonName)}</b><small>${esc(s.file)}</small></div>${s.risky ? '<span class="warn" title="Uses a format this player often cannot play">may not play</span>' : ''}<span class="sz">${esc(s.size)}</span>${DL && canDownload(s.url) ? `<button class="dl" data-d="${i}" title="Download to watch offline">&#x2B73;</button>` : ''}</div>`).join('')}`;
  bg.querySelectorAll('.dl').forEach(b => b.onclick = e => {
    e.stopPropagation(); const s = list[+b.dataset.d];
    DL.start(s.url, label, req.id); b.disabled = true; b.innerHTML = '&#x2713;';
    toast('Downloading “' + label + '” – see Downloads', 2600);
  });
  bg.querySelectorAll('.src').forEach(el => el.onclick = () => { shut(); play({ url: list[+el.dataset.i].url, title: req.name, sub: req.type === 'series' ? `S${req.season} E${req.episode}` : '', start: req.start || 0, list, idx: +el.dataset.i, req }); });
}

// Play: straight into the player – the title's picture while the best source is found and started (no list)
let startTok = 0;
async function watch(req) {
  if (castTV) return castTitle(req);   // connected to a TV: the TV plays it
  if (!acct.addons.length) return sources(req);
  const tok = ++startTok;
  const tt = String(req.id).split(':')[0];
  const sub = req.type === 'series' ? `Season ${req.season} · Episode ${req.episode}` : '';
  if (cur && !cur.live) saveResume(true);
  stopEngines(); cur = null; msg('');
  clearTimeout(trailerIn.t); document.querySelectorAll('iframe.trailer').forEach(f => f.remove()); trailerIn.on = false;
  clearInterval(endOfTitle.t); const pe = $('#pl-end'); if (pe) pe.remove();
  $('#player').classList.remove('mini', 'hidden');
  $('#pl-title').textContent = req.name || ''; $('#pl-sub').textContent = sub;
  $('#pl-src').classList.add('hidden');
  starting(true, { bg: /^tt\d+$/.test(tt) ? bgOf(tt) : '', title: req.name, sub, note: 'Getting it ready' });
  const list = await streamsFor(req);
  if (tok !== startTok || $('#player').classList.contains('hidden')) return;
  if (!list.length) { starting(true, { note: 'Nothing playable for this right now', stop: true }); return; }
  starting(true, { note: 'Starting' + (list[0].quality ? ' · ' + list[0].quality : '') });
  play({ url: list[0].url, title: req.name, sub, start: req.start || 0, list, idx: 0, req, keepStart: true });
}
function starting(on, o = {}) {
  const el = $('#pl-start');
  if (!on) { el.classList.add('hidden'); return; }
  if (o.bg !== undefined) { const im = $('img', el); im.classList.remove('in'); if (o.bg) { im.onload = () => im.classList.add('in'); im.src = o.bg; } else im.removeAttribute('src'); }
  if (o.title !== undefined) $('h1', el).textContent = o.title || '';
  if (o.sub !== undefined) $('.ps-sub', el).textContent = o.sub || '';
  if (o.note !== undefined) $('.ps-note', el).textContent = o.note;
  el.classList.toggle('stopped', !!o.stop);
  el.classList.remove('hidden');
}

const canDownload = u => /^https?:/.test(u) && !/\.m3u8(\?|$)|\/live\//i.test(u);

// Downloads page: progress while downloading, then play offline, show in Finder or delete
let dlList = [];
if (DL) DL.onChange(l => { dlList = l; if (current === 'downloads' && $('#page')) drawDownloads($('#page')); });
function renderDownloads(pg) { drawDownloads(pg); DL.list(); }
function drawDownloads(pg) {
  const mb = b => b > 0 ? (b > 1e9 ? (b / 1e9).toFixed(1) + ' GB' : Math.round(b / 1e6) + ' MB') : '';
  pg.innerHTML = `<div class="search"><h1 style="font-size:40px;margin:0 0 8px">Downloads</h1><div style="color:var(--grey);margin-bottom:26px">Saved in Movies › Fof TV. They play without internet.</div>
    ${dlList.length ? dlList.map(d => {
      const tt = ttOf(d.id); const pr = d.size > 0 ? Math.round(d.got * 100 / d.size) : 0;
      return `<div class="dlrow" data-k="${esc(d.key)}"><div class="dlimg">${tt ? `<img loading="lazy" src="${esc(bgOf(tt))}" onerror="this.remove()">` : ''}</div>
        <div class="dlx"><b>${esc(d.title)}</b><span>${d.state === 'running' ? `Downloading · ${pr}% of ${mb(d.size)}` : d.state === 'done' ? mb(d.size || d.got) : 'Download failed'}</span>${d.state === 'running' ? `<div class="prog"><i style="width:${pr}%"></i></div>` : ''}</div>
        ${d.state === 'done' ? '<button class="btn primary" data-a="play">&#x25B6;&nbsp; Play</button><button class="btn" data-a="show">Show</button>' : ''}
        <button class="btn" data-a="${d.state === 'running' ? 'cancel' : 'del'}">${d.state === 'running' ? 'Cancel' : 'Delete'}</button></div>`;
    }).join('') : '<div class="empty" style="padding:30px 0">Nothing downloaded yet. On a title, pick a source and press the download arrow next to it.</div>'}</div>`;
  pg.querySelectorAll('.dlrow button').forEach(b => b.onclick = () => {
    const d = dlList.find(x => x.key === b.closest('.dlrow').dataset.k); if (!d) return;
    const a = b.dataset.a;
    if (a === 'play') play({ url: 'file://' + d.file.split('/').map(encodeURIComponent).join('/'), title: d.title, sub: 'Downloaded' });
    else if (a === 'show') DL.show(d.key);
    else if (a === 'cancel') DL.cancel(d.key);
    else if (a === 'del') DL.remove(d.key);
  });
}

// ------------------------------------------------------------------ live TV (account playlist: Xtream or M3U)
const live = { loaded: false, cats: [], chans: [], cat: null, xt: null };
function xtreamOf(u) {
  try { const x = new URL(u); const user = x.searchParams.get('username'), pass = x.searchParams.get('password');
    if (user && pass) return { base: x.origin, user, pass }; } catch (e) {}
  return null;
}
async function loadLive() {
  if (live.loaded || !acct.playlist) return;
  const xt = xtreamOf(acct.playlist); live.xt = xt;
  if (xt) {
    const api = `${xt.base}/player_api.php?username=${encodeURIComponent(xt.user)}&password=${encodeURIComponent(xt.pass)}`;
    const [cats, chans] = await Promise.all([fetch(api + '&action=get_live_categories').then(r => r.json()), fetch(api + '&action=get_live_streams').then(r => r.json())]);
    live.cats = (cats || []).map(c => ({ id: String(c.category_id), name: c.category_name }));
    live.chans = (chans || []).map(c => ({ name: c.name, logo: c.stream_icon, cat: String(c.category_id), sid: c.stream_id, url: `${xt.base}/live/${xt.user}/${xt.pass}/${c.stream_id}.ts` }));
  } else {
    const txt = await fetch(acct.playlist, { referrerPolicy: 'no-referrer' }).then(r => r.text());
    const lines = txt.split(/\r?\n/); const cats = new Map();
    for (let i = 0; i < lines.length; i++) {
      const l = lines[i]; if (!l.startsWith('#EXTINF')) continue;
      const url = (lines[i + 1] || '').trim(); if (!url || url.startsWith('#')) continue;
      if (/\/(movie|series)\//.test(url)) continue;
      const g = (l.match(/group-title="([^"]*)"/) || [])[1] || 'Other';
      const logo = (l.match(/tvg-logo="([^"]*)"/) || [])[1] || '';
      const name = l.split(',').slice(1).join(',').trim();
      if (!cats.has(g)) cats.set(g, { id: g, name: g });
      live.chans.push({ name, logo, cat: g, url });
    }
    live.cats = [...cats.values()];
  }
  const counts = {}; live.chans.forEach(c => counts[c.cat] = (counts[c.cat] || 0) + 1);
  live.cats = live.cats.filter(c => counts[c.id]).map(c => Object.assign(c, { n: counts[c.id] }));
  // "My categories" (account setting, also used by every TV): only those show here
  live.catsAll = live.cats;
  try { const r = await sb('/rest/v1/fof_account_config?select=settings'); live.keep = ((r && r[0] && r[0].settings && r[0].settings.live_cats) || []).filter(Boolean); } catch (e) { live.keep = []; }
  if (live.keep.length) { const k = new Set(live.keep); const f = live.cats.filter(c => k.has(c.name)); if (f.length) live.cats = f; }
  live.loaded = true;
}
// "My categories": pick the provider categories this household uses. Saved in the account, so every TV reads only
// these (small TVs cannot hold a full provider list) and the Live TV list here shows only these.
function myCategories(pg) {
  const all = live.catsAll || live.cats, chosen = new Set(live.keep || []);
  const SUGGEST = /sport|espn|nba|wnba|nfl|ncaa|college|premier|epl|bundesliga|champions|uefa|la ?liga|serie a|ligue 1|mls|soccer|football|fox|abc|cbs|nbc|news|local|entertainment/i;
  const bg = document.createElement('div'); bg.className = 'sheet-bg'; bg.style.zIndex = 95;
  const draw = f => {
    const list = all.filter(c => !f || foldName(c.name).includes(foldName(f)));
    $('#mcl', bg).innerHTML = list.map(c => `<label class="mcrow" style="display:flex;align-items:center;gap:12px;padding:10px 4px;border-bottom:1px solid #1f1f1f;cursor:pointer"><input type="checkbox" data-n="${esc(c.name)}" ${chosen.has(c.name) ? 'checked' : ''} style="width:20px;height:20px;accent-color:#E50914"><span style="flex:1">${esc(c.name)}</span><small style="color:#888">${c.n || ''}</small></label>`).join('');
    bg.querySelectorAll('#mcl input').forEach(i => i.onchange = () => { if (i.checked) chosen.add(i.dataset.n); else chosen.delete(i.dataset.n); $('#mcn', bg).textContent = chosen.size ? chosen.size + ' chosen' : 'All categories'; });
  };
  bg.innerHTML = `<div class="sheet" style="display:flex;flex-direction:column"><button class="sx" title="Close">&#x2715;</button><small class="kick">Live TV</small><h2>My categories</h2>
    <div class="sub">Every TV on your account loads only these, so it starts fast and fits small TVs. Nothing chosen = all.</div>
    <div style="display:flex;gap:8px;margin:10px 0"><input id="mcf" placeholder="Find a category" style="flex:1;height:38px;background:#1a1a1a;border:1px solid #333;border-radius:6px;color:#fff;padding:0 12px">
    <button class="btn" id="mcs" style="height:38px;padding:0 12px;font-size:14px">Suggested</button><button class="btn" id="mcc" style="height:38px;padding:0 12px;font-size:14px">Clear</button></div>
    <div id="mcl" style="flex:1;overflow-y:auto;min-height:200px"></div>
    <div style="display:flex;align-items:center;gap:12px;padding-top:12px"><span id="mcn" style="flex:1;color:#aaa">${chosen.size ? chosen.size + ' chosen' : 'All categories'}</span><button class="btn primary" id="mcsave" style="height:42px;padding:0 20px">Save</button></div></div>`;
  document.body.appendChild(bg); requestAnimationFrame(() => bg.classList.add('on'));
  bg.onclick = e => { if (e.target === bg || e.target.closest('.sx')) bg.remove(); };
  draw('');
  $('#mcf', bg).oninput = e => draw(e.target.value);
  $('#mcs', bg).onclick = () => { all.forEach(c => { if (SUGGEST.test(c.name)) chosen.add(c.name); }); draw($('#mcf', bg).value); $('#mcn', bg).textContent = chosen.size + ' chosen'; };
  $('#mcc', bg).onclick = () => { chosen.clear(); draw($('#mcf', bg).value); $('#mcn', bg).textContent = 'All categories'; };
  $('#mcsave', bg).onclick = async () => {
    const b = $('#mcsave', bg); b.disabled = true; b.textContent = 'Saving…';
    try {
      const r = await sb('/rest/v1/fof_account_config?select=settings');
      const settings = Object.assign({}, (r && r[0] && r[0].settings) || {}, { live_cats: [...chosen].sort() });
      await sb('/rest/v1/fof_account_config?on_conflict=account_id', { method: 'POST', body: { settings }, headers: { Prefer: 'resolution=merge-duplicates,return=minimal' } });
      live.keep = [...chosen]; live.loaded = false; bg.remove();
      toast(chosen.size ? 'Saved. Your TVs load only these ' + chosen.size + ' categories from their next start.' : 'Saved. Your TVs load all categories again.', 5000);
      renderLive(pg);
    } catch (e) { b.disabled = false; b.textContent = 'Save'; toast('Not saved: ' + String(e.message || e).slice(0, 80), 5000); }
  };
}

// Guide: what is on now and next for each channel (Xtream short EPG, titles are base64), cached 10 minutes
const epg = new Map();
const b64 = t => { try { return decodeURIComponent(escape(atob(t || ''))); } catch (e) { return t || ''; } };
async function epgOf(c) {
  if (!live.xt || !c.sid) return null;
  const hit = epg.get(c.sid); if (hit && Date.now() - hit.t < 600000) return hit.v;
  const api = `${live.xt.base}/player_api.php?username=${encodeURIComponent(live.xt.user)}&password=${encodeURIComponent(live.xt.pass)}`;
  let v = null;
  try {
    const j = await fetch(`${api}&action=get_short_epg&stream_id=${c.sid}&limit=3`, { referrerPolicy: 'no-referrer' }).then(r => r.json());
    const now = Date.now() / 1000;
    const l = (j.epg_listings || []).map(e => ({ title: b64(e.title), start: +e.start_timestamp, end: +e.stop_timestamp })).filter(e => e.end > now).sort((a, b) => a.start - b.start);
    if (l.length) v = { now: l[0], next: l[1] || null };
  } catch (e) {}
  epg.set(c.sid, { t: Date.now(), v });
  return v;
}
const hm = ts => new Date(ts * 1000).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
async function fillGuide(list, root) {
  const queue = list.slice(0, 120).map((c, i) => [c, i]);
  const worker = async () => { while (queue.length) { const [c, i] = queue.shift(); const v = await epgOf(c); const el = root.querySelector(`[data-i="${i}"] .epg`); if (!el || !v) continue;
    const pct = Math.max(0, Math.min(100, (Date.now() / 1000 - v.now.start) * 100 / (v.now.end - v.now.start)));
    el.innerHTML = `<div class="nowt">${esc(v.now.title)}</div><div class="bar"><i style="width:${pct}%"></i></div>${v.next ? `<div class="nextt">Next ${hm(v.next.start)} · ${esc(v.next.title)}</div>` : ''}`; } };
  await Promise.all(Array.from({ length: 6 }, worker));
}

// "US: TNT East HD" → "TNT East": a channel's name as a wordmark when it has no logo (same as the TV)
const chMark = n => (String(n || '').replace(/^\s*(\[[^\]]{1,6}\]|\|?[A-Z]{2,3}\s*[:|\-]\|?)\s*/, '').replace(/\s*\b(FHD|UHD|HD|SD|4K|HEVC|H265|RAW|\(.*?\))\s*$/i, '').trim() || String(n || '')).slice(0, 28);
async function renderLive(pg) {
  if (!acct.playlist) { pg.innerHTML = '<div class="empty"><h2 style="color:#fff">Live TV</h2>No playlist in your account yet. Sign in to the same account on the TV with your IPTV playlist set; it syncs here automatically.</div>'; return; }
  pg.innerHTML = '<div class="spin"></div>';
  try { await loadLive(); } catch (e) {
    // Phone web app: most IPTV providers only answer plain http and do not allow web pages to read them
    const why = WEB && /^http:/.test(acct.playlist) ? 'Your IPTV provider only works over plain http, which phone browsers do not allow in a web app. Live TV works on the TV and the Mac; movies, shows and sports scores work here.' : `Your playlist could not be loaded (${esc(e.message)}).`;
    pg.innerHTML = `<div class="empty">${why}</div>`; return;
  }
  if (current !== 'live') return;
  if (!live.cat) live.cat = (live.cats.find(c => /sport/i.test(c.name)) || live.cats[0] || {}).id;
  pg.innerHTML = `<div class="livepage"><div class="cats"><input id="lf" placeholder="Find a category"><div class="viewsw"><button id="vg" class="${store.get('liveView', 'grid') === 'grid' ? 'on' : ''}">Channels</button><button id="vl" class="${store.get('liveView', 'grid') === 'guide' ? 'on' : ''}">Guide</button></div><button id="mycats" class="mycats" title="Choose which categories your TVs load">&#x2699;&#xFE0E; My categories${live.keep && live.keep.length ? ' (' + live.keep.length + ')' : ''}</button><div id="cl"></div></div><div class="chgrid ${store.get('liveView', 'grid') === 'guide' ? 'guide' : ''}" id="cg"></div></div>`;
  $('#vg').onclick = () => { store.set('liveView', 'grid'); renderLive(pg); };
  $('#mycats').onclick = () => myCategories(pg);
  $('#vl').onclick = () => { store.set('liveView', 'guide'); renderLive(pg); };
  const drawCats = f => {
    $('#cl').innerHTML = live.cats.filter(c => !f || foldName(c.name).includes(foldName(f))).map(c => `<button data-c="${esc(c.id)}" class="${c.id === live.cat ? 'on' : ''}">${esc(c.name)}<span>${c.n}</span></button>`).join('');
    document.querySelectorAll('#cl button').forEach(b => b.onclick = () => { live.cat = b.dataset.c; drawCats(f); drawChans(); });
  };
  const drawChans = () => {
    const l = live.chans.filter(c => c.cat === live.cat).slice(0, 600);
    if (store.get('liveView', 'grid') === 'grid') return drawSplit(l);
    $('#cg').classList.remove('lvsplit');
    $('#cg').innerHTML = l.map((c, i) => `<div class="ch" data-i="${i}"><div class="live">LIVE</div><div class="lg">${c.logo ? `<img loading="lazy" src="${esc(c.logo)}" onerror="this.outerHTML='<b>${esc(chMark(c.name)).replace(/'/g, '&#39;')}</b>'">` : `<b>${esc(chMark(c.name))}</b>`}</div><div class="nm">${esc(c.name)}</div><div class="epg"></div></div>`).join('');
    fillGuide(l, $('#cg'));
    document.querySelectorAll('#cg .ch').forEach(el => el.onclick = () => { stopPreview(); const c = l[+el.dataset.i]; play({ url: c.url, title: c.name, sub: 'Live TV', live: true, zap: l, zi: +el.dataset.i }); });
    // The channel's own color from its logo, and a muted preview after resting on a card for a moment
    document.querySelectorAll('#cg .ch').forEach(el => {
      const img = $('.lg img', el);
      if (img) { const tint = () => { const c = logoColor(img); if (c) $('.lg', el).style.background = `linear-gradient(180deg, ${c}, transparent)`; }; img.complete ? tint() : img.addEventListener('load', tint, { once: true }); }
      if (store.get('liveView', 'grid') === 'grid') {
        el.onmouseenter = () => { clearTimeout(previewT); previewT = setTimeout(() => startPreview(el, l[+el.dataset.i]), 1200); };
        el.onmouseleave = () => { clearTimeout(previewT); stopPreview(); };
      }
    });
    $('#cg').scrollTop = 0;
  };
  const drawSplit = l => {
    $('#cg').classList.add('lvsplit');
    $('#cg').innerHTML = `<div class="lvlist">${l.map((c, i) => `<div class="lvr" data-i="${i}" tabindex="0"><div class="lvlg">${c.logo ? `<img loading="lazy" src="${esc(c.logo)}" onerror="this.outerHTML='<b>${esc(chMark(c.name)).replace(/'/g, '&#39;')}</b>'">` : `<b>${esc(chMark(c.name))}</b>`}</div><div class="lvt"><b>${esc(c.name)}</b><div class="epg"></div></div></div>`).join('')}</div><div class="lvpanel" id="lvp"></div>`;
    fillGuide(l, $('#cg'));
    const pick = i => {
      const c = l[i]; if (!c) return; live.selCh = c.url;
      $('#cg').querySelectorAll('.lvr').forEach(el => el.classList.toggle('sel', +el.dataset.i === i));
      stopPreview(); clearTimeout(previewT);
      $('#lvp').innerHTML = `<div class="lvbig"><div class="lg">${c.logo ? `<img src="${esc(c.logo)}">` : `<b>${esc(chMark(c.name))}</b>`}</div><div class="lvon" id="lvon"><span class="live">LIVE</span><b>${esc(c.name)}</b></div></div><div class="lvnext" id="lvnx"></div><button class="btn primary" id="lvplay">&#x25B6;&nbsp; Watch</button>`;
      $('#lvplay').onclick = () => { stopPreview(); play({ url: c.url, title: c.name, sub: 'Live TV', live: true, zap: l, zi: i }); };
      epgOf(c).then(e => {
        if (live.selCh !== c.url || !$('#lvon')) return;
        const now = e && e.now, nx = e && e.next;
        if (now) $('#lvon').innerHTML = `<span class="live">LIVE</span><b>${esc(now.title || c.name)}</b><small>${esc(c.name)} · ${hm(now.start)}–${hm(now.end)}</small>`;
        $('#lvnx').innerHTML = '<small>UP NEXT</small>' + (nx ? hm(nx.start) + ' · ' + esc(nx.title) : 'No guide data for this channel');
      }).catch(() => {});
      if (HOVER) previewT = setTimeout(() => { if (live.selCh === c.url && $('#lvp .lvbig')) startPreview($('#lvp .lvbig'), c); }, 1200);
    };
    $('#cg').querySelectorAll('.lvr').forEach(el => {
      const i = +el.dataset.i;
      el.onclick = () => { stopPreview(); play({ url: l[i].url, title: l[i].name, sub: 'Live TV', live: true, zap: l, zi: i }); };
      if (HOVER) el.onmouseenter = () => { clearTimeout(drawSplit.h); drawSplit.h = setTimeout(() => pick(i), 250); };
    });
    pick(Math.max(0, l.findIndex(c => c.url === live.selCh)));
    $('#cg').scrollTop = 0;
  };
  $('#lf').oninput = e => drawCats(e.target.value.toLowerCase().trim());
  drawCats(''); drawChans();
}

// Average color of a logo (colorful pixels count more), as a deep tone for the card's top
function logoColor(img) {
  try {
    const c = document.createElement('canvas'); c.width = c.height = 24; const x = c.getContext('2d'); x.drawImage(img, 0, 0, 24, 24);
    const d = x.getImageData(0, 0, 24, 24).data; let r = 0, g = 0, b = 0, w = 0;
    for (let i = 0; i < d.length; i += 4) { if (d[i + 3] < 40) continue; const mx = Math.max(d[i], d[i + 1], d[i + 2]), mn = Math.min(d[i], d[i + 1], d[i + 2]); const s = mx ? (mx - mn) / mx : 0; const wt = 0.05 + s * s * mx / 255; r += d[i] * wt; g += d[i + 1] * wt; b += d[i + 2] * wt; w += wt; }
    if (!w) return null; r /= w; g /= w; b /= w;
    if (Math.max(r, g, b) - Math.min(r, g, b) < 25) return null;
    const k = 120 / Math.max(r, g, b); return `rgba(${Math.round(r * k)},${Math.round(g * k)},${Math.round(b * k)},.75)`;
  } catch (e) { return null; }
}
let previewT = null, preview = null;
function startPreview(el, c) {
  stopPreview();
  if (!$('#player').classList.contains('hidden') || !window.Hls || !Hls.isSupported()) return;
  const xt = /\/live\/[^/]+\/[^/]+\/\d+\.ts(\?|$)/.test(c.url);
  const u = xt ? c.url.replace(/\.ts(\?|$)/, '.m3u8$1') : c.url;
  if (!/m3u8/i.test(u)) return;
  const v = document.createElement('video'); v.className = 'pv'; v.muted = true; v.playsInline = true;
  const h = new Hls({ maxBufferLength: 8, enableWorker: true });
  h.loadSource(u); h.attachMedia(v);
  h.on(Hls.Events.ERROR, (e, d) => { if (d.fatal) stopPreview(); });
  v.oncanplay = () => { v.play().catch(() => {}); v.classList.add('on'); };
  ($('.lg', el) || el).appendChild(v);
  preview = { h, v };
}
function stopPreview() { if (!preview) return; try { preview.h.destroy(); } catch (e) {} preview.v.remove(); preview = null; }

// ------------------------------------------------------------------ sports (ESPN scoreboards, like the TV's Sports)
const LEAGUES = [
  ['nba', 'NBA', 'basketball/nba'], ['nfl', 'NFL', 'football/nfl'], ['ncaaf', 'College Football', 'football/college-football'],
  ['ncaab', 'College Basketball', 'basketball/mens-college-basketball'], ['nhl', 'NHL', 'hockey/nhl'], ['mlb', 'MLB', 'baseball/mlb'],
  ['wnba', 'WNBA', 'basketball/wnba'], ['epl', 'Premier League', 'soccer/eng.1'], ['ucl', 'Champions League', 'soccer/uefa.champions'],
  ['mls', 'MLS', 'soccer/usa.1'], ['laliga', 'La Liga', 'soccer/esp.1']
];
let sportPick = store.get('sport', 'all');

async function scoreboard(path) {
  const j = await getJson(`https://site.api.espn.com/apis/site/v2/sports/${path}/scoreboard`, 45 * 1000).catch(() => null);
  return ((j && j.events) || []).map(e => {
    const c = (e.competitions || [])[0] || {};
    const t = side => { const x = (c.competitors || []).find(k => k.homeAway === side) || {}; const tm = x.team || {};
      return { name: tm.displayName || '', short: tm.shortDisplayName || tm.name || '', nick: tm.name || '', city: tm.location || '', abbr: tm.abbreviation || '', logo: tm.logo || '', color: tm.color || '', record: ((x.records || [])[0] || {}).summary || '', score: x.score, win: x.winner }; };
    const st = (e.status && e.status.type) || {};
    const nets = [...new Set([...(c.broadcasts || []).flatMap(b => b.names || []), ...((c.geoBroadcasts || []).map(g => g.media && g.media.shortName).filter(Boolean))])];
    return { id: e.id, name: e.name, date: e.date, state: st.state, detail: st.shortDetail || st.detail || '', home: t('home'), away: t('away'), nets, path };
  });
}

function gameHtml(g, i) {
  const live = g.state === 'in', post = g.state === 'post';
  const when = live ? g.detail : post ? 'Final' : new Date(g.date).toLocaleString([], { weekday: 'short', hour: 'numeric', minute: '2-digit' });
  const row = (t, other) => `<div class="team ${post && t.win === false ? 'lose' : ''}">${t.logo ? `<img src="${esc(t.logo)}" onerror="this.style.visibility='hidden'">` : '<span style="width:30px"></span>'}<span class="tn">${esc(t.short || t.name)}</span>${g.state !== 'pre' ? `<span class="sc">${esc(t.score || '0')}</span>` : ''}</div>`;
  return `<div class="game" data-g="${i}"><div class="top">${live ? '<span class="live">LIVE</span>' : ''}<span>${esc(when)}</span><span class="net">${esc(g.nets.join(' · '))}</span></div>${row(g.away)}${row(g.home)}</div>`;
}

const HOVER = matchMedia('(hover: hover)').matches;
const teamCol = c => c ? '#' + String(c).replace('#', '') : '#2a3142';
function spCard(g, i) {
  const live = g.state === 'in', post = g.state === 'post';
  const when = live ? g.detail : post ? 'Final' : new Date(g.date).toLocaleString([], { weekday: 'short', hour: 'numeric', minute: '2-digit' });
  const row = t => `<div class="spt" style="background:linear-gradient(90deg,${teamCol(t.color)}dd,${teamCol(t.color)}77)">${t.logo ? `<img src="${esc(t.logo)}" onerror="this.style.visibility='hidden'">` : '<span class="nolg"></span>'}<span class="tn">${esc(t.short || t.name)}<small>${esc(t.record || '')}</small></span>${g.state !== 'pre' ? `<b>${esc(t.score || '0')}</b>` : ''}</div>`;
  return `<div class="spc" data-g="${i}" tabindex="0"><div class="spl">${esc(g.league || '')}</div><div class="spteams">${row(g.away)}${row(g.home)}</div><div class="sps">${live ? '<span class="live">LIVE</span>' : ''}<b>${esc(when)}</b><small>${esc(g.nets.join(' · '))}</small></div></div>`;
}
function spPanel(g) {
  const p = $('#spp'); if (!p) return;
  stopPreview();
  const live = g.state === 'in';
  p.innerHTML = `<div class="spbig"><div class="spbg"><div style="background:linear-gradient(180deg,${teamCol(g.away.color)},#0c1018)">${g.away.logo ? `<img src="${esc(g.away.logo)}">` : ''}</div><div style="background:linear-gradient(180deg,${teamCol(g.home.color)},#0c1018)">${g.home.logo ? `<img src="${esc(g.home.logo)}">` : ''}</div></div><div class="lg" id="spv"></div>
    <div class="spsc"><div>${[g.away, g.home].map(t => `<div class="spr">${esc(t.short || t.name)}${g.state !== 'pre' ? `<b>${esc(t.score || '0')}</b>` : ''}</div>`).join('')}</div><div class="spw">${live ? '<span class="live">LIVE</span>' : ''}<b>${esc(live ? g.detail : g.state === 'post' ? 'Final' : new Date(g.date).toLocaleString([], { weekday: 'short', hour: 'numeric', minute: '2-digit' }))}</b></div></div></div>
    <div class="spinfo"><div><small>GAME</small>${esc([g.league, g.away.name + ' at ' + g.home.name].filter(Boolean).join(' · '))}</div><div><small>WATCHING ON</small><span id="spch">${esc(g.nets.join(' · ') || '—')}</span></div><button class="btn primary" id="spwatch">&#x25B6;&nbsp; ${live ? 'Watch' : 'Game options'}</button></div>`;
  $('#spwatch').onclick = () => { stopPreview(); watchGame(g); };
  // A live game plays muted in the big picture after a moment (Mac only; the phone keeps its data)
  if (live && HOVER && !WEB) setTimeout(async () => {
    if (!$('#spv') || renderSports.sel !== g.id) return;
    try { await loadLive(); } catch (e) { return; }
    const c = gameChannels(g)[0]; if (!c || renderSports.sel !== g.id || !$('#spv')) return;
    $('#spch').textContent = c.name; startPreview($('#spv').parentNode, c);
  }, 1200);
}
// ------------------------------------------------------------------ pinned channels on the Sports page (ESPN and NBA TV to start)
const pinList = () => store.get('sportPins', ['name:ESPN', 'name:NBA TV']);
const pinSave = l => store.set('sportPins', l);
const pinBase = t => String(t || '').toLowerCase().replace(/^\s*[a-z]{2,3}\s*[:|\-]\s*/, '').replace(/[\[(].*?[\])]/g, ' ')
  .replace(/\b(fhd|uhd|hd|sd|4k|hevc|h265|1080p?|720p?|50fps|60fps)\b/g, ' ').replace(/[^a-z0-9 ]/g, ' ').replace(/\s+/g, ' ').trim();
const pinScore = t => { t = t.toLowerCase(); let s = 0; if (/^\s*(us|usa)\s*[:|\-]/.test(t)) s += 2; if (/\b(fhd|1080)/.test(t)) s += 1; else if (/\bhd\b/.test(t)) s += .5; if (/\b(backup|raw|plus|alt)\b/.test(t)) s -= 1; return s; };
function pinResolve(p) {
  const chans = (live && live.chans) || [];
  if (p.startsWith('url:')) return chans.find(c => c.url === p.slice(4)) || null;
  const want = pinBase(p.slice(5));
  return chans.filter(c => pinBase(c.name) === want).sort((a, b) => pinScore(b.name) - pinScore(a.name))[0] || null;
}
const pinLabel = (p, c) => p.startsWith('name:') ? p.slice(5) : (c ? c.name : 'Channel');
const pinCol = n => /espn/i.test(n) ? '#c8102e' : /nba/i.test(n) ? '#1d428a' : /nfl/i.test(n) ? '#013369' : /fox/i.test(n) ? '#003f7d' : '#2a3142';
function pinPanel(x, el) {
  const p = $('#spp'); if (!p) return;
  stopPreview(); renderSports.sel = null;
  $('#sg').querySelectorAll('.spc,.pinc').forEach(e => e.classList.toggle('sel', e === el));
  const col = pinCol(x.c ? x.c.name : x.p); setGlow(col, col);
  p.innerHTML = `<div class="spbig"><div class="spbg"><div style="background:linear-gradient(160deg,${col},#0c1018)">${x.c && x.c.logo ? `<img src="${esc(x.c.logo)}">` : ''}</div></div><div class="lg" id="spv"></div>
    <div class="spsc"><div><div class="spr">${esc(pinLabel(x.p, x.c))}</div></div><div class="spw"><b>Pinned channel</b></div></div></div>
    <div class="spinfo"><div><small>CHANNEL</small>${esc(x.c ? x.c.name : 'Not in your playlist')}</div><div><small>PINNED</small>Click to watch · &#x2715; to unpin</div>${x.c ? '<button class="btn primary" id="spwatch">&#x25B6;&nbsp; Watch</button>' : ''}</div>`;
  if (x.c) {
    $('#spwatch').onclick = () => { stopPreview(); play({ url: x.c.url, title: x.c.name, live: true }); };
    if (HOVER && !WEB) setTimeout(() => { if ($('#spv') && el.classList.contains('sel')) startPreview($('#spv').parentNode, x.c); }, 900);
  }
}
function pinPicker(done) {
  const bg = document.createElement('div'); bg.className = 'sheet-bg';
  const chans = ((live && live.chans) || []).filter(c => /sport|espn|nba|nfl|nhl|mlb|fox|tnt|tbs|dazn|bein|sky/i.test(c.name + ' ' + (c.cat || '')));
  bg.innerHTML = `<div class="sheet"><button class="sx" title="Close">&#x2715;</button><h2>Pin a channel</h2><input class="pinq" placeholder="Search channels" style="width:100%;padding:10px 14px;border-radius:10px;border:1px solid #333;background:#141a26;color:#fff;font-size:15px;margin:8px 0 12px"><div class="chlist"></div></div>`;
  document.body.appendChild(bg);
  const close = () => bg.remove();
  bg.onclick = e => { if (e.target === bg || e.target.closest('.sx')) close(); };
  const draw = q => {
    const l = (q ? ((live && live.chans) || []).filter(c => c.name.toLowerCase().includes(q.toLowerCase())) : chans).slice(0, 80);
    $('.chlist', bg).innerHTML = l.map((c, i) => `<div class="src" data-i="${i}">${c.logo ? `<img loading="lazy" src="${esc(c.logo)}" onerror="this.style.visibility='hidden'">` : ''}<div class="nm2"><b>${esc(c.name)}</b></div></div>`).join('') || '<div class="sub">No channels match.</div>';
    bg.querySelectorAll('.src').forEach(el => el.onclick = () => { const c = l[+el.dataset.i]; const ps = pinList(); if (!ps.includes('url:' + c.url)) pinSave(ps.concat('url:' + c.url)); close(); done(); });
  };
  $('.pinq', bg).oninput = e => draw(e.target.value.trim());
  draw('');
}

// ------------------------------------------------------------------ background glow (Option A): the colors of what is selected
const glowCache = {};
function glowDark(hex, k) {
  const m = String(hex || '').replace('#', '').match(/^([0-9a-f]{6})$/i); if (!m) return null;
  const n = parseInt(m[1], 16); const r = (n >> 16) & 255, g = (n >> 8) & 255, b = n & 255;
  return `rgb(${Math.round(r * k)},${Math.round(g * k)},${Math.round(b * k)})`;
}
function setGlow(a, b) {
  const s = document.body.style;
  if (!a) { s.removeProperty('--g1'); s.removeProperty('--g2'); return; }
  s.setProperty('--g1', glowDark(a, .62) || a); s.setProperty('--g2', glowDark(b || a, .45) || b || a);
}
// Mood color of an artwork: read at 16×16, colorful pixels count more; deep tone (no bright tint behind text)
function glowArt(url) {
  if (!url) return setGlow(null);
  if (glowCache[url] !== undefined) return glowCache[url] ? setGlow(glowCache[url]) : setGlow(null);
  const im = new Image(); im.crossOrigin = 'anonymous';
  im.onload = () => {
    try {
      const c = document.createElement('canvas'); c.width = c.height = 16; const x = c.getContext('2d'); x.drawImage(im, 0, 0, 16, 16);
      const d = x.getImageData(0, 0, 16, 16).data; let r = 0, g = 0, b = 0, w = 0;
      for (let i = 0; i < d.length; i += 4) { const mx = Math.max(d[i], d[i + 1], d[i + 2]), mn = Math.min(d[i], d[i + 1], d[i + 2]); const sat = mx ? (mx - mn) / mx : 0; const wt = .05 + sat * sat * mx / 255; r += d[i] * wt; g += d[i + 1] * wt; b += d[i + 2] * wt; w += wt; }
      const hex = '#' + [r / w, g / w, b / w].map(v => Math.round(Math.min(255, v * 1.1)).toString(16).padStart(2, '0')).join('');
      glowCache[url] = hex; setGlow(hex);
    } catch (e) { glowCache[url] = ''; setGlow(null); }
  };
  im.onerror = () => { glowCache[url] = ''; setGlow(null); };
  im.src = url;
}

async function renderSports(pg) {
  if (acct.playlist) loadLive().catch(() => {});
  pg.innerHTML = `<div class="sports"><h1>Sports</h1><div class="chips">${[['all', 'All']].concat(LEAGUES.map(l => [l[0], l[1]])).map(([k, l]) => `<button data-k="${k}" class="${k === sportPick ? 'on' : ''}">${l}</button>`).join('')}</div><div id="sg"><div class="spin"></div></div></div>`;
  pg.querySelectorAll('.chips button').forEach(b => b.onclick = () => { sportPick = b.dataset.k; store.set('sport', sportPick); renderSports(pg); });
  const ls = sportPick === 'all' ? LEAGUES : LEAGUES.filter(l => l[0] === sportPick);
  const lists = await Promise.all(ls.map(l => scoreboard(l[2]).then(gs => gs.map(g => Object.assign(g, { league: l[1], lk: l[0] })))));
  if (current !== 'sports' || !$('#sg')) return;
  const seen = new Set(); const all = lists.flat().filter(g => !seen.has(g.id) && seen.add(g.id));
  const mine = all.filter(g => g.state !== 'post' && (followed(teamKey(g, g.home)) || followed(teamKey(g, g.away))));
  const groups = [['Your Teams', mine], ['Live Now', all.filter(g => g.state === 'in' && !mine.includes(g))], ['Coming Up', all.filter(g => g.state === 'pre' && !mine.includes(g)).sort((a, b) => new Date(a.date) - new Date(b.date))], ['Final', all.filter(g => g.state === 'post')]];
  let n = 0; const idx = [];
  // Pins need the channel list: never hold the games back for it, draw again once it is there
  if (!live.loaded && acct.playlist && !renderSports.wait) { renderSports.wait = 1; loadLive().then(() => { renderSports.wait = 0; if (current === 'sports') renderSports(pg); }).catch(() => { renderSports.wait = 0; }); }
  const pins = pinList().map(p => ({ p, c: pinResolve(p) }));
  const pinHtml = `<h3 class="spsec">YOUR CHANNELS</h3>${pins.map((x, i) => `<div class="pinc" data-p="${i}" tabindex="0"><span class="pinlg" style="background:${pinCol(x.c ? x.c.name : x.p)}">${x.c && x.c.logo ? `<img src="${esc(x.c.logo)}" onerror="this.remove()">` : ''}<b>${esc(pinLabel(x.p, x.c))}</b></span><div class="pinnm"><b>${esc(pinLabel(x.p, x.c))}</b><small>${x.c ? esc(x.c.name) : live.loaded ? 'Not in your playlist' : 'Finding channel…'}</small></div><button class="pinx" title="Unpin">&#x2715;</button></div>`).join('')}<button class="pinadd">+ Pin a channel</button>`;
  const listHtml = pinHtml + groups.filter(([, l]) => l.length).map(([h, l]) => `<h3 class="spsec">${h === 'Live Now' ? '<i></i>' : ''}${h.toUpperCase()}<small>${l.length} ${l.length === 1 ? 'game' : 'games'}</small></h3>${l.map(g => { idx.push(g); return spCard(g, n++); }).join('')}`).join('');
  if (!listHtml) { $('#sg').innerHTML = '<div class="empty" style="padding:30px 0">No games today in this league.</div>'; return; }
  // Split view (same as the TV): games on the left, the selected one large on the right
  const keep = renderSports.sel;
  $('#sg').innerHTML = `<div class="spsplit"><div class="splist">${listHtml}</div><div class="sppanel" id="spp"></div></div>`;
  const pick = i => {
    const g = idx[i]; if (!g) return; renderSports.sel = g.id;
    $('#sg').querySelectorAll('.spc').forEach(el => el.classList.toggle('sel', +el.dataset.g === i));
    spPanel(g); setGlow(teamCol(g.home.color), teamCol(g.away.color));
  };
  $('#sg').querySelectorAll('.spc').forEach(el => {
    el.onclick = () => { stopPreview(); watchGame(idx[+el.dataset.g]); };   // hover (Mac) shows it on the right, a click watches
    if (HOVER) el.onmouseenter = () => { clearTimeout(renderSports.h); renderSports.h = setTimeout(() => pick(+el.dataset.g), 250); };
  });
  $('#sg').querySelectorAll('.pinc').forEach(el => {
    const x = pins[+el.dataset.p];
    el.onclick = e => {
      if (e.target.closest('.pinx')) { e.stopPropagation(); pinSave(pinList().filter(p => p !== x.p)); renderSports(pg); return; }
      if (x.c) { stopPreview(); play({ url: x.c.url, title: x.c.name, live: true, zap: pins.map(y => y.c).filter(Boolean), zi: 0 }); }
    };
    if (HOVER) el.onmouseenter = () => { clearTimeout(renderSports.h); renderSports.h = setTimeout(() => pinPanel(x, el), 250); };
  });
  $('.pinadd', $('#sg')).onclick = () => pinPicker(() => renderSports(pg));
  pick(Math.max(0, idx.findIndex(g => g.id === keep)));
  clearTimeout(renderSports.t); renderSports.t = setTimeout(() => { if (current === 'sports') renderSports(pg); }, 60000);
}

// Channels in the playlist that carry a game: the teams' own channels first, then the national network
const NET_ALIASES = { 'ESPN': ['espn'], 'ESPN2': ['espn2', 'espn 2'], 'ABC': ['abc'], 'TNT': ['tnt'], 'TBS': ['tbs'], 'NBA TV': ['nba tv', 'nbatv'], 'FOX': ['fox'], 'FS1': ['fs1', 'fox sports 1'], 'CBS': ['cbs'], 'NBC': ['nbc'], 'Peacock': ['peacock'], 'Prime Video': ['prime', 'amazon'], 'NFL Network': ['nfl network'], 'NHL Network': ['nhl network'], 'MLB Network': ['mlb network'], 'truTV': ['trutv', 'tru tv'], 'USA Net': ['usa network'], 'SEC Network': ['sec network'], 'ACC Network': ['acc network'], 'Big Ten Network': ['big ten', 'btn'] };
// Fold letters that accent-stripping misses ("Bodø/Glimt" → "bodo glimt", "Malmö" → "malmo", "Æ" → "ae")
function foldName(s) { return String(s || '').toLowerCase().replace(/ø/g, 'o').replace(/æ/g, 'ae').replace(/œ/g, 'oe').replace(/ß/g, 'ss')
  .replace(/[đð]/g, 'd').replace(/ł/g, 'l').replace(/ı/g, 'i').replace(/þ/g, 'th').normalize('NFD').replace(/[\u0300-\u036f]/g, '')
  .replace(/[^a-z0-9]+/g, ' ').trim(); }
// Words too common to name a team on their own (a club's own city is only ambiguous when two clubs share it)
const TEAM_GENERIC = new Set(['club', 'city', 'united', 'real', 'athletic', 'atletico', 'sporting', 'inter', 'borussia', 'olympique', 'racing', 'dynamo', 'dinamo', 'rovers', 'wanderers', 'albion', 'county', 'town', 'west', 'north', 'south', 'east', 'saint', 'red', 'star', 'young', 'boys', 'the', 'and', 'madrid', 'milan', 'manchester', 'london', 'glasgow', 'sheffield', 'bristol', 'munich', 'lisbon']);
function gameChannels(g) {
  const norm = s => ' ' + foldName(s) + ' ';
  const has = (n, w) => { const k = foldName(w); return k.length > 2 && n.includes(' ' + k + ' '); };
  const league = { nba: 'nba', wnba: 'wnba', nfl: 'nfl', nhl: 'nhl', mlb: 'mlb', ncaaf: 'ncaa', ncaab: 'ncaa' }[g.lk] || '';
  const nets = g.nets.flatMap(n => NET_ALIASES[n] || [n]).map(foldName).filter(Boolean);   // "Paramount+" → "paramount"
  const out = [];
  // Event channels ("DAZN 59: Tenerife vs. Barcelona @ 22 Nov") must name both teams and, when dated, the game's day –
  // otherwise old or unrelated events ("Sunderland vs Hull City @ 8 Sep", "Barcelona: Coppa Shell") showed up and never started
  const MON = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'];
  const gd = new Date(g.date), gDay = gd.getDate(), gMon = gd.getMonth();
  // Event titles often shorten a club ("LASK" for "LASK Linz", "Bodø/Glimt" for "FK Bodø/Glimt"): a distinctive word of the
  // name counts too, unless both teams share it. Both teams and the date must still match, so other games stay out.
  const words = t => new Set([t.name, t.short, t.nick].flatMap(x => foldName(x).split(' ')).filter(w => w.length >= 4 && !/^\d+$/.test(w) && !TEAM_GENERIC.has(w)));
  const hw = words(g.home), aw = words(g.away);
  const own = { home: [...hw].filter(w => !aw.has(w)), away: [...aw].filter(w => !hw.has(w)) };
  const teamHit = (n, t) => has(n, t.nick) || has(n, t.short) || has(n, t.name) || (t.city && t.nick && has(n, t.city + ' ' + t.nick))
    || own[t === g.home ? 'home' : 'away'].some(w => n.includes(' ' + w + ' '));
  live.chans.forEach(c => {
    const n = norm(c.name); let sc = 0;
    const lower = String(c.name || '').toLowerCase();
    const dm = lower.match(/\b(\d{1,2})\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\b/) || lower.match(/\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\s+(\d{1,2})\b/);
    const isEvent = !!dm || / vs?\.? | v | @ |: .+ - /i.test(' ' + c.name + ' ');
    if (isEvent) {
      if (dm) {
        const day = +(/\d/.test(dm[1]) ? dm[1] : dm[2]), mon = MON.indexOf((/\d/.test(dm[1]) ? dm[2] : dm[1]).slice(0, 3));
        if (day !== gDay || mon !== gMon) return;
      }
      if (!(teamHit(n, g.home) && teamHit(n, g.away))) return;
      sc = 12;
    } else for (const t of [g.home, g.away]) {
      // Without a league tag (soccer): only a channel that is just the team ("Hull City", "Barça TV"), not a long show title
      if (!league && chMark(c.name).length > (t.name || t.short).length + 10) continue;
      if (has(n, t.nick) && (!league || n.includes(' ' + league + ' ') || t.nick.split(' ').length > 1)) sc = Math.max(sc, 10);
      else if (has(n, t.name)) sc = Math.max(sc, 9);
    }
    if (!sc && nets.some(w => n.includes(' ' + w + ' ')) && /\b(us|usa)\b/.test(n)) sc = 5;
    if (!sc) return;
    if (/\b(fhd|hd|1080)\b/.test(n)) sc += 0.5;
    if (/\b(4k|uhd)\b/.test(n)) sc += 0.3;
    if (/\b(backup|raw|plus)\b/.test(n)) sc -= 0.4;
    if (/\bradio\b/.test(n)) sc -= 6;   // audio only: after every TV channel
    out.push(Object.assign({ sc }, c));
  });
  return out.sort((a, b) => b.sc - a.sc).slice(0, 30);
}
async function watchGame(g) {
  const bg = document.createElement('div'); bg.className = 'sheet-bg';
  const head = `<button class="sx" title="Close">&#x2715;</button><small class="kick">${esc(g.league || 'Watch')}</small><h2>${esc(g.away.short)} at ${esc(g.home.short)}</h2>`;
  bg.innerHTML = `<div class="sheet">${head}<div class="sub">Finding channels…</div>${'<div class="skel"></div>'.repeat(5)}</div>`;
  document.body.appendChild(bg);
  const close = () => { bg.remove(); document.removeEventListener('keydown', esck); };
  const esck = e => { if (e.key === 'Escape') close(); };
  document.addEventListener('keydown', esck); bg.onclick = e => { if (e.target === bg || e.target.closest('.sx')) close(); };
  try { await loadLive(); } catch (e) {}
  const list = gameChannels(g);
  const lastK = [teamKey(g, g.home), teamKey(g, g.away)], picks = store.get('gamePick', {});
  const lastU = lastK.map(k => picks[k]).find(Boolean);
  const li = list.findIndex(c => c.url === lastU);
  if (li > 0) list.unshift(list.splice(li, 1)[0]);
  if (li >= 0) list[0].last = true;
  if (!document.body.contains(bg)) return;
  const live = g.state === 'in', pre = g.state === 'pre';
  const side = (t, dir) => `<div class="wh-t" style="background:linear-gradient(${dir},${teamCol(t.color)},${teamCol(t.color)}33)"><span class="wh-lg">${t.logo ? `<img src="${esc(t.logo)}" onerror="this.style.visibility='hidden'">` : esc(t.abbr || '')}</span><b>${esc(t.short || t.name)}</b><small>${esc(t.record || '')}</small></div>`;
  const mid = `<div class="wh-m">${live ? '<span class="live">LIVE</span>' : ''}<b>${pre ? esc(new Date(g.date).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })) : esc((g.away.score || '0') + ' – ' + (g.home.score || '0'))}</b><small>${esc([live ? g.detail : g.state === 'post' ? 'Final' : new Date(g.date).toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric' }), g.nets.length ? 'On ' + g.nets.join(', ') : ''].filter(Boolean).join(' · '))}</small></div>`;
  const why = c => c.sc >= 12 ? 'This game' : c.sc >= 9 ? 'Team channel' : 'National broadcast';
  $('.sheet', bg).classList.add('wl');
  $('.sheet', bg).innerHTML = `<button class="sx" title="Close">&#x2715;</button><small class="kick">${esc(g.league || 'Watch')}</small><div class="wh">${side(g.away, '135deg')}${mid}${side(g.home, '225deg')}</div>` +
    (list.length ? `<div class="wl-h">Sources <small>${list.length} found · best first</small></div><div class="chlist">${list.map((c, i) => `<div class="src" data-i="${i}" tabindex="0"><span class="wl-lg">${c.logo ? `<img loading="lazy" src="${esc(c.logo)}" onerror="this.remove()">` : ''}</span><div class="nm2"><b>${esc(c.name)}</b><small>${why(c)}</small></div><span class="wl-cs">${i === 0 ? '<span class="wl-c best">BEST PICK</span>' : ''}${c.last ? '<span class="wl-c ok">&#x2713; Worked before</span>' : '<span class="wl-c">Available</span>'}</span></div>`).join('')}</div>`
      : '<div class="sub">No channel in your playlist matches this game. Try the league\'s category in Live TV.</div>');
  bg.querySelectorAll('.src').forEach(el => el.onclick = () => { close(); const i = +el.dataset.i; const pk = store.get('gamePick', {}); lastK.forEach(k => pk[k] = list[i].url); store.set('gamePick', pk); play({ url: list[i].url, title: list[i].name, sub: `${g.away.short} at ${g.home.short}`, live: true, zap: list, zi: i }); });
  // Follow a team (its games first, a score bar while you watch something else, a notification when they start),
  // Add to Calendar for later games, and a second game in the corner of the player
  const tools = document.createElement('div'); tools.className = 'gtools';
  const teamBtn = t => `<button class="btn" data-team="${esc(teamKey(g, t))}">${followed(teamKey(g, t)) ? '&#x2605; Following ' : '&#x2606; Follow '}${esc(t.short)}</button>`;
  tools.innerHTML = teamBtn(g.away) + teamBtn(g.home) + (g.state === 'pre' ? '<button class="btn" data-cal="1">&#x1F4C5; Add to Calendar</button>' : '');
  $('.sheet', bg).appendChild(tools);
  tools.querySelectorAll('[data-team]').forEach(b => b.onclick = () => { toggleFollow(b.dataset.team); close(); watchGame(g); });
  const cal = tools.querySelector('[data-cal]');
  if (cal) cal.onclick = () => {
    const t = new Date(g.date), e = new Date(t.getTime() + 3 * 3600 * 1000), f = d => d.toISOString().replace(/[-:]|\.\d{3}/g, '');
    window.open(`https://calendar.google.com/calendar/render?action=TEMPLATE&text=${encodeURIComponent(g.away.short + ' at ' + g.home.short)}&dates=${f(t)}/${f(e)}&details=${encodeURIComponent(g.league + (g.nets.length ? ' · On ' + g.nets.join(', ') : '') + ' · Watch in Fof TV')}`);
  };
  if (cur && cur.live && !$('#player').classList.contains('hidden') && list.length) {
    bg.querySelectorAll('.src').forEach(el => {
      const b = document.createElement('button'); b.className = 'corner'; b.textContent = 'In the corner'; b.title = 'Play this game small in the corner of what you are watching';
      b.onclick = ev => { ev.stopPropagation(); close(); const c = list[+el.dataset.i]; startCorner(c.url, `${g.away.short} at ${g.home.short}`); };
      el.appendChild(b);
    });
  }
}

// ------------------------------------------------------------------ game day: followed teams, score bar, notifications
const teamKey = (g, t) => g.lk + ':' + (t.abbr || t.short);
const followed = k => store.get('teams', []).includes(k);
function toggleFollow(k) { const l = store.get('teams', []); store.set('teams', l.includes(k) ? l.filter(x => x !== k) : l.concat(k)); toast(l.includes(k) ? 'Unfollowed' : 'Following – you get a notification when their games start', 2400); pollTeams(); }
let teamGames = [];
async function pollTeams() {
  const ts = store.get('teams', []); if (!ts.length) { teamGames = []; drawScoreBar(); return; }
  const lks = [...new Set(ts.map(k => k.split(':')[0]))];
  const lists = await Promise.all(LEAGUES.filter(l => lks.includes(l[0])).map(l => scoreboard(l[2]).then(gs => gs.map(g => Object.assign(g, { league: l[1], lk: l[0] })))));
  const mine = lists.flat().filter(g => followed(teamKey(g, g.home)) || followed(teamKey(g, g.away)));
  const prev = new Map(teamGames.map(g => [g.id, g.state]));
  const told = store.get('startTold', []);
  mine.forEach(g => {
    if (g.state === 'in' && prev.get(g.id) !== 'in' && !told.includes(g.id)) {
      told.push(g.id);
      try { const n = new Notification(`${g.away.short} at ${g.home.short} is on`, { body: `${g.league}${g.nets.length ? ' · ' + g.nets.join(', ') : ''} · ${g.detail}` }); n.onclick = () => watchGame(g); } catch (e) {}
    }
  });
  store.set('startTold', told.slice(-100));
  teamGames = mine; drawScoreBar();
}
setInterval(pollTeams, 90 * 1000); setTimeout(pollTeams, 5000);
// Score bar: while something plays, the live games of the teams you follow (not the one on screen), top right
function drawScoreBar() {
  let bar = $('#scorebar');
  const live = teamGames.filter(g => g.state === 'in' && !(cur && cur.sub === `${g.away.short} at ${g.home.short}`));
  if (!live.length) { if (bar) bar.remove(); return; }
  if (!bar) { bar = document.createElement('div'); bar.id = 'scorebar'; $('#player').appendChild(bar); }
  bar.innerHTML = live.slice(0, 3).map((g, i) => `<div class="sb" data-i="${i}"><span class="live">LIVE</span><img src="${esc(g.away.logo)}" onerror="this.remove()"><b>${esc(g.away.score || '0')}</b><span>–</span><b>${esc(g.home.score || '0')}</b><img src="${esc(g.home.logo)}" onerror="this.remove()"><small>${esc(g.detail)}</small></div>`).join('');
  bar.querySelectorAll('.sb').forEach(el => el.onclick = () => watchGame(live[+el.dataset.i]));
}

// Second game in the corner (muted); click it to swap it with the big picture
let corner = null;
function startCorner(url, title) {
  stopCorner();
  const xt = /\/live\/[^/]+\/[^/]+\/\d+\.ts(\?|$)/.test(url);
  const u = xt ? url.replace(/\.ts(\?|$)/, '.m3u8$1') : url;
  const box = document.createElement('div'); box.id = 'corner';
  box.innerHTML = `<video muted playsinline></video><div class="ct">${esc(title)}<button title="Close">&#x2715;</button></div>`;
  $('#player').appendChild(box);
  const v = $('video', box);
  if (window.Hls && Hls.isSupported() && /m3u8/i.test(u)) { const h = new Hls({ maxBufferLength: 20, enableWorker: true }); h.loadSource(u); h.attachMedia(v); corner = { h, url, title }; }
  else { v.src = url; corner = { url, title }; }
  v.play().catch(() => {});
  $('button', box).onclick = e => { e.stopPropagation(); stopCorner(); };
  box.onclick = () => { const c = corner, main = cur; stopCorner(); if (main && main.live) startCorner(main.url, main.title); play({ url: c.url, title: c.title, sub: c.title, live: true }); };
}
function stopCorner() { if (!corner) return; try { corner.h && corner.h.destroy(); } catch (e) {} const b = $('#corner'); if (b) b.remove(); corner = null; }

// ------------------------------------------------------------------ search
function renderSearch(pg) {
  pg.innerHTML = `<div class="search"><div class="q">${ICONS.search.replace('<svg', '<svg width="30" height="30"')}<input id="sq" placeholder="Search movies, TV shows, channels"></div><div class="grid" id="sr"></div></div>`;
  const inp = $('#sq'); inp.focus();
  let t;
  inp.oninput = () => { clearTimeout(t); t = setTimeout(() => runSearch(inp.value.trim()), 350); };
  runSearch('');
}
async function runSearch(q) {
  const box = $('#sr'); if (!box) return;
  if (!live.loaded && acct.playlist) loadLive().catch(() => {});
  if (q.length < 2) {
    const l = await catalog('movie');
    box.innerHTML = `<h4>Top Searches</h4>` + l.slice(0, 18).map(m => cardHtml(m, 'movie', { poster: true })).join(''); wireRows(box); return;
  }
  const [mv, sr] = await Promise.all([catalog('movie', 'search=' + encodeURIComponent(q)), catalog('series', 'search=' + encodeURIComponent(q))]);
  if (($('#sq') || {}).value?.trim() !== q) return;
  const ch = live.loaded ? live.chans.filter(c => (' ' + foldName(c.name) + ' ').includes(foldName(q))).slice(0, 24) : [];
  box.innerHTML = (ch.length ? '<h4>Live TV</h4>' + ch.map((c, i) => `<div class="ch" data-ci="${i}"><div class="live">LIVE</div><div class="lg">${c.logo ? `<img src="${esc(c.logo)}" onerror="this.remove()">` : `<b>${esc(c.name.slice(0, 2))}</b>`}</div><div class="nm">${esc(c.name)}</div></div>`).join('') : '') +
    (mv.length ? '<h4>Movies</h4>' + mv.slice(0, 24).map(m => cardHtml(m, 'movie', { poster: true })).join('') : '') +
    (sr.length ? '<h4>TV Shows</h4>' + sr.slice(0, 24).map(m => cardHtml(m, 'series', { poster: true })).join('') : '') ||
    `<div class="empty" style="grid-column:1/-1;padding:20px 0">No results for "${esc(q)}".</div>`;
  wireRows(box);
  box.querySelectorAll('[data-ci]').forEach(el => el.onclick = () => { const c = ch[+el.dataset.ci]; play({ url: c.url, title: c.name, sub: 'Live TV', live: true, zap: ch, zi: +el.dataset.ci }); });
}

// ------------------------------------------------------------------ iPhone: VLC for formats Apple's player cannot open
// .mkv / .avi / .webm (most addon movie files) play in a native VLC player; when it closes, the spot is saved as
// usual and Next Episode / Continue Watching keep working. Other formats stay in Apple's player (AirPlay, PiP).
const vlcFor = url => /\.(mkv|avi|webm|wmv|flv)(\?|#|$)/i.test(String(url || '').split('?')[0]) || /\.(mkv|avi)\b/i.test(String(url || ''));
async function playVlc(item, startS) {
  const nat = window.Capacitor.Plugins && window.Capacitor.Plugins.FofNative;
  if (!nat || !nat.playVlc) return fail('This source does not play on the iPhone (format). Trying the next one.');
  if (cur && !cur.live) saveResume(true);
  cur = item; stopEngines(); starting(false); $('#player').classList.add('hidden');
  try {
    const r = await nat.playVlc({ url: item.url, title: item.title || '', sub: item.sub || '', start: startS != null ? startS : (item.start || 0) / 1000 });
    if (r && r.duration > 0) saveResume(true, r.position, r.duration);
    // TV button in the VLC player: continue this on the TV from the same spot
    if (r && r.tv && item.req) {
      const send = () => castTitle(Object.assign({}, item.req, { name: item.req.name || item.title, start: Math.round((r.position || 0) * 1000) }));
      cur = null; if (castTV) send(); else castPick(send); return;
    }
    if (r && r.failed) { if (item.list && item.idx + 1 < item.list.length) { toast('Source did not start – trying the next one'); return play(Object.assign({}, item, { url: item.list[item.idx + 1].url, idx: item.idx + 1 })); } toast('This one will not start right now. Try Sources on the title page.', 4000); }
  } catch (e) { toast('Could not open the player: ' + String(e.message || e).slice(0, 60)); }
  cur = null;
}

// ------------------------------------------------------------------ Play on TV
// Pick one of the account's TVs; from then on Play, episodes and channels start on that TV (its own sources and
// player, full quality) and this screen becomes the remote. Commands go through the account: fof_cast_send
// (docs/supabase/cast.sql); the TV app picks them up within a few seconds while it is open.
let castTV = store.get('castTV', null);   // remembered: Play keeps going to that TV until you disconnect
let castZap = null;   // channel list of the last channel sent, for CH+ / CH- on the remote bar
const TV_ICON = '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="4" width="20" height="13" rx="2"/><path d="M8 21h8M12 17v4"/></svg>';
(function castStyle() {
  const st = document.createElement('style');
  st.textContent = `#castbtn{position:fixed;top:calc(14px + env(safe-area-inset-top));right:16px;z-index:45;width:42px;height:42px;border-radius:50%;border:0;background:rgba(0,0,0,.55);color:#fff;display:flex;align-items:center;justify-content:center;cursor:pointer}
#castbtn.on{background:#E50914}
#castbar{position:fixed;left:50%;transform:translateX(-50%);bottom:calc(24px + env(safe-area-inset-bottom));z-index:46;display:flex;align-items:center;gap:6px;background:#181818;border:1px solid #2a2a2a;border-radius:12px;padding:8px 10px 8px 14px;box-shadow:0 10px 30px rgba(0,0,0,.6);max-width:calc(100% - 24px)}
#castbar .nm{font-size:13px;color:#bbb;margin-right:6px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:170px}#castbar .nm b{color:#fff;display:block;font-size:14px}
#castbar button{min-width:40px;padding:0 6px;font-weight:700;font-size:15px;height:40px;border-radius:8px;border:0;background:transparent;color:#fff;font-size:17px;cursor:pointer}#castbar button:active{background:#333}
.tvpick{display:flex;align-items:center;gap:14px;padding:14px 8px;margin:0 -8px;border-radius:8px;cursor:pointer}.tvpick:hover{background:#222}.tvpick small{display:block;color:#888;font-size:13px;margin-top:2px}
@media (max-width: 820px), (hover: none) and (pointer: coarse){#castbar{bottom:calc(76px + env(safe-area-inset-bottom));left:10px;right:10px;transform:none;max-width:none;flex-wrap:wrap;justify-content:space-between;gap:2px;padding:10px 8px}#castbar .nm{flex-basis:100%;max-width:none;margin:0 6px 4px}#castbar .nm b{display:inline;margin-right:6px}#castbar button{min-width:34px;padding:0 4px;font-size:14px}}`;
  document.head.appendChild(st);
})();
// Phone: hide the floating TV button while scrolling down through a page, bring it back on the way up
if (matchMedia('(max-width: 820px), (hover: none) and (pointer: coarse)').matches) {
  let lastY = 0;
  document.addEventListener('scroll', e => {
    const el = e.target, b = $('#castbtn'); if (!b || !el || el.scrollTop == null || !el.classList || !el.classList.contains('page')) return;
    const y = el.scrollTop; if (Math.abs(y - lastY) < 8) return;
    b.classList.toggle('away', y > 40 && y > lastY); lastY = y;
  }, true);
}
function castButton() {
  if (!$('#castbtn')) { const b = document.createElement('button'); b.id = 'castbtn'; b.title = 'Play on TV'; b.innerHTML = TV_ICON; b.onclick = castPick; document.body.appendChild(b); }
  $('#castbtn').classList.toggle('on', !!castTV);
  const bar = $('#castbar');
  if (!castTV) { if (bar) bar.remove(); return; }
  if (bar) return;
  const b = document.createElement('div'); b.id = 'castbar';
  b.innerHTML = `<div class="nm"><b>${esc(castTV.name)}</b>Playing on TV</div><button data-c="skip" data-v="-10" title="Back 10 s">&#x21BA;</button><button data-c="pause" title="Play / Pause">&#x23EF;</button><button data-c="skip" data-v="10" title="Forward 10 s">&#x21BB;</button><button data-z="-1" title="Channel down">CH&#x2212;</button><button data-z="1" title="Channel up">CH+</button><button data-c="vol" data-v="-1" title="Quieter">&#x2212;</button><button data-c="vol" data-v="1" title="Louder">+</button><button data-c="stop" title="Stop">&#x25A0;</button><button class="x" title="Disconnect">&#x2715;</button>`;
  b.querySelectorAll('[data-c]').forEach(x => x.onclick = () => castSend({ t: 'ctl', c: x.dataset.c, v: x.dataset.v || '' }));
  b.querySelector('.x').onclick = () => { castTV = null; store.del('castTV'); castButton(); toast('Playing here again', 1800); };
  b.querySelectorAll('[data-z]').forEach(x => x.onclick = () => {
    if (!castZap || !castZap.list.length) return toast('Start a channel first', 1800);
    castZap.i = (castZap.i + +x.dataset.z + castZap.list.length) % castZap.list.length;
    const c = castZap.list[castZap.i]; castLive({ url: c.url, title: c.name, zap: castZap.list, zi: castZap.i });
  });
  document.body.appendChild(b);
}
async function castPick(onPicked) {
  const bg = document.createElement('div'); bg.className = 'sheet-bg';
  bg.style.zIndex = 95;   // above the player (80): the TV list also opens from the player's TV button
  bg.innerHTML = `<div class="sheet"><button class="sx" title="Close">&#x2715;</button><small class="kick">Play on TV</small><h2>Choose a TV</h2><div class="sub">Looking for your TVs…</div></div>`;
  document.body.appendChild(bg); requestAnimationFrame(() => bg.classList.add('on'));
  const shut = () => bg.remove();
  bg.onclick = e => { if (e.target === bg || e.target.closest('.sx')) shut(); };
  let tvs = [];
  try { tvs = ((await sb('/rest/v1/fof_devices?select=id,name,kind,app_version,last_seen,revoked&revoked=eq.false&order=last_seen.desc')) || []).filter(d => /TV|Android/i.test(d.kind || '') && !/app/i.test(d.kind || '')); } catch (e) {}
  // Every reinstall or new sign-in on a TV registers a new device: show each TV once, its most recent sign-in
  const seen = new Set(); tvs = tvs.filter(d => { const k = (d.name || d.kind || '').toLowerCase(); if (seen.has(k)) return false; seen.add(k); return true; });
  // Play on TV came with TV version 1.3.220
  const tvOld = d => { const m = /^1\.3\.(\d+)/.exec(d.app_version || ''); return m ? +m[1] < 220 : false; };
  // TVs hidden on this device (a box you no longer use)
  const hidden = store.get('hiddenTVs', []);
  const shown = tvs.filter(d => !hidden.includes((d.name || d.kind || '').toLowerCase()));
  // AirPlay TVs (Fire TV, Samsung, Apple TV …): this list cannot see them, Apple's AirPlay menu can
  const canAir = !!((window.fof && window.fof.airplay) || NATIVE);
  const airBtn = () => $('#pl-airmac') || $('#pl-air');
  const sh = $('.sheet', bg); if (!sh) return;
  sh.innerHTML = `<button class="sx" title="Close">&#x2715;</button><small class="kick">Play on TV</small><h2>Choose a TV</h2>` +
    (canAir ? `<div class="tvpick" data-air="1"><svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><path d="M5 17H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-1"/><path d="M12 15l5 6H7z" fill="currentColor"/></svg><div><b>AirPlay TVs</b><small>Med's Fire TV, Samsung and any other AirPlay TV</small></div></div>` : '') +
    (shown.length ? `<div class="sub" style="margin-top:14px">With Fof TV on the TV (Fof TV has to be open there)</div>` + shown.map(d => { const i = tvs.indexOf(d); return `<div class="tvpick" data-i="${i}">${TV_ICON}<div style="flex:1"><b>${esc(d.name || d.kind)}</b><small>${esc(d.kind)}${tvOld(d) ? ' · update Fof TV on this TV first' : ''}${castTV && castTV.id === d.id ? ' · connected' : ''}</small></div><button class="hidetv" data-h="${i}" title="Hide this TV" style="color:#888;font-size:13px;padding:6px 8px">Hide</button></div>`; }).join('') : '') +
    (window.fof && window.fof.smartTv ? `<div class="sub" style="margin-top:14px">Smart TVs on your Wi-Fi (the TV plays it itself, even with this app closed)</div><div id="smarttvs"><div class="sub" style="opacity:.7">Looking for TVs…</div></div>` : '') +
    (castTV ? `<div class="tvpick" data-i="-1"><span style="width:22px;text-align:center">&#x2715;</span><div><b>Play on this ${esc(HERE)}</b></div></div>` : '') +
    (hidden.length ? `<div class="sub" style="margin-top:14px"><a href="#" id="unhide" style="color:#aaa">Show hidden TVs (${hidden.length})</a></div>` : '') +
    (!shown.length && !canAir ? `<div class="sub">No TV is signed in to your account yet. Sign in to Fof TV on the TV (Settings › Account), then try again.</div>` : '');
  sh.querySelectorAll('.hidetv').forEach(b => b.onclick = ev => {
    ev.stopPropagation(); const d = tvs[+b.dataset.h], k = (d.name || d.kind || '').toLowerCase();
    store.set('hiddenTVs', [...new Set([...hidden, k])]);
    if (castTV && castTV.id === d.id) { castTV = null; store.del('castTV'); castButton(); }
    shut(); castPick(onPicked);
  });
  if (window.fof && window.fof.smartTv) window.fof.smartTv.find().then(list => {
    const box = $('#smarttvs', sh); if (!box) return;
    box.innerHTML = list.length ? list.map((d, i) => `<div class="tvpick" data-s="${i}">${TV_ICON}<div><b>${esc(d.name)}</b><small>${esc([d.maker, d.model].filter(Boolean).join(' '))}${castTV && castTV.id === d.id ? ' · connected' : ''}</small></div></div>`).join('')
      : '<div class="sub" style="opacity:.7">No smart TV answered. Is it on and on the same Wi-Fi?</div>';
    box.querySelectorAll('[data-s]').forEach(el => el.onclick = () => {
      const d = list[+el.dataset.s]; shut();
      castTV = { id: d.id, name: d.name, dlna: true }; store.set('castTV', castTV); castButton();
      if (onPicked) return onPicked();
      toast('Connected to ' + d.name + '. Press Play on anything.', 2600);
    });
  });
  const un = $('#unhide', sh); if (un) un.onclick = ev => { ev.preventDefault(); store.del('hiddenTVs'); shut(); castPick(onPicked); };
  sh.querySelectorAll('.tvpick:not([data-s])').forEach(el => el.onclick = () => {
    shut();
    if (el.dataset.air) {
      // AirPlay goes through the player (it hands the stream to Apple's player / QuickTime)
      if (castTV) { castTV = null; store.del('castTV'); castButton(); }
      if (cur && !$('#player').classList.contains('hidden') && airBtn()) return airBtn().click();
      return toast('Start a channel, game or movie, then press the TV button in the player and pick AirPlay TVs', 4500);
    }
    const i = +el.dataset.i;
    castTV = i < 0 ? null : { id: tvs[i].id, name: tvs[i].name || tvs[i].kind };
    if (castTV) store.set('castTV', castTV); else store.del('castTV');
    castButton();
    if (castTV && onPicked) return onPicked();
    toast(castTV ? 'Connected to ' + castTV.name + '. Press Play on anything.' : 'Playing here again', 2600);
  });
}
async function castSend(cmd) {
  if (!castTV) return false;
  if (castTV.dlna) {
    const tv = window.fof && window.fof.smartTv; if (!tv) { toast('Smart TVs work from the Mac app'); return false; }
    if (cmd.t === 'ctl') { const r = await tv.control(castTV.id, cmd.c, cmd.v); if (r && !r.ok) toast('TV: ' + r.err); return !!(r && r.ok); }
    const r = await tv.play(castTV.id, cmd.url, cmd.title, cmd.pos || 0);
    if (r && r.ok === false) { toast(castTV.name + ' could not play it: ' + String(r.err || '').slice(0, 80), 6000); return false; }
    if (r && r.relayed) setTimeout(() => toast('Playing through this Mac – keep it awake while you watch this one', 6000), 3200);
    return true;
  }
  try {
    const r = await rpc('fof_cast_send', { p_to: castTV.id, p_cmd: cmd });
    if (r && r.err) { toast(r.err); return false; }
    return true;
  } catch (e) {
    toast(/404|PGRST202|fof_cast_send/.test(String(e.message)) ? 'Play on TV is not switched on for your account yet.' : 'Could not reach the TV (' + String(e.message).slice(0, 40) + ')');
    return false;
  }
}
async function castTitle(req) {
  const tt = String(req.id).split(':')[0];
  if (!/^tt\d+$/.test(tt)) { toast('This title can only play here.'); return false; }
  const label = req.type === 'series' ? `${req.name} S${req.season} E${req.episode}` : req.name;
  // Smart TV: it needs the stream itself, so this device picks the source (best first) and hands over the link
  if (castTV.dlna) {
    toast('Finding the best source for ' + label + '…', 3000);
    let list = []; try { list = await streamsFor(Object.assign({}, req, { id: tt })); } catch (e) {}
    for (const src of list.slice(0, 3)) {
      if (await castSend({ url: src.url, title: label, pos: Math.max(0, Math.round((req.start || 0) / 1000)) })) { toast('Playing ' + label + ' on ' + castTV.name, 3000); return true; }
    }
    if (!list.length) toast('No source found for ' + label, 4000);
    return false;
  }
  if (await castSend({ t: 'play', id: 'stremio:' + tt, kind: req.type === 'series' ? 'series' : 'movie', title: req.name || '', s: req.season || 0, e: req.episode || 0, pos: Math.max(0, Math.round(req.start || 0)) })) {
    toast('Starting ' + label + ' on ' + castTV.name, 3000); return true;
  }
  return false;
}
async function castLive(item) {
  if (item.zap && item.zap.length) castZap = { list: item.zap, i: item.zi || 0 };
  const m = /\/live\/[^/]+\/[^/]+\/(\d+)(\.\w+)?(\?|$)/.exec(item.url || '');
  if (await castSend({ t: 'live', sid: m ? m[1] : '', name: item.title || '', url: item.url, title: item.title || '' })) { toast('Switching ' + castTV.name + ' to ' + (item.title || 'the channel'), 2600); return true; }
  return false;
}

// ------------------------------------------------------------------ player (hls.js for HLS, mpegts.js for live .ts, native for mp4/webm/mkv)
const V = $('#video'); let hls = null, mts = null, cur = null, idleT = null;
function stopEngines() { if (hls) { hls.destroy(); hls = null; } if (mts) { try { mts.destroy(); } catch (e) {} mts = null; } V.removeAttribute('src'); V.load(); }
function msg(t) { const m = $('#pl-msg'); if (!t) return m.classList.add('hidden'); m.textContent = t; m.classList.remove('hidden'); }
function play(item) {
  if (castTV && item.live && !item.here) return castLive(item);
  if (NATIVE && !item.live && vlcFor(item.url)) return playVlc(item);
  if (cur && !cur.live) saveResume(true);
  clearInterval(endOfTitle.t); const pe = $('#pl-end'); if (pe) pe.remove();
  clearTimeout(trailerIn.t); document.querySelectorAll('iframe.trailer').forEach(f => f.remove()); trailerIn.on = false;
  cur = item; stopEngines(); msg('');
  if (!item.keepStart) starting(false);
  $('#player').classList.remove('mini');
  $('#player').classList.remove('hidden');
  $('#pl-title').textContent = item.title || ''; $('#pl-sub').textContent = item.sub || '';
  $('#pl-src').classList.toggle('hidden', !(item.list && item.list.length > 1) && !item.zap);
  $('#pl-src').textContent = item.zap ? 'Channels' : 'Sources';
  startEngine(item, item.url, 0);
  setTimeout(drawScoreBar, 500);
  V.onloadedmetadata = () => { if (item.start > 30000 && isFinite(V.duration)) V.currentTime = Math.max(0, item.start / 1000 - 5); };
  V.onerror = () => { if (!hls && !mts) fail('This source does not play on the ' + HERE + ' (format). Trying the next one.'); };
  poke();
}
// Live: HLS (.m3u8) through hls.js first – it keeps a buffer and recovers from network hiccups far better than raw
// .ts – then raw .ts through mpegts.js with a 1 MB cushion. Never chase the live edge (that caused the stalls).
function startEngine(item, url, attempt) {
  const xtLive = item.live && /\/live\/[^/]+\/[^/]+\/\d+\.ts(\?|$)/.test(url);
  const isHls = /\.m3u8(\?|$)/i.test(url) || /m3u8/i.test(url) || (xtLive && attempt === 0);
  const hlsUrl = xtLive && attempt === 0 ? url.replace(/\.ts(\?|$)/, '.m3u8$1') : url;
  const isTs = /\.ts(\?|$)/i.test(url) || (item.live && !isHls);
  if (isHls && window.Hls && Hls.isSupported()) {
    hls = new Hls({ enableWorker: true, lowLatencyMode: false, liveSyncDurationCount: 4, maxBufferLength: 40, maxMaxBufferLength: 90, backBufferLength: 30,
      manifestLoadingMaxRetry: 4, levelLoadingMaxRetry: 6, fragLoadingMaxRetry: 8, fragLoadingRetryDelay: 600 });
    let netRetries = 0;
    hls.loadSource(hlsUrl); hls.attachMedia(V);
    hls.on(Hls.Events.ERROR, (e, d) => {
      if (!d.fatal) return;
      if (d.type === Hls.ErrorTypes.NETWORK_ERROR && netRetries++ < 2) return hls.startLoad();
      if (d.type === Hls.ErrorTypes.MEDIA_ERROR && netRetries++ < 2) return hls.recoverMediaError();
      if (xtLive && attempt === 0) { stopEngines(); return startEngine(item, url, 1); }   // provider has no HLS: plain .ts
      fail('This stream does not start.');
    });
  } else if (isTs && window.mpegts && mpegts.isSupported()) {
    mts = mpegts.createPlayer({ type: 'mpegts', isLive: !!item.live, url }, {
      enableWorker: true, enableStashBuffer: true, stashInitialSize: 1024 * 1024, liveBufferLatencyChasing: false,
      autoCleanupSourceBuffer: true, autoCleanupMaxBackwardDuration: 60, autoCleanupMinBackwardDuration: 30, lazyLoad: false });
    mts.attachMediaElement(V); mts.load();
    mts.on(mpegts.Events.ERROR, () => fail('This channel does not start right now.'));
  } else V.src = NATIVE ? hlsUrl : url;
  V.play().catch(() => {});
}

// Movies and episodes: a source that has not started after 20 s goes to the next one; one that played for a minute is
// remembered for that title (it goes first next time)
let vodWait = 0;
setInterval(() => {
  if (!cur || cur.live || $('#player').classList.contains('hidden')) { vodWait = 0; return; }
  if (cur.list && !cur.remembered && V.currentTime > 60) { cur.remembered = true; rememberSource(cur.req, cur.list[cur.idx]); }
  if (V.currentTime > 1 || V.paused && V.readyState >= 2) { vodWait = 0; return; }
  vodWait += 2;
  if (vodWait >= 14) { vodWait = 0; fail('This source does not start.'); }
}, 2000);

// Live watchdog: a channel frozen for 12 s reconnects by itself (up to 3 times in a row)
let lastT = -1, frozenFor = 0, reconnects = 0;
setInterval(() => {
  if (!cur || !cur.live || V.paused || $('#player').classList.contains('hidden')) { frozenFor = 0; return; }
  if (Math.abs(V.currentTime - lastT) < 0.05) frozenFor += 2; else { frozenFor = 0; if (V.currentTime > 30) reconnects = 0; }
  lastT = V.currentTime;
  if (frozenFor >= 12 && reconnects < 3) { reconnects++; frozenFor = 0; toast('Reconnecting the channel…'); const it = cur; stopEngines(); startEngine(it, it.url, 0); }
}, 2000);

function fail(t) {
  // Next source automatically, like the TV's failover
  if (cur && cur.list && cur.idx + 1 < cur.list.length && !cur.noFallback) { const n = cur.idx + 1; const starting_ = !$('#pl-start').classList.contains('hidden'); if (starting_) starting(true, { note: 'Trying another copy' }); else toast('Source did not start – trying the next one'); return play(Object.assign({}, cur, { url: cur.list[n].url, idx: n, keepStart: starting_ })); }
  if (!$('#pl-start').classList.contains('hidden')) return starting(true, { note: 'This one will not start right now. Try Sources on the title page.', stop: true });
  msg(t);
}
function closePlayer() { startTok++; starting(false); stopCorner(); const sb = $('#scorebar'); if (sb) sb.remove(); saveResume(true); if (document.pictureInPictureElement) document.exitPictureInPicture().catch(() => {}); stopEngines(); cur = null; $('#player').classList.add('hidden'); if (document.fullscreenElement) document.exitFullscreen(); }
function fmt(s) { if (!isFinite(s)) return 'LIVE'; s = Math.floor(s); const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), x = s % 60; return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(x).padStart(2, '0'); }
function poke() { $('#player').classList.remove('idle'); clearTimeout(idleT); idleT = setTimeout(() => { if (!V.paused) $('#player').classList.add('idle'); }, 3000); }
V.ontimeupdate = () => {
  saveResume(false);
  const d = V.duration, p = V.currentTime;
  const live = !isFinite(d) || (cur && cur.live);
  $('#pl-seek').style.visibility = live ? 'hidden' : 'visible';
  $('#pl-time').textContent = live ? '● LIVE' : fmt(p) + ' / ' + fmt(d);
  if (!live && d > 0) {
    $('#pl-pos').style.width = (p / d * 100) + '%'; $('#pl-knob').style.left = (p / d * 100) + '%';
    if (V.buffered.length) $('#pl-buf').style.width = (V.buffered.end(V.buffered.length - 1) / d * 100) + '%';
  }
};
V.onplay = () => { $('#pl-play').innerHTML = '&#10074;&#10074;'; msg(''); };
V.onpause = () => { $('#pl-play').innerHTML = '&#x25B6;'; poke(); };
V.onwaiting = () => { clearTimeout(V.wt); V.wt = setTimeout(() => { if (V.readyState < 3 && cur) msg('Loading…'); }, 1500); };
V.onplaying = () => { clearTimeout(V.wt); msg(''); starting(false); };
// No sound: Chrome plays the picture of some MKV files but cannot decode their audio (AC3, E-AC3, DTS) and stays
// silent. A few seconds in, if no audio has been decoded, move on to the next source from the same spot.
V.addEventListener('playing', () => {
  const it = cur; clearTimeout(V.sndT);
  if (!it || it.live || NATIVE || V.webkitAudioDecodedByteCount == null) return;
  V.sndT = setTimeout(() => {
    if (cur !== it || V.paused || V.muted || V.currentTime < 3 || V.webkitAudioDecodedByteCount > 0) return;
    if (it.list && it.idx + 1 < it.list.length && !it.noFallback) {
      toast('No sound on this source – switching to the next one', 3500);
      const n = it.idx + 1; play(Object.assign({}, it, { url: it.list[n].url, idx: n, start: Math.round(V.currentTime * 1000) }));
    } else toast('This source has no sound here. Try another one in Sources.', 4500);
  }, 5000);
});
$('#pl-play').onclick = () => V.paused ? V.play() : V.pause();
$('#pl-rw').onclick = () => { V.currentTime = Math.max(0, V.currentTime - 10); };
$('#pl-ff').onclick = () => { V.currentTime = V.currentTime + 10; };
$('#pl-vol').oninput = e => { V.volume = +e.target.value; };
// Mac: AirPlay through Apple's QuickTime Player (the Mac shell opens the stream there; its AirPlay button lists the TVs)
(function macAirPlay() {
  if (!window.fof || !window.fof.airplay) return;
  const b = document.createElement('button'); b.id = 'pl-airmac'; b.className = 'pl-icon'; b.title = 'AirPlay (opens in QuickTime)';
  b.innerHTML = '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><path d="M5 17H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-1"/><path d="M12 15l5 6H7z" fill="currentColor"/></svg>';
  b.onclick = () => {
    if (!cur || !cur.url) return;
    // Live: the provider's HLS link (QuickTime cannot play raw .ts); movies and episodes: the source as it is
    const u = cur.live ? cur.url.replace(/(\/live\/[^/]+\/[^/]+\/\d+)\.ts(\?|$)/, '$1.m3u8$2') : cur.url;
    if (!cur.live && /\.(mkv|avi)(\?|$)/i.test(u)) toast('This source is .mkv, which QuickTime may not play. Try another source if it does not start.', 5000);
    toast('Opening for AirPlay…', 2500);
    // Pause here only once the stream is open in QuickTime/Safari (older Mac shells return nothing: keep playing)
    Promise.resolve(window.fof.airplay(u)).then(r => {
      if (r && r.ok) { V.pause(); toast(r.app === 'Safari' ? 'Opened in Safari: click the AirPlay icon in the video and pick your TV' : 'Opened in QuickTime: click the AirPlay icon (bottom right) and pick your TV', 7000); }
      else if (r) toast('AirPlay could not open (' + (r.err || 'unknown') + '). Use the TV button instead.', 7000);
      else toast('Update the Mac app to use AirPlay (Fof TV 0.5.1)', 5000);
    }).catch(e => toast('AirPlay could not open: ' + String(e.message || e).slice(0, 80), 6000));
  };
  $('#pl-full').before(b);
})();
(function multiviewButton() {
  if (NATIVE) return;
  const b = document.createElement('button'); b.id = 'pl-multi'; b.className = 'pl-icon'; b.title = 'Add to Multiview (up to 4 games)';
  b.innerHTML = '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="8" height="8" rx="1"/><rect x="13" y="3" width="8" height="8" rx="1"/><rect x="3" y="13" width="8" height="8" rx="1"/><path d="M17 13v8M13 17h8"/></svg>';
  b.onclick = () => { if (!cur || !cur.live) return toast('Multiview is for live channels and games', 2500); const it = cur; closePlayer(); multiAdd(it.url, it.title, it.sub); };
  $('#pl-full').before(b);
})();
const multi = [];
function multiAdd(url, title, sub) {
  if (multi.some(m => m.url === url)) return multiDraw();
  if (multi.length >= 4) { toast('Multiview holds 4 games. Close one first.', 3000); return multiDraw(); }
  multi.push({ url, title, sub }); multiDraw();
}
function multiDraw() {
  let box = $('#multi');
  if (!multi.length) { if (box) { box.querySelectorAll('div[data-u]').forEach(t => { try { t._h && t._h.destroy(); } catch (e) {} }); box.remove(); } const mb = $('#multibar'); if (mb) mb.remove(); return; }
  if (!box) {
    box = document.createElement('div'); box.id = 'multi';
    box.style.cssText = 'position:fixed;inset:0;z-index:82;background:#000;display:grid;gap:4px;padding:0';
    document.body.appendChild(box);
  }
  const n = multi.length; box.style.gridTemplateColumns = n === 1 ? '1fr' : '1fr 1fr'; box.style.gridTemplateRows = n <= 2 ? '1fr' : '1fr 1fr';
  // Keep tiles that are still there (no restart), add new ones, drop closed ones
  [...box.children].forEach(t => { if (!multi.some(m => m.url === t.dataset.u)) { try { t._h && t._h.destroy(); } catch (e) {} t.remove(); } });
  multi.forEach((m, i) => {
    let t = [...box.children].find(x => x.dataset.u === m.url);
    if (!t) {
      t = document.createElement('div'); t.dataset.u = m.url;
      t.style.cssText = 'position:relative;background:#111;overflow:hidden;outline:4px solid transparent;outline-offset:-4px;cursor:pointer';
      t.innerHTML = `<video muted playsinline style="width:100%;height:100%;object-fit:contain"></video><div style="position:absolute;left:10px;top:8px;right:44px;font-size:13px;font-weight:700;text-shadow:0 1px 3px #000">${esc(m.title)}<span style="display:block;font-weight:500;color:#bbb">${esc(m.sub || '')}</span></div><button class="mx" title="Close" style="position:absolute;right:8px;top:6px;width:30px;height:30px;border-radius:50%;background:rgba(0,0,0,.6);color:#fff;font-size:15px">&#x2715;</button>`;
      box.appendChild(t);
      const v = $('video', t), xt = /\/live\/[^/]+\/[^/]+\/\d+\.ts(\?|$)/.test(m.url), u = xt ? m.url.replace(/\.ts(\?|$)/, '.m3u8$1') : m.url;
      if (window.Hls && Hls.isSupported() && /m3u8/i.test(u)) { const h = new Hls({ maxBufferLength: 12, enableWorker: true }); h.loadSource(u); h.attachMedia(v); t._h = h; } else v.src = u;
      v.play().catch(() => {});
      $('.mx', t).onclick = e => { e.stopPropagation(); const k = multi.findIndex(x => x.url === m.url); if (k >= 0) multi.splice(k, 1); multiDraw(); };
      t.onclick = () => box.querySelectorAll('div[data-u]').forEach(o => { const on = o === t; $('video', o).muted = !on; o.style.outlineColor = on ? '#fff' : 'transparent'; let k = $('.snd', o); if (on && !k) { k = document.createElement('span'); k.className = 'snd'; k.textContent = '♪ SOUND'; k.style.cssText = 'position:absolute;right:48px;top:8px;background:#E50914;color:#fff;font-weight:800;font-size:12px;padding:3px 8px;border-radius:5px'; o.appendChild(k); } else if (!on && k) k.remove(); });
      t.ondblclick = () => { const k = multi.findIndex(x => x.url === m.url); multi.splice(k, 1); multiDraw(); play({ url: m.url, title: m.title, sub: m.sub, live: true }); };
    }
  });
  if (!$('#multibar')) {
    const bar = document.createElement('div'); bar.id = 'multibar';
    bar.style.cssText = 'position:fixed;right:14px;bottom:14px;z-index:83;display:flex;gap:8px';
    bar.innerHTML = '<button class="btn" id="mxadd" style="height:38px;padding:0 14px;font-size:14px">+ Add a game</button><button class="btn primary" id="mxall" style="height:38px;padding:0 14px;font-size:14px">Close Multiview</button>';
    document.body.appendChild(bar);
    $('#mxadd').onclick = () => { box.style.display = 'none'; bar.style.display = 'none'; toast('Pick a game or channel, then press Multiview in the player', 3500); go('sports'); };
    $('#mxall').onclick = () => { multi.splice(0); multiDraw(); };
  }
  box.style.display = 'grid'; $('#multibar').style.display = 'flex';
  if (!multi.length) $('#multibar').remove();
}

(function playerTvButton() {
  const b = document.createElement('button'); b.id = 'pl-tv'; b.className = 'pl-icon'; b.title = 'Play on TV'; b.innerHTML = TV_ICON;
  const send = async () => {
    if (!cur) return;
    const it = cur, at = isFinite(V.duration) && !it.live ? V.currentTime : 0;
    const ok = it.live ? await castLive(it) : (it.req ? await castTitle(Object.assign({}, it.req, { name: it.req.name || it.title, start: Math.round(at * 1000) })) : (toast('This one can only play here.'), false));
    if (ok) closePlayer();
  };
  b.onclick = () => castTV ? send() : castPick(send);
  $('#pl-full').before(b);
})();
$('#pl-full').onclick = () => NATIVE && V.webkitEnterFullscreen ? V.webkitEnterFullscreen() : document.fullscreenElement ? document.exitFullscreen() : $('#player').requestFullscreen();
// iPhone: AirPlay to an Apple TV or AirPlay TV, and a reminder before the 7-day signing runs out (SideStore renews it)
if (NATIVE) {
  V.setAttribute('x-webkit-airplay', 'allow');
  const air = document.createElement('button'); air.id = 'pl-air'; air.className = 'pl-icon'; air.title = 'AirPlay';
  air.innerHTML = '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><path d="M5 17H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-1"/><path d="M12 15l5 6H7z" fill="currentColor"/></svg>';
  // The web player stops when the app goes to the background; Apple's own player keeps playing over AirPlay
  // (and in picture in picture) after Fof TV is closed, so AirPlay hands the stream over to it
  air.onclick = () => {
    const nat0 = window.Capacitor.Plugins && window.Capacitor.Plugins.FofNative;
    const src = cur && (V.currentSrc || V.src);
    if (!nat0 || !nat0.playNative || !src || /^blob:/.test(src)) { if (V.webkitShowPlaybackTargetPicker) V.webkitShowPlaybackTargetPicker(); return; }
    const at = isFinite(V.duration) && !(cur && cur.live) ? V.currentTime : 0;
    V.pause();
    nat0.playNative({ url: src, title: (cur && cur.title) || '', start: at, airplay: true }).catch(() => { V.play().catch(() => {}); });
  };
  $('#pl-full').before(air);
  const nat = window.Capacitor.Plugins && window.Capacitor.Plugins.FofNative;
  if (nat) nat.info().then(i => {
    if (!i || !i.expires) return;
    const left = (new Date(i.expires) - Date.now()) / 864e5; if (left > 2) return;
    const b = document.createElement('div');
    b.style.cssText = 'position:fixed;left:12px;right:12px;top:calc(10px + env(safe-area-inset-top));z-index:999;background:#141414;border:1px solid #e50914;border-radius:12px;padding:12px 14px;display:flex;align-items:center;gap:12px;font-size:14px';
    b.innerHTML = `<span style="flex:1">Fof TV needs a refresh ${left < 1 ? 'today' : 'in ' + Math.ceil(left) + ' days'} or it stops opening.</span><button class="btn primary" style="height:36px;padding:0 14px">Renew</button><button class="x" style="background:none;border:0;color:#aaa;font-size:18px">&#x2715;</button>`;
    b.querySelector('.btn').onclick = () => nat.openSideStore();
    b.querySelector('.x').onclick = () => b.remove();
    document.body.appendChild(b);
  }).catch(() => {});
}
$('#pl-back').onclick = closePlayer;
// Mini player: the video floats in a small macOS window above everything while you keep browsing
$('#pl-mini').onclick = async () => {
  try { await V.requestPictureInPicture(); $('#player').classList.add('mini'); } catch (e) { toast('The mini player is not available for this video.'); }
};
V.addEventListener('leavepictureinpicture', () => {
  if ($('#player').classList.contains('mini')) { $('#player').classList.remove('mini'); if (!V.paused && cur) poke(); else closePlayer(); }
});
V.onended = () => { saveResume(true); endOfTitle(); };
async function endOfTitle() {
  const it = cur; if (!it || it.live || !it.req) return;
  const req = it.req;
  const box = document.createElement('div'); box.id = 'pl-end'; $('#player').appendChild(box);
  const clear = () => { clearInterval(endOfTitle.t); box.remove(); };
  $('#pl-back').addEventListener('click', clear, { once: true });
  if (req.type === 'series') {
    const m = await metaOf('series', req.id);
    const eps = ((m && m.videos) || []).filter(v => v.season > 0).sort((a, b) => a.season - b.season || a.episode - b.episode);
    const i = eps.findIndex(v => v.season === req.season && v.episode === req.episode);
    const nx = i >= 0 ? eps[i + 1] : null;
    if (nx) {
      let n = 8;
      box.innerHTML = `<div class="nextcard">${nx.thumbnail ? `<img src="${esc(nx.thumbnail)}" onerror="this.remove()">` : ''}<div><small>NEXT EPISODE</small><b>S${nx.season} E${nx.episode} · ${esc(nx.name || nx.title || '')}</b></div><button class="btn primary" id="nxgo">&#x25B6;&nbsp; Play in <span id="nxn">${n}</span></button><button class="btn" id="nxno">Cancel</button></div>`;
      const goNext = () => { clear(); watch({ type: 'series', id: req.id, name: req.name, season: nx.season, episode: nx.episode }); };
      $('#nxgo', box).onclick = goNext;
      $('#nxno', box).onclick = clear;
      endOfTitle.t = setInterval(() => { n--; const s = $('#nxn', box); if (s) s.textContent = n; if (n <= 0) goNext(); }, 1000);
      return;
    }
  }
  const m = await metaOf(req.type, req.id);
  const g = m && (m.genres || [])[0];
  const l = g ? (await catalog(req.type, 'genre=' + encodeURIComponent(g))).filter(x => x.id !== req.id).slice(0, 6) : [];
  if (!l.length) return clear();
  box.innerHTML = `<div class="morelike"><small>${esc(req.name)}</small><h2>More Like This</h2><div class="mlrow">${l.map(x => cardHtml(x, req.type, { poster: true })).join('')}</div></div>`;
  box.querySelectorAll('[data-id]').forEach(c => c.onclick = () => { clear(); closePlayer(); openTitle(c.dataset.type, c.dataset.id); });
}
$('#pl-seek').onclick = e => { const r = e.currentTarget.getBoundingClientRect(); if (isFinite(V.duration)) V.currentTime = (e.clientX - r.left) / r.width * V.duration; };
$('#pl-src').onclick = () => {
  if (!cur) return;
  if (cur.zap) { const z = cur.zap; closePlayer(); return; }
  const c = cur; closePlayer(); sources(Object.assign({}, c.req, { start: Math.round(V.currentTime * 1000) }));
};
$('#player').addEventListener('mousemove', poke);
V.addEventListener('click', () => V.paused ? V.play() : V.pause());
V.addEventListener('dblclick', () => $('#pl-full').click());
document.addEventListener('keydown', e => {
  if ($('#player').classList.contains('hidden') || $('#player').classList.contains('mini') || e.target.tagName === 'INPUT') return;
  poke();
  if (e.key === ' ') { e.preventDefault(); V.paused ? V.play() : V.pause(); }
  else if (e.key === 'ArrowRight' && cur && !cur.zap) V.currentTime += 10;
  else if (e.key === 'ArrowLeft' && cur && !cur.zap) V.currentTime = Math.max(0, V.currentTime - 10);
  else if ((e.key === 'ArrowUp' || e.key === 'ArrowDown') && cur && cur.zap) {
    const n = (cur.zi + (e.key === 'ArrowUp' ? -1 : 1) + cur.zap.length) % cur.zap.length; const c = cur.zap[n];
    play({ url: c.url, title: c.name, sub: 'Live TV', live: true, zap: cur.zap, zi: n }); e.preventDefault();
  }
  else if (e.key === 'f' || e.key === 'F') $('#pl-full').click();
  else if (e.key === 'Escape') { if (document.fullscreenElement) document.exitFullscreen(); else closePlayer(); }
  else if (e.key === 'm' || e.key === 'M') V.muted = !V.muted;
});

// ------------------------------------------------------------------ start
async function start() {
  if (!session) return showSignIn();
  $('#app').innerHTML = '<div class="center"><div class="spin"></div></div>';
  try { await loadAccount(); }
  catch (e) { if (!session) return showSignIn(); return showSignIn('Could not reach your account: ' + e.message); }
  const last = store.get('profile', null);
  const p = acct.profiles.find(x => x.id === last);
  if (p && !(acct.pins[p.id] && acct.pins[p.id].pin_set && !acct.pins[p.id].unlocked)) return openProfile(p);
  if (acct.profiles.length === 1 && !(acct.pins[acct.profiles[0].id] || {}).pin_set) return openProfile(acct.profiles[0]);
  showWho();
}
start();

// ------------------------------------------------------------------ Calendar (same as the TV): your shows' new episodes
// (My List, dates from the catalog), and big games of the next 7 days on national TV. Month, week or list.
const cal = { tab: 0, filter: 0, view: 2, day: null, data: null };
const dkey = d => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
async function calData() {
  if (cal.data && Date.now() - cal.data.t < 30 * 60 * 1000) return cal.data.l;
  const out = [];
  const ids = acct.mylist.map(ttOf).filter(Boolean).slice(0, 40);
  const from = Date.now() - 45 * 864e5, to = Date.now() + 75 * 864e5;
  await Promise.all(ids.map(async tt => {
    const m = await metaOf('series', tt); if (!m || !m.videos) return;
    for (const v of m.videos) {
      const t = Date.parse(v.released || ''); if (!(t > from && t < to) || !v.season) continue;
      out.push({ day: dkey(new Date(t)), t, kind: 0, title: m.name, sub: 'S' + v.season + ' · E' + v.episode, img: v.thumbnail || m.background, open: () => openTitle('series', m.id) });
    }
  }));
  const days = [...Array(7)].map((_, i) => { const d = new Date(Date.now() + i * 864e5); return d.getFullYear() + String(d.getMonth() + 1).padStart(2, '0') + String(d.getDate()).padStart(2, '0'); });
  await Promise.all(LEAGUES.slice(0, 6).flatMap(l => days.map(async ds => {
    const j = await getJson(`https://site.api.espn.com/apis/site/v2/sports/${l[2]}/scoreboard?dates=${ds}`, 30 * 60 * 1000).catch(() => null);
    for (const e of (j && j.events) || []) {
      const c = (e.competitions || [])[0] || {}; const nets = (c.broadcasts || []).flatMap(b => b.names || []);
      if (!nets.length) continue;
      const side = h => ((c.competitors || []).find(k => k.homeAway === h) || {}).team || {};
      const a = side('away'), hm = side('home'), t = Date.parse(e.date);
      out.push({ day: dkey(new Date(t)), t, kind: 2, title: (a.shortDisplayName || '') + ' at ' + (hm.shortDisplayName || ''), sub: l[1] + ' · ' + nets.join(' · '),
        colors: ['#' + (a.color || '2a3142'), '#' + (hm.color || '2a3142')], open: () => go('sports') });
    }
  })));
  out.sort((x, y) => x.t - y.t);
  cal.data = { t: Date.now(), l: out };
  return out;
}
function calCard(e) {
  const tm = e.kind === 2 ? new Date(e.t).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }) : '';
  const bg = e.kind === 2 ? `background:linear-gradient(90deg,${e.colors[0]},${e.colors[1]})` : (e.img ? `background:#1a2030 url('${esc(e.img)}') center/cover` : 'background:#1a2030');
  return `<div class="calcard" tabindex="0" style="${bg}">${tm ? `<span class="calt">${tm}</span>` : ''}<div class="calb"><b>${esc(e.title)}</b><small>${esc(e.sub)}</small></div></div>`;
}
async function renderCalendar(pg) {
  if (!$('#calstyle')) { const st = document.createElement('style'); st.id = 'calstyle'; st.textContent = `
.calp{padding:40px 56px 60px}.calp h1{font-size:40px;margin:0}.calp .tz{color:#999;font-size:15px;margin-left:12px}
.calbar{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin:18px 0 10px}.calbar button{padding:8px 16px;border-radius:20px;background:#1a2030;color:#ccc;font-weight:600;font-size:15px}
.calbar button.on{background:#fff;color:#000}.calbar .v.on{background:#4d8bff;color:#fff}.calbar .sp{flex:1}
.calrow{display:flex;gap:14px;overflow-x:auto;padding:6px 0 14px;scrollbar-width:none}.calrow::-webkit-scrollbar{display:none}
.calcard{flex:none;width:280px;aspect-ratio:16/9;border-radius:12px;position:relative;overflow:hidden;cursor:pointer;outline:3px solid transparent}.calcard:hover,.calcard:focus{outline-color:#fff}
.calcard:after{content:'';position:absolute;inset:0;background:linear-gradient(180deg,transparent 40%,#000d)}.calb{position:absolute;left:12px;right:12px;bottom:10px;z-index:1}
.calb b{display:block;font-size:17px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.calb small{color:#ddd;font-size:12px}.calt{position:absolute;left:10px;top:8px;z-index:1;background:#0009;border-radius:5px;padding:2px 7px;font-size:12px;font-weight:800}
.calh{font-size:22px;font-weight:800;margin:14px 0 6px}.calh small{color:#999;font-size:14px;font-weight:500;margin-left:8px}
.calgrid{display:grid;grid-template-columns:repeat(7,1fr);gap:8px}.calgrid .dn{color:#999;font-size:12px}
.calday{height:76px;border-radius:10px;background:#151a25 center/cover;position:relative;cursor:pointer;outline:3px solid transparent;overflow:hidden}.calday.sel{outline-color:#4d8bff}.calday:hover{outline-color:#fff}
.calday:after{content:'';position:absolute;inset:0;background:linear-gradient(180deg,#0006,#000b)}.calday .n{position:absolute;left:8px;top:4px;font-weight:800;z-index:1}.calday .f{opacity:.4}
.calday .x{position:absolute;left:8px;right:8px;bottom:5px;z-index:1;font-size:12px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
@media (max-width: 820px){.calp{padding:20px 14px 30px}.calp h1{font-size:30px}.calday{height:54px}.calday .x{display:none}.calcard{width:220px}}`; document.head.appendChild(st); }
  if (!cal.day) cal.day = new Date();
  pg.innerHTML = '<div class="calp"><div style="display:flex;align-items:baseline"><h1>Calendar</h1><span class="tz">Times in your time zone</span></div><div id="calbody"><div class="spin"></div></div></div>';
  const all = await calData();
  if (current !== 'calendar' || !$('#calbody')) return;
  const draw = () => {
    const list = all.filter(e => cal.filter === 0 || (cal.filter === 1 && e.kind === 0) || (cal.filter === 2 && e.kind === 2));
    const today = dkey(new Date()), sel = dkey(cal.day);
    const btn = (k, i, t, cls = '') => `<button data-${k}="${i}" class="${cls}${cal[k] === i ? ' on' : ''}">${t}</button>`;
    let h = `<div class="calbar">${['My calendar', 'Airing today', 'Following'].map((t, i) => btn('tab', i, t)).join('')}<span class="sp"></span>${cal.tab === 0 ? ['List', 'Week', 'Month'].map((t, i) => btn('view', i, t, 'v')).join('') : ''}</div>`;
    if (cal.tab !== 2) h += `<div class="calbar">${['All', 'Shows', 'Sports'].map((t, i) => btn('filter', i, t)).join('')}</div>`;
    const byDay = {}; list.forEach(e => (byDay[e.day] = byDay[e.day] || []).push(e));
    const dayName = d => (dkey(d) === today ? 'Today · ' : '') + d.toLocaleDateString([], { weekday: 'long', month: 'short', day: 'numeric' });
    const rowOf = l => `<div class="calrow">${l.map(calCard).join('')}</div>`;
    if (cal.tab === 1) h += `<div class="calh">Airing today</div>` + rowOf(byDay[today] || []) + ((byDay[today] || []).length ? '' : '<div class="empty">Nothing from your list or the big games today.</div>');
    else if (cal.tab === 2) {
      const next = {}; list.filter(e => e.kind === 0 && e.day >= today).forEach(e => { if (!next[e.title]) next[e.title] = e; });
      h += `<div class="calh">Coming back<small>${Object.keys(next).length} with a date</small></div>` + rowOf(Object.values(next));
    } else if (cal.view === 0) {
      Object.keys(byDay).filter(k => k >= today).sort().slice(0, 21).forEach(k => { h += `<div class="calh">${dayName(new Date(k + 'T12:00'))}<small>${byDay[k].length}</small></div>` + rowOf(byDay[k]); });
    } else {
      const sl = byDay[sel] || [];
      h += `<div class="calh">${dayName(cal.day)}<small>${sl.length ? sl.length + ' on this day' : 'Nothing this day'}</small></div>` + (sl.length ? rowOf(sl) : '');
      const d0 = new Date(cal.day); if (cal.view === 2) d0.setDate(1); d0.setDate(d0.getDate() - d0.getDay());
      h += `<div class="calbar"><b style="font-size:18px">${cal.day.toLocaleDateString([], { month: 'long', year: 'numeric' })}</b><span class="sp"></span><button data-nav="-1">‹ Previous</button><button data-nav="0">Today</button><button data-nav="1">Next ›</button></div>`;
      h += '<div class="calgrid">' + ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'].map(x => `<div class="dn">${x}</div>`).join('');
      for (let i = 0; i < (cal.view === 1 ? 7 : 42); i++) {
        const d = new Date(d0); d.setDate(d0.getDate() + i); const k = dkey(d), l = byDay[k] || [], f = l[0];
        const bg = f ? (f.kind === 2 ? `background:linear-gradient(90deg,${f.colors[0]},${f.colors[1]})` : (f.img ? `background-image:url('${esc(f.img)}')` : '')) : '';
        h += `<div class="calday${k === sel ? ' sel' : ''}" data-d="${k}" style="${bg}"><span class="n${cal.view === 2 && d.getMonth() !== cal.day.getMonth() ? ' f' : ''}">${d.getDate()}</span>${f ? `<span class="x">${esc(f.title)}${l.length > 1 ? ' +' + (l.length - 1) : ''}</span>` : ''}</div>`;
      }
      h += '</div>';
    }
    const box = $('#calbody'); box.innerHTML = h;
    box.querySelectorAll('[data-tab]').forEach(b => b.onclick = () => { cal.tab = +b.dataset.tab; draw(); });
    box.querySelectorAll('[data-view]').forEach(b => b.onclick = () => { cal.view = +b.dataset.view; draw(); });
    box.querySelectorAll('[data-filter]').forEach(b => b.onclick = () => { cal.filter = +b.dataset.filter; draw(); });
    box.querySelectorAll('[data-d]').forEach(b => b.onclick = () => { cal.day = new Date(b.dataset.d + 'T12:00'); draw(); });
    box.querySelectorAll('[data-nav]').forEach(b => b.onclick = () => {
      const n = +b.dataset.nav, d = new Date(cal.day);
      if (n === 0) cal.day = new Date(); else if (cal.view === 1) { d.setDate(d.getDate() + 7 * n); cal.day = d; } else { d.setDate(1); d.setMonth(d.getMonth() + n); cal.day = d; }
      draw();
    });
    // Cards: the same order as drawn
    const shown = [...box.querySelectorAll('.calcard')]; let i = 0;
    const flat = [];
    if (cal.tab === 1) flat.push(...(byDay[today] || []));
    else if (cal.tab === 2) { const seen = {}; list.filter(e => e.kind === 0 && e.day >= today).forEach(e => { if (!seen[e.title]) { seen[e.title] = 1; flat.push(e); } }); }
    else if (cal.view === 0) Object.keys(byDay).filter(k => k >= today).sort().slice(0, 21).forEach(k => flat.push(...byDay[k]));
    else flat.push(...(byDay[sel] || []));
    shown.forEach(el => { const e = flat[i++]; if (e) el.onclick = e.open; });
  };
  draw();
}

// ------------------------------------------------------------------ Nuvio look (same as the TV): billboard and wide cards
function bbHtml() { return '<div id="bb" class="bb"><div class="bbbg"></div><div class="bbin"><div class="bbk"></div><h1 class="bbt"></h1><div class="bbm"></div><p class="bbd"></p></div></div>'; }
function bbShow(m) {
  const b = $('#bb'); if (!b || !m) return;
  const tt = ttOf(m.id);
  $('.bbbg', b).style.backgroundImage = `url('${esc(m.background || (tt ? bgOf(tt) : ''))}')`;
  glowArt(m.background || (tt ? bgOf(tt) : ''));
  $('.bbk', b).textContent = m.type === 'series' ? 'SERIES' : 'MOVIE';
  $('.bbt', b).innerHTML = tt ? `<img src="${esc(logoOf(tt))}" alt="${esc(m.name)}" onerror="this.outerHTML='${esc(m.name).replace(/'/g, '&#39;')}'">` : esc(m.name);
  $('.bbm', b).innerHTML = [m.genres && m.genres[0], m.runtime, m.releaseInfo || m.year].filter(Boolean).map(esc).join(' &bull; ') + (m.imdbRating ? ` <span class="imdb">IMDb</span> ${esc(m.imdbRating)}` : '');
  $('.bbd', b).textContent = m.description || '';
}
function bbWire(pg, first) {
  if (first) bbShow(first);
  let t = null;
  const on = async el => {
    const c = el.closest('[data-id]'); if (!c || !c.dataset.type) return;
    clearTimeout(t); t = setTimeout(async () => { const m = await metaOf(c.dataset.type, c.dataset.id); if (m) bbShow(Object.assign({ type: c.dataset.type }, m)); }, 220);
  };
  pg.addEventListener('mouseover', e => { if (e.target.closest && e.target.closest('.card.nv')) on(e.target); });
  pg.addEventListener('focusin', e => { if (e.target.closest && e.target.closest('.card.nv')) on(e.target); });
}
(function glowStyle() {
  const st = document.createElement('style');
  st.textContent = `@property --g1{syntax:'<color>';inherits:true;initial-value:#3a0b12}@property --g2{syntax:'<color>';inherits:true;initial-value:#161a2a}
body{--g1:#3a0b12;--g2:#161a2a;background:radial-gradient(ellipse 65% 75% at 82% 0%,var(--g1),transparent 70%),radial-gradient(ellipse 55% 60% at 4% 104%,var(--g2),transparent 70%),#0b0c10!important;transition:--g1 .9s,--g2 .9s}
.rail{background:rgba(0,0,0,.35)!important}
.pinc{display:flex;align-items:center;gap:14px;padding:10px 12px;border-radius:14px;background:#141a26;cursor:pointer;outline:3px solid transparent;flex:none;position:relative}
.pinc.sel,.pinc:hover{outline-color:#fff}.pinlg{width:86px;height:52px;border-radius:9px;flex:none;display:flex;align-items:center;justify-content:center;overflow:hidden}
.pinlg img{max-width:70px;max-height:40px;object-fit:contain}.pinlg img+b{display:none}.pinlg b{font-size:14px;font-weight:900;text-transform:uppercase}
.pinnm{flex:1;min-width:0}.pinnm b{display:block;font-size:17px}.pinnm small{display:block;color:#aaa;font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.pinx{flex:none;width:30px;height:30px;border-radius:50%;border:0;background:#ffffff14;color:#bbb;cursor:pointer;opacity:0}.pinc:hover .pinx{opacity:1}
.pinadd{flex:none;height:40px;border-radius:12px;border:1px dashed #ffffff33;background:none;color:#aaa;font-size:14px;cursor:pointer;margin-bottom:6px}.pinadd:hover{background:#fff;color:#000}
@media (hover:none){.pinx{opacity:1}}`;
  document.head.appendChild(st);
})();
(function wlStyle() {
  const st = document.createElement('style');
  st.textContent = `.sheet.wl{width:min(860px,96vw)}
.wh{display:flex;border-radius:16px;overflow:hidden;margin:8px 0 18px;min-height:150px}
.wh-t{flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:4px;padding:16px 8px;min-width:0}
.wh-t b{font-size:19px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:100%}.wh-t small{color:#ddd;font-size:13px}
.wh-lg{width:64px;height:64px;border-radius:50%;background:#0005;display:flex;align-items:center;justify-content:center;font-weight:900}.wh-lg img{width:46px;height:46px;object-fit:contain}
.wh-m{width:34%;max-width:240px;background:#0d1119;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:4px;padding:10px;text-align:center}
.wh-m .live{position:static;margin:0}.wl-cs{display:flex;gap:8px;flex:none}
.wh-m b{font-size:40px;font-weight:900;white-space:nowrap}.wh-m small{color:#ccc;font-size:13px}
.wl-h{font-size:19px;font-weight:800;margin:0 0 10px}.wl-h small{font-size:13px;color:#999;font-weight:500;margin-left:6px}
.sheet.wl .chlist{display:flex;flex-direction:column;gap:8px}
.sheet.wl .src{display:flex;align-items:center;gap:14px;padding:11px 14px;border-radius:12px;background:#141a26;border:2px solid transparent;cursor:pointer}
.sheet.wl .src:hover,.sheet.wl .src:focus{background:#ffffff14;border-color:#fff;outline:none}
.wl-lg{width:54px;height:34px;flex:none;border-radius:7px;background:#232a38;display:flex;align-items:center;justify-content:center}.wl-lg img{max-width:46px;max-height:28px;object-fit:contain}
.sheet.wl .nm2{flex:1;min-width:0}.sheet.wl .nm2 b{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.wl-c{flex:none;font-size:12px;font-weight:700;padding:4px 9px;border-radius:11px;background:#ffffff18;color:#ccc;white-space:nowrap}
.wl-c.ok{background:#1e5a32;color:#7be39a}.wl-c.best{background:#E50914;color:#fff}
@media (max-width:600px){.wh{min-height:120px}.wh-m{width:38%}.wh-m b{font-size:24px}.sheet.wl .src{flex-wrap:wrap}.wl-cs{width:100%;padding-left:64px}.wh-lg{width:48px;height:48px}.wh-lg img{width:34px;height:34px}.wh-t b{font-size:15px}.sheet.wl .src{gap:10px;padding:10px}}`;
  document.head.appendChild(st);
})();
(function nvStyle() {
  const st = document.createElement('style');
  st.textContent = `
.card.nv{width:300px;aspect-ratio:16/9;border-radius:12px;background:#151a25;position:relative;overflow:hidden;flex:none;outline:3px solid transparent;transition:transform .12s}
.card.nv>img:first-child{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}
.card.nv i{position:absolute;inset:0;background:linear-gradient(180deg,transparent 45%,#000c)}
.card.nv .nl{position:absolute;left:14px;bottom:12px;max-width:62%;max-height:42%;object-fit:contain;object-position:left bottom}
.card.nv .nt{position:absolute;left:14px;right:14px;bottom:10px;font-size:19px;font-weight:800;text-shadow:0 2px 8px #000a;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.card.nv:hover,.card.nv:focus{outline-color:#fff;transform:scale(1.04)}
.card.nv.seeall{display:flex;align-items:center;justify-content:center;font-size:20px;font-weight:800;cursor:pointer;background:#1a1e28}
.bb{position:relative;height:min(46vh,460px);margin-bottom:-40px}
.bbbg{position:absolute;right:0;top:0;width:68%;height:100%;background:center/cover no-repeat;transition:background-image .3s}
.bbbg:after{content:'';position:absolute;inset:0;background:linear-gradient(90deg,var(--bg,#000) 0%,rgba(0,0,0,.6) 30%,transparent 65%),linear-gradient(0deg,var(--bg,#000),transparent 45%)}
.bbin{position:absolute;left:56px;top:44px;width:min(640px,55%)}
.bbk{color:#bbb;font-weight:700;letter-spacing:3px;font-size:14px}
.bbt{margin:6px 0 10px;font-size:52px;line-height:1.05}.bbt img{max-height:110px;max-width:440px;object-fit:contain;display:block}
.bbm{color:#ddd;font-size:16px}.imdb{background:#f5c518;color:#000;font-weight:800;padding:1px 6px;border-radius:4px;font-size:12px;margin-left:6px}
.bbd{color:#ddd;font-size:16px;line-height:1.45;display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden}
.nvgen{position:relative;z-index:2;display:flex;gap:10px;flex-wrap:wrap;align-items:center;padding:56px 56px 0}
.nvgen .nvname{font-size:30px;font-weight:800;margin-right:10px}
.nvgen button{padding:8px 18px;border-radius:20px;border:2px solid #ffffff30;color:#ddd;font-weight:600}.nvgen button:hover,.nvgen button.on{background:#fff;color:#000;border-color:#fff}
.row{position:relative;z-index:1}
.spsplit{display:flex;gap:24px;align-items:flex-start}.splist{width:500px;flex:none;display:flex;flex-direction:column;gap:12px;max-height:calc(100vh - 210px);overflow:auto;padding:4px;scrollbar-width:none}
.spsec{display:flex;align-items:center;gap:8px;font-size:15px;letter-spacing:.5px;margin:10px 0 0}.spsec i{width:9px;height:9px;border-radius:50%;background:#E50914}.spsec small{margin-left:auto;color:#999;font-weight:500}
.spc{display:flex;border-radius:14px;overflow:hidden;background:#141a26;height:92px;cursor:pointer;outline:3px solid transparent;flex:none}.spc.sel,.spc:hover{outline-color:#5aa0ff}
.spl{width:52px;display:flex;align-items:center;justify-content:center;background:#0d1119;color:#bbb;font-size:11px;font-weight:800;text-align:center}
.spteams{flex:1;display:flex;flex-direction:column;min-width:0}.spt{flex:1;display:flex;align-items:center;gap:10px;padding:0 12px}.spt img,.spt .nolg{width:26px;height:26px;object-fit:contain}
.spt .tn{flex:1;font-weight:700;font-size:17px;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.spt small{display:block;font-size:11px;color:#ffffffaa;font-weight:500}.spt b{font-size:22px}
.sps{width:130px;padding:0 12px;display:flex;flex-direction:column;justify-content:center;background:#10151f;font-size:15px}.sps small{color:#999;font-size:11px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.sps .live{align-self:flex-start;position:static}
.sppanel{flex:1;min-width:0;position:sticky;top:0}.spbig{position:relative;aspect-ratio:16/9;border-radius:16px;overflow:hidden;background:#0c1018}
.spbg{position:absolute;inset:0;display:flex}.spbg>div{flex:1;display:flex;align-items:center;justify-content:center}.spbg img{width:38%;max-width:180px}
.spbig .lg{position:absolute;inset:0}.spbig video.pv{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;opacity:0;transition:opacity .4s}.spbig video.pv.on{opacity:1}
.spsc{position:absolute;left:0;right:0;bottom:0;display:flex;align-items:flex-end;padding:60px 24px 18px;background:linear-gradient(0deg,#000d,transparent)}.spsc>div:first-child{flex:1}
.spr{font-size:30px;font-weight:800}.spr b{margin-left:14px;font-size:36px}.spw{text-align:right;font-size:20px}.spw .live{position:static;display:inline-block}.spw b{display:block}
.spinfo{display:flex;gap:14px;margin-top:14px;align-items:stretch}.spinfo>div{flex:1;background:#141a26;border-radius:14px;padding:12px 16px;font-size:15px}.spinfo small{display:block;color:#999;font-weight:700;letter-spacing:1px;font-size:11px;margin-bottom:4px}
.chgrid.lvsplit{display:flex !important;gap:24px;align-items:flex-start;overflow:hidden}
.lvlist{width:460px;flex:none;display:flex;flex-direction:column;gap:10px;max-height:calc(100vh - 120px);overflow:auto;padding:4px;scrollbar-width:none}
.lvr{display:flex;gap:12px;align-items:center;background:#141a26;border-radius:12px;padding:10px;cursor:pointer;outline:3px solid transparent;flex:none}.lvr.sel,.lvr:hover{outline-color:#5aa0ff}
.lvlg{width:96px;height:56px;border-radius:8px;background:#0d1119;display:flex;align-items:center;justify-content:center;flex:none;overflow:hidden}.lvlg img{max-width:84%;max-height:80%;object-fit:contain}
.lvt{min-width:0;flex:1}.lvt>b{display:block;font-size:16px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.lvt .epg{font-size:12px;color:#aaa}
.lvpanel{flex:1;min-width:0;display:flex;flex-direction:column;gap:14px}.lvbig{position:relative;aspect-ratio:16/9;border-radius:16px;overflow:hidden;background:#0c1018}
.lvbig .lg{position:absolute;inset:0;display:flex;align-items:center;justify-content:center}.lvbig .lg img{max-width:30%;max-height:40%;object-fit:contain}.lvbig video.pv{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;opacity:0;transition:opacity .4s}.lvbig video.pv.on{opacity:1}
.lvon{position:absolute;left:0;right:0;bottom:0;padding:60px 22px 18px;background:linear-gradient(0deg,#000d,transparent)}.lvon b{display:block;font-size:28px;margin-top:6px}.lvon small{color:#ccc;font-size:15px}.lvon .live{position:static;display:inline-block}
.lvnext{background:#141a26;border-radius:14px;padding:12px 16px;font-size:15px;min-height:20px}.lvnext small{display:block;color:#999;font-weight:700;letter-spacing:1px;font-size:11px;margin-bottom:4px}
#lvplay,#spwatch{align-self:flex-start}
@media (max-width: 820px), (hover: none) and (pointer: coarse){
.card.nv{width:220px}.bb{height:300px;margin-bottom:-30px}.bbbg{width:100%;opacity:.55}.bbin{left:16px;right:16px;top:auto;bottom:50px;width:auto}.bbt{font-size:30px}.bbt img{max-height:64px;max-width:240px}.bbd{-webkit-line-clamp:2;font-size:14px}
.nvgen{padding:40px 14px 0;flex-wrap:nowrap;overflow-x:auto;scrollbar-width:none}.nvgen button{flex:none}.nvgen .nvname{font-size:22px;flex:none}
.spsplit{flex-direction:column-reverse}.splist{width:100%;max-height:none}.sppanel{position:static;width:100%}.spr{font-size:20px}.spr b{font-size:24px}.spinfo{flex-wrap:wrap}.spinfo>div{flex:1 1 100%}
.chgrid.lvsplit{flex-direction:column-reverse}.lvlist{width:100%;max-height:none}.lvon b{font-size:20px}}`;
  document.head.appendChild(st);
})();

(function whoStyle() {
  const st = document.createElement('style');
  st.textContent = `
.wv{overflow:hidden;background:#0b0d12}.wwall{position:absolute;inset:-80px;display:grid;grid-template-columns:repeat(6,1fr);gap:18px;transform:rotate(-6deg);opacity:.32;filter:blur(2px)}
.wwall div{aspect-ratio:16/9;border-radius:14px;background:#1a1e28 center/cover}
.wshade{position:absolute;inset:0;background:radial-gradient(ellipse at center,rgba(11,13,18,.55),#0b0d12 78%)}
.wv h1,.wv .tiles,.wv .wso{position:relative;z-index:1}.wv .wordmark{z-index:1}.wv h1{font-weight:800;font-size:56px}
.wv .tiles{gap:70px}.wv .tile .av{width:210px;height:210px;border-radius:50%;font-size:88px;outline:none;box-shadow:0 0 0 0 #fff;transition:transform .15s,box-shadow .15s}
.wv .tile:hover .av,.wv .tile:focus-visible .av{box-shadow:0 0 0 6px #fff,0 0 60px rgba(255,255,255,.3);transform:scale(1.05)}
.wv .tile{width:260px;color:#aaa}.wv .tile:hover,.wv .tile:focus-visible{color:#fff}.wv .wn{font-size:26px;font-weight:600}
.wv .wc{font-size:14px;color:#9a9a9a;max-width:260px;line-height:1.35;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;min-height:18px;margin-top:-6px;text-align:center}
.wso{margin-top:60px;color:#888;font-size:16px;letter-spacing:3px;text-transform:uppercase}.wso:hover{color:#fff}
@media (max-width: 820px), (hover: none) and (pointer: coarse){.wv .tiles{gap:26px}.wv .tile{width:150px}.wv .tile .av{width:130px;height:130px;font-size:56px}.wv h1{font-size:32px}.wwall{grid-template-columns:repeat(3,1fr)}.wv .wc{max-width:150px;font-size:12px}}`;
  document.head.appendChild(st);
})();
