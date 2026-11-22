// Fof TV for Mac – the TV app's account, profiles, catalog, addons, playlist and player in a desktop window.
'use strict';

// ------------------------------------------------------------------ account service (same as the TV app)
const SB = 'https://msooylkizqvdrgxrdjww.supabase.co';
const ANON = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im1zb295bGtpenF2ZHJneHJkand3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTQ3MTAzMTksImV4cCI6MjExMDI4NjMxOX0.PBF00osXJngq78z74Q2jVKFkc5yvJ41z9d7E0jJjryo';
const CM = 'https://v3-cinemeta.strem.io';
const VERSION = (window.fof && window.fof.version) || '0.1';

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
  await rpc('fof_register_device', { p_id: deviceId(), p_name: 'Mac', p_kind: 'Mac app', p_version: VERSION }).catch(() => {});
  acct.profiles = await sb('/rest/v1/fof_profiles?select=id,slot,name,avatar,photo&deleted=eq.false&order=slot');
  const ps = await rpc('fof_pin_status').catch(() => []);
  acct.pins = {}; (ps || []).forEach(p => acct.pins[p.profile_id] = p);
  const sec = await rpc('fof_secrets_get').catch(() => []);
  (sec || []).forEach(s => {
    if (s.name === 'addons') { try { acct.addons = JSON.parse(s.value || '[]').filter(a => /^https?:/.test(a)); } catch (e) {} }
    if (s.name === 'playlist') acct.playlist = s.value || '';
  });
}

// The TV saves Continue Watching as an Android preference file inside the profile's data ({"e": ["s", "<json>"]})
async function loadWatch(pid) {
  acct.watch = [];
  try {
    const rows = await sb('/rest/v1/fof_profile_data?select=data&profile_id=eq.' + pid);
    const files = rows && rows[0] && rows[0].data && rows[0].data.files;
    const w = files && files.ipvio_watch && files.ipvio_watch.e;
    if (w && w[0] === 's') acct.watch = JSON.parse(w[1]).map(o => ({ id: o.i, title: o.t, url: o.u, pos: o.p, dur: o.d, ts: o.ts, s: o.s || 0, e: o.e || 0, next: !!o.n }));
  } catch (e) { console.warn('watch', e); }
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
  $('#em').focus();
  $('#f').onsubmit = async e => {
    e.preventDefault(); $('#er').textContent = '';
    try { await auth('/auth/v1/token?grant_type=password', { email: $('#em').value.trim(), password: $('#pw').value }); await start(); }
    catch (x) { $('#er').textContent = x.message; }
  };
}

function showWho() {
  $('#app').innerHTML = `<div class="center who"><div class="wordmark">FOF TV</div><h1>Who's watching?</h1>
    <div class="tiles">${acct.profiles.map((p, i) => `<button class="tile" data-i="${i}"><div class="av">${avatarHtml(p, i)}</div>${esc(p.name || 'Profile')}${acct.pins[p.id] && acct.pins[p.id].pin_set ? '<div class="lock">&#x1F512; PIN</div>' : ''}</button>`).join('')}</div>
    <button class="btn-outline" id="so">Sign Out</button></div>`;
  document.querySelectorAll('.tile').forEach(b => b.onclick = () => pickProfile(acct.profiles[+b.dataset.i]));
  $('#so').onclick = signOut;
  const f = $('.tile'); if (f) f.focus();
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
        await rpc('fof_register_device', { p_id: deviceId(), p_name: 'Mac', p_kind: 'Mac app', p_version: VERSION }).catch(() => {});
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
  const r = await fetch(url); if (!r.ok) throw new Error('HTTP ' + r.status);
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
  live: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="2"/><path d="M16.2 7.8a6 6 0 0 1 0 8.4M7.8 16.2a6 6 0 0 1 0-8.4M19 5a10 10 0 0 1 0 14M5 19A10 10 0 0 1 5 5"/></svg>'
};
const NAV = [['search', 'Search'], ['home', 'Home'], ['shows', 'TV Shows'], ['movies', 'Movies'], ['sports', 'Sports'], ['live', 'Live TV']];
let current = 'home';
const navStack = [];

function renderShell(tab) {
  const p = acct.profile;
  $('#app').innerHTML = `<div class="shell"><nav class="rail"><img class="mark" src="mark.png">
    ${NAV.map(([k, l]) => `<a data-k="${k}" class="${k === tab ? 'on' : ''}">${ICONS[k]}<span>${l}</span></a>`).join('')}
    <a class="me" id="me" title="Switch profile"><div class="av">${avatarHtml(p, 0)}</div><span>${esc(p.name)}</span></a></nav>
    <main class="page" id="page"></main></div>`;
  document.querySelectorAll('.rail a[data-k]').forEach(a => a.onclick = () => go(a.dataset.k));
  $('#me').onclick = () => { acct.profile = null; store.del('profile'); showWho(); };
  go(tab);
}
function go(tab, arg) {
  current = tab;
  document.querySelectorAll('.rail a[data-k]').forEach(a => a.classList.toggle('on', a.dataset.k === tab));
  const pg = $('#page'); pg.scrollTop = 0;
  if (tab === 'home') renderHome(pg);
  else if (tab === 'movies') renderCategory(pg, 'movie');
  else if (tab === 'shows') renderCategory(pg, 'series');
  else if (tab === 'live') renderLive(pg);
  else if (tab === 'sports') renderSports(pg);
  else if (tab === 'search') renderSearch(pg);
  else if (tab === 'detail') renderDetail(pg, arg);
}
function openTitle(type, id) { navStack.push(current); go('detail', { type, id }); }

// ------------------------------------------------------------------ home / Movies / TV Shows
function cardHtml(m, type, opts = {}) {
  const img = posterOf(m);
  return `<div class="card" data-type="${esc(type || m.type)}" data-id="${esc(m.id)}" title="${esc(m.name)}">
    <div class="ph">${esc(m.name)}</div>${img ? `<img loading="lazy" src="${esc(img)}" onerror="this.remove()">` : ''}${opts.top ? '<div class="tag10">TOP<br>10</div>' : ''}</div>`;
}
function rowHtml(title, inner, more) {
  return `<section class="row"><h2>${esc(title)}${more ? `<small data-more="${esc(more)}">See all &#x203A;</small>` : ''}</h2>
    <div class="strip"><button class="arrow l">&#x2039;</button><div class="track">${inner}</div><button class="arrow r">&#x203A;</button></div></section>`;
}
function wireRows(root) {
  root.querySelectorAll('.strip').forEach(s => {
    const t = $('.track', s);
    $('.arrow.l', s).onclick = () => t.scrollBy({ left: -t.clientWidth * 0.85 });
    $('.arrow.r', s).onclick = () => t.scrollBy({ left: t.clientWidth * 0.85 });
  });
  root.querySelectorAll('[data-id]').forEach(c => { if (!c.onclick) c.onclick = () => openTitle(c.dataset.type, c.dataset.id); });
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

async function renderHome(pg) {
  pg.innerHTML = '<div class="spin"></div>';
  const [mv, sr] = await Promise.all([catalog('movie'), catalog('series')]);
  if (current !== 'home') return;
  const featured = [...mv.slice(0, 5), ...sr.slice(0, 3)].filter(m => m.id && m.id.startsWith('tt'));
  let idx = 0;
  const top = [...mv.slice(0, 5), ...sr.slice(0, 5)].sort(() => 0.5 - Math.random()).slice(0, 10);
  const genreRows = [['Action', 'movie', 'Action'], ['Laugh Out Loud', 'movie', 'Comedy'], ['Binge-worthy Crime', 'series', 'Crime'], ['Sci-Fi & Fantasy', 'movie', 'Sci-Fi'], ['Edge-of-Your-Seat Thrillers', 'movie', 'Thriller'], ['Family Movie Night', 'movie', 'Family'], ['Drama Series', 'series', 'Drama'], ['Animation', 'movie', 'Animation']];
  pg.innerHTML = `<div id="herobox"></div>${continueRow()}
    ${rowHtml('Top 10 Movies & Shows Today', top.map((m, i) => `<div class="top10" data-type="${m.type}" data-id="${m.id}"><div class="num">${i + 1}</div>${cardHtml(m, m.type)}</div>`).join(''))}
    ${rowHtml('Popular Movies', mv.slice(0, 30).map(m => cardHtml(m, 'movie')).join(''), 'movie')}
    ${rowHtml('Popular TV Shows', sr.slice(0, 30).map(m => cardHtml(m, 'series')).join(''), 'series')}
    <div id="genres"></div><div style="height:60px"></div>`;
  const drawHero = () => {
    const m = featured[idx]; if (!m) return;
    $('#herobox').innerHTML = heroHtml(m, m.type, idx, featured.length);
    $('#hplay').onclick = () => openTitle(m.type, m.id, true);
    $('#hinfo').onclick = () => openTitle(m.type, m.id);
  };
  drawHero();
  clearInterval(renderHome.t);
  renderHome.t = setInterval(() => { if (current !== 'home' || !$('#herobox')) return clearInterval(renderHome.t); idx = (idx + 1) % featured.length; drawHero(); }, 9000);
  wireRows(pg); wireContinue(pg);
  pg.querySelectorAll('[data-more]').forEach(s => s.onclick = () => go(s.dataset.more === 'movie' ? 'movies' : 'shows'));
  for (const [title, type, g] of genreRows) {
    const l = await catalog(type, 'genre=' + encodeURIComponent(g));
    if (current !== 'home' || !$('#genres')) return;
    if (l.length) { $('#genres').insertAdjacentHTML('beforeend', rowHtml(title, l.slice(0, 30).map(m => cardHtml(m, type)).join(''))); wireRows($('#genres')); }
  }
}

async function renderCategory(pg, type) {
  const name = type === 'movie' ? 'Movies' : 'TV Shows';
  pg.innerHTML = '<div class="spin"></div>';
  const top = await catalog(type);
  if (current !== (type === 'movie' ? 'movies' : 'shows')) return;
  const m = top.find(x => x.id && x.id.startsWith('tt')) || top[0];
  const genres = type === 'movie' ? ['Action', 'Comedy', 'Drama', 'Thriller', 'Horror', 'Sci-Fi', 'Romance', 'Animation', 'Documentary', 'Family', 'Crime', 'Adventure']
    : ['Drama', 'Comedy', 'Crime', 'Action', 'Sci-Fi', 'Reality-TV', 'Animation', 'Documentary', 'Mystery', 'Fantasy'];
  pg.innerHTML = `<div style="position:relative">${m ? heroHtml(m, type, 0, 1) : ''}<div style="position:absolute;top:44px;left:84px;display:flex;align-items:center;gap:26px;z-index:2">
      <div style="font-size:44px;font-weight:700">${name}</div>
      <select id="gsel" style="background:rgba(0,0,0,.6);color:#fff;border:1px solid #fff;padding:8px 14px;font-size:16px;border-radius:4px"><option value="">Genres</option>${genres.map(g => `<option>${g}</option>`).join('')}</select></div></div>
    ${rowHtml(`Top 10 ${name} Today`, top.slice(0, 10).map((x, i) => `<div class="top10" data-type="${type}" data-id="${x.id}"><div class="num">${i + 1}</div>${cardHtml(x, type)}</div>`).join(''))}
    ${rowHtml('Popular ' + name, top.slice(10, 40).map(x => cardHtml(x, type)).join(''))}<div id="genres"></div><div style="height:60px"></div>`;
  if (m) { $('#hplay').onclick = () => openTitle(type, m.id); $('#hinfo').onclick = () => openTitle(type, m.id); }
  wireRows(pg);
  $('#gsel').onchange = async e => {
    const g = e.target.value; if (!g) return;
    const l = await catalog(type, 'genre=' + encodeURIComponent(g));
    $('#genres').innerHTML = `<section class="search" style="padding-top:20px"><div class="grid"><h4>${esc(name)} · ${esc(g)}</h4>${l.map(x => cardHtml(x, type)).join('')}</div></section>`;
    wireRows($('#genres')); $('#genres').scrollIntoView();
  };
  for (const g of genres.slice(0, 8)) {
    const l = await catalog(type, 'genre=' + encodeURIComponent(g));
    if (!$('#genres') || current !== (type === 'movie' ? 'movies' : 'shows')) return;
    if (l.length) { $('#genres').insertAdjacentHTML('beforeend', rowHtml(g, l.slice(0, 30).map(x => cardHtml(x, type)).join(''))); wireRows($('#genres')); }
  }
}

// ------------------------------------------------------------------ title page
async function renderDetail(pg, { type, id }) {
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
      <div class="btns"><button class="btn primary" id="dplay">&#x25B6;&nbsp; ${resume ? (resume.s ? `Resume S${resume.s} E${resume.e}` : 'Resume') : 'Play'}</button>${m.trailers && m.trailers.length ? '<button class="btn" id="trl">Trailer</button>' : ''}</div>
      <div class="cast">${(m.genres || []).length ? `<b>Genres:</b> ${esc(m.genres.join(', '))}<br>` : ''}${(m.cast || []).length ? `<b>Cast:</b> ${esc(m.cast.slice(0, 6).join(', '))}` : ''}</div>
    </div></div>
    ${type === 'series' ? `<div class="section"><h3>Episodes <span id="sn"></span></h3><div class="eps"><div class="seasons" id="seasons"></div><div class="eplist" id="eplist"></div></div></div>` : ''}
    <div id="more"></div><div style="height:60px"></div></div>`;
  $('#back').onclick = () => go(navStack.pop() || 'home');
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
    document.querySelectorAll('#eplist .ep').forEach(e => e.onclick = () => sources({ type, id, name: m.name, season, episode: +e.dataset.e, start: resume && resume.s === season && resume.e === +e.dataset.e ? resume.pos : 0 }));
  };
  if (type === 'series' && seasons.length) drawSeason();
  $('#dplay').onclick = () => {
    if (type === 'series') { const s = resume && resume.s ? resume.s : season; const e = resume && resume.e ? resume.e : (epsOf(s)[0] || {}).episode || 1; sources({ type, id, name: m.name, season: s, episode: e, start: resume && !resume.next ? resume.pos : 0 }); }
    else sources({ type, id, name: m.name, start: resume ? resume.pos : 0 });
  };
  // You Might Also Like: same first genre
  const g = (m.genres || [])[0];
  if (g) catalog(type, 'genre=' + encodeURIComponent(g)).then(l => {
    if (!$('#more')) return;
    $('#more').innerHTML = rowHtml('You Might Also Like', l.filter(x => x.id !== id).slice(0, 24).map(x => cardHtml(x, type)).join(''));
    wireRows($('#more'));
  });
}

// ------------------------------------------------------------------ addon streams (account addons, like the TV's Sources)
async function streamsFor({ type, id, season, episode }) {
  const sid = type === 'series' ? `${id}:${season}:${episode}` : id;
  const all = [];
  await Promise.all(acct.addons.map(async base => {
    try {
      const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), 15000);
      const r = await fetch(`${base.replace(/\/manifest\.json$/, '').replace(/\/$/, '')}/stream/${type}/${encodeURIComponent(sid)}.json`, { signal: ctl.signal });
      clearTimeout(t);
      const j = await r.json();
      let addon = base.replace(/^https?:\/\//, '').split('/')[0];
      (j.streams || []).forEach(s => { if (s.url && /^https?:/.test(s.url)) all.push({ url: s.url, name: (s.name || addon).replace(/\n/g, ' '), title: s.title || s.description || '', addon, headers: s.behaviorHints && s.behaviorHints.proxyHeaders && s.behaviorHints.proxyHeaders.request }); });
    } catch (e) {}
  }));
  all.forEach(s => {
    const t = (s.name + ' ' + s.title);
    s.quality = /2160|\b4k\b|uhd/i.test(t) ? '4K' : /1080/.test(t) ? '1080p' : /720/.test(t) ? '720p' : /480|sd\b/i.test(t) ? 'SD' : '';
    s.size = (t.match(/(\d+(?:[.,]\d+)?)\s?(GB|MB)/i) || [''])[0].replace(',', '.');
    s.file = (s.title.split('\n').map(x => x.trim()).find(x => /\.(mkv|mp4|avi|ts|m4v|webm)\b/i.test(x)) || s.title.split('\n')[0] || '').replace(/^[^\w\[]+/, '').slice(0, 140);
    // The Mac plays H.264 MP4/MKV well; HEVC/x265, AV1 and some MKV audio often not
    s.risky = /hevc|x265|h\.?265|\bav1\b|dolby ?vision|\bdv\b|truehd|atmos|dts/i.test(t);
    s.addonName = s.name.split(/\s{2,}|\|/)[0].trim().slice(0, 40);
  });
  const rank = s => (s.risky ? 0 : 10) + ({ '4K': 3, '1080p': 4, '720p': 2, 'SD': 1 }[s.quality] || 0) + (/\.mp4\b|mp4\)/i.test(s.name + s.title) ? 1 : 0);
  return all.sort((a, b) => rank(b) - rank(a));
}

async function sources(req) {
  const bg = document.createElement('div'); bg.className = 'sheet-bg';
  const label = req.type === 'series' ? `${req.name} · S${req.season} E${req.episode}` : req.name;
  bg.innerHTML = `<div class="sheet"><h2>${esc(label)}</h2><div class="sub">Looking in your addons…</div><div class="spin"></div></div>`;
  document.body.appendChild(bg);
  bg.onclick = e => { if (e.target === bg) bg.remove(); };
  const close = e => { if (e.key === 'Escape') { bg.remove(); document.removeEventListener('keydown', close); } };
  document.addEventListener('keydown', close);
  if (!acct.addons.length) { $('.sheet', bg).innerHTML = `<h2>No addons yet</h2><div class="sub">Add Stremio addons on the TV (Settings → Stremio addons). They sync to this Mac through your account.</div>`; return; }
  const list = await streamsFor(req);
  if (!document.body.contains(bg)) return;
  if (!list.length) { $('.sheet', bg).innerHTML = `<h2>${esc(label)}</h2><div class="sub">None of your addons has a playable link for this right now.</div>`; return; }
  $('.sheet', bg).innerHTML = `<h2>${esc(label)}</h2><div class="sub">${list.length} sources · the ones most likely to play on a Mac first. If one does not start, the next one starts by itself.</div>${list.map((s, i) => `<div class="src" data-i="${i}"><span class="q ${s.quality === '4K' ? 'k4' : s.quality === '1080p' ? 'fhd' : ''}">${esc(s.quality || 'HD?')}</span><div class="nm2"><b>${esc(s.addonName)}</b><small>${esc(s.file)}</small></div>${s.risky ? '<span class="warn" title="Uses a format the Mac player often cannot play">may not play</span>' : ''}<span class="sz">${esc(s.size)}</span></div>`).join('')}`;
  bg.querySelectorAll('.src').forEach(el => el.onclick = () => { bg.remove(); document.removeEventListener('keydown', close); play({ url: list[+el.dataset.i].url, title: req.name, sub: req.type === 'series' ? `S${req.season} E${req.episode}` : '', start: req.start || 0, list, idx: +el.dataset.i, req }); });
  // One click play: with "auto" the best source starts at once (like the TV's one-click setting)
  if (req.auto) bg.querySelector('.src').click();
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
    live.chans = (chans || []).map(c => ({ name: c.name, logo: c.stream_icon, cat: String(c.category_id), url: `${xt.base}/live/${xt.user}/${xt.pass}/${c.stream_id}.ts` }));
  } else {
    const txt = await fetch(acct.playlist).then(r => r.text());
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
  live.loaded = true;
}
async function renderLive(pg) {
  if (!acct.playlist) { pg.innerHTML = '<div class="empty"><h2 style="color:#fff">Live TV</h2>No playlist in your account yet. Sign in to the same account on the TV with your IPTV playlist set; it syncs here automatically.</div>'; return; }
  pg.innerHTML = '<div class="spin"></div>';
  try { await loadLive(); } catch (e) { pg.innerHTML = `<div class="empty">Your playlist could not be loaded (${esc(e.message)}).</div>`; return; }
  if (current !== 'live') return;
  if (!live.cat) live.cat = (live.cats.find(c => /sport/i.test(c.name)) || live.cats[0] || {}).id;
  pg.innerHTML = `<div class="livepage"><div class="cats"><input id="lf" placeholder="Find a category"><div id="cl"></div></div><div class="chgrid" id="cg"></div></div>`;
  const drawCats = f => {
    $('#cl').innerHTML = live.cats.filter(c => !f || c.name.toLowerCase().includes(f)).map(c => `<button data-c="${esc(c.id)}" class="${c.id === live.cat ? 'on' : ''}">${esc(c.name)}<span>${c.n}</span></button>`).join('');
    document.querySelectorAll('#cl button').forEach(b => b.onclick = () => { live.cat = b.dataset.c; drawCats(f); drawChans(); });
  };
  const drawChans = () => {
    const l = live.chans.filter(c => c.cat === live.cat).slice(0, 600);
    $('#cg').innerHTML = l.map((c, i) => `<div class="ch" data-i="${i}"><div class="live">LIVE</div><div class="lg">${c.logo ? `<img loading="lazy" src="${esc(c.logo)}" onerror="this.outerHTML='<b>${esc(c.name.slice(0, 2).toUpperCase())}</b>'">` : `<b>${esc(c.name.slice(0, 2).toUpperCase())}</b>`}</div><div class="nm">${esc(c.name)}</div></div>`).join('');
    document.querySelectorAll('#cg .ch').forEach(el => el.onclick = () => { const c = l[+el.dataset.i]; play({ url: c.url, title: c.name, sub: 'Live TV', live: true, zap: l, zi: +el.dataset.i }); });
    $('#cg').scrollTop = 0;
  };
  $('#lf').oninput = e => drawCats(e.target.value.toLowerCase().trim());
  drawCats(''); drawChans();
}

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
      return { name: tm.displayName || '', short: tm.shortDisplayName || tm.name || '', nick: tm.name || '', city: tm.location || '', abbr: tm.abbreviation || '', logo: tm.logo || '', score: x.score, win: x.winner }; };
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

async function renderSports(pg) {
  pg.innerHTML = `<div class="sports"><h1>Sports</h1><div class="chips">${[['all', 'All']].concat(LEAGUES.map(l => [l[0], l[1]])).map(([k, l]) => `<button data-k="${k}" class="${k === sportPick ? 'on' : ''}">${l}</button>`).join('')}</div><div id="sg"><div class="spin"></div></div></div>`;
  pg.querySelectorAll('.chips button').forEach(b => b.onclick = () => { sportPick = b.dataset.k; store.set('sport', sportPick); renderSports(pg); });
  const ls = sportPick === 'all' ? LEAGUES : LEAGUES.filter(l => l[0] === sportPick);
  const lists = await Promise.all(ls.map(l => scoreboard(l[2]).then(gs => gs.map(g => Object.assign(g, { league: l[1], lk: l[0] })))));
  if (current !== 'sports' || !$('#sg')) return;
  const seen = new Set(); const all = lists.flat().filter(g => !seen.has(g.id) && seen.add(g.id));
  const groups = [['Live Now', all.filter(g => g.state === 'in')], ['Coming Up', all.filter(g => g.state === 'pre').sort((a, b) => new Date(a.date) - new Date(b.date))], ['Final', all.filter(g => g.state === 'post')]];
  let n = 0; const idx = [];
  $('#sg').innerHTML = groups.filter(([, l]) => l.length).map(([h, l]) => `<h2>${h}</h2><div class="games">${l.map(g => { idx.push(g); return gameHtml(g, n++); }).join('')}</div>`).join('')
    || '<div class="empty" style="padding:30px 0">No games today in this league.</div>';
  $('#sg').querySelectorAll('[data-g]').forEach(el => el.onclick = () => watchGame(idx[+el.dataset.g]));
  clearTimeout(renderSports.t); renderSports.t = setTimeout(() => { if (current === 'sports') renderSports(pg); }, 60000);
}

// Channels in the playlist that carry a game: the teams' own channels first, then the national network
const NET_ALIASES = { 'ESPN': ['espn'], 'ESPN2': ['espn2', 'espn 2'], 'ABC': ['abc'], 'TNT': ['tnt'], 'TBS': ['tbs'], 'NBA TV': ['nba tv', 'nbatv'], 'FOX': ['fox'], 'FS1': ['fs1', 'fox sports 1'], 'CBS': ['cbs'], 'NBC': ['nbc'], 'Peacock': ['peacock'], 'Prime Video': ['prime', 'amazon'], 'NFL Network': ['nfl network'], 'NHL Network': ['nhl network'], 'MLB Network': ['mlb network'], 'truTV': ['trutv', 'tru tv'], 'USA Net': ['usa network'], 'SEC Network': ['sec network'], 'ACC Network': ['acc network'], 'Big Ten Network': ['big ten', 'btn'] };
function gameChannels(g) {
  const norm = s => ' ' + String(s || '').toLowerCase().replace(/[^a-z0-9]+/g, ' ') + ' ';
  const has = (n, w) => w && w.length > 2 && n.includes(' ' + w.toLowerCase() + ' ');
  const league = { nba: 'nba', wnba: 'wnba', nfl: 'nfl', nhl: 'nhl', mlb: 'mlb', ncaaf: 'ncaa', ncaab: 'ncaa' }[g.lk] || '';
  const nets = g.nets.flatMap(n => NET_ALIASES[n] || [n.toLowerCase()]);
  const out = [];
  live.chans.forEach(c => {
    const n = norm(c.name); let sc = 0;
    for (const t of [g.home, g.away]) {
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
  bg.innerHTML = `<div class="sheet"><h2>${esc(g.away.short)} at ${esc(g.home.short)}</h2><div class="sub">Finding the channels in your playlist…</div><div class="spin"></div></div>`;
  document.body.appendChild(bg);
  const close = () => { bg.remove(); document.removeEventListener('keydown', esck); };
  const esck = e => { if (e.key === 'Escape') close(); };
  document.addEventListener('keydown', esck); bg.onclick = e => { if (e.target === bg) close(); };
  try { await loadLive(); } catch (e) {}
  const list = gameChannels(g);
  if (!document.body.contains(bg)) return;
  $('.sheet', bg).innerHTML = `<h2>${esc(g.away.short)} at ${esc(g.home.short)}</h2><div class="sub">${esc(g.league)} · ${esc(g.detail)}${g.nets.length ? ' · On ' + esc(g.nets.join(', ')) : ''}</div>` +
    (list.length ? `<div class="chlist">${list.map((c, i) => `<div class="src" data-i="${i}">${c.logo ? `<img src="${esc(c.logo)}" onerror="this.style.visibility='hidden'">` : ''}<div class="nm2"><b>${esc(c.name)}</b><small>${c.sc >= 9 ? 'Team channel' : 'National broadcast'}</small></div></div>`).join('')}</div>`
      : '<div class="sub">No channel in your playlist matches this game. Try the league\'s category in Live TV.</div>');
  bg.querySelectorAll('.src').forEach(el => el.onclick = () => { close(); const i = +el.dataset.i; play({ url: list[i].url, title: list[i].name, sub: `${g.away.short} at ${g.home.short}`, live: true, zap: list, zi: i }); });
}

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
    box.innerHTML = `<h4>Top Searches</h4>` + l.slice(0, 18).map(m => cardHtml(m, 'movie')).join(''); wireRows(box); return;
  }
  const [mv, sr] = await Promise.all([catalog('movie', 'search=' + encodeURIComponent(q)), catalog('series', 'search=' + encodeURIComponent(q))]);
  if (($('#sq') || {}).value?.trim() !== q) return;
  const ch = live.loaded ? live.chans.filter(c => c.name.toLowerCase().includes(q.toLowerCase())).slice(0, 24) : [];
  box.innerHTML = (ch.length ? '<h4>Live TV</h4>' + ch.map((c, i) => `<div class="ch" data-ci="${i}"><div class="live">LIVE</div><div class="lg">${c.logo ? `<img src="${esc(c.logo)}" onerror="this.remove()">` : `<b>${esc(c.name.slice(0, 2))}</b>`}</div><div class="nm">${esc(c.name)}</div></div>`).join('') : '') +
    (mv.length ? '<h4>Movies</h4>' + mv.slice(0, 24).map(m => cardHtml(m, 'movie')).join('') : '') +
    (sr.length ? '<h4>TV Shows</h4>' + sr.slice(0, 24).map(m => cardHtml(m, 'series')).join('') : '') ||
    `<div class="empty" style="grid-column:1/-1;padding:20px 0">No results for "${esc(q)}".</div>`;
  wireRows(box);
  box.querySelectorAll('[data-ci]').forEach(el => el.onclick = () => { const c = ch[+el.dataset.ci]; play({ url: c.url, title: c.name, sub: 'Live TV', live: true, zap: ch, zi: +el.dataset.ci }); });
}

// ------------------------------------------------------------------ player (hls.js for HLS, mpegts.js for live .ts, native for mp4/webm/mkv)
const V = $('#video'); let hls = null, mts = null, cur = null, idleT = null;
function stopEngines() { if (hls) { hls.destroy(); hls = null; } if (mts) { try { mts.destroy(); } catch (e) {} mts = null; } V.removeAttribute('src'); V.load(); }
function msg(t) { const m = $('#pl-msg'); if (!t) return m.classList.add('hidden'); m.textContent = t; m.classList.remove('hidden'); }
function play(item) {
  cur = item; stopEngines(); msg('');
  $('#player').classList.remove('hidden');
  $('#pl-title').textContent = item.title || ''; $('#pl-sub').textContent = item.sub || '';
  $('#pl-src').classList.toggle('hidden', !(item.list && item.list.length > 1) && !item.zap);
  $('#pl-src').textContent = item.zap ? 'Channels' : 'Sources';
  startEngine(item, item.url, 0);
  V.onloadedmetadata = () => { if (item.start > 30000 && isFinite(V.duration)) V.currentTime = Math.max(0, item.start / 1000 - 5); };
  V.onerror = () => { if (!hls && !mts) fail('This source does not play on the Mac (format). Trying the next one.'); };
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
  } else V.src = url;
  V.play().catch(() => {});
}

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
  if (cur && cur.list && cur.idx + 1 < cur.list.length && !cur.noFallback) { toast('Source did not start – trying the next one'); const n = cur.idx + 1; return play(Object.assign({}, cur, { url: cur.list[n].url, idx: n })); }
  msg(t);
}
function closePlayer() { stopEngines(); cur = null; $('#player').classList.add('hidden'); if (document.fullscreenElement) document.exitFullscreen(); }
function fmt(s) { if (!isFinite(s)) return 'LIVE'; s = Math.floor(s); const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), x = s % 60; return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(x).padStart(2, '0'); }
function poke() { $('#player').classList.remove('idle'); clearTimeout(idleT); idleT = setTimeout(() => { if (!V.paused) $('#player').classList.add('idle'); }, 3000); }
V.ontimeupdate = () => {
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
V.onplaying = () => { clearTimeout(V.wt); msg(''); };
$('#pl-play').onclick = () => V.paused ? V.play() : V.pause();
$('#pl-rw').onclick = () => { V.currentTime = Math.max(0, V.currentTime - 10); };
$('#pl-ff').onclick = () => { V.currentTime = V.currentTime + 10; };
$('#pl-vol').oninput = e => { V.volume = +e.target.value; };
$('#pl-full').onclick = () => document.fullscreenElement ? document.exitFullscreen() : $('#player').requestFullscreen();
$('#pl-back').onclick = closePlayer;
$('#pl-seek').onclick = e => { const r = e.currentTarget.getBoundingClientRect(); if (isFinite(V.duration)) V.currentTime = (e.clientX - r.left) / r.width * V.duration; };
$('#pl-src').onclick = () => {
  if (cur.zap) { const z = cur.zap; closePlayer(); return; }
  const c = cur; closePlayer(); sources(Object.assign({}, c.req, { start: Math.round(V.currentTime * 1000) }));
};
$('#player').addEventListener('mousemove', poke);
V.addEventListener('click', () => V.paused ? V.play() : V.pause());
V.addEventListener('dblclick', () => $('#pl-full').click());
document.addEventListener('keydown', e => {
  if ($('#player').classList.contains('hidden') || e.target.tagName === 'INPUT') return;
  poke();
  if (e.key === ' ') { e.preventDefault(); V.paused ? V.play() : V.pause(); }
  else if (e.key === 'ArrowRight' && !cur.zap) V.currentTime += 10;
  else if (e.key === 'ArrowLeft' && !cur.zap) V.currentTime = Math.max(0, V.currentTime - 10);
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
