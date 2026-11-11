package io.neta.music.data.lyrics

import android.util.Base64
import io.neta.music.data.Song
import io.neta.music.data.native.NativeHttp
import io.neta.music.data.native.mergeLyricLayers
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

internal data class OnlineLyricsResult(val source: String, val text: String)

/**
 * 移植自 Netamade feature/lyrics/LyricsRepository 的公开在线查询与评分。
 * 不包含 ROOT、其他应用缓存、扫描及 TLS 豁免；每个 HTTP 调用由 NativeHttp 接收取消。
 */
internal object OnlineLyricsSources {
    private const val MINIMUM_ONLINE_MATCH_SCORE = 100.0
    private const val REQUEST_TIMEOUT_MS = 4_000L
    private val SODA_TRACK_ID = Regex("(?:^|\\D)(\\d{6,})(?:$|\\D)")

    fun priority(song: Song): List<String> = when (song.source) {
        "qq" -> listOf("QQ音乐", "酷狗", "酷狗移动端", "网易云", "酷我", "LrcLib")
        "kugou" -> listOf("酷狗", "酷狗移动端", "QQ音乐", "网易云", "酷我", "LrcLib")
        "kuwo" -> listOf("酷我", "酷狗", "酷狗移动端", "QQ音乐", "网易云", "LrcLib")
        "soda" -> listOf("汽水音乐", "网易云", "QQ音乐", "酷狗", "酷狗移动端", "酷我", "LrcLib")
        else -> listOf("网易云", "QQ音乐", "酷狗", "酷狗移动端", "酷我", "LrcLib")
    }

    suspend fun fetch(song: Song, name: String): OnlineLyricsResult? {
        val result = when (name) {
            "汽水音乐" -> soda(song)
            "QQ音乐" -> qq(song)
            "网易云" -> netease(song)
            "酷狗" -> kugou(song)
            "酷狗移动端" -> kugouMobile(song)
            "酷我" -> kuwo(song)
            else -> lrclib(song)
        }
        return result?.copy(text = LyricText.normalize(result.text))?.takeIf { accepts(song, it.text) }
    }

    private suspend fun qq(song: Song): OnlineLyricsResult? {
        val root = JSONObject(get("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?format=json&n=6&w=${enc(song.title + " " + song.artist)}", mapOf("Referer" to "https://y.qq.com/")))
            .optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list") ?: return null
        var id: String? = null
        var best = -1.0
        for (i in 0 until root.length()) {
            val item = root.optJSONObject(i) ?: continue
            val artist = item.optJSONArray("singer")?.optJSONObject(0)?.optString("name", "") ?: ""
            val score = score(song, item.optString("songname"), artist, item.optLong("interval") * 1000)
            if (score >= MINIMUM_ONLINE_MATCH_SCORE && score > best) {
                best = score
                id = item.optString("songmid", item.optString("mid", ""))
            }
        }
        if (id.isNullOrBlank()) return null
        val body = JSONObject(get("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=${enc(id)}&g_tk=5381&format=json&loginUin=0&hostUin=0&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0", mapOf("Referer" to "https://y.qq.com/")))
        val encoded = body.optString("lyric", "")
        val lyric = encoded.takeIf { it.isNotBlank() }?.let { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }
        val translation = body.optString("trans").takeIf(String::isNotBlank)?.let {
            runCatching { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault("")
        }.orEmpty()
        return if (lyric != null && looksLikeLrc(lyric)) {
            OnlineLyricsResult("QQ音乐", mergeLyricLayers(lyric, translation))
        } else translation.takeIf(::looksLikeLrc)?.let { OnlineLyricsResult("QQ音乐", it) }
    }

    private suspend fun netease(song: Song): OnlineLyricsResult? {
        platformTrackId(song, "netease")?.let { id ->
            neteaseById(id)?.let { return OnlineLyricsResult("网易云", it) }
        }
        // 沿用美式已采用的 Web 搜索入口，不回到旧 PC 搜索接口。
        val body = post(
            "https://music.163.com/api/search/get/web",
            mapOf("s" to (song.title + " " + song.artist), "type" to "1", "limit" to "10", "offset" to "0"),
            mapOf("Referer" to "https://music.163.com/"),
        )
        val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs") ?: return null
        var id = -1L
        var best = -1.0
        for (i in 0 until songs.length()) {
            val item = songs.optJSONObject(i) ?: continue
            val artist = item.optJSONArray("artists")?.optJSONObject(0)?.optString("name", "") ?: ""
            val score = score(song, item.optString("name"), artist, item.optLong("duration"))
            if (score >= MINIMUM_ONLINE_MATCH_SCORE && score > best) {
                best = score
                id = item.optLong("id", -1L)
            }
        }
        if (id < 0) return null
        return neteaseById(id)?.let { OnlineLyricsResult("网易云", it) }
    }

    private suspend fun neteaseById(id: Long): String? {
        val result = JSONObject(
            get(
                "https://music.163.com/api/song/lyric?id=$id&lv=-1&kv=-1&tv=-1&yv=-1&rv=-1",
                mapOf("Referer" to "https://music.163.com/"),
            ),
        )
        val lyric = result.optJSONObject("lrc")?.optString("lyric", "") ?: ""
        val fallback = result.optJSONObject("tlyric")?.optString("lyric", "") ?: ""
        val romaji = result.optJSONObject("rlyric")?.optString("lyric", "").orEmpty()
        return if (looksLikeLrc(lyric)) mergeLyricLayers(lyric, fallback, romaji) else fallback.takeIf(::looksLikeLrc)
    }

    private suspend fun kugou(song: Song): OnlineLyricsResult? {
        return kugouLyrics(song)?.let { OnlineLyricsResult("酷狗", it) }
    }

    /** 歌曲 hash 只用于检索；下载必须使用歌词候选返回的 ID 和 accesskey。 */
    private suspend fun kugouLyrics(song: Song, hash: String? = null): String? {
        val hashParam = hash?.let { "&hash=${enc(it)}" }.orEmpty()
        // 实际接口对“歌手 - 歌名”能给出歌手字段；只传歌名时可能把歌名重复到 singer。
        val keyword = listOf(song.artist, song.title).filter(String::isNotBlank).joinToString(" - ")
        val candidates = JSONObject(
            get(
                "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=${enc(keyword)}&duration=${song.duration.toLong() * 1_000L}$hashParam",
                mapOf("User-Agent" to "Mozilla/5.0"),
            ),
        ).optJSONArray("candidates") ?: return null
        var id = ""
        var access = ""
        var best = -1.0
        for (i in 0 until candidates.length()) {
            val item = candidates.optJSONObject(i) ?: continue
            val score = score(song, item.optString("song"), item.optString("singer"), item.optLong("duration", 0L))
            if (score >= MINIMUM_ONLINE_MATCH_SCORE && score > best) {
                best = score
                id = item.optString("id")
                access = item.optString("accesskey")
            }
        }
        if (id.isBlank() || access.isBlank()) return null
        val response = JSONObject(
            get(
                "https://lyrics.kugou.com/download?ver=1&client=pc&id=${enc(id)}&accesskey=${enc(access)}&fmt=lrc&charset=utf8",
                mapOf("User-Agent" to "Mozilla/5.0"),
            ),
        )
        // 酷狗下载接口返回顶层 content，其中保存 Base64 编码的完整 LRC。
        val encoded = response.optString("content").takeIf(String::isNotBlank) ?: return null
        return String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8).takeIf(::looksLikeLrc)
    }

    /** 移动端在 PC 检索没有合适候选时提供另一条已匹配歌曲 hash 的检索路径。 */
    private suspend fun kugouMobile(song: Song): OnlineLyricsResult? {
        val candidates = JSONObject(
            get(
                "https://mobilecdn.kugou.com/api/v3/search/song?keyword=${enc(song.title + " " + song.artist)}&page=1&pagesize=6&showtype=1",
                mapOf("User-Agent" to "Mozilla/5.0"),
            ),
        ).optJSONObject("data")?.optJSONArray("info") ?: return null
        var hash = ""
        var best = -1.0
        for (i in 0 until candidates.length()) {
            val item = candidates.optJSONObject(i) ?: continue
            val rawDuration = item.optLong("duration", 0L)
            val durationMs = when {
                rawDuration in 1..9_999L -> rawDuration * 1_000L
                rawDuration > 0L -> rawDuration
                else -> item.optLong("timelength", 0L)
            }
            val score = score(song, item.optString("songname"), item.optString("singername"), durationMs)
            if (score >= MINIMUM_ONLINE_MATCH_SCORE && score > best) {
                best = score
                hash = item.optString("hash")
            }
        }
        if (hash.isBlank()) return null
        return kugouLyrics(song, hash)?.let { OnlineLyricsResult("酷狗移动端", it) }
    }

    private suspend fun kuwo(song: Song): OnlineLyricsResult? {
        platformTrackId(song, "kuwo")?.let { id ->
            kuwoById(id.toString())?.let { return OnlineLyricsResult("酷我", it) }
        }
        val list = JSONObject(get("http://search.kuwo.cn/r.s?all=${enc(song.title + " " + song.artist)}&ft=music&itemset=web_2013&client=kt&pn=0&rn=6&rformat=json", mapOf("Referer" to "http://www.kuwo.cn/"))).optJSONArray("abslist") ?: return null
        var id = ""; var best = -1.0
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val score = score(song, item.optString("SONGNAME"), item.optString("ARTIST"), 0)
            if (score >= MINIMUM_ONLINE_MATCH_SCORE && score > best) {
                best = score
                id = item.optString("MUSICRID").replace("MUSIC_", "")
            }
        }
        if (id.isBlank()) return null
        return kuwoById(id)?.let { OnlineLyricsResult("酷我", it) }
    }

    private suspend fun kuwoById(id: String): String? {
        val rows = JSONObject(get("https://www.kuwo.cn/openapi/v1/www/lyric/getlyric?musicId=${enc(id)}", mapOf("Referer" to "http://www.kuwo.cn/"))).optJSONObject("data")?.optJSONArray("lrclist") ?: return null
        val lyric = buildString { for (i in 0 until rows.length()) { val row = rows.optJSONObject(i) ?: continue; val time = row.optDouble("time", 0.0); append(String.format(Locale.ROOT, "[%02d:%02d.%03d]%s\n", (time / 60).toInt(), (time % 60).toInt(), ((time - kotlin.math.floor(time)) * 1000).toInt(), row.optString("lineLyric", ""))) } }
        return lyric.takeIf(::looksLikeLrc)
    }

    /** 汽水 ID 只在本平台歌曲时直查分享页，缺失时按元数据搜索。 */
    private suspend fun soda(song: Song): OnlineLyricsResult? {
        val directId = if (song.source == "soda") {
            sodaTrackId(song.extra["track_id"]?.takeIf(String::isNotBlank) ?: song.id)
        } else null
        directId?.let { id -> sodaById(id)?.let { return OnlineLyricsResult("汽水音乐", it) } }
        val searchedId = sodaSearch(song) ?: return null
        if (searchedId == directId) return null
        return sodaById(searchedId)?.let { OnlineLyricsResult("汽水音乐", it) }
    }

    private suspend fun sodaSearch(song: Song): String? {
        val query = listOf(song.title, song.artist).filter(String::isNotBlank).joinToString(" ")
        if (query.isBlank()) return null
        val response = get(
            "https://api.qishui.com/luna/pc/search/track?q=${enc(query)}&cursor=0&search_method=input&aid=386088&device_platform=web&channel=pc_web",
            mapOf("Referer" to "https://www.qishui.com/"),
        )
        val groups = JSONObject(response).optJSONArray("result_groups") ?: return null
        var bestId: String? = null
        var bestScore = -1.0
        for (groupIndex in 0 until groups.length()) {
            val tracks = groups.optJSONObject(groupIndex)?.optJSONArray("data") ?: continue
            for (trackIndex in 0 until tracks.length()) {
                val track = tracks.optJSONObject(trackIndex)?.optJSONObject("entity")
                    ?.optJSONObject("track") ?: continue
                val id = track.optString("id").takeIf(String::isNotBlank) ?: continue
                val artists = track.optJSONArray("artists")
                    ?.let { array ->
                        (0 until array.length()).mapNotNull { index ->
                            array.optJSONObject(index)?.optString("name")?.takeIf(String::isNotBlank)
                        }.joinToString("/")
                    }.orEmpty()
                val score = score(song, track.optString("name"), artists, track.optLong("duration", 0L))
                if (score >= MINIMUM_ONLINE_MATCH_SCORE && score > bestScore) {
                    bestId = id
                    bestScore = score
                }
            }
        }
        return bestId
    }

    private suspend fun sodaById(trackId: String): String? = sodaSentencesToLrc(
        get("https://www.qishui.com/share/track?track_id=${enc(trackId)}", mapOf("Referer" to "https://www.qishui.com/")),
    )

    private fun sodaSentencesToLrc(page: String): String? = runCatching {
        val routerData = page.indexOf("_ROUTER_DATA")
        val valueStart = page.indexOf('=', startIndex = (routerData + "_ROUTER_DATA".length).coerceAtLeast(0))
        val jsonStart = page.indexOf('{', startIndex = (valueStart + 1).coerceAtLeast(0))
        if (routerData < 0 || valueStart < 0 || jsonStart < 0) return@runCatching null
        val jsonEnd = jsonObjectEnd(page, jsonStart) ?: return@runCatching null
        val sentences = JSONObject(page.substring(jsonStart, jsonEnd + 1))
            .optJSONObject("loaderData")
            ?.optJSONObject("track_page")
            ?.optJSONObject("audioWithLyricsOption")
            ?.optJSONObject("lyrics")
            ?.optJSONArray("sentences") ?: return@runCatching null
        buildString {
            for (index in 0 until sentences.length()) {
                val sentence = sentences.optJSONObject(index) ?: continue
                val startMs = sentence.optLong("startMs", -1L)
                if (startMs < 0L) continue
                val text = sentence.optString("text").ifBlank {
                    sentence.optJSONArray("words")?.let { words ->
                        (0 until words.length()).joinToString("") { wordIndex ->
                            words.optJSONObject(wordIndex)?.optString("text")
                                ?: words.optJSONObject(wordIndex)?.optString("word").orEmpty()
                        }
                    }.orEmpty()
                }.trim()
                if (text.isNotBlank()) {
                    append("[%02d:%02d.%03d]%s\n".format(
                        Locale.ROOT,
                        startMs / 60_000L,
                        (startMs / 1_000L) % 60L,
                        startMs % 1_000L,
                        text,
                    ))
                }
            }
        }.takeIf(::looksLikeLrc)
    }.getOrNull()

    private fun sodaTrackId(value: String?): String? =
        value?.let(SODA_TRACK_ID::find)?.groupValues?.getOrNull(1)

    private fun jsonObjectEnd(text: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            when (text[index]) {
                '\\' -> if (inString) escaped = !escaped
                '"' -> if (!escaped) inString = !inString else escaped = false
                '{' -> if (!inString) depth++
                '}' -> if (!inString && --depth == 0) return index
                else -> escaped = false
            }
        }
        return null
    }

    private suspend fun lrclib(song: Song): OnlineLyricsResult? {
        val headers = mapOf("Lrclib-Client" to "NetaMusic (online lyrics fallback)")
        val first = pickLrc(song, get("https://lrclib.net/api/search?track_name=${enc(song.title)}&artist_name=${enc(song.artist)}", headers))
        val lyric = first ?: pickLrc(song, get("https://lrclib.net/api/search?q=${enc(song.title + " " + song.artist)}", headers))
        return lyric?.let { OnlineLyricsResult("LrcLib", it) }
    }

    private fun pickLrc(song: Song, text: String): String? = runCatching {
        val array = JSONArray(text)
        (0 until array.length()).asSequence().mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            if (item.optBoolean("instrumental", false)) return@mapNotNull null
            val title = item.optString("trackName", item.optString("name", ""))
            val artist = item.optString("artistName", item.optString("artist", ""))
            val rawDuration = item.optLong("duration", 0L)
            val durationMs = if (rawDuration in 1..9_999) rawDuration * 1_000L else rawDuration
            val lyric = item.optString("syncedLyrics", "")
            val score = score(song, title, artist, durationMs)
            lyric.takeIf(::looksLikeLrc)
                ?.takeIf { score >= MINIMUM_ONLINE_MATCH_SCORE }
                ?.let { score to it }
        }.maxByOrNull { it.first }?.second
    }.getOrNull()

    private fun score(song: Song, title: String, artist: String, duration: Long): Double {
        if (versionTags(song.title) != versionTags(title)) return Double.NEGATIVE_INFINITY
        val expectedTitle = normalizeForMatch(song.title)
        val candidateTitle = normalizeForMatch(title)
        if (expectedTitle.isBlank() || candidateTitle.isBlank()) return Double.NEGATIVE_INFINITY
        var value = when {
            expectedTitle == candidateTitle -> 100
            expectedTitle.contains(candidateTitle) || candidateTitle.contains(expectedTitle) -> 55
            else -> -80
        }
        val expectedArtist = normalizeForMatch(song.artist)
        val candidateArtist = normalizeForMatch(artist)
        if (expectedArtist.isNotBlank() && candidateArtist.isNotBlank()) {
            value += when {
                expectedArtist == candidateArtist -> 70
                expectedArtist.contains(candidateArtist) || candidateArtist.contains(expectedArtist) -> 45
                else -> -45
            }
        }
        if (song.duration.toLong() * 1_000L > 0L && duration > 0L) {
            value += when (kotlin.math.abs(song.duration.toLong() * 1_000L - duration)) {
                in 0..2_000L -> 35
                in 2_001L..5_000L -> 25
                in 5_001L..15_000L -> 5
                else -> -20
            }
        }
        return value.toDouble()
    }

    /** 候选已按评分/版本选定；正文标签可以简写或省略，不能包含明显相反的身份。 */
    fun accepts(song: Song, text: String): Boolean {
        if (!looksLikeLrc(text)) return false
        val title = Regex("(?im)^\\s*\\[ti\\s*:\\s*(.+)]").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val artist = Regex("(?im)^\\s*\\[ar\\s*:\\s*(.+)]").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        if (title.isNotBlank()) {
            val expectedTitle = normalizeForMatch(song.title)
            val actualTitle = normalizeForMatch(title)
            if (actualTitle.isBlank() || !(expectedTitle.contains(actualTitle) || actualTitle.contains(expectedTitle))) return false
            val actualVersion = versionTags(title)
            if (actualVersion.isNotEmpty() && actualVersion != versionTags(song.title)) return false
        }
        val expectedArtist = normalizeForMatch(song.artist)
        val actualArtist = normalizeForMatch(artist)
        return expectedArtist.isBlank() || actualArtist.isBlank() ||
            expectedArtist.contains(actualArtist) || actualArtist.contains(expectedArtist)
    }

    private fun platformTrackId(song: Song, source: String): Long? {
        if (song.source != source) return null
        val id = when (source) {
            "netease" -> song.extra["song_id"]?.takeIf(String::isNotBlank) ?: song.id
            "kuwo" -> song.extra["rid"]?.takeIf(String::isNotBlank) ?: song.id
            else -> return null
        }
        return id.removePrefix("MUSIC_").toLongOrNull()?.takeIf { it > 0L }
    }

    private fun normalizeForMatch(value: String) = value.lowercase(Locale.ROOT)
        .replace(Regex("[\\s\\p{P}]+"), "").trim()

    private fun versionTags(value: String): Set<String> {
        val title = value.lowercase(Locale.ROOT)
        return buildSet {
            if ("live" in title || "现场" in title) add("live")
            for (tag in listOf("伴奏", "翻唱", "升调", "降调", "remix", "dj", "sped", "slowed", "ktv")) {
                if (tag in title) add(tag)
            }
        }
    }

    private fun looksLikeLrc(text: String) = LyricText.hasTimedLines(text)
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private suspend fun get(url: String, headers: Map<String, String>): String =
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            NativeHttp.get(url, headers, userAgent = headers["User-Agent"] ?: NativeHttp.DEFAULT_USER_AGENT)
        }
            ?: throw IOException("歌词接口请求超时")

    private suspend fun post(url: String, form: Map<String, String>, headers: Map<String, String>): String =
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) { NativeHttp.postForm(url, form, headers).body }
            ?: throw IOException("歌词接口请求超时")
}

/** 与播放器的增强 LRC 兼容；有效行必须有歌词文字，不把空时间标签或无歌词提示当命中。 */
internal object LyricText {
    private val timedLine = Regex("(?m)^\\s*\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?](.*)$")
    private val lineTime = Regex("\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?]")
    private val wordTime = Regex("</?\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>")
    private val emptyHeader = Regex("(?i)\\[(?:ti|ar|al|length)\\s*:\\s*]")
    private val placeholders = setOf("暂无歌词", "暂时没有歌词", "暂无歌词请欣赏", "纯音乐", "纯音乐请欣赏",
        "此歌曲为没有填词的纯音乐请您欣赏", "instrumental", "nolyric", "nolyrics")

    fun hasTimedLines(text: String): Boolean = timedLine.findAll(text.removePrefix("\uFEFF")).any { match ->
        val content = wordTime.replace(lineTime.replace(match.groupValues[1], ""), "").trim()
        val normalized = content.lowercase(Locale.ROOT).replace(Regex("[\\s\\p{P}]+"), "")
        content.isNotBlank() && normalized.isNotBlank() && normalized !in placeholders
    }

    fun normalize(text: String): String = text.trim().removePrefix("\uFEFF").lineSequence()
        .filterNot { emptyHeader.matches(it.trim()) }.joinToString("\n")
}
