package io.neta.music.data.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.neta.music.BuildConfig
import io.neta.music.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class UpdateManifest(
    val schema: String = "",
    val channel: String = "stable",
    val versionCode: Long = 0,
    val versionName: String = "",
    val tag: String = "",
    val publishedAt: String = "",
    val minSdk: Int = 0,
    val apk: UpdateApk = UpdateApk(),
    val notes: List<String> = emptyList(),
)

@Serializable
data class UpdateApk(
    val url: String = "",
    @SerialName("size") val size: Long = 0,
    @SerialName("sha256") val sha256: String = "",
)

data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val tag: String,
    val publishedAt: String,
    val size: Long,
    val url: String,
    val sha256: String,
    val notes: List<String>,
)

enum class UpdatePhase { Idle, Checking, Available, Downloading, Verifying, Ready, Error }

data class UpdateUiState(
    val currentVersionName: String = "",
    val currentVersionCode: Long = 0,
    val phase: UpdatePhase = UpdatePhase.Idle,
    val info: UpdateInfo? = null,
    val progress: Float = 0f,
    val phaseLabel: String = "",
    val message: String = "",
    val error: String = "",
    val file: File? = null,
    val permissionRequired: Boolean = false,
    /** 只收起下载期间的进度弹窗，不阻止完成后的前台安装提示。 */
    val backgroundDownload: Boolean = false,
)

enum class InstallResult { Launched, NeedsPermission, Failed }

@Serializable
private data class UpdateCdnProvider(val name: String = "", val prefix: String)

class UpdateRepository(
    private val context: Context,
    private val settings: SettingsStore,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mirrors by lazy {
        context.assets.open("cdn-providers.json").bufferedReader().use { reader ->
            json.decodeFromString<List<UpdateCdnProvider>>(reader.readText())
                .map {
                    val prefix = it.prefix.trim().trimEnd('/')
                    it.copy(prefix = if (prefix.isEmpty()) "" else "$prefix/")
                }
                .distinctBy { it.prefix }
        }
    }
    private val downloadHttp = http.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    @Volatile
    private var activeCall: okhttp3.Call? = null

    private var installResultReceiver: BroadcastReceiver? = null

    @Volatile
    private var downloadCancelled = false

    init {
        val info = currentPackageInfo()
        _state.value = UpdateUiState(
            currentVersionName = info?.name.orEmpty(),
            currentVersionCode = info?.code ?: 0L,
        )
    }

    private data class CurrentVersion(val name: String, val code: Long)

    private fun currentPackageInfo(): CurrentVersion? = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        CurrentVersion(info.versionName.orEmpty(), code)
    }.getOrNull()

    suspend fun check(manual: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (MANIFEST_URL.isBlank()) {
            _state.update {
                it.copy(phase = UpdatePhase.Idle, info = null, message = "尚未配置更新仓库", error = "")
            }
            return@withContext false
        }
        _state.update { it.copy(phase = UpdatePhase.Checking, phaseLabel = "正在检查更新", error = "", permissionRequired = false) }
        try {
            val manifest = fetchManifest(mirrorOrder())
                ?: throw IOException("检查更新失败：所有线路均不可用")
            settings.lastUpdateCheck(System.currentTimeMillis())
            val current = currentPackageInfo() ?: throw IOException("无法读取当前版本")
            if (manifest.minSdk > 0 && Build.VERSION.SDK_INT < manifest.minSdk) {
                throw IOException("这个更新需要 Android ${manifest.minSdk} 或更高版本")
            }
            if (manifest.versionCode <= current.code) {
                _state.update {
                    it.copy(
                        currentVersionName = current.name,
                        currentVersionCode = current.code,
                        phase = UpdatePhase.Idle,
                        info = null,
                        message = "已是最新版本",
                        error = "",
                        phaseLabel = "",
                        backgroundDownload = false,
                    )
                }
                return@withContext true
            }
            if (manifest.apk.url.isBlank() || manifest.apk.sha256.isBlank()) {
                throw IOException("更新清单缺少安装包地址或校验信息")
            }
            val info = UpdateInfo(
                versionCode = manifest.versionCode,
                versionName = manifest.versionName,
                tag = manifest.tag,
                publishedAt = manifest.publishedAt,
                size = manifest.apk.size,
                url = manifest.apk.url,
                sha256 = manifest.apk.sha256,
                notes = manifest.notes,
            )
            _state.update {
                it.copy(
                    currentVersionName = current.name,
                    currentVersionCode = current.code,
                    phase = UpdatePhase.Available,
                    info = info,
                    message = if (manual) "发现新版本 ${info.versionName}（构建 ${info.versionCode}）" else "",
                    error = "",
                    phaseLabel = "",
                    backgroundDownload = it.backgroundDownload && it.info?.versionCode == info.versionCode,
                )
            }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.update {
                it.copy(
                    phase = if (manual) UpdatePhase.Error else UpdatePhase.Idle,
                    phaseLabel = "",
                    error = if (manual) error.message ?: "检查更新失败" else "",
                    message = if (manual) "" else it.message,
                )
            }
            false
        }
    }

    suspend fun download(): Boolean = withContext(Dispatchers.IO) {
        val info = _state.value.info ?: return@withContext false
        val current = _state.value.phase
        if (current == UpdatePhase.Downloading || current == UpdatePhase.Verifying) return@withContext false
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, "neta-car-music-${info.versionName}-${info.versionCode}.apk")
        if (info.size > 0 && directory.usableSpace < info.size + 8L * 1024 * 1024) {
            _state.update {
                it.copy(
                    phase = UpdatePhase.Error,
                    error = "存储空间不足，至少需要 ${formatSize(info.size)} 可用空间",
                    file = null,
                )
            }
            return@withContext false
        }
        _state.update {
            it.copy(
                phase = UpdatePhase.Downloading,
                progress = 0f,
                phaseLabel = "正在下载",
                error = "",
                file = null,
                permissionRequired = false,
            )
        }
        downloadCancelled = false
        try {
            var lastError: Exception? = null
            var downloaded = false
            var successfulMirror: UpdateCdnProvider? = null
            val order = mirrorOrder()
            for ((index, mirror) in order.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (downloadCancelled) break
                val name = mirror.name.ifBlank { Uri.parse(mirror.prefix).host ?: "直连" }
                _state.update {
                    it.copy(
                        progress = 0f,
                        phaseLabel = "${if (index == 0) "正在下载" else "已切换线路"} · $name（${index + 1}/${order.size}）",
                    )
                }
                try {
                    downloadFrom(mirrorUrl(mirror, info.url), target, info)
                    downloaded = true
                    successfulMirror = mirror
                    break
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    target.delete()
                    lastError = error
                    if (downloadCancelled) break
                    Log.w(TAG, "更新下载线路 $name 失败，尝试下一条：${error.message}")
                }
            }
            if (!downloaded) throw if (downloadCancelled) IOException("下载已取消")
                else lastError ?: IOException("所有下载线路均不可用")
            _state.update { it.copy(phase = UpdatePhase.Verifying, phaseLabel = "正在校验 SHA-256", progress = 1f) }
            verifyApk(target, info)
            // 只有大小、SHA-256、包名和版本均校验通过，才记为成功线路；清单可达不等于 APK 可用。
            successfulMirror?.let { settings.lastSuccessfulUpdateCdn(it.prefix) }
            _state.update {
                it.copy(
                    phase = UpdatePhase.Ready,
                    phaseLabel = "校验通过",
                    progress = 1f,
                    file = target,
                    error = "",
                    message = if (it.backgroundDownload) "更新包已下载并校验，回到应用后会自动打开系统安装器"
                        else "更新包校验通过，正在打开系统安装器",
                )
            }
            true
        } catch (error: CancellationException) {
            target.delete()
            throw error
        } catch (error: Exception) {
            target.delete()
            _state.update {
                it.copy(
                    phase = UpdatePhase.Error,
                    phaseLabel = "",
                    error = error.message ?: "更新包下载失败",
                    file = null,
                )
            }
            false
        } finally {
            activeCall?.cancel()
            activeCall = null
        }
    }

    /** 下载服务停止时中断网络读取，不与界面开关绑定。 */
    fun cancelDownload() {
        downloadCancelled = true
        activeCall?.cancel()
        activeCall = null
    }

    fun continueInBackground() {
        _state.update { if (it.info == null) it else it.copy(backgroundDownload = true) }
    }

    fun reportDownloadStartFailure(message: String) {
        _state.update { it.copy(phase = UpdatePhase.Error, error = message) }
    }

    /** 从单个线路下载更新包；大小或摘要对不上会抛异常，由调用方换下一条线路。 */
    private suspend fun downloadFrom(url: String, target: File, info: UpdateInfo): Unit = coroutineScope {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        val call = downloadHttp.newCall(request)
        val received = AtomicLong(0L)
        val slowDetected = AtomicBoolean(false)
        activeCall = call
        if (downloadCancelled) call.cancel()
        // 只靠 readTimeout 无法识别持续返回少量数据的节点；每 10 秒检查一次实际吞吐量。
        val watchdog = launch(Dispatchers.IO) {
            var windowStartedAt = System.nanoTime()
            var windowStartedBytes = 0L
            try {
                while (isActive) {
                    delay(1_000L)
                    val now = System.nanoTime()
                    val elapsed = now - windowStartedAt
                    if (elapsed < SLOW_WINDOW_MS * 1_000_000L) continue
                    val bytes = received.get()
                    val speed = (bytes - windowStartedBytes) * 1_000_000_000L / elapsed
                    if (speed < MIN_DOWNLOAD_BYTES_PER_SECOND) {
                        slowDetected.set(true)
                        call.cancel()
                        break
                    }
                    windowStartedAt = now
                    windowStartedBytes = bytes
                }
            } finally {
                // 父协程取消时也中断阻塞读取。
                call.cancel()
            }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("下载失败（HTTP ${response.code}）")
                val body = response.body
                val total = info.size.takeIf { it > 0 } ?: body.contentLength()
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                val progressGate = DownloadProgressGate()
                val buffer = ByteArray(64 * 1024)
                target.outputStream().use { output ->
                    body.byteStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            copied += read
                            received.set(copied)
                            if (total > 0 && progressGate.shouldEmit(System.nanoTime(), complete = copied >= total)) {
                                _state.update { it.copy(progress = (copied.toFloat() / total).coerceIn(0f, 1f)) }
                            }
                        }
                    }
                }
                if (info.size > 0 && copied != info.size) {
                    throw IOException("下载大小与更新清单不一致")
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(info.sha256, ignoreCase = true)) {
                    throw IOException("更新包 SHA-256 校验失败")
                }
            }
        } catch (error: IOException) {
            if (slowDetected.get()) throw IOException("下载速度持续低于 100 KB/s，已放弃当前线路", error)
            throw error
        } finally {
            watchdog.cancel()
            call.cancel()
            activeCall = null
        }
    }

    /** 上次完整下载成功的线路优先，其后按资源列表顺序；不再让全量 HEAD 测速阻塞实际请求。 */
    private suspend fun mirrorOrder(): List<UpdateCdnProvider> {
        val previous = settings.state.first().lastSuccessfulUpdateCdn
        return mirrors.sortedBy { if (it.prefix == previous) 0 else 1 }
    }

    /** 依次尝试各线路，返回第一个能解析且 schema 正确的更新清单。 */
    private fun fetchManifest(order: List<UpdateCdnProvider>): UpdateManifest? {
        for (mirror in order) {
            val body = fetchText(mirrorUrl(mirror, MANIFEST_URL)) ?: continue
            val manifest = runCatching { json.decodeFromString<UpdateManifest>(body) }.getOrNull() ?: continue
            if (manifest.schema == "neta.music.update") return manifest
        }
        return null
    }

    private fun fetchText(url: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .header("User-Agent", USER_AGENT)
            .build()
        val call = http.newCall(request)
        call.timeout().timeout(MANIFEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        call.execute().use { response ->
            if (response.isSuccessful) response.body.string() else null
        }
    } catch (_: Exception) {
        null
    }

    private fun mirrorUrl(mirror: UpdateCdnProvider, target: String): String =
        mirror.prefix + target

    private fun verifyApk(file: File, info: UpdateInfo) {
        val current = currentPackageInfo() ?: throw IOException("无法读取当前版本")
        if (info.versionCode <= current.code) throw IOException("更新清单版本不高于当前版本，已拒绝安装")
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: throw IOException("无法读取更新包信息")
        if (archive.packageName != context.packageName) {
            throw IOException("更新包包名与当前应用不一致，已拒绝安装")
        }
        val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        if (archiveCode != info.versionCode) {
            throw IOException("更新包版本号与清单不一致，已拒绝安装")
        }
    }

    /**
     * 唤起系统安装器，不拿 `PackageManager.canRequestPackageInstalls` 当门槛：
     * 部分电视盒子这个接口恒返回 false，安装器却能正常安装，拦在门上只会把用户送去一个不存在的设置页。
     *
     * 依次尝试 ACTION_VIEW → ACTION_INSTALL_PACKAGE → PackageInstaller 会话；只有全都起不来时才认为
     * 真的缺“安装未知来源”授权，返回 [InstallResult.NeedsPermission]。连授权页都没有的 ROM
     * 由调用方转到手动安装，见 [exportForManualInstall]。
     */
    suspend fun install(file: File): InstallResult {
        _state.update { it.copy(permissionRequired = false, error = "") }
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.onFailure {
            Log.w(TAG, "无法生成更新包的 content URI", it)
        }.getOrNull()
        if (uri != null) {
            if (startInstallerActivity(Intent.ACTION_VIEW, uri)) return InstallResult.Launched
            if (startInstallerActivity(Intent.ACTION_INSTALL_PACKAGE, uri)) return InstallResult.Launched
        }
        if (withContext(Dispatchers.IO) { commitInstallSession(file) }) return InstallResult.Launched
        _state.update { it.copy(permissionRequired = true, error = "请先允许本应用安装未知来源的应用") }
        return InstallResult.NeedsPermission
    }

    /** 用隐式 Action 唤起系统安装器；没有任何 Activity 能承接时返回 false。 */
    private fun startInstallerActivity(action: String, apkUri: Uri): Boolean = try {
        val intent = Intent(action)
            .setDataAndType(apkUri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // 有 clipData 时授权按 clipData 走，个别 ROM 只认这里不看 setData
        intent.clipData = ClipData.newRawUri("", apkUri)
        context.startActivity(intent)
        true
    } catch (error: ActivityNotFoundException) {
        Log.w(TAG, "$action 没有可响应的系统安装器", error)
        false
    } catch (error: Exception) {
        Log.w(TAG, "$action 唤起安装器失败", error)
        false
    }

    /**
     * 打开“安装未知应用”授权页。各 ROM 的设置页面不一样，按带包名的专用页 → 不带包名的同名页
     * → 应用详情页逐级退，全部没有时导出更新包并提示手动安装，不再只丢一条无法打开的报错。
     */
    /** 返回是否真的打开了授权页；一个都打不开时已改为提示手动安装，调用方不必再等用户回来。 */
    suspend fun openInstallPermissionSettings(): Boolean {
        for (intent in installPermissionIntents()) {
            if (tryStartActivity(intent)) return true
        }
        manualInstallTip()
        return false
    }

    private fun installPermissionIntents(): List<Intent> {
        val packageUri = Uri.parse("package:${context.packageName}")
        fun setting(action: String, data: Uri?) = Intent(action, data).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val intents = mutableListOf<Intent>()
        if (Build.VERSION.SDK_INT >= 26) {
            intents += setting(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri)
            intents += setting(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, null)
        }
        intents += setting(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        return intents
    }

    private fun tryStartActivity(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (error: Exception) {
        Log.w(TAG, "打开 ${intent.action} 失败", error)
        false
    }

    /** 授权页和安装器都不可用时的最后一级：把更新包放到外部目录，让用户用文件管理器装。 */
    suspend fun exportForManualInstall() = manualInstallTip()

    private suspend fun manualInstallTip() {
        val file = _state.value.file ?: return
        val savedPath = withContext(Dispatchers.IO) { exportApkForManualInstall(file) }
        val tip = if (savedPath != null) {
            "这个系统没有可用的安装器，更新包已保存到 $savedPath，请用文件管理器安装"
        } else {
            "这个系统没有可用的安装器，请通过应用市场或 U 盘安装更新包"
        }
        _state.update { state -> state.copy(permissionRequired = false, error = tip) }
    }

    /**
     * 走系统 PackageInstaller 会话安装，不依赖安装器 Activity 的 IntentFilter：
     * 即使 ROM 没注册 ACTION_VIEW 安装入口，也能由系统安装服务接管。
     */
    private fun commitInstallSession(file: File): Boolean {
        val installer = context.packageManager.packageInstaller
        var sessionId = -1
        return try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("neta-car-music.apk", 0, file.length()).use { output ->
                        input.copyTo(output)
                        // 写完后必须写透，否则系统读到的是不完整的包；minSdk 23 没有 Session.fsync（API 24）
                        output.flush()
                    }
                }
                registerInstallResultReceiver()
                session.commit(installResultSender(sessionId))
            }
            true
        } catch (error: Exception) {
            Log.w(TAG, "PackageInstaller 会话安装失败", error)
            if (sessionId >= 0) {
                runCatching { installer.abandonSession(sessionId) }
            }
            false
        }
    }

    private fun installResultSender(sessionId: Int): IntentSender {
        val intent = Intent(INSTALL_RESULT_ACTION).setPackage(context.packageName)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 31) {
            // 系统安装服务要把结果写回这个 Intent，必须可变
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
    }

    private fun registerInstallResultReceiver() {
        if (installResultReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    // 部分 ROM 把安装确认界面交给调用方拉起；最终结果仍会回到这里，先不注销
                    confirmIntentOf(intent)?.let { confirm ->
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { receiverContext.startActivity(confirm) }
                            .onFailure { Log.w(TAG, "拉起安装确认界面失败", it) }
                    }
                    return
                }
                unregisterInstallResultReceiver()
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    _state.update { it.copy(error = "", message = "更新包已安装") }
                } else {
                    _state.update {
                        it.copy(error = "安装失败${if (detail.isNullOrBlank()) "" else "：$detail"}")
                    }
                }
            }
        }
        installResultReceiver = receiver
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(INSTALL_RESULT_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun unregisterInstallResultReceiver() {
        val receiver = installResultReceiver ?: return
        installResultReceiver = null
        runCatching { context.unregisterReceiver(receiver) }
            .onFailure { Log.w(TAG, "注销安装结果监听失败", it) }
    }

    @Suppress("DEPRECATION")
    private fun confirmIntentOf(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    /** 把更新包复制到外部目录供文件管理器安装，返回路径；失败返回 null。 */
    private fun exportApkForManualInstall(file: File): String? = try {
        val directory = context.getExternalFilesDir(null) ?: context.filesDir
        val target = File(directory, file.name)
        file.copyTo(target, overwrite = true)
        target.absolutePath
    } catch (error: Exception) {
        Log.w(TAG, "导出更新包失败", error)
        null
    }

    fun openReleasePage() {
        if (RELEASES_URL.isBlank()) {
            _state.update { it.copy(message = "尚未配置更新仓库") }
            return
        }
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun clearError() {
        _state.update { it.copy(error = "") }
    }

    companion object {
        private const val TAG = "UpdateRepository"
        private const val INSTALL_RESULT_ACTION = BuildConfig.APPLICATION_ID + ".action.INSTALL_RESULT"
        val RELEASES_URL = BuildConfig.RELEASE_REPOSITORY.takeIf { it.isNotBlank() }
            ?.let { "https://github.com/$it/releases" }.orEmpty()
        val MANIFEST_URL = RELEASES_URL.takeIf { it.isNotBlank() }
            ?.let { "$it/latest/download/update.json" }.orEmpty()
        private const val USER_AGENT = "NetaCarMusic-Android"
        private const val MANIFEST_TIMEOUT_MS = 10_000L
        private const val SLOW_WINDOW_MS = 10_000L
        private const val MIN_DOWNLOAD_BYTES_PER_SECOND = 100L * 1024L

    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024L -> String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
