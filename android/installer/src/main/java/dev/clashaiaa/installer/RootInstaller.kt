package dev.clashaiaa.installer

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Installs the Clashaiaa native probe into a rooted Android, on-device.
 *
 * This is the same operation as `tools/install_probe.py`, but driven from inside the
 * device (or inside a rooted VM guest) instead of through ADB. It is deliberately
 * conservative: every precondition is checked from the existing installation, and any
 * mismatch aborts before a single byte is written.
 */
class RootInstaller(private val context: Context) {

    private val log = StringBuilder()
    private var failed = false
    private var lastWriteProbe = ""

    private fun ok(name: String, detail: String) {
        log.append("OK    ").append(name).append("  ").append(detail).append('\n')
    }

    private fun warn(name: String, detail: String) {
        log.append("WARN  ").append(name).append("  ").append(detail).append('\n')
    }

    private fun fail(name: String, detail: String) {
        log.append("FAIL  ").append(name).append("  ").append(detail).append('\n')
        failed = true
    }

    private fun heading(text: String) {
        log.append('\n').append("== ").append(text).append(" ==\n")
    }

    // ---------------------------------------------------------------- root shell

    /** Runs a command through the ordinary shell, for diagnostics only. */
    private fun sh(command: String): Pair<Int, String> = exec("sh", command)

    /**
     * Runs a command with the highest privilege available: `su` when one works,
     * otherwise the ordinary shell. Some VM hosts hand out root-group membership
     * instead of a `su` binary, and that can still be enough to write the library
     * directory, so the operations below are attempted either way and the write
     * test decides whether the install is safe.
     */
    private fun root(command: String): Pair<Int, String> = exec(suProbe.first ?: "sh", command)

    /**
     * True only when this app can actually create, write and read back a file in
     * [dir]. An earlier version matched on the substring "WRITABLE", which also
     * matched "NOT_WRITABLE" and produced false positives; this one checks the
     * exact exit status.
     */
    private fun writable(dir: String): Boolean {
        val probe = "$dir/.clashaiaa_write_probe"
        val (_, out) = sh("(echo probe > '$probe' && cat '$probe' && rm -f '$probe') 2>&1 ; echo RESULT=\$?")
        lastWriteProbe = out.replace('\n', ' ').trim()
        return out.substringAfterLast("RESULT=").trim() == "0"
    }

    private fun exec(binary: String, command: String): Pair<Int, String> {
        return try {
            val process = ProcessBuilder(binary, "-c", "$command ; echo __EXIT__\$?")
                .redirectErrorStream(true)
                .start()
            val raw = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return -1 to "timed out (root prompt not answered?)"
            }
            val marker = raw.lastIndexOf("__EXIT__")
            if (marker < 0) return -1 to raw.trim()
            val code = raw.substring(marker + 8).trim().toIntOrNull() ?: -1
            code to raw.substring(0, marker).trim()
        } catch (exc: Exception) {
            -1 to (exc.message ?: exc.javaClass.simpleName)
        }
    }

    /**
     * Finds a working `su`, and keeps a transcript of everything it tried. The
     * transcript is what makes "root mode is on but nothing works" diagnosable.
     */
    private val suProbe: Pair<String?, String> by lazy {
        val report = StringBuilder()
        val (_, pathOut) = sh("echo \"PATH=\$PATH\"; command -v su 2>/dev/null || echo 'su: not on PATH'")
        report.append("shell: ").append(pathOut.replace('\n', ' ').trim()).append('\n')

        val (_, uidOut) = sh("id")
        report.append("unprivileged id: ").append(uidOut.replace('\n', ' ').trim()).append('\n')

        val (_, managerOut) = sh(
            "pm list packages 2>/dev/null | grep -iE 'superuser|magisk|kernelsu|apatch|root' | head -5" +
                " || echo 'no root manager package'"
        )
        report.append("root manager: ").append(managerOut.replace('\n', ' ').trim()).append('\n')

        val (_, lsOut) = sh("ls -l /system/xbin/su /system/xbin/daemonsu /system/bin/su 2>&1 | tr '\\n' ' '")
        report.append("su files: ").append(lsOut.trim()).append('\n')

        val (_, selOut) = sh("getenforce 2>&1; ls -Z /system/xbin/su 2>&1")
        report.append("selinux: ").append(selOut.replace('\n', ' ').trim()).append('\n')

        val candidates = listOf(
            "su", "/system/bin/su", "/system/xbin/su",
            // Some VM hosts deploy SuperSU as a daemon plus a thin `su` symlink and
            // forget the setuid bit on the symlink target; try the real binary too.
            "/system/xbin/daemonsu", "/system/xbin/su99", "/su/bin/su",
            "/sbin/su", "/system/su", "/system/bin/.ext/su", "/debug_ramdisk/su", "/vendor/bin/su",
        )
        var found: String? = null
        for (candidate in candidates) {
            val (code, out) = exec(candidate, "id")
            val usable = code == 0 && out.contains("uid=0")
            val detail = if (usable) "uid=0 OK" else out.trim().replace('\n', ' ').take(70).ifEmpty { "exit=$code" }
            report.append(if (usable) "  OK   " else "  --   ").append(candidate).append(" -> ").append(detail).append('\n')
            if (usable && found == null) found = candidate
        }
        found to report.toString()
    }

    // ---------------------------------------------------------------- helpers

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (start in 0..haystack.size - needle.size) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return true
        }
        return false
    }

    private fun remoteSha(path: String): String =
        root("sha256sum '$path'").second.lineSequence().firstOrNull()
            ?.trim()?.substringBefore(' ') ?: ""

    /** Locates the game's extracted native library directory, or null with a logged reason. */
    private fun resolveLibDir(): String? {
        // PackageManager is the authoritative non-root lookup.  Calling `pm path`
        // from an ordinary app process is blocked on recent Android/HyperOS builds,
        // which used to make an installed game look missing whenever KernelSU access
        // had not yet been granted to this installer.
        val packageDir = try {
            context.packageManager.getApplicationInfo(PACKAGE, 0).nativeLibraryDir
        } catch (_: Exception) {
            null
        }
        if (!packageDir.isNullOrBlank()) return packageDir

        // Keep the shell fallback for older rooted guests whose PackageManager does
        // not expose nativeLibraryDir correctly.
        val (code, out) = root("pm path $PACKAGE")
        val apk = out.lineSequence().firstOrNull { it.startsWith("package:") }
            ?.removePrefix("package:")?.trim()
        if (code != 0 || apk.isNullOrEmpty()) {
            fail("package", "$PACKAGE is not installed or is hidden from this app ($out)")
            return null
        }
        for (abi in listOf("arm64", "arm64-v8a")) {
            val dir = apk.substringBeforeLast('/') + "/lib/" + abi
            if (root("test -d '$dir'").first == 0) return dir
        }
        fail("libdir", "no extracted native library directory next to $apk")
        return null
    }

    private fun verifyOriginalSdk(data: ByteArray): String? {
        val elf = data.size > 64 && data[0] == 0x7f.toByte() && data[1] == 'E'.code.toByte() &&
            data[2] == 'L'.code.toByte() && data[3] == 'F'.code.toByte() &&
            data[4] == 2.toByte() && data[5] == 1.toByte() &&
            data[18] == 0xb7.toByte() && data[19] == 0x00.toByte()
        if (!elf) return "not a 64-bit little-endian ARM64 ELF"
        if (contains(data, "NullsProbe".toByteArray())) return "already the probe; restore the game first"
        if (contains(data, "libscid_sdk_real.so".toByteArray())) return "already a proxy; restore the game first"
        val missing = FORWARD_SYMBOLS.filter { !contains(data, it.toByteArray() + 0) }
        if (missing.isNotEmpty()) {
            return "unsupported build, missing ${missing.size} required symbols (${missing.first()})"
        }
        return null
    }

    /**
     * Copies owner, mode and SELinux label from a healthy sibling so the linker is
     * allowed to map the new file. Metadata problems are warnings, not failures.
     */
    private fun copyMetadata(reference: String, target: String) {
        val stat = root("stat -c '%u:%g %a' '$reference'").second.trim()
        if (stat.matches(Regex("""\d+:\d+ \d+"""))) {
            val (owner, mode) = stat.split(' ')
            root("chown $owner '$target'")
            root("chmod $mode '$target'")
            ok("metadata", "owner/mode $stat copied from the reference library")
        } else {
            // Older guests ship a toybox without `stat -c`. Fall back to what the
            // verified reference library actually looks like on this install.
            root("chown 1000:1000 '$target'")
            root("chmod 755 '$target'")
            warn("metadata", "no usable stat ('$stat'); applied the reference default 1000:1000 755")
        }

        val context = root("ls -Z '$reference'").second.split(Regex("\\s+"))
            .firstOrNull { it.startsWith("u:object_r:") }
        if (context == null) {
            root("restorecon '$target'")
            warn("selinux", "reference label unreadable; ran restorecon instead")
        } else {
            val (code, out) = root("chcon '$context' '$target'")
            if (code == 0) {
                ok("selinux", "label $context applied")
            } else {
                root("restorecon '$target'")
                warn("selinux", "chcon failed ($out); ran restorecon instead")
            }
        }
        val after = root("ls -Z '$target'").second
        if (after.isNotBlank()) ok("selinux check", after)
    }

    /** Reads the original SDK into the app's own storage. Returns (bytes, sha) or null. */
    private fun backupOriginal(libDir: String): Pair<ByteArray, String>? {
        val staging = File(context.cacheDir, "sdk").apply { mkdirs() }
        val pulled = File(staging, "original-sdk.so")
        val (code, out) = root("cp '$libDir/libscid_sdk.so' '${pulled.absolutePath}' && " +
            "chmod 644 '${pulled.absolutePath}'")
        if (code != 0 || !pulled.isFile || pulled.length() < 1024) {
            fail("backup", "cannot read the installed SDK: $out")
            return null
        }
        var data = pulled.readBytes()
        var problem = verifyOriginalSdk(data)
        if (problem != null) {
            // A probe is already installed. Its companion `libscid_sdk_real.so`
            // is the untouched original, so an upgrade can be installed over an
            // existing probe without a Restore first. If that companion is not
            // a clean original either, this still refuses before writing.
            val companion = File(staging, "companion-sdk.so")
            val (companionCode, companionOut) = root(
                "cp '$libDir/libscid_sdk_real.so' '${companion.absolutePath}' && " +
                    "chmod 644 '${companion.absolutePath}'"
            )
            if (companionCode != 0 || !companion.isFile || companion.length() < 1024) {
                fail("backup", "$problem; no readable libscid_sdk_real.so companion ($companionOut)")
                return null
            }
            val companionData = companion.readBytes()
            val companionProblem = verifyOriginalSdk(companionData)
            if (companionProblem != null) {
                fail("backup", "$problem; companion is not a clean original either: $companionProblem")
                return null
            }
            ok("backup", "installed SDK is a probe; using the verified companion original instead")
            data = companionData
            problem = null
        }
        val digest = sha256(data)
        // Durable copy outside the cache, plus a receipt.
        File(context.filesDir, "original-sdk.so").writeBytes(data)
        File(context.filesDir, "receipt.txt").writeText(
            "package=$PACKAGE\nlibDir=$libDir\noriginal_sha256=$digest\nbytes=${data.size}\n"
        )
        ok("backup", "original SDK saved to app storage, sha256=$digest")
        return data to digest
    }

    private fun stageProbe(): Pair<File, String>? {
        val staged = File(File(context.cacheDir, "sdk").apply { mkdirs() }, "probe.so")
        context.assets.open("libscid_sdk.so").use { input ->
            staged.outputStream().use { output -> input.copyTo(output) }
        }
        val digest = sha256(staged.readBytes())
        if (digest != PROBE_SHA) {
            fail("probe", "bundled probe checksum mismatch: $digest")
            return null
        }
        ok("probe", "bundled probe verified, sha256=$digest")
        return staged to digest
    }

    // ---------------------------------------------------------------- public API

    /** Read-only preflight: reports exactly how far this environment gets. */
    fun inspect(): String {
        log.clear(); failed = false
        heading("environment")
        log.append(suProbe.second)
        val su = suProbe.first
        if (su != null) ok("root", "su at '$su' returns uid=0")
        else warn("root", "no su binary anywhere; falling back to the write test below")
        ok("abi", sh("getprop ro.product.cpu.abi").second.trim())
        ok("release", sh("getprop ro.build.version.release").second.trim())
        ok("sdk", sh("getprop ro.build.version.sdk").second.trim())

        heading("target")
        val libDir = resolveLibDir() ?: return log.toString()
        ok("libdir", libDir)
        ok("libdir perms", root("ls -ld '$libDir'").second.ifBlank { "(unreadable)" })
        val canWrite = writable(libDir)
        if (canWrite) ok("write access", "YES - the library directory is writable as this app")
        else if (su != null) ok("write access", "app sandbox is read-only; verified su will perform writes")
        else warn("write access", "no - grant this installer Superuser access in KernelSU")
        if (lastWriteProbe.isNotBlank()) log.append("      probe output: ").append(lastWriteProbe).append('\n')

        val gameSha = remoteSha("$libDir/libg.so")
        if (gameSha == GAME_SHA) ok("libg.so", "matches the attested $GAME_VERSION build")
        else fail("libg.so", "fingerprint mismatch: $gameSha")

        val sdkPath = "$libDir/libscid_sdk.so"
        val installedSdkSha = remoteSha(sdkPath)
        val installedProbe = installedSdkSha == PROBE_SHA
        val sourcePath = if (installedProbe) "$libDir/libscid_sdk_real.so" else sdkPath
        val pull = File(File(context.cacheDir, "sdk").apply { mkdirs() }, "original-sdk.so")
        if (root("cp '$sourcePath' '${pull.absolutePath}'").first == 0 && pull.isFile) {
            val bytes = pull.readBytes()
            val problem = verifyOriginalSdk(bytes)
            if (problem == null && installedProbe) {
                ok("probe", "already installed, sha256=$installedSdkSha")
                ok("real sdk", "clean original SDK, sha256=${sha256(bytes)}")
            } else if (problem == null) ok("sdk", "clean original SDK, sha256=${sha256(bytes)}")
            else fail("sdk", problem)
        } else {
            fail("sdk", "cannot read $sourcePath")
        }
        ok("probe asset", "sha256=${sha256(context.assets.open("libscid_sdk.so").use { it.readBytes() })}")

        log.append('\n').append(
            when {
                failed -> "PREFLIGHT: not usable"
                su != null || canWrite -> "PREFLIGHT: safe to install"
                else -> "PREFLIGHT: not safe"
            }
        )
        return log.toString()
    }

    /** Performs the swap. Aborts on the first hard failure, before writing anything. */
    fun install(): String {
        log.clear(); failed = false

        heading("preflight")
        val su = suProbe.first
        if (su == null) log.append(suProbe.second)

        val abi = sh("getprop ro.product.cpu.abi").second.trim()
        if (abi != "arm64-v8a") {
            fail("arch", "unsupported abi '$abi'; this probe is ARM64 only")
            return log.toString()
        }
        ok("arch", abi)

        val libDir = resolveLibDir() ?: return log.toString()
        ok("libdir", libDir)
        ok("libdir perms", root("ls -ld '$libDir'").second)
        val canWrite = writable(libDir)
        if (canWrite) ok("write access", "the library directory is writable as this app")
        else warn("write access", "not writable; root is required")
        if (lastWriteProbe.isNotBlank()) log.append("      probe output: ").append(lastWriteProbe).append('\n')
        if (su == null && !canWrite) {
            fail("privilege", "no usable su and the library directory is not writable - this VM cannot host the probe")
            return log.toString()
        }

        val gameSha = remoteSha("$libDir/libg.so")
        if (gameSha != GAME_SHA) {
            fail("libg.so", "fingerprint mismatch: $gameSha (nothing was changed)")
            return log.toString()
        }
        ok("libg.so", "matches the attested $GAME_VERSION build")

        val backup = backupOriginal(libDir) ?: return log.toString()
        val staged = stageProbe() ?: return log.toString()

        heading("install")
        val realPath = "$libDir/libscid_sdk_real.so"
        if (root("test -f '$realPath'").first == 0) {
            val existing = remoteSha(realPath)
            if (existing == backup.second) {
                ok("libscid_sdk_real.so", "already the original SDK, left in place")
            } else {
                fail("libscid_sdk_real.so", "unexpected content ($existing); refusing to overwrite")
                return log.toString()
            }
        } else {
            val (copyCode, copyOut) = root("cat '${File(context.filesDir, "original-sdk.so").absolutePath}' > '$realPath'")
            if (copyCode != 0) {
                fail("libscid_sdk_real.so", "copy failed: $copyOut")
                return log.toString()
            }
            copyMetadata("$libDir/libg.so", realPath)
            ok("libscid_sdk_real.so", "original SDK placed alongside the proxy")
        }

        // Overwriting in place keeps the inode, so owner, group, mode and SELinux label
        // of the existing library are preserved without touching the APK signature.
        val (writeCode, writeOut) = root("cat '${staged.first.absolutePath}' > '$libDir/libscid_sdk.so'")
        if (writeCode != 0) {
            fail("libscid_sdk.so", "write failed: $writeOut")
            return log.toString()
        }
        ok("libscid_sdk.so", "probe written in place (inode and metadata preserved)")

        heading("verify")
        val proxySha = remoteSha("$libDir/libscid_sdk.so")
        val realSha = remoteSha(realPath)
        if (proxySha == PROBE_SHA) ok("proxy", proxySha) else fail("proxy", "sha256 $proxySha != $PROBE_SHA")
        if (realSha == backup.second) ok("real sdk", realSha) else fail("real sdk", "sha256 $realSha != ${backup.second}")
        ok("libg.so", remoteSha("$libDir/libg.so") + " (untouched)")

        log.append('\n').append(
            if (failed) "INSTALL FAILED - use Restore to return to a clean state"
            else "INSTALL OK - the APK signature is untouched, so the client's own checks are satisfied.\n" +
                "Launch Null's Royale, then start the overlay app."
        )
        return log.toString()
    }

    /** Puts the original SDK back and removes the companion library. */
    fun restore(): String {
        log.clear(); failed = false
        heading("restore")
        if (suProbe.first == null) warn("root", "no su binary; attempting the restore anyway")
        val saved = File(context.filesDir, "original-sdk.so")
        if (!saved.isFile || saved.length() < 1024) {
            fail("backup", "no saved original SDK in app storage")
            return log.toString()
        }
        val libDir = resolveLibDir() ?: return log.toString()
        val (code, out) = root("cat '${saved.absolutePath}' > '$libDir/libscid_sdk.so'")
        if (code != 0) {
            fail("restore", "write failed: $out")
            return log.toString()
        }
        root("rm -f '$libDir/libscid_sdk_real.so'")
        val now = remoteSha("$libDir/libscid_sdk.so")
        val expected = sha256(saved.readBytes())
        if (now == expected) ok("restored", "libscid_sdk.so = original, sha256=$now")
        else fail("restored", "sha256 $now != $expected")
        log.append('\n').append(if (failed) "RESTORE FAILED" else "RESTORE OK")
        return log.toString()
    }

    fun receipt(): String {
        val file = File(context.filesDir, "receipt.txt")
        return if (file.isFile) file.readText() else "no receipt yet"
    }

    private companion object {
        const val PACKAGE = "nullsroyale.rel.free"
        const val GAME_VERSION = "15.535.13"
        const val GAME_SHA = "110aa2b5cac391c498645e072b0d88729428c2c2845e7e2737ca8ee979059783"
        // probe/artifacts/candidates/stable-candidate/libscid_sdk.so, rebuilt
        // with the loopback-only endpoint, card names, the nulls-ghost.v1 feed
        // and the off-by-default battle-log trace hooks.
        const val PROBE_SHA = "f64eefe96d3b648f581c99ed5760357359b2e86315accd8316d1c9ceb3a9eb88"

        val FORWARD_SYMBOLS = listOf(
            "Java_xyz_daniillnull_connect_sdk_NTConnect_avatarImageData",
            "Java_xyz_daniillnull_connect_sdk_NTConnect_bindAccount",
            "Java_xyz_daniillnull_connect_sdk_NTConnect_forgetAccount",
            "Java_xyz_daniillnull_connect_sdk_NTConnect_generateQrCodeResult",
            "Java_xyz_daniillnull_connect_sdk_NTConnect_getConfig",
            "Java_xyz_daniillnull_connect_sdk_NTConnect_getSessionTokenResult",
            "Java_xyz_daniillnull_connect_sdk_NTConnect_loadAccount",
            "Java_xyz_daniillnull_connect_sdk_NTConnect_logOut",
            "Java_xyz_daniillnull_connect_sdk_NTConnect_publicProfileData",
            "Java_xyz_daniillnull_connect_sdk_NTConnect_setProfile",
            "Java_xyz_daniillnull_connect_sdk_NTConnect_setProfileFailed",
            "Java_xyz_daniillnull_connect_sdk_NTConnect_windowDidDismiss",
            "_ZN11SupercellIdC1ERK17SupercellIdParams",
            "_ZN11SupercellIdC2ERK17SupercellIdParams",
            "_ZN11SupercellIdD0Ev",
            "_ZN11SupercellIdD1Ev",
            "_ZN11SupercellIdD2Ev",
            "_ZN15SupercellSocialC1EP23SupercellSocialDelegate",
            "_ZN15SupercellSocialC2EP23SupercellSocialDelegate",
            "_ZN15SupercellSocialD0Ev",
            "_ZN15SupercellSocialD1Ev",
            "_ZN15SupercellSocialD2Ev",
        )
    }
}
