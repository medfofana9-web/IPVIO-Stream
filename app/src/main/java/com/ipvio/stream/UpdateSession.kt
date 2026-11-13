package com.ipvio.stream

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * App update through a package installer session. Besides the APK it writes "base.dm" (dex metadata): the compile
 * profile packed in the APK (assets/dexopt). With it the system compiles the app's code during the install, the same way
 * Play Store installs work; without it a sideloaded update runs uncompiled (slow start, jerky scrolling) until the
 * device compiles it later while idle. Any failure falls back to the system installer screen ([AppUpdate.viewInstall]).
 */
object UpdateSession {
    @Volatile private var pending: File? = null

    fun start(c: Context, apk: File): Boolean = runCatching {
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            setSize(apk.length())
            // Android 12+: an app updating itself needs no extra confirmation
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        try {
            pi.openSession(id).use { s ->
                s.openWrite("base.apk", 0, apk.length()).use { o -> apk.inputStream().use { it.copyTo(o, 256 * 1024) }; s.fsync(o) }
                dexMetadata(apk)?.let { dm -> s.openWrite("base.dm", 0, dm.size.toLong()).use { o -> o.write(dm); s.fsync(o) } }
                pending = apk
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi2 = PendingIntent.getBroadcast(c, id, Intent(c, UpdateResultReceiver::class.java).setPackage(c.packageName), flags)
                s.commit(pi2.intentSender)
            }
        } catch (e: Exception) {
            runCatching { pi.abandonSession(id) }
            throw e
        }
        true
    }.getOrElse { Diag.report("Update session: " + it.javaClass.simpleName); false }

    /** "base.dm": a zip with the profile of the APK as primary.prof (and primary.profm for Android 12+). Null when the APK has none. */
    internal fun dexMetadata(apk: File): ByteArray? = runCatching {
        ZipFile(apk).use { z ->
            val prof = z.getEntry("assets/dexopt/baseline.prof") ?: return null
            val profm = z.getEntry("assets/dexopt/baseline.profm")
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { out ->
                fun put(name: String, e: ZipEntry) {
                    val b = z.getInputStream(e).use { it.readBytes() }
                    val ze = ZipEntry(name).apply {
                        method = ZipEntry.STORED; size = b.size.toLong(); compressedSize = b.size.toLong()
                        crc = CRC32().also { it.update(b) }.value
                    }
                    out.putNextEntry(ze); out.write(b); out.closeEntry()
                }
                put("primary.prof", prof)
                if (profm != null) put("primary.profm", profm)
            }
            bos.toByteArray()
        }
    }.getOrNull()

    /** The session failed: open the system installer with the same file instead. */
    internal fun fallback(c: Context) {
        val f = pending ?: return
        pending = null
        if (f.exists()) AppUpdate.viewInstall(c, f)
    }
}

/** Result of the installer session: asks for confirmation when the system wants it; on failure the old way. */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null || runCatching { c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) UpdateSession.fallback(c)
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            // Cancelled by the user: nothing more to do
            PackageInstaller.STATUS_FAILURE_ABORTED -> {}
            else -> {
                Diag.report("Update session status $st: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty().take(120))
                UpdateSession.fallback(c)
            }
        }
    }
}
