// iPhone self-update: the screens (desktop/renderer: app.js, styles.css, mobile.css) come from the newest commit on
// GitHub, like the Mac app. A downloaded version is kept in localStorage and used from the next start (or when the
// app comes back from the background while nothing plays). If it fails to start, it is dropped and the built-in one runs.
(function () {
  var REPO = 'medfofana9-web/IPVIO-Stream', KEY = 'fofUpd', FILES = ['app.js', 'styles.css', 'mobile.css'];
  var BUILT = window.FOF_BUILD_TIME || 0;
  var cache = null;
  try { cache = JSON.parse(localStorage.getItem(KEY) || 'null'); } catch (e) {}
  // An app installed later than the download carries newer screens itself
  if (cache && !(cache.date > BUILT && cache.files && cache.files['app.js'])) cache = null;

  if (cache) {
    var links = document.querySelectorAll('link[rel=stylesheet]');
    for (var i = 0; i < links.length; i++) {
      var n = links[i].getAttribute('href');
      if (cache.files[n] != null) { var st = document.createElement('style'); st.textContent = cache.files[n]; links[i].replaceWith(st); }
    }
  }
  window.__fofStarted = false;
  var s = document.createElement('script');
  if (cache) {
    s.text = cache.files['app.js'] + '\n;window.__fofStarted = true;';
    document.body.appendChild(s);
    if (!window.__fofStarted) {   // the downloaded screens broke: back to the built-in ones
      try { localStorage.setItem(KEY + 'Bad', cache.sha); localStorage.removeItem(KEY); } catch (e) {}
      location.reload();
      return;
    }
  } else {
    s.src = 'app.js';
    document.body.appendChild(s);
  }

  var pending = false, lastCheck = 0;
  function get(url, json) {
    return fetch(url, { cache: 'no-store' }).then(function (r) { if (!r.ok) throw new Error(url + ' ' + r.status); return json ? r.json() : r.text(); });
  }
  function check() {
    if (Date.now() - lastCheck < 20 * 60 * 1000) return;
    lastCheck = Date.now();
    get('https://api.github.com/repos/' + REPO + '/commits?path=desktop/renderer&per_page=1', true).then(function (l) {
      var c = l && l[0]; if (!c) return;
      var date = Date.parse(c.commit.committer.date) || 0;
      if (date <= BUILT) return;                                   // the installed app is this new already
      if (cache && cache.sha === c.sha) return;
      if (localStorage.getItem(KEY + 'Bad') === c.sha) return;     // this one failed before
      return Promise.all(FILES.map(function (f) { return get('https://raw.githubusercontent.com/' + REPO + '/' + c.sha + '/desktop/renderer/' + f); }))
        .then(function (txt) {
          // (no parse check here: the page's security policy blocks eval; a version that fails to start is dropped on launch)
          if (txt[0].length < 50000) throw new Error('app.js looks incomplete');
          var files = {}; FILES.forEach(function (f, i) { files[f] = txt[i]; });
          localStorage.setItem(KEY, JSON.stringify({ sha: c.sha, date: date, files: files }));
          pending = true;
        });
    }).catch(function (e) { console.warn('update check', e); });
  }
  function idle() { var p = document.getElementById('player'); return !p || p.classList.contains('hidden'); }
  setTimeout(check, 5000);
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState !== 'visible') return;
    if (pending && idle()) { location.reload(); return; }
    check();
  });
})();
