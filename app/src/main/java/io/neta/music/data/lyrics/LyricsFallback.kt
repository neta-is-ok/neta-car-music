package io.neta.music.data.lyrics

import android.content.Context
import android.util.AtomicFile
import android.util.LruCache
import io.neta.music.AppLog
import io.neta.music.data.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** 原平台/本地流程结束后调用；只补歌词，不替换播放源，不把失败长期缓存。 */
internal class LyricsFallback(context: Context) {
    private val directory by lazy { File(context.cacheDir, "online-lyrics") }
    private val memory = object : LruCache<String, OnlineLyricsResult>(1_048_576) {
        override fun sizeOf(key: String, value: OnlineLyricsResult): Int = value.text.length * 2
    }

    suspend fun resolve(song: Song, original: String): String = withContext(Dispatchers.IO) {
        if (LyricText.hasTimedLines(original) || song.isLive || song.name.isBlank()) return@withContext original
        currentCoroutineContext().ensureActive()
        val identity = identity(song)
        memory.get(identity)?.let { return@withContext it.text }
        readCache(song, identity)?.let { cached ->
            memory.put(identity, cached)
            AppLog.i("LyricsFallback", "复用在线歌词缓存：来源=${cached.source}")
            return@withContext cached.text
        }

        AppLog.i("LyricsFallback", "原来源无有效同步歌词，开始在线兜底")
        var attempted = 0
        var failures = 0
        // 六个通用来源各一轮短请求的占用预算，不改变原平台的网络配置或阻塞音频起播。
        val result = withTimeoutOrNull(ONLINE_BUDGET_MS) {
            for (source in OnlineLyricsSources.priority(song)) {
                currentCoroutineContext().ensureActive()
                attempted++
                val candidate = try {
                    OnlineLyricsSources.fetch(song, source)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    failures++
                    null
                }
                if (candidate != null) return@withTimeoutOrNull candidate
            }
            null
        }
        currentCoroutineContext().ensureActive()
        if (result == null) {
            AppLog.i("LyricsFallback", "在线歌词未命中：已查询来源=$attempted，请求异常=$failures")
            return@withContext original
        }
        memory.put(identity, result)
        writeCache(identity, result)
        AppLog.i("LyricsFallback", "在线歌词已命中：来源=${result.source}")
        result.text
    }

    private fun identity(song: Song): String = listOf(
        song.source, song.id, song.name, song.artist, song.album, song.duration,
    ).joinToString("\u0000")

    private fun cacheFile(identity: String): File {
        val key = MessageDigest.getInstance("MD5").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$key.json")
    }

    private fun readCache(song: Song, identity: String): OnlineLyricsResult? {
        return runCatching {
            val file = cacheFile(identity)
            if (!file.isFile || file.length() !in 1L..MAX_CACHE_BYTES) return@runCatching null
            val data = JSONObject(AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            if (data.optInt("v") != 1 || data.optString("identity") != identity) return@runCatching null
            val text = data.optString("text")
            if (!OnlineLyricsSources.accepts(song, text)) return@runCatching null
            OnlineLyricsResult(data.optString("source", "在线缓存"), text)
        }.getOrElse {
            AppLog.w("LyricsFallback", "在线歌词缓存不可读，继续联网检索")
            null
        }
    }

    private fun writeCache(identity: String, result: OnlineLyricsResult) {
        runCatching {
            val data = JSONObject().put("v", 1).put("identity", identity)
                .put("source", result.source).put("text", result.text)
                .toString().toByteArray(Charsets.UTF_8)
            if (data.size.toLong() > MAX_CACHE_BYTES) return
            directory.mkdirs()
            val file = AtomicFile(cacheFile(identity))
            val output = file.startWrite()
            try {
                output.write(data)
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
        }.onFailure { AppLog.w("LyricsFallback", "在线歌词未能写入缓存，本次歌词仍可使用") }
    }

    private companion object {
        const val ONLINE_BUDGET_MS = 24_000L
        const val MAX_CACHE_BYTES = 2_097_152L
    }
}
