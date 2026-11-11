package ru.rusinstaller.core

import android.content.Context

/** Runs bundled arm64 command-line tools (Termux builds of 7-Zip and innoextract). */
object NativeTools {
    const val SEVEN_ZIP = "lib7zz.so"
    const val INNOEXTRACT = "libinnoextract.so"

    class Result(val code: Int, val output: String)

    fun run(ctx: Context, exe: String, args: List<String>, onLine: ((String) -> Unit)? = null): Result {
        val dir = ctx.applicationInfo.nativeLibraryDir
        val pb = ProcessBuilder(listOf("$dir/$exe") + args).redirectErrorStream(true)
        pb.environment().apply {
            put("LD_LIBRARY_PATH", dir)
            put("TMPDIR", ctx.cacheDir.absolutePath)
            put("HOME", ctx.filesDir.absolutePath)
            put("LANG", "C.UTF-8")
            put("LC_ALL", "C.UTF-8")
        }
        pb.directory(ctx.cacheDir)
        val p = pb.start()
        // No interactive prompts (e.g. a password request): close stdin so the tool fails instead of hanging.
        p.outputStream.close()
        val sb = StringBuilder()
        p.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { raw ->
                val line = raw.substringAfterLast('\r').trimEnd()
                if (line.isEmpty()) return@forEach
                if (sb.length < 300_000) sb.appendLine(line)
                onLine?.invoke(line)
            }
        }
        return Result(p.waitFor(), sb.toString())
    }
}
