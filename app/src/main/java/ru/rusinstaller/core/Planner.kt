package ru.rusinstaller.core

import java.io.File

/** Snapshot of a game folder: lowercase relative path -> (actual relative path, isDir). */
class GameIndex(val root: String, nodes: List<Node>) {
    class E(val actual: String, val isDir: Boolean)

    val entries: Map<String, E> = HashMap<String, E>(nodes.size * 2).apply {
        for (n in nodes) put(n.name.lowercase(), E(n.name, n.isDir))
    }
    val byName: Map<String, List<String>> = entries.keys.groupBy { it.substringAfterLast('/') }

    /** Game sub-directories (relative, actual case), shallow first — for the "change target" picker. */
    fun dirs(): List<String> = entries.values.filter { it.isDir }.map { it.actual }.sortedBy { it.count { c -> c == '/' } }

    companion object {
        suspend fun load(root: String): GameIndex {
            val b = Backends.forPath(root)
            return GameIndex(root, b.listTree(root, maxDepth = 10, maxEntries = 300_000))
        }
    }
}

class PlanItem(val src: File, val targetRel: String, val replaces: Boolean, val size: Long)

class Plan(
    /** Directory inside the extracted content whose contents are copied (relative, "" = everything). */
    val srcRel: String,
    /** Directory inside the game folder that receives the files (relative, actual case, "" = game root). */
    val targetRel: String,
    val items: List<PlanItem>,
    /** Share of files that land in already existing game folders (0..1). */
    val fit: Double,
    val auto: Boolean,
    /** Files are written by the original installer running in the built-in Wine, not copied by us. */
    val viaWine: Boolean = false,
) {
    val replaced get() = items.count { it.replaces }
    val added get() = items.count { !it.replaces }
    val bytes get() = items.sumOf { it.size }

    /**
     * When the payload is just a program (an online installer / patcher such as Siberian Studio's
     * Russianizer.exe) copying it does not translate anything: it has to be run inside the emulator.
     * Returns that exe's path relative to the game folder.
     */
    val patcherExe: String?
        get() {
            if (viaWine || items.isEmpty() || replaced > 0) return null
            val helperExt = setOf("exe", "dll", "txt", "ini", "cfg", "json", "xml", "url", "nfo", "ico", "png", "jpg", "bmp", "config", "manifest")
            if (!items.all { it.targetRel.substringAfterLast('.', "").lowercase() in helperExt }) return null
            return items.filter { it.targetRel.endsWith(".exe", true) }.maxByOrNull { it.size }?.targetRel
        }
}

object Planner {
    /** Folders that never belong in the game directory. */
    private val JUNK_DIRS = setOf("\$pluginsdir", "\$temp", "__macosx", "tmp", "{tmp}", "\$_outdir")
    private fun isJunkFile(name: String): Boolean {
        val n = name.lowercase()
        return n == "[nsis].nsi" || n.matches(Regex("""unins\d*\.(exe|dat|msg)""")) ||
            n.startsWith("uninstall") && n.endsWith(".exe") || n == "thumbs.db" || n == ".ds_store" || n == "desktop.ini"
    }

    /** All files of the extracted content as relative paths, junk removed. */
    fun sourceFiles(root: File): List<String> {
        val out = ArrayList<String>()
        fun walk(dir: File, rel: String, depth: Int) {
            for (k in dir.listFiles().orEmpty().sortedBy { it.name }) {
                val r = if (rel.isEmpty()) k.name else "$rel/${k.name}"
                if (k.isDirectory) {
                    if (k.name.lowercase() in JUNK_DIRS && depth <= 2) continue
                    walk(k, r, depth + 1)
                } else if (!isJunkFile(k.name)) out += r
            }
        }
        walk(root, "", 1)
        return out
    }

    /** Directories of the extracted content that may serve as copy roots. */
    fun sourceDirs(root: File, files: List<String> = sourceFiles(root)): List<String> {
        val dirs = linkedSetOf("")
        for (f in files) {
            var p = f.substringBeforeLast('/', "")
            val chain = ArrayList<String>()
            while (p.isNotEmpty()) { chain += p; p = p.substringBeforeLast('/', "") }
            dirs += chain.reversed()
        }
        return dirs.toList()
    }

    fun auto(root: File, game: GameIndex): Plan {
        val files = sourceFiles(root)
        val score = HashMap<Pair<String, String>, Double>()

        // Every source path (files and their parent dirs) is matched against same-named game entries;
        // each match votes for a (source root, game target) pair. Longer matched suffixes weigh more.
        val entries = LinkedHashMap<String, Boolean>()
        for (f in files) {
            entries[f] = false
            var p = f.substringBeforeLast('/', "")
            while (p.isNotEmpty() && p !in entries) { entries[p] = true; p = p.substringBeforeLast('/', "") }
        }
        for ((path, isDir) in entries) {
            val comps = path.lowercase().split('/')
            val candidates = game.byName[comps.last()] ?: continue
            if (candidates.size > 400) continue // too generic a name to be informative
            for (k in comps.indices) {
                val rest = comps.subList(k, comps.size).joinToString("/")
                val sKey = comps.subList(0, k).joinToString("/")
                for (gp in candidates) {
                    if (game.entries[gp]?.isDir != isDir) continue
                    val gKey = when {
                        gp == rest -> ""
                        gp.endsWith("/$rest") -> gp.substring(0, gp.length - rest.length - 1)
                        else -> continue
                    }
                    val w = (comps.size - k) * (if (isDir) 1.0 else 2.0)
                    score.merge(sKey to gKey, w, Double::plus)
                }
            }
        }

        val best = score.entries.maxWithOrNull(
            compareBy<Map.Entry<Pair<String, String>, Double>> { it.value }
                .thenByDescending { it.key.second.length }
                .thenByDescending { it.key.first.length }
        )
        if (best != null) {
            val (s, g) = best.key
            return build(root, game, actualSrc(files, s), game.entries[g]?.actual ?: "", auto = true, files = files)
        }

        // Nothing matched: unwrap single wrapper folders ("Rus/…") and copy into the game root.
        var s = ""
        val top = files.map { it.substringBefore('/') }.toSet()
        if (files.any { it.startsWith("app/", true) } && files.all { it.startsWith("app/", true) || !it.contains('/') }) {
            s = files.first { it.startsWith("app/", true) }.substringBefore('/')
        } else if (top.size == 1 && files.all { it.contains('/') }) {
            s = top.first()
            while (true) {
                val inner = files.filter { it.startsWith("$s/") }.map { it.removePrefix("$s/") }
                val t = inner.map { it.substringBefore('/') }.toSet()
                if (t.size == 1 && inner.all { it.contains('/') }) s = "$s/${t.first()}" else break
            }
        }
        return build(root, game, s, "", auto = true, files = files)
    }

    private fun actualSrc(files: List<String>, lower: String): String {
        if (lower.isEmpty()) return ""
        val f = files.first { it.lowercase().startsWith("$lower/") }
        return f.substring(0, lower.length)
    }

    fun build(root: File, game: GameIndex, srcRel: String, targetRel: String, auto: Boolean = false,
              files: List<String> = sourceFiles(root)): Plan {
        val prefix = if (srcRel.isEmpty()) "" else "$srcRel/"
        val items = ArrayList<PlanItem>()
        var fitting = 0
        for (f in files) {
            if (!f.startsWith(prefix)) continue
            val rel = f.removePrefix(prefix)
            val full = if (targetRel.isEmpty()) rel else "$targetRel/$rel"
            val resolved = resolveCase(game, full)
            val parent = resolved.substringBeforeLast('/', "")
            if (parent.isEmpty() || game.entries[parent.lowercase()]?.isDir == true) fitting++
            val existing = game.entries[resolved.lowercase()]
            val src = File(root, f)
            items += PlanItem(src, resolved, existing != null && !existing.isDir, src.length())
        }
        val fit = if (items.isEmpty()) 0.0 else fitting.toDouble() / items.size
        return Plan(srcRel, targetRel, items, fit, auto)
    }

    /** Plan for a protected installer run in Wine: what it will write into the game folder. */
    fun forWine(entries: List<InnoEntry>, game: GameIndex, installer: File): Plan {
        val items = entries.map { e ->
            val resolved = resolveCase(game, e.path)
            val existing = game.entries[resolved.lowercase()]
            PlanItem(installer, resolved, existing != null && !existing.isDir, e.size)
        }
        val fitting = items.count { val p = it.targetRel.substringBeforeLast('/', ""); p.isEmpty() || game.entries[p.lowercase()]?.isDir == true }
        return Plan("", "", items, if (items.isEmpty()) 1.0 else fitting.toDouble() / items.size, auto = true, viaWine = true)
    }

    /** Game files on Android are case-sensitive, Windows games are not: reuse existing names' case. */
    private fun resolveCase(game: GameIndex, rel: String): String {
        val parts = rel.split('/')
        val out = ArrayList<String>(parts.size)
        for (i in parts.indices) {
            val lowerPath = parts.subList(0, i + 1).joinToString("/").lowercase()
            val e = game.entries[lowerPath]
            out += e?.actual?.substringAfterLast('/') ?: parts[i]
        }
        return out.joinToString("/")
    }
}
