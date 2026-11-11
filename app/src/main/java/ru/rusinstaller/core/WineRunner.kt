package ru.rusinstaller.core

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Built-in Wine (Termux "hangover-wine" 11.16 + wowbox64 for 32-bit programs).
 *
 * The archive in assets was prepared on the build machine:
 *  - debug info stripped, dev tools removed;
 *  - Termux paths rewritten to /data/data/ru.ruarm/files (so the app id must stay "ru.ruarm");
 *  - FreeType (+ libpng, brotli, bzip2, zlib) and fontconfig (+ expat, minimal usr/etc/fonts/fonts.conf)
 *    added to usr/lib: without FreeType Wine has no fonts at all,
 *    text measures 0 px and newer Inno Setup builds fail with "BaseUnitX = 0"; with FreeType but
 *    without fontconfig every Wine process crashes on a NULL call (explorer.exe respawn loop);
 *  - i386 kernel32 export "IsWow64Process2" renamed, so old Inno Setup builds see an x64 CPU
 *    instead of ARM64 and do not refuse to install.
 * Executing it from the app's data dir requires targetSdk 28 (same as Winlator/GameNative).
 */
object WineRunner {
    private const val VERSION = "hangover-11.16-ruarm4"
    private const val ASSET = "wine.tar.xz"

    private fun files(ctx: Context) = ctx.filesDir
    private fun usr(ctx: Context) = File(files(ctx), "usr")
    private fun bin(ctx: Context) = File(usr(ctx), "opt/hangover-wine/bin")
    private fun marker(ctx: Context) = File(usr(ctx), ".ruarm_version")
    fun prefix(ctx: Context) = File(files(ctx), "home/.wine")
    fun driveC(ctx: Context) = File(prefix(ctx), "drive_c")

    fun isInstalled(ctx: Context) = runCatching { marker(ctx).readText() == VERSION }.getOrDefault(false)

    private fun checkLocation(ctx: Context) {
        val p = files(ctx).absolutePath
        if (p != "/data/user/0/ru.ruarm/files" && p != "/data/data/ru.ruarm/files")
            throw IOException("Встроенный Wine работает только у основного пользователя устройства (папка $p).")
    }

    /** Unpacks Wine from the APK on first use (~950 MB, about a minute). */
    fun ensureInstalled(ctx: Context, log: (String) -> Unit) {
        checkLocation(ctx)
        if (isInstalled(ctx)) return
        log("Распаковываю встроенный Wine (один раз, около минуты)…")
        usr(ctx).deleteRecursively()
        val xz = File(ctx.cacheDir, ASSET)
        ctx.assets.open(ASSET).use { i -> xz.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
        val tool = "${ctx.applicationInfo.nativeLibraryDir}/${NativeTools.SEVEN_ZIP}"
        val cmd = "'$tool' x -so -y '${xz.absolutePath}' | '$tool' x -si -ttar -y -bd -bb0 '-o${files(ctx).absolutePath}'"
        val pb = ProcessBuilder("/system/bin/sh", "-c", cmd).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = ctx.applicationInfo.nativeLibraryDir
        val p = pb.start()
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        xz.delete()
        if (code != 0 || !File(bin(ctx), "wine").exists()) throw IOException("Не удалось распаковать Wine:\n${out.takeLast(400)}")
        // executable bits are not kept by the archive
        bin(ctx).listFiles()?.forEach { it.setExecutable(true, false) }
        File(usr(ctx), "opt/hangover-wine/lib/wine/aarch64-unix").listFiles()?.forEach { it.setExecutable(true, false) }
        File(usr(ctx), "lib").listFiles()?.forEach { it.setExecutable(true, false) }
        File(usr(ctx), "tmp").mkdirs()
        File(files(ctx), "home").mkdirs()
        marker(ctx).writeText(VERSION)
        log("Wine готов")
    }

    /** Creates the Wine prefix once and switches it to the headless ("null") graphics driver. */
    fun ensurePrefix(ctx: Context, log: (String) -> Unit) {
        if (File(prefix(ctx), "system.reg").exists() && File(prefix(ctx), ".ruarm_headless").exists()) return
        log("Настраиваю Wine (один раз)…")
        run(ctx, listOf("wineboot", "-i"), log, 10)
        // without a display Wine refuses to create windows; installers create them even in silent mode
        run(ctx, listOf("reg", "add", "HKCU\\Software\\Wine\\Drivers", "/v", "Graphics", "/d", "null", "/f"), log, 5)
        killServer(ctx)
        File(prefix(ctx), ".ruarm_headless").writeText("1")
    }

    private fun env(ctx: Context): Map<String, String> {
        val f = files(ctx).absolutePath
        return mapOf(
            "PATH" to "${bin(ctx).absolutePath}:/system/bin",
            "LD_LIBRARY_PATH" to "${usr(ctx).absolutePath}/lib",
            "HOME" to "$f/home",
            "TMPDIR" to "${usr(ctx).absolutePath}/tmp",
            "USER" to "ruarm",
            "WINEPREFIX" to prefix(ctx).absolutePath,
            "WINEDEBUG" to "-all",
            "HODLL" to "wowbox64.dll",
            "WINEDLLOVERRIDES" to "mscoree,mshtml=;winemenubuilder.exe=d",
            "DISPLAY" to "",
            "LANG" to "ru_RU.UTF-8",
            "FONTCONFIG_FILE" to "${usr(ctx).absolutePath}/etc/fonts/fonts.conf",
        )
    }

    /** Runs `wine <args>`; returns the exit code. Output lines go to [log] (noise filtered). */
    fun run(ctx: Context, args: List<String>, log: (String) -> Unit, timeoutMinutes: Long): Int {
        val pb = ProcessBuilder(listOf(File(bin(ctx), "wine").absolutePath) + args).redirectErrorStream(true)
        pb.environment().apply { remove("WAYLAND_DISPLAY"); putAll(env(ctx)) }
        pb.directory(files(ctx))
        val p = pb.start()
        p.outputStream.close()
        val reader = Thread {
            runCatching {
                p.inputStream.bufferedReader().forEachLine { l ->
                    if (!l.contains("FreeType", true) && !l.contains("font library") && !l.contains("2.0.5") &&
                        !l.startsWith("[BOX64]") && l.isNotBlank()) log(l.take(200))
                }
            }
        }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            p.destroyForcibly()
            killServer(ctx)
            throw IOException("Установщик не завершился за $timeoutMinutes мин.")
        }
        reader.join(2000)
        return p.exitValue()
    }

    /**
     * Starts ruarm_clicker.exe next to an installer: it presses OK/Yes in message boxes that installer
     * scripts show even in silent mode (nobody can see them without a display) and reports their text.
     * @param onMessage receives the text of every dialog it closed
     */
    fun startClicker(ctx: Context, onMessage: (String) -> Unit): Process? = runCatching {
        val exe = File(driveC(ctx), "ruarm/clicker.exe")
        exe.parentFile?.mkdirs()
        ctx.assets.open("ruarm_clicker.exe").use { i -> exe.outputStream().use { o -> i.copyTo(o) } }
        val pb = ProcessBuilder(File(bin(ctx), "wine").absolutePath, "C:\\ruarm\\clicker.exe").redirectErrorStream(true)
        pb.environment().apply { remove("WAYLAND_DISPLAY"); putAll(env(ctx)) }
        pb.directory(files(ctx))
        val p = pb.start()
        p.outputStream.close()
        Thread {
            runCatching {
                p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { l ->
                    if (l.startsWith("MSG|")) onMessage(l.removePrefix("MSG|").trim())
                }
            }
        }.apply { isDaemon = true; start() }
        p
    }.getOrNull()

    fun killServer(ctx: Context) {
        runCatching {
            val pb = ProcessBuilder(File(bin(ctx), "wineserver").absolutePath, "-k").redirectErrorStream(true)
            pb.environment().putAll(env(ctx))
            val p = pb.start()
            p.outputStream.close()
            p.inputStream.readBytes()
            p.waitFor(20, TimeUnit.SECONDS)
        }
    }

    /** Linux path -> path inside Wine (drive Z: is the root of the file system). */
    fun winPath(path: String) = "Z:" + path.replace('/', '\\')
}
