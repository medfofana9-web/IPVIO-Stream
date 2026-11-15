package com.ipvio.stream

import kotlinx.coroutines.launch
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

    /** Switches shown on the page: key, title, description, current value. */
    private fun flags(c: Context): List<Triple<String, Pair<String, String>, Boolean>> {
        SportPrefs.init(c); GameDay.init(c)
        return listOf(
            Triple("clean_look", "Clean look (Nuvio style)" to "Calm focus, titles under posters, no changing background", FofPrefs.cleanLook),
            Triple("spoiler_pics", "Blur unwatched episodes" to "Pictures stay blurred until you watch the episode", FofPrefs.spoilerPics),
            Triple("spoiler_text", "Hide unwatched descriptions" to "Episode text shows after you watch it", FofPrefs.spoilerText),
            Triple("auto_next", "Play next episode automatically" to "At the end of an episode", AppSettings.autoNext),
            Triple("auto_skip_intro", "Skip intros automatically" to "When intro times are known", AppSettings.autoSkipIntro),
            Triple("hide_scores", "Hide sports scores" to "Spoiler-free sports", SportPrefs.hideScores),
            Triple("close_alerts", "Close-game alerts" to "Pop-up when a game is close at the end", GameDay.closeAlerts),
            Triple("auto_teams", "Auto-tune my teams" to "Switch to your teams' games when they start", GameDay.autoTeams),
            Triple("motion", "Animations" to "Off makes moving around the fastest on slow devices", Look.motion > 0),
            Triple("auto_update", "Check for updates automatically" to "At start, every 15 minutes and when the app comes back", AppUpdate.auto(c)),
            Triple("early_updates", "Early updates" to "Get new versions right away. Off: only versions that ran 24 hours without problems", AppUpdate.early(c))
        )
    }

    private fun setFlag(c: Context, k: String, on: Boolean): Boolean {
        when (k) {
            "spoiler_pics", "spoiler_text", "clean_look" -> FofPrefs.setFlag(c, k, on)
            "auto_next" -> AppSettings.setPlayerExtra(c, on, AppSettings.hideSec, AppSettings.showEndTime, AppSettings.autoSkipIntro)
            "auto_skip_intro" -> AppSettings.setPlayerExtra(c, AppSettings.autoNext, AppSettings.hideSec, AppSettings.showEndTime, on)
            "hide_scores" -> SportPrefs.setHideScores(c, on)
            "close_alerts" -> GameDay.setCloseAlerts(c, on)
            "auto_teams" -> GameDay.setAutoTeams(c, on)
            "motion" -> AppSettings.setLook(c, Look.accentIdx, Look.custom, Look.bgIdx, if (on) 2 else 0, Look.uiScale)
            "auto_update" -> AppUpdate.setAuto(c, on)
            "early_updates" -> AppUpdate.setEarly(c, on)
            else -> return false
        }
        return true
    }

    fun state(c: Context): String {
        val fl = flags(c).joinToString(",") { (k, td, on) -> "{\"k\":${q(k)},\"t\":${q(td.first)},\"d\":${q(td.second)},\"on\":$on}" }
        val rows = HomeLayout.blocks.filter { !it.fixed }.joinToString(",") { b ->
            "{\"id\":${q(b.id)},\"t\":${q(Lang.tr(b.title))},\"d\":${q(Lang.tr(b.sub))},\"on\":${HomeLayout.shown(b.id)}}"
        }
        val ads = Stremio.addons.joinToString(",") { a -> "{\"b\":${q(a.base)},\"n\":${q(a.name)}}" }
        // App health for the phone (no photos of the TV needed): version, what is loaded, speed of this run, last crash
        val ch = PlaylistStore.channels
        val content = "${ch.count { it.kind == Kind.LIVE }} channels" + ch.count { it.kind != Kind.LIVE }.let { if (it > 0) " · $it movies/series" else "" }
        val crash = CrashLog.last(c)?.let { it.first + " (" + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(it.second)) + ")" } ?: "none"
        val speed = MainSampler.timings.joinToString(",") { q(it) }
        val health = "\"health\":{\"ver\":${q(BuildConfig.VERSION_NAME)},\"content\":${q(content)},\"epg\":${q(Xmltv.channelCount.toString() + " channels with guide")},\"speed\":[$speed],\"crash\":${q(crash)}},"
        return "{$health\"flags\":[$fl],\"rows\":[$rows],\"oneclick\":${FofPrefs.oneClick},\"tmdb\":${Config.tmdbOwn || Config.TMDB_KEY.isNotBlank()},\"pvod\":${FofPrefs.providerVod},\"hero\":${General.heroSec},\"addons\":[$ads]}"
    }

    /** Applies one change; runs the state change on the main thread. */
    fun set(c: Context, k: String, id: String, v: String): String {
        when (k) {
            "row" -> if (HomeLayout.block(id) != null) main.post { HomeLayout.setShown(c, id, v == "1") }
            "oneclick" -> main.post { FofPrefs.setOneClick(c, v == "1") }
            "pvod" -> main.post {
                val on = v == "1"
                if (on != FofPrefs.providerVod) {
                    FofPrefs.setProviderVod(c, on)
                    // Off: read the saved list again without movies/series. On: they were never downloaded, so load it anew
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        if (on) PlaylistStore.savedUrl(c)?.let { PlaylistStore.load(c, it, save = false) } else PlaylistStore.reapplyLanguages(c)
                    }
                }
            }
            "flag" -> main.post { setFlag(c, id, v == "1") }
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
input[type=text],input[type=password],select{width:100%;box-sizing:border-box;background:#0b0b0d;color:#fff;border:1px solid #33333a;border-radius:10px;padding:12px;font-size:16px}
button{background:#e50914;color:#fff;border:0;border-radius:10px;padding:12px 16px;font-size:16px;font-weight:600}button.g{background:#2a2a30}
#msg{position:fixed;left:50%;bottom:20px;transform:translateX(-50%);background:#fff;color:#000;padding:10px 16px;border-radius:20px;display:none}a{color:#e50914}
</style></head><body><main><h1>Fof TV</h1><div style="color:#8a8a92">Changes show on the TV right away. <a id="rl" href="/r">Phone remote</a></div>
<h2>Playback</h2><div class="c"><div class="r"><div>One-click play<small>Play starts the best stream at once</small></div><label class="t"><input type="checkbox" id="oc"><span></span></label></div>
<div class="r"><div>Provider movies &amp; series in search<small>Off: movies and shows come only from your Stremio addons</small></div><label class="t"><input type="checkbox" id="pv"><span></span></label></div>
<div class="r"><div>Big picture changes every<small>0 = stays on one title</small></div><select id="hero" style="width:110px"><option>0</option><option>6</option><option>9</option><option>12</option><option>20</option></select></div></div>
<h2>TMDB key</h2><div class="c"><div class="r"><div>Descriptions, ratings, cast and episode pictures<small id="tk">From themoviedb.org (free account &gt; Settings &gt; API)</small></div></div></div>
<div style="display:flex;gap:8px;margin-top:10px"><input type="text" id="tki" placeholder="Paste your TMDB API key (v3)"><button onclick="tmdb()">Save</button></div>
<h2>App updates</h2><div class="c"><div class="r"><div>Check for a new version<small id="updv">The TV also checks by itself every hour</small></div><button class="g" onclick="upd()">Check now</button></div></div>
<h2>Your avatar</h2><div class="c" id="avs"></div><input type="file" id="avf" accept="image/*" style="display:none">
<h2>Family account</h2><div class="c" id="acct"></div>
<h2>App health</h2><div class="c" id="health"></div>
<h2>Viewing</h2><div class="c" id="flags"></div>
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
var hh=s.health||{};document.getElementById('health').innerHTML='<div class="r"><div>Version<small>'+esc(hh.ver||'')+'</small></div></div><div class="r"><div>Loaded<small>'+esc(hh.content||'')+' · '+esc(hh.epg||'')+'</small></div></div><div class="r"><div>Speed of this run<small>'+((hh.speed||[]).map(esc).join('<br>')||'not measured yet')+'</small></div></div><div class="r"><div>Last crash<small>'+esc(hh.crash||'none')+'</small></div></div>';
document.getElementById('flags').innerHTML=s.flags.map(function(r){return '<div class="r"><div>'+esc(r.t)+'<small>'+esc(r.d)+'</small></div><label class="t"><input type="checkbox" data-k="'+esc(r.k)+'"'+(r.on?' checked':'')+'><span></span></label></div>'}).join('');
Array.prototype.forEach.call(document.querySelectorAll('#flags input'),function(i){i.onchange=function(){set('flag',i.getAttribute('data-k'),i.checked?1:0)}});
document.getElementById('rows').innerHTML=s.rows.map(function(r){return '<div class="r"><div>'+esc(r.t)+'<small>'+esc(r.d)+'</small></div><label class="t"><input type="checkbox" data-id="'+esc(r.id)+'"'+(r.on?' checked':'')+'><span></span></label></div>'}).join('');
Array.prototype.forEach.call(document.querySelectorAll('#rows input'),function(i){i.onchange=function(){set('row',i.getAttribute('data-id'),i.checked?1:0)}});
document.getElementById('ads').innerHTML=s.addons.length?s.addons.map(function(a){return '<div class="r"><div>'+esc(a.n)+'<small>'+esc(a.b)+'</small></div><button class="g" data-b="'+esc(a.b)+'">Remove</button></div>'}).join(''):'<div class="r"><div>No addons yet</div></div>';
Array.prototype.forEach.call(document.querySelectorAll('#ads button'),function(b){b.onclick=function(){set('addonrm',b.getAttribute('data-b'),'').then(function(){setTimeout(load,400)})}})
}).catch(function(){})}
function tmdb(){var k=document.getElementById('tki').value.trim();if(!k)return;api('cfgset',{k:'tmdb',id:k,v:''}).then(function(){toast('Saved');document.getElementById('tki').value='';setTimeout(load,300)}).catch(function(){})}
function upd(){toast('Checking...');api('cfgset',{k:'upd',id:'',v:''}).then(function(r){document.getElementById('updv').textContent=r.msg||'';toast(r.msg||'Done')}).catch(function(){})}
function add(){var u=document.getElementById('au').value.trim();if(!u)return;toast('Adding...');api('addon',{u:u}).then(function(r){if(r.err)toast(r.err);else{toast('Added');document.getElementById('au').value='';setTimeout(load,400)}}).catch(function(){toast('Not added')})}
var acc={mode:'up',kind:'join',av:''};
var avFor=0;
function avLoad(){api('avstate').then(function(s){document.getElementById('avs').innerHTML=s.list.map(function(p){
return '<div class="r"><div style="display:flex;align-items:center;gap:12px">'+(p.img?'<img src="'+esc(p.img)+'" style="width:48px;height:48px;border-radius:10px;object-fit:cover">':'<div style="width:48px;height:48px;border-radius:10px;background:#2D5BE3;display:flex;align-items:center;justify-content:center;font-weight:700">'+esc((p.name||'?').charAt(0))+'</div>')+
'<div>'+esc(p.name)+'<small>'+esc(p.what||'Pick a picture from your phone, or choose a show, movie or player on the TV (hold OK on the profile)')+'</small></div></div><button class="g" onclick="avPick('+p.id+')">Photo</button></div>'}).join('')}).catch(function(){})}
function avPick(id){avFor=id;var f=document.getElementById('avf');f.value='';f.click()}
document.addEventListener('change',function(e){if(e.target.id!='avf')return;var file=e.target.files[0];if(!file)return;toast('Uploading …');
var img=new Image();img.onload=function(){var s=Math.min(img.width,img.height),c=document.createElement('canvas');c.width=c.height=320;
c.getContext('2d').drawImage(img,(img.width-s)/2,(img.height-s)/2,s,s,0,0,320,320);
c.toBlob(function(b){fetch('/apip/avatar?code='+encodeURIComponent(code)+'&p='+avFor,{method:'POST',body:b}).then(function(r){if(r.status==403){ask();throw 0}return r.json()})
.then(function(j){toast(j.err||'Avatar saved');avLoad()}).catch(function(e){if(e)toast('Not saved')})},'image/jpeg',0.86)};
img.onerror=function(){toast('That file is not a picture')};img.src=URL.createObjectURL(file)});
function acctLoad(){api('acct').then(function(s){var b=document.getElementById('acct');
if(s.member){var h='<div class="r"><div>'+esc(s.member.name||'You')+(s.member.owner?' · owner':' · family')+'<small>'+esc(s.email)+(s.status?' · '+esc(s.status):'')+'</small></div><button class="g" onclick="acctOut()">Sign out</button></div>'+(s.sync?'<div class="r"><div>Your watching<small>'+esc(s.sync)+'</small></div></div>':'');
if(s.member.owner)h+='<div class="r"><div>Share this TV\'s setup<small>Playlist and Stremio addons go to everyone in the family</small></div><button class="g" onclick="acctShare()">Share</button></div>'+
'<div class="r"><div>Invite someone<small id="invc">They enter the code when they sign up on their TV</small></div><button class="g" onclick="acctInv()">New code</button></div>'+
'<div class="r"><div>Problems on family TVs<small>Crashes, frozen screens and forced closes</small></div><button class="g" onclick="acctProb()">Show</button></div><div id="probs"></div>';
b.innerHTML=h;return}
var av=s.avatars.map(function(a){return '<img src="'+esc(a.u)+'" data-k="'+esc(a.k)+'" title="'+esc(a.n)+'" loading="lazy" style="width:60px;height:60px;box-sizing:border-box;border-radius:12px;margin:3px;cursor:pointer;border:3px solid '+(acc.av==a.k?'#fff':'transparent')+'" onclick="acc.av=this.getAttribute(\'data-k\');acctLoad()">'}).join('');
b.innerHTML=(s.signedIn?'<div class="r"><div>Signed in<small>'+esc(s.email)+'</small></div><button class="g" onclick="acctOut()">Sign out</button></div>':
'<div class="r"><div>'+(acc.mode=='up'?'New account':'I already have an account')+'</div><button class="g" onclick="acc.mode=acc.mode==\'up\'?\'in\':\'up\';acctLoad()">'+(acc.mode=='up'?'I have one':'New account')+'</button></div>'+
'<div style="padding:8px 0"><input type="text" id="ae" placeholder="Email" autocomplete="email"><input type="password" id="ap" placeholder="Password (6+ characters)" style="margin-top:8px"></div>')+
'<div class="r"><div>'+(acc.kind=='join'?'Join my family':'I am the owner')+'<small>'+(acc.kind=='join'?'Use the code you got from the owner':'Your playlist and addons are shared with the family')+'</small></div><button class="g" onclick="acc.kind=acc.kind==\'join\'?\'owner\':\'join\';acctLoad()">'+(acc.kind=='join'?'I am the owner':'Join instead')+'</button></div>'+
'<div style="padding:8px 0"><input type="text" id="an" placeholder="Your name"><input type="text" id="ac" placeholder="'+(acc.kind=='join'?'Invite code':'Family name (e.g. Fofana family)')+'" style="margin-top:8px"></div>'+
'
'<div style="display:flex;justify-content:flex-end;padding-top:10px"><button onclick="acctGo()">Continue</button></div>';
}).catch(function(){})}
function v(id){var e=document.getElementById(id);return e?e.value:''}
function acctGo(){if(!acc.av){toast('Choose your avatar');return}toast('Working...');var c=v('ac');
api('acctgo',{mode:acc.mode,kind:acc.kind,e:v('ae'),p:v('ap'),name:v('an'),av:acc.av,code:acc.kind=='join'?c:'',fam:acc.kind=='owner'?c:''}).then(function(r){if(r.err)toast(r.err);else{toast('Done');setTimeout(acctLoad,600)}}).catch(function(){toast('Not done')})}
function acctOut(){api('acctout').then(function(){setTimeout(acctLoad,400)})}
function acctProb(){var d=document.getElementById('probs');d.innerHTML='<small>Loading...</small>';api('acctprob').then(function(r){if(r.err){d.innerHTML='<small>'+esc(r.err)+'</small>';return}
d.innerHTML=r.list.length?r.list.map(function(p){return '<div class="r"><div>'+esc(p.who||'Someone')+' · '+esc(p.at)+'<small>'+esc(p.kind)+' · '+esc(p.v)+' · '+esc(p.dev)+'</small></div></div>'}).join(''):'<small>No problems reported. Everything runs smoothly.</small>'}).catch(function(){d.innerHTML='<small>Not loaded</small>'})}
function acctShare(){toast('Sharing...');api('acctshare').then(function(r){toast(r.err||'Shared with the family')})}
function acctInv(){api('acctinv',{n:1}).then(function(r){if(r.err)toast(r.err);else document.getElementById('invc').innerHTML='Code: <b style="font-size:20px;color:#fff;letter-spacing:3px">'+esc(r.code)+'</b> · one person, 14 days'})}
avLoad();acctLoad();load()</script></body></html>"""
}
