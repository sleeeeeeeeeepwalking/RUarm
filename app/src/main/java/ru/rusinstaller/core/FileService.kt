package ru.rusinstaller.core

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import ru.rusinstaller.IFileService
import java.io.File
import java.io.FileOutputStream
import kotlin.system.exitProcess

/** Shizuku user service: plain java.io running with shell privileges (can reach Android/data). */
class FileService() : IFileService.Stub() {

    @Keep
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    override fun destroy() {
        exitProcess(0)
    }

    override fun listDir(path: String): Array<String>? =
        File(path).listFiles()
            ?.map { if (it.isDirectory) "d|${it.name}" else "f|${it.name}|${it.length()}" }
            ?.toTypedArray()

    override fun listTree(root: String, maxDepth: Int, maxEntries: Int): Array<String> {
        val out = ArrayList<String>()
        fun walk(dir: File, rel: String, depth: Int) {
            val kids = dir.listFiles() ?: return
            for (k in kids) {
                if (out.size >= maxEntries) return
                val r = if (rel.isEmpty()) k.name else "$rel/${k.name}"
                if (k.isDirectory) {
                    out += "d|$r"
                    if (depth < maxDepth) walk(k, r, depth + 1)
                } else out += "f|$r|${k.length()}"
            }
        }
        walk(File(root), "", 1)
        return out.toTypedArray()
    }

    override fun exists(path: String) = File(path).exists()
    override fun isDir(path: String) = File(path).isDirectory
    override fun mkdirs(path: String) = File(path).let { it.isDirectory || it.mkdirs() }

    override fun writeFile(path: String, src: ParcelFileDescriptor): String? = try {
        val f = File(path)
        f.parentFile?.mkdirs()
        ParcelFileDescriptor.AutoCloseInputStream(src).use { i ->
            FileOutputStream(f).use { o -> i.copyTo(o, 1 shl 20) }
        }
        null
    } catch (t: Throwable) {
        "${t.javaClass.simpleName}: ${t.message}"
    }

    override fun openRead(path: String): ParcelFileDescriptor? = try {
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
    } catch (t: Throwable) {
        null
    }

    override fun delete(path: String) = File(path).delete()
}
