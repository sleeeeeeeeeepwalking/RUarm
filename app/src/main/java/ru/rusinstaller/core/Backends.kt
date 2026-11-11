package ru.rusinstaller.core

import android.os.ParcelFileDescriptor
import ru.rusinstaller.IFileService
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

data class Node(val name: String, val isDir: Boolean, val size: Long = 0)

/** File access for game folders. Paths are absolute, '/' separated. */
interface FileBackend {
    fun list(path: String): List<Node>?
    /** Recursive listing; Node.name is the path relative to root. */
    fun listTree(root: String, maxDepth: Int, maxEntries: Int): List<Node>
    fun exists(path: String): Boolean
    fun isDir(path: String): Boolean
    fun mkdirs(path: String): Boolean
    fun writeFrom(src: File, dst: String)
    fun readTo(src: String, dst: File)
    fun delete(path: String): Boolean
}

class NeedShizukuException : IOException(
    "Эта папка находится в Android/data. Чтобы работать с ней, включи Shizuku (кнопка на главном экране)."
)

fun isPrivilegedPath(path: String): Boolean =
    path.contains("/Android/data/") || path.contains("/Android/obb/") ||
        path.endsWith("/Android/data") || path.endsWith("/Android/obb")

object DirectBackend : FileBackend {
    override fun list(path: String): List<Node>? =
        File(path).listFiles()?.map { Node(it.name, it.isDirectory, if (it.isFile) it.length() else 0) }

    override fun listTree(root: String, maxDepth: Int, maxEntries: Int): List<Node> {
        val out = ArrayList<Node>()
        fun walk(dir: File, rel: String, depth: Int) {
            val kids = dir.listFiles() ?: return
            for (k in kids) {
                if (out.size >= maxEntries) return
                val r = if (rel.isEmpty()) k.name else "$rel/${k.name}"
                val d = k.isDirectory
                out += Node(r, d, if (d) 0 else k.length())
                if (d && depth < maxDepth) walk(k, r, depth + 1)
            }
        }
        walk(File(root), "", 1)
        return out
    }

    override fun exists(path: String) = File(path).exists()
    override fun isDir(path: String) = File(path).isDirectory
    override fun mkdirs(path: String) = File(path).let { it.isDirectory || it.mkdirs() }

    override fun writeFrom(src: File, dst: String) {
        val f = File(dst)
        f.parentFile?.mkdirs()
        FileInputStream(src).use { i -> FileOutputStream(f).use { o -> i.copyTo(o, 1 shl 20) } }
    }

    override fun readTo(src: String, dst: File) {
        dst.parentFile?.mkdirs()
        FileInputStream(src).use { i -> FileOutputStream(dst).use { o -> i.copyTo(o, 1 shl 20) } }
    }

    override fun delete(path: String) = File(path).delete()
}

class ShizukuBackend(private val s: IFileService) : FileBackend {
    private fun parse(e: String): Node {
        val p = e.split('|')
        return Node(p[1], p[0] == "d", p.getOrNull(2)?.toLongOrNull() ?: 0)
    }

    override fun list(path: String) = s.listDir(path)?.map(::parse)
    override fun listTree(root: String, maxDepth: Int, maxEntries: Int) =
        (s.listTree(root, maxDepth, maxEntries) ?: emptyArray()).map(::parse)
    override fun exists(path: String) = s.exists(path)
    override fun isDir(path: String) = s.isDir(path)
    override fun mkdirs(path: String) = s.mkdirs(path)

    override fun writeFrom(src: File, dst: String) {
        val err = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY).use { s.writeFile(dst, it) }
        if (err != null) throw IOException(err)
    }

    override fun readTo(src: String, dst: File) {
        val pfd = s.openRead(src) ?: throw IOException("Не удалось прочитать $src")
        dst.parentFile?.mkdirs()
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { i ->
            FileOutputStream(dst).use { o -> i.copyTo(o, 1 shl 20) }
        }
    }

    override fun delete(path: String) = s.delete(path)
}

object Backends {
    /** Backend able to access [path]; binds the Shizuku service when needed. */
    suspend fun forPath(path: String): FileBackend =
        if (isPrivilegedPath(path)) {
            ShizukuBackend(ShizukuManager.service() ?: throw NeedShizukuException())
        } else DirectBackend
}
