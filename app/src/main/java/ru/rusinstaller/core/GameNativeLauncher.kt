package ru.rusinstaller.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Runs a Windows program inside GameNative's Wine without touching its settings:
 * GameNative accepts `app.gamenative.LAUNCH_GAME` with a one-launch container override,
 * so we start the game with its executable temporarily replaced by the installer/patcher.
 * The game folder is drive A: inside that container.
 */
object GameNativeLauncher {
    private val PACKAGES = listOf("app.gamenative", "app.gamenative.gold")
    private const val ACTION = "app.gamenative.LAUNCH_GAME"

    /** Silent Inno Setup install straight into the game folder (drive A:). */
    const val INNO_SILENT_ARGS = "/SILENT /SUPPRESSMSGBOXES /NORESTART /SP- /DIR=A:\\"

    fun installedPackage(ctx: Context): String? = PACKAGES.firstOrNull {
        try { ctx.packageManager.getPackageInfo(it, 0); true } catch (_: PackageManager.NameNotFoundException) { false }
    }

    fun isGameNativeSteamGame(game: Game) = game.path.contains("/GameNative/Steam/steamapps/common/", ignoreCase = true) ||
        game.path.contains("/app.gamenative", ignoreCase = true) && game.path.contains("/steamapps/common/", ignoreCase = true)

    /** Steam appid of a GameNative-installed game, or null. */
    suspend fun findAppId(game: Game): Int? {
        val b = Backends.forPathOrNull(game.path) ?: return null
        // 1) steam_appid.txt written by GameNative next to steam_api(64).dll after the first launch
        b.listTree(game.path, maxDepth = 3, maxEntries = 20_000)
            .filter { !it.isDir && it.name.substringAfterLast('/').equals("steam_appid.txt", true) }
            .forEach { n ->
                val tmp = kotlin.io.path.createTempFile().toFile()
                try {
                    b.readTo("${game.path}/${n.name}", tmp)
                    tmp.readText().trim().toIntOrNull()?.let { if (it > 0) return it }
                } catch (_: Exception) {
                } finally { tmp.delete() }
            }
        // 2) depots GameNative downloaded: ".DepotDownloader/completed/<depot>_<gid>.manifest"
        val depots = (b.list("${game.path}/.DepotDownloader/completed").orEmpty() + b.list("${game.path}/.DepotDownloader").orEmpty())
            .mapNotNull { Regex("""^(\d+)_\d+""").find(it.name)?.groupValues?.get(1)?.toIntOrNull() }
            .toSet()
        // 3) Steam search by folder name, cross-checked with depot ids (depot = appid + small offset)
        val candidates = runCatching { searchSteam(game.name) }.getOrDefault(emptyList())
        candidates.firstOrNull { (id, _) -> depots.any { it - id in 1..50 } }?.let { return it.first }
        val norm = { s: String -> s.lowercase().filter { it.isLetterOrDigit() } }
        candidates.firstOrNull { norm(it.second) == norm(game.name) }?.let { return it.first }
        if (depots.isNotEmpty()) return depots.min() - 1
        return candidates.firstOrNull()?.first
    }

    private fun searchSteam(name: String): List<Pair<Int, String>> {
        val url = URL("https://steamcommunity.com/actions/SearchApps/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20"))
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "RUarm")
        return try {
            val arr = JSONArray(c.inputStream.bufferedReader().readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                o.optString("appid").toIntOrNull()?.let { it to o.optString("name") }
            }.filterNotNull()
        } finally {
            c.disconnect()
        }
    }

    /**
     * @param exe path of the program relative to the game folder
     * @return null on success, otherwise an error text
     */
    fun launch(ctx: Context, appId: Int, exe: String, args: String): String? {
        val pkg = installedPackage(ctx) ?: return "GameNative не установлен"
        val winExe = exe.replace('/', '\\')
        val config = JSONObject().put("executablePath", winExe).put("execArgs", args.ifEmpty { " " })
        val i = Intent(ACTION).apply {
            setPackage(pkg)
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("app_id", appId)
            putExtra("game_source", "STEAM")
            putExtra("container_config", config.toString())
            if (args.isNotEmpty()) putExtra("exec_args", args)
        }
        return try {
            ctx.startActivity(i)
            null
        } catch (e: Exception) {
            "Не удалось открыть GameNative: ${e.message}"
        }
    }
}
