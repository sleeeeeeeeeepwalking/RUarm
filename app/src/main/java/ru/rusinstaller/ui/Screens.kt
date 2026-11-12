package ru.rusinstaller.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.rusinstaller.BuildConfig
import ru.rusinstaller.core.GameScanner
import ru.rusinstaller.core.InstallRecord
import ru.rusinstaller.core.Installer
import ru.rusinstaller.core.ShizukuManager

// Триколор
private val FlagWhite = Color(0xFFF4F5F8)
private val FlagBlue = Color(0xFF2F6BFF)
private val FlagRed = Color(0xFFE5383B)

private val Accent = FlagBlue
private val Good = Color(0xFF4CC38A)
private val Warn = Color(0xFFFFB547)
private val Bad = FlagRed
private val Muted = Color(0xFF9CA2B4)

private val Colors = darkColorScheme(
    primary = FlagBlue,
    onPrimary = Color.White,
    background = Color(0xFF101218),
    surface = Color(0xFF101218),
    surfaceVariant = Color(0xFF1B1E27),
    onSurfaceVariant = Muted,
    error = FlagRed,
)

@Composable
fun App(vm: MainViewModel) {
    MaterialTheme(colorScheme = Colors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 860.dp).fillMaxSize()) {
                    when (val s = vm.screen) {
                        Screen.Home -> HomeScreen(vm)
                        Screen.PickFile -> PickFileScreen(vm)
                        Screen.PickGame -> PickGameScreen(vm)
                        Screen.Work -> WorkScreen(vm)
                        Screen.Review -> ReviewScreen(vm)
                        Screen.Done -> DoneScreen(vm)
                        Screen.History -> HistoryScreen(vm)
                        Screen.Settings -> SettingsScreen(vm)
                        is Screen.Browse -> BrowseScreen(vm, s.purpose)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- building blocks

/** Thin white-blue-red stripe. */
@Composable
private fun FlagStripe(modifier: Modifier = Modifier, height: Int = 4) {
    Row(modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(height.dp))) {
        Box(Modifier.weight(1f).fillMaxSize().background(FlagWhite))
        Box(Modifier.weight(1f).fillMaxSize().background(FlagBlue))
        Box(Modifier.weight(1f).fillMaxSize().background(FlagRed))
    }
}

@Composable
private fun Header(title: String, onBack: (() -> Unit)?, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
        else Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
private fun Tile(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Accent,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val content: @Composable () -> Unit = {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            trailing?.invoke()
        }
    }
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    val shape = RoundedCornerShape(14.dp)
    if (onClick != null) Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = colors, shape = shape) { content() }
    else Card(modifier = Modifier.fillMaxWidth(), colors = colors, shape = shape) { content() }
}

@Composable
private fun Arrow() = Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted)

@Composable
private fun Notice(text: String, color: Color = Warn, icon: ImageVector = Icons.Filled.Warning) {
    Row(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).padding(12.dp),
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Collapsible "details" row. */
@Composable
private fun Expander(title: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onToggle).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = Muted)
    }
}

@Composable
private fun BigButton(text: String, color: Color = Accent, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 17.sp) }
}

private val listPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)

private fun size(ctx: android.content.Context, bytes: Long) = Formatter.formatShortFileSize(ctx, bytes)

private fun openAllFilesSettings(ctx: android.content.Context) {
    val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
    runCatching { ctx.startActivity(i) }.onFailure { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
}

// ---------------------------------------------------------------- home

@Composable
private fun HomeScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.refreshPermissions() }
    LaunchedEffect(vm.hasAllFiles) { vm.loadHistory() }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("RUarm", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                FlagStripe(Modifier.width(72.dp))
            }
            IconButton(onClick = { vm.go(Screen.Settings) }) { Icon(Icons.Filled.Settings, "Настройки", tint = Muted) }
        }

        Spacer(Modifier.weight(1f))

        Column(Modifier.widthIn(max = 560.dp).align(Alignment.CenterHorizontally)) {
        if (!vm.hasAllFiles) {
            Notice("Нужно разрешение «Доступ ко всем файлам» — без него программа не видит игры.", Warn)
            Spacer(Modifier.height(12.dp))
            BigButton("Разрешить доступ") { openAllFilesSettings(ctx) }
        } else {
            Text("Закинь exe или архив русификатора,\nвыбери игру — и готово.",
                style = MaterialTheme.typography.titleMedium, color = Muted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            BigButton("Установить русификатор") { vm.startInstall() }
        }

        Spacer(Modifier.height(16.dp))
        val active = vm.history.count { !it.restored }
        if (vm.history.isNotEmpty()) {
            Tile("Установленные", if (active > 0) "$active шт. · можно откатить" else "все откачены",
                icon = Icons.Filled.CheckCircle, iconTint = Good, trailing = { Arrow() }) { vm.go(Screen.History) }
        }
        }
        Spacer(Modifier.weight(1.3f))
    }
}

// ---------------------------------------------------------------- settings

@Composable
private fun SettingsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    var shizukuHelp by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshPermissions() }
    @Suppress("UNUSED_VARIABLE") val v = vm.librariesVersion

    Column(Modifier.fillMaxSize()) {
        Header("Настройки", onBack = { vm.back() })
        LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                if (vm.hasAllFiles) Tile("Доступ к файлам", "Разрешён", icon = Icons.Filled.CheckCircle, iconTint = Good)
                else Tile("Доступ к файлам", "Не разрешён — нажми", icon = Icons.Filled.Warning, iconTint = Warn) { openAllFilesSettings(ctx) }
            }
            item {
                when (vm.shizuku) {
                    ShizukuManager.State.READY -> Tile("Shizuku", "Подключён", icon = Icons.Filled.CheckCircle, iconTint = Good)
                    ShizukuManager.State.NO_PERMISSION -> Tile("Shizuku", "Нажми, чтобы разрешить", icon = Icons.Filled.Warning, iconTint = Warn) { ShizukuManager.requestPermission() }
                    ShizukuManager.State.NOT_RUNNING -> Tile("Shizuku", "Нужен только для игр в Android/data", icon = Icons.Filled.Info, iconTint = Muted, trailing = { Arrow() }) { shizukuHelp = true }
                }
            }
            items(vm.libraries) { lib ->
                Tile(lib.substringAfterLast('/'), "Папка с играми", trailing = {
                    IconButton(onClick = { vm.removeLibrary(lib) }) { Icon(Icons.Filled.Close, "Убрать", tint = Muted) }
                })
            }
            item {
                Tile("Добавить папку с играми", "Если игр нет в списке (каждая подпапка = игра)", icon = Icons.Filled.Add) {
                    if (vm.hasAllFiles) vm.openBrowser(BrowsePurpose.LIBRARY) else openAllFilesSettings(ctx)
                }
            }
            item {
                Text("Резервные копии: ${Installer.HOME.absolutePath}/backups\nВерсия ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
            }
        }
    }

    if (shizukuHelp) {
        AlertDialog(
            onDismissRequest = { shizukuHelp = false },
            confirmButton = {
                TextButton(onClick = {
                    shizukuHelp = false
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Скачать Shizuku") }
            },
            dismissButton = { TextButton(onClick = { shizukuHelp = false; vm.refreshPermissions() }) { Text("Закрыть") } },
            title = { Text("Shizuku") },
            text = {
                Text(
                    "Нужен, только если игры лежат в Android/data.\n\n" +
                        "1. Установи Shizuku.\n2. Включи «Отладку по Wi-Fi» в параметрах разработчика.\n" +
                        "3. Запусти Shizuku через беспроводную отладку.\n4. Вернись сюда и нажми «Разрешить»."
                )
            },
        )
    }
}

// ---------------------------------------------------------------- step 1

@Composable
private fun PickFileScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.chooseUri(uri) }
    Column(Modifier.fillMaxSize()) {
        Header("Какой русификатор?", onBack = { vm.back() })
        LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.recent, key = { it.absolutePath }) { f ->
                val date = DateFormat.format("dd.MM HH:mm", f.lastModified())
                Tile(f.name, "${size(ctx, f.length())} · $date", trailing = { Arrow() }) { vm.chooseFile(f) }
            }
            if (vm.recent.isEmpty()) item {
                Text("В «Загрузках» нет exe/zip/rar/7z.", color = Muted, modifier = Modifier.padding(8.dp))
            }
            item {
                Tile("Другой файл…", icon = Icons.Filled.Search) { picker.launch(arrayOf("*/*")) }
            }
        }
    }
}

// ---------------------------------------------------------------- step 2

@Composable
private fun PickGameScreen(vm: MainViewModel) {
    Column(Modifier.fillMaxSize()) {
        Header("Для какой игры?", onBack = { vm.back() })
        if (vm.games.size > 6) {
            OutlinedTextField(
                value = vm.query, onValueChange = { vm.query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Поиск") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                leadingIcon = { Icon(Icons.Filled.Search, null) },
            )
        }
        val q = vm.query.trim().lowercase()
        val list = vm.games.filter { q.isEmpty() || it.name.lowercase().contains(q) }
        LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.scanning) item {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Ищу игры…", color = Muted)
                }
            }
            if (!vm.scanning && vm.games.isEmpty()) items(vm.scanNotes) { Notice(it, Accent, Icons.Filled.Info) }
            items(list, key = { it.path }) { g ->
                Tile(g.name, g.source.substringBefore(" ·"), trailing = { Arrow() }) { vm.chooseGame(g) }
            }
            item {
                Tile("Указать папку вручную", icon = Icons.Filled.Search) { vm.openBrowser(BrowsePurpose.GAME_FOLDER) }
            }
        }
    }
}

// ---------------------------------------------------------------- progress

@Composable
private fun WorkScreen(vm: MainViewModel) {
    var details by remember { mutableStateOf(false) }
    val state = rememberLazyListState()
    LaunchedEffect(vm.workLog.size, details) { if (details && vm.workLog.isNotEmpty()) state.scrollToItem(vm.workLog.lastIndex) }
    val err = vm.workError
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Spacer(Modifier.height(24.dp))
        Text(if (err != null) "Не получилось" else "Секунду…", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        if (err == null) {
            Text(vm.workTitle, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            val p = vm.workProgress
            if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
            else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = Accent)
        } else {
            Spacer(Modifier.height(8.dp))
            Notice(err, Bad)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { vm.back() }) { Text("Назад") }
        }
        Spacer(Modifier.height(16.dp))
        Expander("Подробности", details) { details = !details }
        if (details) {
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxWidth().weight(1f).background(Color(0xFF0B0C10), RoundedCornerShape(12.dp)).padding(10.dp),
            ) {
                items(vm.workLog) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Muted) }
            }
        }
    }
}

// ---------------------------------------------------------------- step 3

@Composable
private fun ReviewScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val plan = vm.plan ?: return
    val ex = vm.extracted ?: return
    val game = vm.game ?: return
    var pickSource by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf(false) }
    val patcher = plan.patcherExe

    Column(Modifier.fillMaxSize()) {
        Header("Всё готово", onBack = { vm.back() })
        LazyColumn(Modifier.weight(1f), contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Text(game.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(vm.input?.name ?: "", color = Muted, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        FlagStripe(Modifier.width(48.dp), height = 3)
                        Spacer(Modifier.height(12.dp))
                        when {
                            plan.items.isEmpty() -> Text("Нет файлов для установки", color = Bad)
                            patcher != null -> Text("Это программа-установщик: её нужно один раз запустить в GameNative")
                            else -> Text(
                                buildString {
                                    append("${plan.items.size} файлов · ${size(ctx, plan.bytes)}")
                                    if (plan.replaced > 0) append("\nЗаменит ${plan.replaced} — оригиналы сохранятся")
                                },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        if (plan.viaWine) {
                            Spacer(Modifier.height(8.dp))
                            Text("Защищённый установщик — RUarm запустит его сам во встроенном Wine. " +
                                "Это займёт несколько минут; первый раз ещё ~1 минута на подготовку Wine.",
                                color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (plan.items.isEmpty()) item { Notice("В русификаторе не нашлось файлов. Открой «Дополнительно» и выбери другую папку.", Bad) }
            else if (patcher == null && plan.fit < 0.6) item {
                Notice("Похоже, русификатор не от этой игры или файлы лягут не туда. Проверь игру или открой «Дополнительно».")
            }
            items(ex.warnings) { Notice(it, if (patcher != null) Accent else Warn, if (patcher != null) Icons.Filled.Info else Icons.Filled.Warning) }

            item { Expander("Дополнительно", advanced) { advanced = !advanced } }
            if (advanced) {
                if (!plan.viaWine) item {
                    Tile("Откуда: ${plan.srcRel.ifEmpty { "весь русификатор" }}", "Папка внутри русификатора", trailing = { Arrow() }) { pickSource = true }
                }
                if (!plan.viaWine) item {
                    Tile("Куда: ${game.name}${if (plan.targetRel.isEmpty()) "" else "/" + plan.targetRel}", "Папка в игре", trailing = { Arrow() }) {
                        vm.openBrowser(BrowsePurpose.TARGET_SUBDIR)
                    }
                }
                item { Expander("Файлы (${plan.items.size})", files) { files = !files } }
                if (files) {
                    items(plan.items.take(400)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
                            Text(if (it.replaces) "замена" else "новый", color = if (it.replaces) Warn else Good, fontSize = 11.sp, modifier = Modifier.width(56.dp))
                            Text(it.targetRel, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        Column(Modifier.padding(16.dp)) {
            BigButton("Установить", Accent, enabled = plan.items.isNotEmpty() || plan.viaWine) { vm.install() }
        }
    }

    if (pickSource) {
        AlertDialog(
            onDismissRequest = { pickSource = false },
            confirmButton = { TextButton(onClick = { pickSource = false }) { Text("Закрыть") } },
            title = { Text("Откуда копировать") },
            text = {
                LazyColumn(Modifier.height(360.dp)) {
                    items(vm.sourceDirs) { d ->
                        Text(
                            if (d.isEmpty()) "весь русификатор" else d,
                            fontWeight = if (d == plan.srcRel) FontWeight.Bold else FontWeight.Normal,
                            color = if (d == plan.srcRel) Accent else Color.Unspecified,
                            modifier = Modifier.fillMaxWidth().clickable { pickSource = false; vm.changeSource(d) }.padding(vertical = 8.dp),
                        )
                    }
                }
            },
        )
    }
}

// ---------------------------------------------------------------- done

@Composable
private fun DoneScreen(vm: MainViewModel) {
    val r = vm.lastRecord
    val patcher = vm.lastPatcher
    if (patcher != null) {
        PatcherDone(vm, patcher, r?.gameName ?: "")
        return
    }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(textAlign = TextAlign.Center)) {
            Icon(Icons.Filled.CheckCircle, null, tint = Good, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(16.dp))
            Text("Готово!", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            FlagStripe(Modifier.width(72.dp))
            Spacer(Modifier.height(14.dp))
            if (r != null) Text("${r.gameName} теперь на русском", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            if (vm.installerMessages.isEmpty()) {
                Text("Если язык не сменился — поставь русский в настройках самой игры.", color = Muted, style = MaterialTheme.typography.bodySmall)
            } else {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.widthIn(max = 640.dp)) {
                    Notice(
                        "Сообщение от авторов русификатора:\n" + vm.installerMessages.joinToString("\n\n"),
                        Warn, Icons.Filled.Info,
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            Box(Modifier.widthIn(max = 360.dp)) { BigButton("Отлично") { vm.home() } }
        }
    }
}

@Composable
private fun PatcherDone(vm: MainViewModel, exe: String, gameName: String) {
    val name = exe.substringAfterLast('/')
    val err = vm.launchError
    if (err == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(textAlign = TextAlign.Center)) {
                Icon(Icons.Filled.CheckCircle, null, tint = Good, modifier = Modifier.size(72.dp))
                Spacer(Modifier.height(16.dp))
                Text("Установка запущена", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                FlagStripe(Modifier.width(72.dp))
                Spacer(Modifier.height(14.dp))
                Text("GameNative откроет установщик вместо «$gameName». Дождись, пока он закончит, и выйди из игры — " +
                    "дальше игра запускается как обычно, уже на русском.", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(6.dp))
                Text("Если окно патчера ждёт нажатия — нажми в нём «Установить».", color = Muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(28.dp))
                Box(Modifier.widthIn(max = 360.dp)) { BigButton("Готово") { vm.home() } }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { vm.relaunch() }) { Text("Запустить ещё раз") }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Header("Осталось запустить", onBack = null)
        LazyColumn(Modifier.weight(1f), contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Notice("Сам запустить не смог: $err", Warn) }
            item {
                Text("$name лежит в папке «$gameName». Запусти его один раз через GameNative:",
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(4.dp))
            }
            item { Tile("1. Настройки контейнера игры → Executable Path") }
            item { Tile("2. Выбери $name", "Если нет в списке — «Custom path…»") }
            item { Tile("3. Запусти игру и пройди установку", "Спросит папку — укажи папку игры") }
            item { Tile("4. Верни Executable Path на exe игры") }
        }
        Column(Modifier.padding(16.dp)) { BigButton("Понятно") { vm.home() } }
    }
}

// ---------------------------------------------------------------- history

@Composable
private fun HistoryScreen(vm: MainViewModel) {
    var confirm by remember { mutableStateOf<InstallRecord?>(null) }
    LaunchedEffect(Unit) { vm.loadHistory() }
    Column(Modifier.fillMaxSize()) {
        Header("Установленные", onBack = { vm.back() })
        LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.history.isEmpty()) item { Text("Пока ничего не установлено.", color = Muted, modifier = Modifier.padding(8.dp)) }
            items(vm.history, key = { it.id }) { r ->
                val date = DateFormat.format("dd.MM.yyyy", r.date)
                Tile(
                    r.gameName,
                    if (r.restored) "Откачен · $date" else "${r.fileName} · $date",
                    icon = if (r.restored) Icons.Filled.Close else Icons.Filled.CheckCircle,
                    iconTint = if (r.restored) Muted else Good,
                    trailing = {
                        if (!r.restored) TextButton(onClick = { confirm = r }) { Text("Откатить", color = FlagRed) }
                        else TextButton(onClick = { vm.forget(r) }) { Text("Убрать", color = Muted) }
                    },
                )
            }
        }
    }
    confirm?.let { r ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            confirmButton = { TextButton(onClick = { confirm = null; vm.restore(r) }) { Text("Откатить", color = FlagRed) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
            title = { Text("Вернуть оригинал?") },
            text = { Text("«${r.gameName}» вернётся в состояние до установки русификатора.") },
        )
    }
}

// ---------------------------------------------------------------- folder browser

@Composable
private fun BrowseScreen(vm: MainViewModel, purpose: BrowsePurpose) {
    val title = when (purpose) {
        BrowsePurpose.GAME_FOLDER -> "Папка игры"
        BrowsePurpose.LIBRARY -> "Папка с играми"
        BrowsePurpose.TARGET_SUBDIR -> "Куда копировать"
    }
    val root = vm.browseRoot
    val shown = when {
        vm.browsePath.isEmpty() -> "Накопители"
        root != null -> (vm.game?.name ?: "") + vm.browsePath.removePrefix(root)
        else -> vm.browsePath.replace("/storage/emulated/0", "Память")
    }
    Column(Modifier.fillMaxSize()) {
        Header(title, onBack = { vm.back() })
        Text(shown, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val canUp = vm.browsePath.isNotEmpty() && !(root != null && vm.browsePath == root)
            if (canUp) item { Tile("..  наверх") { vm.browseUp() } }
            if (vm.browsePath.isNotEmpty() && root == null && vm.browsePath in GameScanner.volumes()) item {
                Tile("Android/data", "Папки приложений — через Shizuku", icon = Icons.Filled.Info) { vm.browseTo(vm.browsePath + "/Android/data") }
            }
            vm.browseError?.let { e -> item { Notice(e, Bad) } }
            items(vm.browseEntries.filter { it.isDir }, key = { it.name }) { n ->
                val label = if (vm.browsePath.isEmpty()) GameScanner.volumeLabel(n.name).replaceFirstChar { it.uppercase() } else n.name
                Tile(label, trailing = { Arrow() }) {
                    vm.browseTo(if (vm.browsePath.isEmpty()) n.name else "${vm.browsePath}/${n.name}")
                }
            }
        }
        Column(Modifier.padding(16.dp)) {
            BigButton("Выбрать эту папку", enabled = vm.browsePath.isNotEmpty()) { vm.browseChoose(purpose) }
        }
    }
}
