package com.ipvio.stream

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.launch

/** Phone settings page, "Family account" section: JSON for the page and the actions behind its buttons. */
object AccountWeb {
    private fun q(s: String) = RemoteLogic.q(s)
    private val main = Handler(Looper.getMainLooper())

    fun state(c: Context): String {
        FofAccount.init(c)
        val m = FofAccount.member
        val avatars = ""
        val mem = if (m == null) "null" else "{\"name\":${q(m.name)},\"avatar\":${q(m.avatar)},\"owner\":${m.owner}}"
        return "{\"signedIn\":${FofAccount.signedIn},\"email\":${q(FofAccount.email.orEmpty())},\"member\":$mem,\"status\":${q(FofAccount.status)},\"sync\":${q(syncLine(c))},\"avatars\":[$avatars]}"
    }

    /** Which profile on this TV follows the person, and when it last synced. */
    private fun syncLine(c: Context): String {
        if (!FofAccount.signedIn) return ""
        Profiles.init(c)
        val p = Profiles.list.firstOrNull { it.id == CloudSync.linked(c) } ?: return ""
        return "Continue watching, My List and favorites follow you (profile " + Profiles.displayName(p) + ")" +
            (if (CloudSync.status.isNotBlank()) " · " + CloudSync.status else "")
    }

    /** Sign up or in, then start a family (owner) or join one (code). Name and avatar become this TV's main profile. */
    suspend fun go(c: Context, p: Map<String, String>): String {
        val mail = p["e"].orEmpty().trim().take(120)
        val pw = p["p"].orEmpty().take(120)
        val name = p["name"].orEmpty().trim().take(40)
        val av = ""
        if (!FofAccount.signedIn) {
            if (mail.isBlank() || pw.length < 6) return err("Enter your email and a password of at least 6 characters.")
            FofAccount.signUpOrIn(c, mail, pw, create = p["mode"] == "up")?.let { return err(it) }
        }
        if (FofAccount.member == null) {
            if (name.isBlank()) return err("Enter your name.")
            val e = if (p["kind"] == "owner") FofAccount.createFamily(c, p["fam"].orEmpty().take(40), name, av)
                else FofAccount.join(c, p["code"].orEmpty().take(12), name, av)
            if (e != null) return err(e)
        }
        // This TV's main profile gets the person's name and picture
        val m = FofAccount.member
        main.post {
            m?.name?.takeIf { it.isNotBlank() }?.let { Profiles.rename(c, 0, it) }
            m?.avatar?.takeIf { it.isNotBlank() }?.let { ProfileAvatars.set(c, 0, it) }
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch { runCatching { FofAccount.sync(c) } }
        return "{\"ok\":true}"
    }

    suspend fun invite(c: Context, uses: Int): String {
        val (code, e) = FofAccount.invite(c, uses.coerceIn(1, 20))
        return if (code != null) "{\"code\":${q(code)}}" else err(e ?: "Could not create a code.")
    }

    /** Owner: problem reports of the family TVs, newest first. */
    suspend fun problems(c: Context): String {
        if (FofAccount.member?.owner != true) return err("Only the owner sees problem reports.")
        val l = FofAccount.problems(c)
        return "{\"list\":[" + l.joinToString(",") { "{\"who\":${q(it.who)},\"kind\":${q(it.kind)},\"v\":${q(it.version)},\"dev\":${q(it.device)},\"at\":${q(it.at.take(16).replace('T', ' '))}}" } + "]}"
    }

    suspend fun share(c: Context): String = FofAccount.share(c)?.let { err(it) } ?: "{\"ok\":true}"

    private fun err(s: String) = "{\"err\":${q(s)}}"

    /** Profiles of this TV with their picture (phone page "Your avatar"). */
    fun avatarState(c: Context): String {
        Profiles.init(c)
        return "{\"list\":[" + Profiles.list.joinToString(",") { p ->
            val pr = ProfileAvatars.presetFor(c, p.id)
            val img = when (val m = pr.model) { is java.io.File -> "/img/p" + p.id + ".jpg?v=" + m.lastModified(); is String -> m; else -> "" }
            "{\"id\":${p.id},\"name\":${q(Profiles.displayName(p))},\"img\":${q(img)},\"what\":${q(pr.name)}}"
        } + "]}"
    }

    /** Photo from the phone for profile [id]. */
    fun avatarUpload(c: Context, q: Map<String, String>, body: ByteArray): String {
        Profiles.init(c)
        val id = q["p"]?.toIntOrNull()?.takeIf { i -> Profiles.list.any { it.id == i } } ?: return err("Unknown profile")
        return if (ProfileAvatars.savePhoto(c, id, body)) "{\"ok\":true}" else err("That file is not a picture")
    }
}
