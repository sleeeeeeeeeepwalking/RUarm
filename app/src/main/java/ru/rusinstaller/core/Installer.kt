package ru.rusinstaller.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One installed russifier; everything needed to roll it back. */
class InstallRecord(
    val id: String,
    val gameName: String,
    val gamePath: String,
    val fileName: String,
    val date: Long,
    val added: List<String>,
    val replaced: List<String>,
    val createdDirs: List<String>,
    val restored: Boolean,
) {
    val dir get() = File(Installer.HOME, "backups/$id")

    fun toJson() = JSONObject().apply {
        put("id", id); put("gameName", gameName); put("gamePath", gamePath); put("fileName", fileName)
        put("date", date); put("restored", restored)
        put("added", JSONArray(added)); put("replaced", JSONArray(replaced)); put("createdDirs", JSONArray(createdDirs))
    }

    fun copy(restored: Boolean) = InstallRecord(id, gameName, gamePath, fileName, date, added, replaced, createdDirs, restored)

    companion object {
        private fun JSONArray?.strings() = if (this == null) emptyList() else List(length()) { getString(it) }
        fun fromJson(o: JSONObject) = InstallRecord(
            o.getString("id"), o.getString("gameName"), o.getString("gamePath"), o.optString("fileName"),
            o.getLong("date"), o.optJSONArray("added").strings(), o.optJSONArray("replaced").strings(),
            o.optJSONArray("createdDirs").strings(), o.optBoolean("restored"),
        )
    }
}

object Installer {
    /** Public folder: backups survive app reinstall and can be inspected by the user. */
    val HOME = File("/storage/emulated/0/RusInstaller")

    fun history(): List<InstallRecord> =
        File(HOME, "backups").listFiles().orEmpty().mapNotNull { d ->
            runCatching { InstallRecord.fromJson(JSONObject(File(d, "manifest.json").readText())) }.getOrNull()
        }.sortedByDescending { it.date }

    private fun save(r: InstallRecord) {
        r.dir.mkdirs()
        File(r.dir, "manifest.json").writeText(r.toJson().toString(2))
    }

    suspend fun install(plan: Plan, game: Game, fileName: String, progress: (Int, Int, String) -> Unit): InstallRecord {
        val b = Backends.forPath(game.path)
        val id = System.currentTimeMillis().toString()
        val backupFiles = File(HOME, "backups/$id/files")
        val added = ArrayList<String>()
        val replaced = ArrayList<String>()
        val createdDirs = ArrayList<String>()
        val knownDirs = HashSet<String>()
        val touched = HashSet<String>()

        fun record(restored: Boolean = false) =
            InstallRecord(id, game.name, game.path, fileName, id.toLong(), added, replaced, createdDirs, restored)

        save(record())
        plan.items.forEachIndexed { i, item ->
            progress(i, plan.items.size, item.targetRel)
            // remember folders we create, so a rollback can remove them again
            var parent = item.targetRel.substringBeforeLast('/', "")
            val chain = ArrayList<String>()
            while (parent.isNotEmpty() && parent !in knownDirs) {
                knownDirs += parent
                if (!b.isDir("${game.path}/$parent")) chain += parent
                parent = parent.substringBeforeLast('/', "")
            }
            createdDirs += chain.reversed()
            val target = "${game.path}/${item.targetRel}"
            if (touched.add(item.targetRel.lowercase())) {
                if (b.exists(target)) {
                    b.readTo(target, File(backupFiles, item.targetRel))
                    replaced += item.targetRel
                } else {
                    added += item.targetRel
                }
            }
            b.writeFrom(item.src, target)
            if (i % 25 == 0) save(record())
        }
        progress(plan.items.size, plan.items.size, "")
        return record().also(::save)
    }

    /**
     * Runs a protected Inno Setup installer in the built-in Wine, silently, straight into the game folder.
     * Originals of files it will overwrite are backed up first (names come from the installer's file list),
     * and everything new it creates is recorded, so a rollback works like for normal installs.
     */
    suspend fun installViaWine(
        ctx: android.content.Context, plan: Plan, game: Game, installer: File, fileName: String,
        log: (String) -> Unit, status: (String) -> Unit,
    ): InstallRecord {
        if (isPrivilegedPath(game.path))
            throw java.io.IOException("Игры в Android/data пока нельзя русифицировать через встроенный Wine.")
        val b = DirectBackend
        val id = System.currentTimeMillis().toString()
        val backupFiles = File(HOME, "backups/$id/files")
        val replaced = ArrayList<String>()
        val before = b.listTree(game.path, 12, 500_000).associate { it.name.lowercase() to it.isDir }

        status("Сохраняю оригинальные файлы…")
        for (item in plan.items) {
            val target = "${game.path}/${item.targetRel}"
            if (item.replaces && b.exists(target)) {
                log("Резервная копия: ${item.targetRel}")
                b.readTo(target, File(backupFiles, item.targetRel))
                replaced += item.targetRel
            }
        }
        fun record(added: List<String>, dirs: List<String>, restored: Boolean = false) =
            InstallRecord(id, game.name, game.path, fileName, id.toLong(), added, replaced, dirs, restored)
        save(record(emptyList(), emptyList()))

        status("Готовлю встроенный Wine…")
        WineRunner.ensureInstalled(ctx, log)
        WineRunner.ensurePrefix(ctx, log)

        // Wine hangs when started on an exe that lives on shared storage / SD card: run a private copy.
        val work = File(WineRunner.driveC(ctx), "ruarm").apply { deleteRecursively(); mkdirs() }
        val setup = File(work, "setup.exe")
        installer.copyTo(setup, overwrite = true)
        val setupLog = File(work, "setup.log")

        status("Устанавливаю русификатор…")
        log("Запускаю установщик в тихом режиме в папку игры")
        val watcher = Thread {
            var last = ""
            while (!Thread.currentThread().isInterrupted) {
                runCatching {
                    val line = setupLog.takeIf { it.exists() }?.readLines(Charsets.UTF_8)
                        ?.lastOrNull { it.contains("Dest filename:") }?.substringAfter("Dest filename:")?.trim()
                    if (line != null && line != last) { last = line; status("Устанавливаю: ${line.substringAfterLast('\\')}") }
                }
                try { Thread.sleep(1500) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
        val code = try {
            WineRunner.run(
                ctx,
                listOf(
                    "C:\\ruarm\\setup.exe", "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/SP-",
                    "/DIR=${WineRunner.winPath(game.path)}", "/LOG=C:\\ruarm\\setup.log",
                ),
                log, timeoutMinutes = 90,
            )
        } finally {
            watcher.interrupt()
            WineRunner.killServer(ctx)
        }
        val logText = runCatching { setupLog.readText(Charsets.UTF_8) }.getOrDefault("")
        setup.delete()

        // whatever appeared in the game folder belongs to the russifier (incl. its own uninstaller)
        val after = b.listTree(game.path, 12, 500_000)
        val added = after.filter { !it.isDir && it.name.lowercase() !in before }.map { it.name }
        val dirs = after.filter { it.isDir && it.name.lowercase() !in before }.map { it.name }

        if (!logText.contains("Installation process succeeded")) {
            val reason = logText.lines().map { it.substringAfter("   ", it).trim() }
                .filter { it.isNotBlank() && !it.startsWith("Log ") && !it.contains("Deinitializing") }.takeLast(4).joinToString("\n")
            // put everything back
            restore(record(added, dirs)) { _, _, _ -> }
            throw java.io.IOException("Установщик не смог поставить русификатор (код $code).\n$reason")
        }
        log("Установщик сообщил: Installation process succeeded")
        return record(added, dirs).also(::save)
    }

    suspend fun restore(r: InstallRecord, progress: (Int, Int, String) -> Unit): InstallRecord {
        val b = Backends.forPath(r.gamePath)
        val total = r.added.size + r.replaced.size
        var n = 0
        for (rel in r.added) {
            progress(n++, total, rel)
            b.delete("${r.gamePath}/$rel")
        }
        val backupFiles = File(r.dir, "files")
        for (rel in r.replaced) {
            progress(n++, total, rel)
            val src = File(backupFiles, rel)
            if (src.exists()) b.writeFrom(src, "${r.gamePath}/$rel")
        }
        // deepest first; File.delete() only removes empty folders
        for (d in r.createdDirs.sortedByDescending { it.length }) b.delete("${r.gamePath}/$d")
        backupFiles.deleteRecursively()
        return r.copy(restored = true).also(::save)
    }

    fun forget(r: InstallRecord) {
        r.dir.deleteRecursively()
    }
}
