package dev.salt.replica

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.salt.replica.playback.PlayerStore
import dev.salt.replica.ui.PlayerApp
import dev.salt.replica.ui.ReplicaSettings

class MainActivity : ComponentActivity() {
    private lateinit var store: PlayerStore
    private lateinit var settings: ReplicaSettings
    private var incoming by mutableStateOf<android.net.Uri?>(null)
    private var qa by mutableStateOf<QaRequest?>(null)
    private var syncRequest by mutableLongStateOf(0L)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = PlayerStore(applicationContext)
        settings = ReplicaSettings(applicationContext)
        incoming = intent.data
        qa = QaRequest.from(intent)
        if (intent.getBooleanExtra("open_sync", false)) syncRequest = System.nanoTime()
        setContent {
            LaunchedEffect(Unit) { dev.salt.replica.sync.LibraryRegistry.revision.collect { store.load() } }
            LaunchedEffect(incoming) { incoming?.let { store.importAudio(listOf(it)); incoming = null } }
            LaunchedEffect(qa, store.ready, store.tracks.size) {
                qa?.takeIf { store.ready && store.tracks.isNotEmpty() }?.let {
                    store.seek(it.position); store.playWhenReady(it.playing)
                    settings.fontSize = 20f; settings.fontWeight = 200
                    settings.flowing = it.flowing; settings.blur = it.blur
                }
            }
            LifecycleEventEffect(Lifecycle.Event.ON_STOP) { store.save(); settings.save() }
            PlayerApp(store, settings, qa, syncRequest)
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); incoming = intent.data; qa = QaRequest.from(intent); if (intent.getBooleanExtra("open_sync", false)) syncRequest = System.nanoTime() }
    override fun onDestroy() { store.release(); super.onDestroy() }
}
