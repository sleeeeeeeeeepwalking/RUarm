package ru.rusinstaller.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.rusinstaller.core.Backends
import ru.rusinstaller.core.ExtractResult
import ru.rusinstaller.core.Extractor
import ru.rusinstaller.core.Game
import ru.rusinstaller.core.GameIndex
import ru.rusinstaller.core.GameNativeLauncher
import ru.rusinstaller.core.GameScanner
import ru.rusinstaller.core.InstallRecord
import ru.rusinstaller.core.Installer
import ru.rusinstaller.core.Node
import ru.rusinstaller.core.Plan
import ru.rusinstaller.core.Planner
import ru.rusinstaller.core.ShizukuManager
import java.io.File

sealed interface Screen {
    data object Home : Screen
    data object PickFile : Screen
    data object PickGame : Screen
    data object Work : Screen
    data object Review : Screen
    data object Done : Screen
    data object History : Screen
    data object Settings : Screen
    data class Browse(val purpose: BrowsePurpose) : Screen
}

enum class BrowsePurpose { GAME_FOLDER, LIBRARY, TARGET_SUBDIR }

class InputFile(val name: String, val file: File)

/** See [MainViewModel.install]: GameNative ignores the exe override today (tested with v1.4). */
private const val AUTO_LAUNCH_WORKS = false

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // ---------- navigation ----------
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = stack.last()
    var busy by mutableStateOf(false); private set

    fun go(s: Screen) { stack += s }
    private fun replaceTop(s: Screen) { stack[stack.lastIndex] = s }
    fun home() { stack.clear(); stack += Screen.Home }

    /** @return false when the app should close. */
    fun back(): Boolean {
        if (busy) return true
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    // ---------- permissions ----------
    var hasAllFiles by mutableStateOf(false); private set
    var shizuku by mutableStateOf(ShizukuManager.State.NOT_RUNNING); private set

    fun refreshPermissions() {
        hasAllFiles = Environment.isExternalStorageManager() ||
            ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        shizuku = ShizukuManager.state()
    }

    // ---------- step 1: russifier file ----------
    var input by mutableStateOf<InputFile?>(null); private set
    var recent by mutableStateOf<List<File>>(emptyList()); private set

    fun loadRecent() {
        viewModelScope.launch(Dispatchers.IO) {
            val home = "/storage/emulated/0"
            val roots = listOf(
                "$home/Download" to 3, "$home/Documents" to 2, "$home/Telegram" to 3,
                "$home/Android/media/org.telegram.messenger/Telegram" to 2,
            )
            val ext = setOf("exe", "zip", "rar", "7z")
            val found = ArrayList<File>()
            for ((r, depth) in roots) {
                File(r).walkTopDown().maxDepth(depth).onEnter { !it.name.startsWith(".") }
                    .filter { it.isFile && it.extension.lowercase() in ext }.forEach { found += it }
            }
            val list = found.distinctBy { it.absolutePath }.sortedByDescending { it.lastModified() }.take(60)
            withContext(Dispatchers.Main) { recent = list }
        }
    }

    fun startInstall() {
        input = null
        go(Screen.PickFile)
        loadRecent()
    }

    fun chooseFile(f: File) {
        input = InputFile(f.name, f)
        afterFileChosen()
    }

    fun chooseUri(uri: Uri) {
        runWork("Копирую файл…") { log ->
            val name = queryName(uri) ?: "russifier.bin"
            log("Файл: $name")
            val dir = File(ctx.cacheDir, "input").apply { deleteRecursively(); mkdirs() }
            val f = File(dir, name.replace('/', '_'))
            ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
            withContext(Dispatchers.Main) {
                input = InputFile(name, f)
                // replace the Work screen by the game picker
                stack.removeAt(stack.lastIndex)
                afterFileChosen()
            }
        }
    }

    /** "Open with…" / "Share" from another app. */
    fun handleIntent(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        }
        if (uri != null && !busy) {
            home()
            go(Screen.PickFile)
            chooseUri(uri)
        }
    }

    private fun queryName(uri: Uri): String? =
        if (uri.scheme == "file") uri.lastPathSegment
        else ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun afterFileChosen() {
        go(Screen.PickGame)
        if (games.isEmpty()) scan()
    }

    // ---------- step 2: game ----------
    var games by mutableStateOf<List<Game>>(emptyList()); private set
    var scanNotes by mutableStateOf<List<String>>(emptyList()); private set
    var scanning by mutableStateOf(false); private set
    var query by mutableStateOf("")
    var game by mutableStateOf<Game?>(null); private set

    val libraries: List<String> get() = prefs.getStringSet("libraries", emptySet())!!.sorted()
    var librariesVersion by mutableStateOf(0); private set

    fun addLibrary(path: String) {
        prefs.edit().putStringSet("libraries", (libraries + path).toSet()).apply()
        librariesVersion++
        games = emptyList()
    }

    fun removeLibrary(path: String) {
        prefs.edit().putStringSet("libraries", (libraries - path).toSet()).apply()
        librariesVersion++
        games = emptyList()
    }

    fun scan() {
        if (scanning) return
        scanning = true
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { GameScanner.scan(libraries) }.getOrElse { ru.rusinstaller.core.ScanResult(emptyList(), listOf("Ошибка поиска: ${it.message}")) }
            }
            games = r.games
            scanNotes = r.notes
            scanning = false
        }
    }

    fun chooseGame(g: Game) {
        game = g
        analyze()
    }

    // ---------- work (analyze / install / restore) ----------
    var workTitle by mutableStateOf(""); private set
    val workLog = mutableStateListOf<String>()
    var workProgress by mutableStateOf<Float?>(null); private set
    var workError by mutableStateOf<String?>(null); private set

    private fun runWork(title: String, block: suspend (log: (String) -> Unit) -> Unit) {
        workTitle = title
        workLog.clear()
        workProgress = null
        workError = null
        busy = true
        go(Screen.Work)
        val log: (String) -> Unit = { line ->
            viewModelScope.launch(Dispatchers.Main) {
                workLog += line
                if (workLog.size > 300) workLog.removeRange(0, workLog.size - 300)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block(log)
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { workError = t.message ?: t.javaClass.simpleName }
            } finally {
                withContext(Dispatchers.Main) { busy = false }
            }
        }
    }

    private fun progress(i: Int, total: Int, text: String) {
        viewModelScope.launch(Dispatchers.Main) {
            workProgress = if (total == 0) 1f else i.toFloat() / total
            if (text.isNotEmpty()) workTitle = text
        }
    }

    // ---------- step 3: plan ----------
    var extracted by mutableStateOf<ExtractResult?>(null); private set
    var index by mutableStateOf<GameIndex?>(null); private set
    var plan by mutableStateOf<Plan?>(null); private set
    var sourceDirs by mutableStateOf<List<String>>(emptyList()); private set

    private fun analyze() {
        val g = game ?: return
        val inFile = input ?: return
        runWork("Готовлю русификатор…") { log ->
            val work = File(ctx.cacheDir, "work").apply { deleteRecursively(); mkdirs() }
            log("Игра: ${g.name}")
            log("Папка: ${g.path}")
            val ex = Extractor.extract(ctx, inFile.file, File(work, "out"), log)
            log("Формат: ${ex.format}")
            log("Читаю папку игры…")
            val idx = GameIndex.load(g.path)
            log("В папке игры ${idx.entries.size} файлов и папок")
            log("Подбираю, куда класть файлы…")
            val p = ex.wineEntries?.let { Planner.forWine(it, idx, inFile.file) } ?: Planner.auto(ex.root, idx)
            val dirs = Planner.sourceDirs(ex.root)
            withContext(Dispatchers.Main) {
                extracted = ex
                index = idx
                plan = p
                sourceDirs = dirs
                replaceTop(Screen.Review)
            }
        }
    }

    fun changeSource(rel: String) {
        val ex = extracted ?: return
        val idx = index ?: return
        val p = plan ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val np = Planner.build(ex.root, idx, rel, p.targetRel)
            withContext(Dispatchers.Main) { plan = np }
        }
    }

    fun changeTarget(rel: String) {
        val ex = extracted ?: return
        val idx = index ?: return
        val p = plan ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val np = Planner.build(ex.root, idx, p.srcRel, rel)
            withContext(Dispatchers.Main) { plan = np }
        }
    }

    var lastRecord by mutableStateOf<InstallRecord?>(null); private set
    /** Set when the "russifier" was only a patcher program that must be run inside the emulator. */
    var lastPatcher by mutableStateOf<String?>(null); private set
    /** Why the program could not be started in GameNative automatically (null = started). */
    @Volatile var launchError: String? = null; private set
    /** Texts of message boxes the installer showed (answered automatically) — shown on the Done screen. */
    val installerMessages = mutableStateListOf<String>()
    @Volatile private var lastLaunch: Triple<Int, String, String>? = null

    fun relaunch() {
        val (appId, exe, args) = lastLaunch ?: return
        GameNativeLauncher.launch(ctx, appId, exe, args)
    }

    fun install() {
        val p = plan ?: return
        val g = game ?: return
        val name = input?.name ?: "?"
        lastPatcher = p.patcherExe
        installerMessages.clear()
        val runArgs = extracted?.runArgs ?: ""
        launchError = null
        runWork("Устанавливаю…") { log ->
            log("Копирую ${p.items.size} файлов в ${g.name}")
            val r = if (p.viaWine) {
                val src = input?.file ?: throw IllegalStateException("Нет файла русификатора")
                Installer.installViaWine(
                    ctx, p, g, src, name, log,
                    status = { s -> viewModelScope.launch(Dispatchers.Main) { workTitle = s } },
                    onMessage = { m -> viewModelScope.launch(Dispatchers.Main) { if (m !in installerMessages) installerMessages += m } },
                )
            } else Installer.install(p, g, name, ::progress)
            log("Готово")
            val exe = p.patcherExe
            if (exe != null) {
                launchError = when {
                    // GameNative (v1.4) drops the one-launch exe override when it builds the game window,
                    // so it would start the game itself instead of the installer. Keep manual steps until fixed.
                    !AUTO_LAUNCH_WORKS -> "GameNative пока не умеет запускать установщик вместо игры по команде извне."
                    !GameNativeLauncher.isGameNativeSteamGame(g) -> "Автозапуск работает только для Steam-игр GameNative."
                    GameNativeLauncher.installedPackage(ctx) == null -> "GameNative не установлен."
                    else -> {
                        withContext(Dispatchers.Main) { workTitle = "Определяю игру в Steam…" }
                        val appId = GameNativeLauncher.findAppId(g)
                        log("Steam appid: $appId")
                        if (appId == null) "Не удалось определить номер игры в Steam (нужен интернет)."
                        else {
                            lastLaunch = Triple(appId, exe, runArgs)
                            GameNativeLauncher.launch(ctx, appId, exe, runArgs)
                        }
                    }
                }
                launchError?.let { log("Автозапуск: $it") }
            }
            File(ctx.cacheDir, "work").deleteRecursively()
            File(ctx.cacheDir, "input").deleteRecursively()
            withContext(Dispatchers.Main) {
                lastRecord = r
                stack.clear()
                stack += Screen.Home
                stack += Screen.Done
            }
        }
    }

    // ---------- history ----------
    var history by mutableStateOf<List<InstallRecord>>(emptyList()); private set

    fun loadHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val h = runCatching { Installer.history() }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { history = h }
        }
    }

    fun restore(r: InstallRecord) {
        runWork("Откатываю русификатор…") { log ->
            log("Игра: ${r.gameName}")
            Installer.restore(r, ::progress)
            log("Оригинальные файлы возвращены")
            val h = Installer.history()
            withContext(Dispatchers.Main) {
                history = h
                stack.removeAt(stack.lastIndex)
            }
        }
    }

    fun forget(r: InstallRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            Installer.forget(r)
            val h = Installer.history()
            withContext(Dispatchers.Main) { history = h }
        }
    }

    // ---------- folder browser ----------
    var browsePath by mutableStateOf(""); private set
    var browseEntries by mutableStateOf<List<Node>>(emptyList()); private set
    var browseError by mutableStateOf<String?>(null); private set
    var browseRoot by mutableStateOf<String?>(null); private set

    fun openBrowser(purpose: BrowsePurpose) {
        when (purpose) {
            BrowsePurpose.TARGET_SUBDIR -> {
                val g = game ?: return
                browseRoot = g.path
                go(Screen.Browse(purpose))
                browseTo(g.path + (plan?.targetRel?.takeIf { it.isNotEmpty() }?.let { "/$it" } ?: ""))
            }
            else -> {
                browseRoot = null
                go(Screen.Browse(purpose))
                browseTo("/storage/emulated/0")
            }
        }
    }

    fun browseUp() {
        val root = browseRoot
        if (root != null && browsePath == root) return
        if (browsePath.isEmpty()) return
        if (browsePath in GameScanner.volumes()) { browseTo(""); return }
        browseTo(browsePath.substringBeforeLast('/'))
    }

    fun browseTo(path: String) {
        browsePath = path
        browseError = null
        if (path.isEmpty()) {
            browseEntries = GameScanner.volumes().map { Node(it, true) }
            return
        }
        browseEntries = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatching {
                val b = Backends.forPath(path)
                b.list(path) ?: throw IllegalStateException("Папка недоступна")
            }
            withContext(Dispatchers.Main) {
                if (browsePath != path) return@withContext
                res.onSuccess { list ->
                    browseEntries = list.filter { it.isDir }.sortedBy { it.name.lowercase() } +
                        list.filter { !it.isDir }.sortedBy { it.name.lowercase() }
                }.onFailure { browseError = it.message }
            }
        }
    }

    fun browseChoose(purpose: BrowsePurpose) {
        val path = browsePath.ifEmpty { return }
        when (purpose) {
            BrowsePurpose.GAME_FOLDER -> {
                stack.removeAt(stack.lastIndex)
                chooseGame(Game(path.substringAfterLast('/'), path, "Выбрана вручную"))
            }
            BrowsePurpose.LIBRARY -> {
                stack.removeAt(stack.lastIndex)
                addLibrary(path)
            }
            BrowsePurpose.TARGET_SUBDIR -> {
                val root = browseRoot ?: return
                stack.removeAt(stack.lastIndex)
                changeTarget(path.removePrefix(root).trimStart('/'))
            }
        }
    }
}
