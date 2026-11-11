package ru.rusinstaller.core

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.rusinstaller.BuildConfig
import ru.rusinstaller.IFileService
import rikka.shizuku.Shizuku

object ShizukuManager {
    enum class State { NOT_RUNNING, NO_PERMISSION, READY }

    const val REQUEST_CODE = 4242

    @Volatile private var bound: IFileService? = null
    private var pending: CompletableDeferred<IFileService?>? = null

    fun state(): State {
        val running = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        if (!running) return State.NOT_RUNNING
        return try {
            if (!Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
                State.READY else State.NO_PERMISSION
        } catch (_: Throwable) {
            State.NO_PERMISSION
        }
    }

    fun requestPermission() {
        try { Shizuku.requestPermission(REQUEST_CODE) } catch (_: Throwable) {}
    }

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, FileService::class.java.name))
            .daemon(false)
            .processNameSuffix("files")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = if (binder != null && binder.pingBinder()) IFileService.Stub.asInterface(binder) else null
            bound = s
            pending?.complete(s)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = null
        }
    }

    /** Bound privileged file service, or null when Shizuku is unavailable. */
    suspend fun service(): IFileService? {
        bound?.let { if (it.asBinder().pingBinder()) return it }
        if (state() != State.READY) return null
        val d = withContext(Dispatchers.Main) {
            pending?.takeIf { !it.isCompleted } ?: CompletableDeferred<IFileService?>().also {
                pending = it
                try { Shizuku.bindUserService(args, conn) } catch (_: Throwable) { it.complete(null) }
            }
        }
        return withTimeoutOrNull(15_000) { d.await() }
    }
}
