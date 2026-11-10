package io.neta.music.data.native

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class NativeResponse(
    val body: String,
    val cookies: Map<String, String>,
    val code: Int,
    /** 只有 binary 请求才填，例如登录二维码图片。 */
    val bytes: ByteArray = ByteArray(0),
    /** 跟随跳转后的最终地址，用于短链还原；没有发生跳转时与请求地址相同。 */
    val finalUrl: String = "",
)

object NativeHttp {
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cookie: String = "",
        randomIp: Boolean = false,
        userAgent: String = DEFAULT_USER_AGENT,
    ): String = getResponse(url, headers, cookie, randomIp, userAgent).body

    suspend fun getResponse(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cookie: String = "",
        randomIp: Boolean = false,
        userAgent: String = DEFAULT_USER_AGENT,
        binary: Boolean = false,
        diagnostic: ((String) -> Unit)? = null,
        cookieJar: CookieJar? = null,
    ): NativeResponse {
        val request = Request.Builder()
            .url(url)
            .get()
            .applyCommonHeaders(headers, cookie, randomIp, userAgent)
            .build()
        return execute(request, binary, diagnostic, cookieJar)
    }

    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        cookie: String = "",
        randomIp: Boolean = false,
        userAgent: String = DEFAULT_USER_AGENT,
        diagnostic: ((String) -> Unit)? = null,
        cookieJar: CookieJar? = null,
    ): NativeResponse {
        val body = FormBody.Builder().apply {
            form.forEach { (key, value) -> add(key, value) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .post(body)
            .applyCommonHeaders(headers, cookie, randomIp, userAgent)
            .build()
        return execute(request, diagnostic = diagnostic, cookieJar = cookieJar)
    }

    suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        cookie: String = "",
        randomIp: Boolean = false,
        userAgent: String = DEFAULT_USER_AGENT,
        diagnostic: ((String) -> Unit)? = null,
        cookieJar: CookieJar? = null,
    ): NativeResponse {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .applyCommonHeaders(headers, cookie, randomIp, userAgent)
            .build()
        return execute(request, diagnostic = diagnostic, cookieJar = cookieJar)
    }

    suspend fun postBytes(
        url: String,
        bytes: ByteArray,
        headers: Map<String, String> = emptyMap(),
        cookie: String = "",
        randomIp: Boolean = false,
        userAgent: String = DEFAULT_USER_AGENT,
        contentType: String = "application/octet-stream",
    ): NativeResponse {
        val request = Request.Builder()
            .url(url)
            .post(bytes.toRequestBody(contentType.toMediaType()))
            .applyCommonHeaders(headers, cookie, randomIp, userAgent)
            .build()
        return execute(request)
    }

    private fun Request.Builder.applyCommonHeaders(
        headers: Map<String, String>,
        cookie: String,
        randomIp: Boolean,
        userAgent: String,
    ): Request.Builder {
        header("User-Agent", userAgent)
        if (cookie.isNotBlank()) header("Cookie", cookie)
        headers.forEach { (key, value) ->
            if (!key.equals("Content-Type", ignoreCase = true) && !key.equals("User-Agent", ignoreCase = true)) {
                header(key, value)
            }
        }
        if (randomIp) {
            val ip = NativeCrypto.randomChinaIp()
            header("X-Forwarded-For", ip)
            header("X-Real-IP", ip)
        }
        return this
    }

    /** POST 二进制请求体，响应也按原始字节返回（汽水/酷狗这类加密响应）。 */
    suspend fun postBinary(
        url: String,
        bytes: ByteArray,
        headers: Map<String, String> = emptyMap(),
        cookie: String = "",
        randomIp: Boolean = false,
        userAgent: String = DEFAULT_USER_AGENT,
        contentType: String = "application/json;charset=utf-8",
    ): NativeResponse {
        val request = Request.Builder()
            .url(url)
            .post(bytes.toRequestBody(contentType.toMediaType()))
            .applyCommonHeaders(headers, cookie, randomIp, userAgent)
            .build()
        return execute(request, binary = true)
    }

    /** 只记录地址结构和 Cookie 名称/长度，不记录查询参数值、凭证或响应正文。 */
    internal fun describeUrlForLog(raw: String): String =
        raw.toHttpUrlOrNull()?.let(::describeUrlForLog) ?: "<missing-or-invalid-url>"

    private fun describeUrlForLog(url: HttpUrl): String =
        "${url.scheme}://${url.host}${url.encodedPath}" +
            url.queryParameterNames.sorted().joinToString(
                prefix = if (url.querySize > 0) "?keys=[" else "",
                postfix = if (url.querySize > 0) "]" else "",
            )

    internal fun describeCookiesForLog(cookies: Map<String, String>): String =
        cookies.toSortedMap().entries.joinToString(prefix = "[", postfix = "]") { (name, value) ->
            "$name(len=${value.length})"
        }

    private fun describeRequestCookiesForLog(request: Request): String =
        describeCookiesForLog(buildMap {
            request.headers.values("Cookie").flatMap { it.split(';') }.forEach { part ->
                val pair = part.trim()
                val index = pair.indexOf('=')
                if (index > 0) put(pair.substring(0, index).trim(), pair.substring(index + 1).trim())
            }
        })

    private fun describeResponseCookiesForLog(response: Response): String =
        response.headers.values("Set-Cookie").joinToString(prefix = "[", postfix = "]") { header ->
            val pair = header.substringBefore(';').trim()
            val name = pair.substringBefore('=')
            val length = pair.substringAfter('=', "").length
            val cookie = Cookie.parse(response.request.url, header)
            "$name(len=$length,domain=${cookie?.domain},path=${cookie?.path},secure=${cookie?.secure})"
        }

    private suspend fun execute(
        request: Request,
        binary: Boolean = false,
        diagnostic: ((String) -> Unit)? = null,
        cookieJar: CookieJar? = null,
    ): NativeResponse =
        suspendCancellableCoroutine { continuation ->
            diagnostic?.invoke(
                "request ${request.method} ${describeUrlForLog(request.url)} " +
                    "cookies=${describeRequestCookiesForLog(request)}"
            )
            // 只有显式提供的登录会话按域名管理 Cookie；原有调用继续使用无 CookieJar 的共享客户端。
            val requestClient = if (cookieJar == null) client else client.newBuilder().cookieJar(cookieJar).build()
            val call = requestClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    diagnostic?.invoke("network failure type=${error.javaClass.simpleName} cancelled=${call.isCanceled()}")
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (diagnostic != null) {
                            generateSequence(it) { hop -> hop.priorResponse }
                                .toList().asReversed().forEachIndexed { index, hop ->
                                    val location = hop.header("Location")?.let { target ->
                                        hop.request.url.resolve(target)?.let(::describeUrlForLog)
                                            ?: "<invalid-url>"
                                    }.orEmpty()
                                    diagnostic(
                                        "response hop=$index http=${hop.code} ${describeUrlForLog(hop.request.url)} " +
                                            "sentCookies=${describeRequestCookiesForLog(hop.request)} " +
                                            "setCookies=${describeResponseCookiesForLog(hop)} location=$location " +
                                            "contentType=${hop.header("Content-Type").orEmpty()}"
                                    )
                                }
                        }
                        val bytes = if (binary) runCatching { it.body?.bytes() }.getOrNull() ?: ByteArray(0) else ByteArray(0)
                        val body = if (binary) "" else runCatching { it.body?.string().orEmpty() }.getOrDefault("")
                        diagnostic?.invoke("body chars=${body.length} bytes=${bytes.size}")
                        if (!it.isSuccessful) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IOException("HTTP ${it.code} ${it.message}: ${body.take(300)}")
                                )
                            }
                            return
                        }
                        val cookies = buildMap {
                            // 登录凭据可能在 302 响应里；按跳转顺序合并，让后续响应覆盖旧值。
                            generateSequence(it) { response -> response.priorResponse }
                                .toList().asReversed().forEach { response ->
                                    response.headers.values("Set-Cookie").forEach { header ->
                                        val pair = header.substringBefore(';').trim()
                                        val index = pair.indexOf('=')
                                        if (index > 0) {
                                            put(pair.substring(0, index).trim(), pair.substring(index + 1).trim())
                                        }
                                    }
                                }
                        }
                        if (continuation.isActive) {
                            continuation.resume(
                                NativeResponse(body, cookies, it.code, bytes, it.request.url.toString())
                            )
                        }
                    }
                }
            })
        }
}
