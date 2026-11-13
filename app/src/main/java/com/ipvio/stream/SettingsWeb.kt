package com.ipvio.stream

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Fof TV settings website (like Nuvio's): opened on the phone at http://<TV>:<port>/s with the code from the TV.
 * Home rows on/off, one-click play, big picture speed, Stremio addons. Answers the /api/cfg* requests of the page.
 */
object SettingsWeb {
    private val main = Handler(Looper.getMainLooper())

    private fun q(s: String) = RemoteLogic.q(s)

    fun state(): String {
        val rows = HomeLayout.blocks.filter { !it.fixed }.joinToString(",") { b ->
            "{\"id\":${q(b.id)},\"t\":${q(Lang.tr(b.title))},\"d\":${q(Lang.tr(b.sub))},\"on\":${HomeLayout.shown(b.id)}}"
        }
        val ads = Stremio.addons.joinToString(",") { a -> "{\"b\":${q(a.base)},\"n\":${q(a.name)}}" }
        return "{\"rows\":[$rows],\"oneclick\":${FofPrefs.oneClick},\"tmdb\":${Config.tmdbOwn || Config.TMDB_KEY.isNotBlank()},\"pvod\":${FofPrefs.providerVod},\"hero\":${General.heroSec},\"addons\":[$ads]}"
    }

    /** Applies one change; runs the state change on the main thread. */
    fun set(c: Context, k: String, id: String, v: String): String {
        when (k) {
            "row" -> if (HomeLayout.block(id) != null) main.post { HomeLayout.setShown(c, id, v == "1") }
            "oneclick" -> main.post { FofPrefs.setOneClick(c, v == "1") }
            "pvod" -> main.post { FofPrefs.setProviderVod(c, v == "1") }
            "hero" -> v.toIntOrNull()?.let { s -> main.post { General.setHeroSec(c, s) } }
            "addonrm" -> main.post { Stremio.remove(c, id) }
            "tmdb" -> { Config.init(c); Config.save(c, id.trim(), if (Config.omdbOwn) Config.OMDB_KEY else "") }
            "upd" -> {
                // Manual update check from the phone; a find shows in the TV's bell and here
                val r = kotlinx.coroutines.runBlocking { AppUpdate.check(c) }
                return r.fold({ u -> if (u == null) "{\"ok\":true,\"msg\":\"You have the newest version (" + AppUpdate.currentName(c) + ").\"}"
                    else "{\"ok\":true,\"msg\":\"New version " + u.name + " – open the bell on the TV to install.\"}" },
                    { "{\"ok\":true,\"msg\":\"Could not reach GitHub. Try again in a minute.\"}" })
            }
            else -> return "{\"err\":\"unknown\"}"
        }
        return "{\"ok\":true}"
    }

    fun page(): String = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Fof TV settings</title><style>
body{margin:0;background:#0b0b0d;color:#f2f2f2;font:16px -apple-system,Roboto,Helvetica,Arial,sans-serif}
main{max-width:640px;margin:0 auto;padding:20px 16px 60px}h1{font-size:26px;margin:4px 0 2px}h2{font-size:15px;text-transform:uppercase;letter-spacing:.08em;color:#9a9aa2;margin:28px 0 8px}
.c{background:#16161a;border-radius:14px;overflow:hidden}.r{display:flex;align-items:center;gap:12px;padding:14px 16px;border-top:1px solid #222228}.r:first-child{border-top:0}
.r div{flex:1}.r small{display:block;color:#8a8a92;font-size:13px;margin-top:2px}
.t{position:relative;width:50px;height:30px;flex:none}.t input{opacity:0;width:0;height:0}.t span{position:absolute;inset:0;background:#3a3a42;border-radius:30px;transition:.2s}
.t span:before{content:"";position:absolute;width:24px;height:24px;left:3px;top:3px;background:#fff;border-radius:50%;transition:.2s}.t input:checked+span{background:#e50914}.t input:checked+span:before{transform:translateX(20px)}
input[type=text],select{width:100%;box-sizing:border-box;background:#0b0b0d;color:#fff;border:1px solid #33333a;border-radius:10px;padding:12px;font-size:16px}
button{background:#e50914;color:#fff;border:0;border-radius:10px;padding:12px 16px;font-size:16px;font-weight:600}button.g{background:#2a2a30}
#msg{position:fixed;left:50%;bottom:20px;transform:translateX(-50%);background:#fff;color:#000;padding:10px 16px;border-radius:20px;display:none}a{color:#e50914}
</style></head><body><main><h1>Fof TV</h1><div style="color:#8a8a92">Changes show on the TV right away. <a id="rl" href="/r">Phone remote</a></div>
<h2>Playback</h2><div class="c"><div class="r"><div>One-click play<small>Play starts the best stream at once</small></div><label class="t"><input type="checkbox" id="oc"><span></span></label></div>
<div class="r"><div>Provider movies &amp; series in search<small>Off: movies and shows come only from your Stremio addons</small></div><label class="t"><input type="checkbox" id="pv"><span></span></label></div>
<div class="r"><div>Big picture changes every<small>0 = stays on one title</small></div><select id="hero" style="width:110px"><option>0</option><option>6</option><option>9</option><option>12</option><option>20</option></select></div></div>
<h2>TMDB key</h2><div class="c"><div class="r"><div>Descriptions, ratings, cast and episode pictures<small id="tk">From themoviedb.org (free account &gt; Settings &gt; API)</small></div></div></div>
<div style="display:flex;gap:8px;margin-top:10px"><input type="text" id="tki" placeholder="Paste your TMDB API key (v3)"><button onclick="tmdb()">Save</button></div>
<h2>App updates</h2><div class="c"><div class="r"><div>Check for a new version<small id="updv">The TV also checks by itself every hour</small></div><button class="g" onclick="upd()">Check now</button></div></div>
<h2>Home rows</h2><div class="c" id="rows"></div>
<h2>Stremio addons</h2><div class="c" id="ads"></div>
<div style="display:flex;gap:8px;margin-top:10px"><input type="text" id="au" placeholder="Paste addon link (manifest.json)"><button onclick="add()">Add</button></div>
</main><div id="msg"></div><script>
var code='';try{code=new URLSearchParams(location.search).get('code')||sessionStorage.getItem('ipvio_code')||''}catch(e){}
function ask(){var c=prompt('Code from the TV');if(c){code=c;try{sessionStorage.setItem('ipvio_code',c)}catch(e){}load()}}
function api(p,o){var u='/api/'+p+'?code='+encodeURIComponent(code);for(var k in o||{})u+='&'+k+'='+encodeURIComponent(o[k]);
return fetch(u).then(function(r){if(r.status==403){ask();throw 0}if(!r.ok)throw 1;return r.json()})}
function toast(t){var m=document.getElementById('msg');m.textContent=t;m.style.display='block';clearTimeout(window.tt);window.tt=setTimeout(function(){m.style.display='none'},1800)}
function esc(s){return String(s).replace(/[&<>"]/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
function set(k,id,v){return api('cfgset',{k:k,id:id,v:v}).then(function(){toast('Saved')}).catch(function(e){if(e)toast('Not saved')})}
function load(){document.getElementById('rl').href='/r?code='+encodeURIComponent(code);api('cfg').then(function(s){try{sessionStorage.setItem('ipvio_code',code)}catch(e){}
var oc=document.getElementById('oc');oc.checked=s.oneclick;oc.onchange=function(){set('oneclick','',oc.checked?1:0)};
var pv=document.getElementById('pv');pv.checked=s.pvod;pv.onchange=function(){set('pvod','',pv.checked?1:0)};
document.getElementById('tk').textContent=s.tmdb?'Key is set ✓':'No key yet: title pages show less information';
var h=document.getElementById('hero');h.value=String(s.hero);h.onchange=function(){set('hero','',h.value)};
document.getElementById('rows').innerHTML=s.rows.map(function(r){return '<div class="r"><div>'+esc(r.t)+'<small>'+esc(r.d)+'</small></div><label class="t"><input type="checkbox" data-id="'+esc(r.id)+'"'+(r.on?' checked':'')+'><span></span></label></div>'}).join('');
Array.prototype.forEach.call(document.querySelectorAll('#rows input'),function(i){i.onchange=function(){set('row',i.getAttribute('data-id'),i.checked?1:0)}});
document.getElementById('ads').innerHTML=s.addons.length?s.addons.map(function(a){return '<div class="r"><div>'+esc(a.n)+'<small>'+esc(a.b)+'</small></div><button class="g" data-b="'+esc(a.b)+'">Remove</button></div>'}).join(''):'<div class="r"><div>No addons yet</div></div>';
Array.prototype.forEach.call(document.querySelectorAll('#ads button'),function(b){b.onclick=function(){set('addonrm',b.getAttribute('data-b'),'').then(function(){setTimeout(load,400)})}})
}).catch(function(){})}
function tmdb(){var k=document.getElementById('tki').value.trim();if(!k)return;api('cfgset',{k:'tmdb',id:k,v:''}).then(function(){toast('Saved');document.getElementById('tki').value='';setTimeout(load,300)}).catch(function(){})}
function upd(){toast('Checking...');api('cfgset',{k:'upd',id:'',v:''}).then(function(r){document.getElementById('updv').textContent=r.msg||'';toast(r.msg||'Done')}).catch(function(){})}
function add(){var u=document.getElementById('au').value.trim();if(!u)return;toast('Adding...');api('addon',{u:u}).then(function(r){if(r.err)toast(r.err);else{toast('Added');document.getElementById('au').value='';setTimeout(load,400)}}).catch(function(){toast('Not added')})}
load()</script></body></html>"""
}
