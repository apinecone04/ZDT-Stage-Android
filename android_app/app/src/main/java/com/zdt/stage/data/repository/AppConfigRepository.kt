package com.zdt.stage.data.repository

import android.content.Context
import com.zdt.stage.data.model.AppConfig
import com.zdt.stage.data.model.AxisConfig
import com.zdt.stage.data.model.Calibration
import com.zdt.stage.data.model.Preset
import com.zdt.stage.data.model.ScriptPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 全局配置持久化仓储：
 * - 内部私有目录即改即存（无需权限）
 * - 兼容 Android 11 分区存储（Storage Access Framework, SAF）的导入与导出
 */
class AppConfigRepository(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val configFile: File by lazy {
        File(context.filesDir, "zdt_config.json")
    }

    private val _config = MutableStateFlow(loadInternal())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)

    private fun loadInternal(): AppConfig {
        return try {
            if (configFile.exists()) {
                val text = configFile.readText(Charsets.UTF_8)
                json.decodeFromString<AppConfig>(text)
            } else {
                val def = AppConfig.default()
                saveInternal(def)
                def
            }
        } catch (e: Exception) {
            AppConfig.default()
        }
    }

    private fun saveInternal(cfg: AppConfig) {
        try {
            val text = json.encodeToString(cfg)
            configFile.writeText(text, Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    fun updateConfig(update: (AppConfig) -> AppConfig) {
        val newCfg = update(_config.value)
        _config.value = newCfg
        scope.launch {
            saveInternal(newCfg)
        }
    }

    suspend fun exportToStream(out: OutputStream): Boolean = withContext(Dispatchers.IO) {
        try {
            val text = json.encodeToString(_config.value)
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            true
        } catch (e: Exception) {
            false
        } finally {
            try { out.close() } catch (_: Exception) {}
        }
    }

    suspend fun importFromStream(inputStream: InputStream): Boolean = withContext(Dispatchers.IO) {
        try {
            val text = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val newCfg = json.decodeFromString<AppConfig>(text)
            _config.value = newCfg
            saveInternal(newCfg)
            true
        } catch (e: Exception) {
            false
        } finally {
            try { inputStream.close() } catch (_: Exception) {}
        }
    }
}
