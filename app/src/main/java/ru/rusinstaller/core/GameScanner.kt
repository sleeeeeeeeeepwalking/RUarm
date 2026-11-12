package ru.rusinstaller.core

import java.io.File

data class Game(val name: String, val path: String, val source: String) {
    val privileged get() = isPrivilegedPath(path)
}

class ScanResult(val games: List<Game>, val notes: List<String>)

object GameScanner {
    /** Packages of Steam-on-Android launchers whose data folder may contain games. */
    private val LAUNCHER_HINTS = listOf("gamenative", "egggame", "gamehub", "xiaoji", "winlator", "pluvia")
    private val SKIP_TOP = setOf("android", "dcim", "pictures", "music", "movies", "alarms", "ringtones",
        "notifications", "podcasts", "audiobooks", "recordings")
    private val NOT_GAMES = setOf("steamworks shared", "steam controller configs", "steamvr")

    fun volumes(): List<String> {
        val out = mutableListOf("/storage/emulated/0")
        File("/storage").listFiles()?.forEach {
            if (it.isDirectory && it.name != "emulated" && it.name != "self" && it.canRead()) out += it.absolutePath
        }
        return out
    }

    fun volumeLabel(path: String) = if (path.startsWith("/storage/emulated/")) "память" else "SD-карта"

    /** [common] is ".../steamapps/common"; GameHub's downloader leaves "steamapps/.steamkit-locks" next to it. */
    private suspend fun sourceOf(common: String): String {
        val steamapps = common.substringBeforeLast('/')
        val gameHub = Backends.forPathOrNull(steamapps)?.list(steamapps)?.any { it.name == ".steamkit-locks" } == true
        return if (gameHub) "GameHub · ${volumeLabel(common)}" else sourceByName(common)
    }

    private fun sourceByName(path: String): String {
        val p = path.lowercase()
        val app = when {
            "gamenative" in p || "pluvia" in p -> "GameNative"
            "egggame" in p || "gamehub" in p || "xiaoji" in p -> "GameHub"
            "winlator" in p -> "Winlator"
            else -> "Steam"
        }
        return "$app · ${volumeLabel(path)}"
    }

    suspend fun scan(customLibraries: List<String>): ScanResult {
        val commons = linkedSetOf<String>()
        val notes = ArrayList<String>()

        for (v in volumes()) {
            findSteamCommon(DirectBackend, v, 0, 5, commons, skipTop = true)
        }

        // Android/data of launchers (GameHub keeps games there) — reachable only through Shizuku.
        val launcherDirs = ArrayList<String>()
        val shizuku = ShizukuManager.service()
        for (v in volumes()) {
            val dataDir = "$v/Android/data"
            val pkgs = if (shizuku != null) ShizukuBackend(shizuku).list(dataDir)?.map { it.name }
            else null
            pkgs?.filter { n -> LAUNCHER_HINTS.any { n.lowercase().contains(it) } }?.forEach { launcherDirs += "$dataDir/$it" }
        }
        if (shizuku != null) {
            val b = ShizukuBackend(shizuku)
            for (d in launcherDirs) findSteamCommon(b, d, 0, 8, commons, skipTop = false)
        }
        // without Shizuku Android/data is skipped; GameHub (site build) can install into shared storage instead

        val games = ArrayList<Game>()
        for (c in commons) {
            val b = Backends.forPathOrNull(c) ?: continue
            val src = sourceOf(c)
            b.list(c)?.filter { it.isDir && it.name.lowercase() !in NOT_GAMES }?.forEach {
                games += Game(it.name, "$c/${it.name}", src)
            }
        }
        for (lib in customLibraries) {
            val b = Backends.forPathOrNull(lib)
            if (b == null) { notes += "Папка $lib недоступна без Shizuku."; continue }
            b.list(lib)?.filter { it.isDir }?.forEach { games += Game(it.name, "$lib/${it.name}", "Моя папка · ${volumeLabel(lib)}") }
        }
        val unique = games.distinctBy { it.path }.sortedBy { it.name.lowercase() }
        if (unique.isEmpty()) {
            notes += "Игры не найдены. В GameNative включи «внешнее хранилище», в GameHub выбери «Папку установки» " +
                "на SD-карте или в памяти — или выбери папку игры вручную."
        }
        return ScanResult(unique, notes)
    }

    private fun findSteamCommon(b: FileBackend, dir: String, depth: Int, maxDepth: Int,
                                out: MutableSet<String>, skipTop: Boolean) {
        val kids = b.list(dir) ?: return
        for (k in kids) {
            if (!k.isDir || k.name.startsWith(".")) continue
            val lower = k.name.lowercase()
            if (skipTop && depth == 0 && lower in SKIP_TOP) continue
            val path = "$dir/${k.name}"
            if (lower == "steamapps") {
                b.list(path)?.firstOrNull { it.isDir && it.name.equals("common", true) }?.let { out += "$path/${it.name}" }
                continue
            }
            if (depth + 1 < maxDepth) findSteamCommon(b, path, depth + 1, maxDepth, out, skipTop)
        }
    }
}

suspend fun Backends.forPathOrNull(path: String): FileBackend? =
    try { forPath(path) } catch (_: NeedShizukuException) { null }
