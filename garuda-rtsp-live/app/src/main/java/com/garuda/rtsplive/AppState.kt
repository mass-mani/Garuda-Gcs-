package com.garuda.rtsplive

import android.app.Application
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

private val Context.settings by preferencesDataStore("garuda_encrypted")

data class LiveState(
    val status: String = "STOPPED",
    val detail: String = "Ready for RTSP camera",
    val active: Boolean = false,
    val testing: Boolean = false,
    val camera: Boolean = false,
    val destination: Boolean = false,
    val info: VideoInfo = VideoInfo(),
    val seconds: Long = 0,
    val bitrate: Double = 0.0,
    val bytes: Long = 0,
    val retries: Int = 0,
    val errors: Int = 0,
    val network: String = "Checking networks",
    val compatibility: Boolean = false,
    val viewerUrl: String = "",
)

class GarudaApp : Application() {
    lateinit var repo: Repository
        private set

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        repo = Repository(this)
        androidx.media3.common.util.Log.setLogger(
            object : androidx.media3.common.util.Log.Logger {
                override fun d(tag: String, message: String, throwable: Throwable?) {}

                override fun i(tag: String, message: String, throwable: Throwable?) {}

                override fun w(tag: String, message: String, throwable: Throwable?) {
                    SafeMessages.native(message)?.let { repo.log("Preview: $it") }
                }

                override fun e(tag: String, message: String, throwable: Throwable?) {
                    repo.log("Preview media error; test the camera connection")
                }
            }
        )
    }
}

class Repository(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val config = MutableStateFlow(Config())
    val loaded = MutableStateFlow(false)
    val state = MutableStateFlow(LiveState())
    val logs = MutableStateFlow<List<String>>(emptyList())
    val networks = Networks(context)
    private val pref = stringPreferencesKey("encrypted_config_v1")
    private val saveLock = kotlinx.coroutines.sync.Mutex()

    init {
        scope.launch {
            try {
                context.settings.data.first()[pref]?.let { encrypted ->
                    val stored = decode(decrypt(encrypted))
                    val current = ConfigMigrations.migrateLegacyTwitchPreset(stored)
                    config.value = current
                    if (current != stored) {
                        context.settings.edit { it[pref] = encrypt(encode(current)) }
                    }
                }
            } catch (_: Exception) {
                log("Settings could not be unlocked. Re-enter connection details.")
            }
            loaded.value = true
        }
        scope.launch {
            networks.description.collect { n ->
                state.update {
                    it.copy(
                        network = n,
                        viewerUrl =
                            if (it.active && config.value.localServer)
                                config.value.localViewerUrl(networks.wifiIpv4()).orEmpty()
                            else it.viewerUrl,
                    )
                }
            }
        }
    }

    fun update(c: Config) {
        config.value = c
        scope.launch(Dispatchers.IO) {
            saveLock.lock()
            try {
                context.settings.edit { it[pref] = encrypt(encode(config.value)) }
            } catch (_: Exception) {
                log("Could not save encrypted settings")
            } finally {
                saveLock.unlock()
            }
        }
    }

    @Synchronized
    fun log(s: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + s.take(350)
        if (logs.value.lastOrNull()?.substringAfter("  ") == s.take(350)) return
        logs.value = (logs.value + line).takeLast(200)
    }

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("garuda.settings.v1", null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            "garuda.settings.v1",
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }

    private fun encrypt(s: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        return Base64.encodeToString(
            cipher.iv + cipher.doFinal(s.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
    }

    private fun decrypt(s: String): String {
        val b = Base64.decode(s, Base64.NO_WRAP)
        require(b.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
        return String(cipher.doFinal(b.copyOfRange(12, b.size)), Charsets.UTF_8)
    }

    private fun encode(c: Config) =
        JSONObject()
            .put("source", c.source)
            .put("server", c.server)
            .put("key", c.key)
            .put("full", c.fullUrl)
            .put("combined", c.combined)
            .put("mode", c.mode.name)
            .put("resolution", c.resolution)
            .put("fps", c.fps)
            .put("bitrate", c.bitrate)
            .put("genpts", c.genpts)
            .put("cameraModel", c.cameraModel)
            .put("localServer", c.localServer)
            .put("streamPath", c.streamPath)
            .put("viewerUrl", c.viewerUrl)
            .toString()

    private fun decode(s: String): Config {
        val j = JSONObject(s)
        return ConfigMigrations.migrateCameraModel(
            Config(
                source = j.getString("source"),
                server = j.getString("server"),
                key = j.getString("key"),
                fullUrl = j.optString("full"),
                combined = j.optBoolean("combined"),
                mode = StreamMode.valueOf(j.optString("mode", "AUTO")),
                resolution = j.optString("resolution", "Original"),
                fps = j.optInt("fps"),
                bitrate = j.optInt("bitrate", 4),
                genpts = j.optBoolean("genpts"),
                cameraModel = j.optString("cameraModel", CameraProfiles.SKYDROID_C12),
                localServer = j.optBoolean("localServer", true),
                streamPath = j.optString("streamPath", "drone0"),
                viewerUrl = j.optString("viewerUrl"),
            ),
            j.has("cameraModel"),
        )
    }

}
