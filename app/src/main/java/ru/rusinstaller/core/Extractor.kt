package ru.rusinstaller.core

import android.content.Context
import java.io.File
import java.io.IOException

class ExtractResult(
    /** Human readable format, e.g. "Установщик Inno Setup". */
    val format: String,
    val root: File,
    val warnings: List<String>,
    /** Command-line arguments when the payload is a program that must be run (silent install etc.). */
    val runArgs: String? = null,
    /** Protected Inno Setup installer to run in the built-in Wine; its {app} files (for backup/rollback). */
    val wineEntries: List<InnoEntry>? = null,
)

/** A file the Inno installer will write into the game folder ({app}), path relative to it. */
class InnoEntry(val path: String, val size: Long)

open class ExtractException(msg: String) : IOException(msg)

/** Inno Setup installer with encrypted files: can only be installed by running it (built-in Wine). */
class NeedsWineException(val entries: List<InnoEntry>) : ExtractException("encrypted Inno Setup")

/** The file is a program (patcher / protected installer) that only works when executed. */
class MustRunException(msg: String, val args: String = "") : ExtractException(msg)

object Extractor {
    private val ARCHIVE_EXT = setOf("exe", "zip", "7z", "rar")
    private val IGNORABLE_EXT = setOf("txt", "url", "nfo", "html", "htm", "pdf", "jpg", "png", "ini", "md", "rtf", "lnk")
    private val ARCHIVE_TYPES = setOf(
        "7z", "zip", "rar", "rar5", "nsis", "cab", "tar", "gzip", "bzip2", "xz", "zstd", "lzma", "lzma86",
        "iso", "udf", "wim", "arj", "lzh", "z", "chm", "squashfs", "cpio",
    )

    fun extract(ctx: Context, input: File, outDir: File, log: (String) -> Unit): ExtractResult {
        outDir.deleteRecursively()
        outDir.mkdirs()
        val warnings = ArrayList<String>()
        var format = try {
            extractOne(ctx, input, outDir, log)
        } catch (e: NeedsWineException) {
            log("Файлы внутри зашифрованы — установлю через встроенный Wine")
            return ExtractResult("Защищённый установщик Inno Setup", outDir, emptyList(), wineEntries = e.entries)
        } catch (e: MustRunException) {
            // Nothing to unpack: put the program itself into the game folder so it can be launched there.
            log("Это программа, которую нужно запустить — подготовлю её к запуску в эмуляторе")
            outDir.deleteRecursively()
            outDir.mkdirs()
            // Wine command lines are fragile with spaces / non-ASCII names
            val safe = if (input.name.all { it.code in 33..126 }) input.name else "ruarm_setup.exe"
            input.copyTo(File(outDir, safe))
            return ExtractResult("Программа-установщик", outDir, listOf(e.message ?: ""), e.args)
        }

        // Russifiers are often shipped as "zip with the real installer inside" — unwrap up to two levels.
        // Only for plain archives: the payload of an installer is final (a lone exe there is the game file itself).
        for (level in 0 until 2) {
            if (!format.startsWith("Архив")) break
            val inner = singleNestedPackage(outDir) ?: break
            log("Внутри найден ${inner.name} — пробую распаковать и его…")
            val nested = File(outDir.parentFile, "nested$level")
            nested.deleteRecursively()
            nested.mkdirs()
            val f = try {
                extractOne(ctx, inner, nested, log)
            } catch (e: ExtractException) {
                log("${inner.name} — не архив, оставляю как есть")
                nested.deleteRecursively()
                break
            }
            outDir.deleteRecursively()
            if (!nested.renameTo(outDir)) throw ExtractException("Не удалось подготовить временную папку")
            format = "$format → $f"
        }

        val all = outDir.walkTopDown().filter { it.isFile }.toList()
        if (all.isEmpty()) throw ExtractException("Файл распакован, но внутри пусто.")
        val names = all.map { it.name.lowercase() }
        if (names.any { it.endsWith(".xdelta") || it.endsWith(".vcdiff") || it.endsWith(".xdelta3") || it.startsWith("xdelta") }) {
            warnings += "Русификатор использует патчи xdelta — их применяет сам установщик. " +
                "Простое копирование может не сработать: поищи версию русификатора без патчера."
        }
        if (names.any { it.endsWith(".bat") || it.endsWith(".cmd") }) {
            warnings += "Внутри есть .bat-скрипты — они не будут запущены, только скопированы."
        }
        return ExtractResult(format, outDir, warnings)
    }

    private fun extractOne(ctx: Context, input: File, out: File, log: (String) -> Unit): String {
        val isExe = input.inputStream().use { s -> ByteArray(2).also { s.read(it) } }
            .contentEquals(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte()))

        if (isExe) {
            log("Проверяю, не установщик ли это Inno Setup…")
            val list = NativeTools.run(ctx, NativeTools.INNOEXTRACT, listOf("--color=0", "--progress=0", "-l", input.absolutePath))
            if (list.code == 0 && list.output.lines().any { it.trimEnd().endsWith("- encrypted") }) {
                throw NeedsWineException(parseInnoList(list.output))
            }
            val r = NativeTools.run(
                ctx, NativeTools.INNOEXTRACT,
                listOf("--color=0", "--progress=0", "-q", "-e", "-d", out.absolutePath, input.absolutePath),
            ) { log(it) }
            if (r.code == 0) return "Установщик Inno Setup"
            val notInno = r.output.contains("Not a supported Inno Setup installer", true) ||
                r.output.contains("not a supported", true)
            if (!notInno) {
                throw ExtractException(
                    "Это установщик Inno Setup, но распаковать его не получилось:\n" +
                        r.output.lines().filter { it.isNotBlank() }.takeLast(4).joinToString("\n")
                )
            }
            out.deleteRecursively()
            out.mkdirs()
        }

        log("Читаю содержимое через 7-Zip…")
        val info = NativeTools.run(ctx, NativeTools.SEVEN_ZIP, listOf("l", "-slt", "-sccUTF-8", "-p_nopass_", input.absolutePath))
        val header = info.output.substringBefore("\n----------")
        val types = Regex("""(?m)^Type = (.+)$""").findAll(header).map { it.groupValues[1].trim().lowercase() }.toList()
        val type = types.firstOrNull { it in ARCHIVE_TYPES }
        if (type == null) {
            if (info.output.contains("Wrong password", true) || info.output.contains("encrypted", true))
                throw ExtractException("Архив защищён паролем — такие пока не поддерживаются.")
            if (isExe) throw MustRunException(
                "Это программа-патчер, а не архив: перевод появляется, только когда её запускают. " +
                    "Её нужно один раз запустить в GameNative — программа подскажет как."
            )
            throw ExtractException("Не похоже на архив (${input.name}).")
        }

        log("Распаковываю (${type.uppercase()})…")
        val r = NativeTools.run(
            ctx, NativeTools.SEVEN_ZIP,
            listOf("x", "-y", "-bd", "-bb0", "-sccUTF-8", "-p_nopass_", "-o${out.absolutePath}", input.absolutePath),
        ) { line -> if (!line.startsWith("Extracting") && line.length < 200) log(line) }
        if (r.output.contains("Wrong password", true)) throw ExtractException("Архив защищён паролем — такие пока не поддерживаются.")
        if (r.code >= 2) {
            throw ExtractException("7-Zip не смог распаковать файл:\n" +
                r.output.lines().filter { it.isNotBlank() }.takeLast(4).joinToString("\n"))
        }
        return when {
            type == "nsis" -> "Установщик NSIS"
            isExe -> "Самораспаковывающийся архив (${type.uppercase()})"
            else -> "Архив ${type.uppercase()}"
        }
    }

    /** `innoextract -l` lines like ` - "app/GameData/data000.cpk" (2.44 GiB) - encrypted` -> {app} entries. */
    private fun parseInnoList(out: String): List<InnoEntry> {
        val re = Regex("""^ - "(.+?)"(?: \[[^\]]*])? \(([\d.]+) (B|KiB|MiB|GiB)\)""")
        val mult = mapOf("B" to 1.0, "KiB" to 1024.0, "MiB" to 1048576.0, "GiB" to 1073741824.0)
        return out.lines().mapNotNull { l ->
            val m = re.find(l) ?: return@mapNotNull null
            val path = m.groupValues[1]
            if (!path.startsWith("app/")) return@mapNotNull null
            InnoEntry(path.removePrefix("app/"), (m.groupValues[2].toDouble() * mult.getValue(m.groupValues[3])).toLong())
        }.distinctBy { it.path.lowercase() }
    }

    /** If the extracted content is just one exe/archive (+ maybe readme), return it. */
    private fun singleNestedPackage(dir: File): File? {
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        val meaningful = files.filter { it.extension.lowercase() !in IGNORABLE_EXT }
        val only = meaningful.singleOrNull() ?: return null
        return only.takeIf { it.extension.lowercase() in ARCHIVE_EXT && it.length() > 1024 }
    }
}
