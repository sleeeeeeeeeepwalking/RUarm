package ru.rusinstaller

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import rikka.shizuku.Shizuku
import ru.rusinstaller.ui.App
import ru.rusinstaller.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, _ -> vm.refreshPermissions() }
    private val shizukuAlive = Shizuku.OnBinderReceivedListener { runOnUiThread { vm.refreshPermissions() } }
    private val shizukuDead = Shizuku.OnBinderDeadListener { runOnUiThread { vm.refreshPermissions() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 28 (needed for the built-in Wine): classic storage permission on top of "all files access"
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
        Shizuku.addRequestPermissionResultListener(shizukuListener)
        Shizuku.addBinderReceivedListenerSticky(shizukuAlive)
        Shizuku.addBinderDeadListener(shizukuDead)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!vm.back()) finish()
            }
        })

        setContent {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) { App(vm) }
        }
        if (savedInstanceState == null) vm.handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPermissions()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuListener)
        Shizuku.removeBinderReceivedListener(shizukuAlive)
        Shizuku.removeBinderDeadListener(shizukuDead)
        super.onDestroy()
    }

    /** Gamepad: A = select, B = back (Retroid Pocket and other Android handhelds). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val mapped = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_DPAD_CENTER
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BACK
            else -> return super.dispatchKeyEvent(event)
        }
        return super.dispatchKeyEvent(
            KeyEvent(event.downTime, event.eventTime, event.action, mapped, event.repeatCount,
                event.metaState, event.deviceId, event.scanCode, event.flags, event.source)
        )
    }
}
