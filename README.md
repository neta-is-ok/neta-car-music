# 吒友爱听

基于 [myMusic](https://github.com/mytv-android/myMusic) 开发的哪吒车机音乐客户端，直接维护 Android 源码。

- 应用名：吒友爱听
- 包名：`io.neta.music`
- 版本：`0.0.6 (6)`，由 [version.properties](version.properties) 统一维护
- 运行目标：Android 11（API 30）
- 技术栈：Kotlin、Jetpack Compose / Material 3、Media3

## 构建

配置 `JAVA_HOME`、`ANDROID_HOME` 后运行：

```sh
./gradlew :app:assembleDebug
```

APK 输出到 `app/build/outputs/apk/debug/neta-car-music-0.0.6-6.apk`。SDK 与依赖版本以 `gradle/libs.versions.toml` 为准。

正式签名包执行：

```sh
./gradlew :app:assembleRelease
```

配置仓库根目录的 `keystore.jks`、`signing.properties` 或对应签名环境变量后，Release 构建会完成签名并自动输出：

- 英文发布包：`app/build/outputs/apk/release/neta-car-music-0.0.6-6.apk`
- 中文名副本：`app/build/outputs/local-apk/release/吒友爱听-0.0.6-6.apk`

两份 APK 的内容和签名相同，文件名随 `version.properties` 自动变化。中文名副本只在配置 Release 签名后生成，不影响原有 Debug 构建或英文发布包。密钥和密码不入库，开发约定见 [AGENTS.md](AGENTS.md)。

## 对外控制

向包 `io.neta.music` 发送 `io.neta.music.action.CONTROL` 广播，通过字符串 `command` 点歌、收藏或切换播放模式；参数及结果字段见 [PlaybackBroadcastReceiver](app/src/main/java/io/neta/music/playback/PlaybackBroadcastReceiver.kt) 的接口说明。

- Android 调用方可携带平台 `android.os.ResultReceiver` 参数 `result_receiver`，结果直接回到发起进程，无需注册结果广播接收器。
- 点歌先返回 `accepted`，搜索及提交播放器后返回 `completed` 或 `error`；两阶段使用相同 `request_id`。受理不等于已经播放出声。
- 未携带回调时，继续使用原 `reply_action/reply_package` 结果广播；有序广播结果保持兼容。

带 `request_id` 的 `play` 另输出低频系统 INFO 事件，tag 固定为 `NetaMusicControl`。正文为单行 JSON：`v=1`、`command=play`、`request_id`、`user_id`、`phase`，阶段为 `received`、`accepted`、`completed` 或 `error`。后三种阶段同时包含 `success`，并按可用情况带 `title`、`artist`、`message`；不输出完整 Intent、歌词或账号数据。标题/歌手各最多 192 字符，错误说明最多 384 字符。

事件通过 `AppLog.systemInfo()` 同时进入应用日志和系统 logcat；普通 `AppLog.i()` 仍只写内存。美式可按请求 ID 和目标用户从已有日志流跟踪副屏状态：发出即提示，后续有事件才更新，缺事件不判超时。这是可选观察通道，不改变原广播/ResultReceiver，也不表示 `completed` 时已经播放出声。

### MediaSession 歌词输出

播放服务通过 `MediaSession.setSessionExtras()` 输出歌词协议 v1，不需要广播、文件或调用方主动查询歌词接口：

```text
io.neta.music.lyrics.VERSION  Long = 1
io.neta.music.lyrics.MEDIA_ID String = 当前 MediaItem.mediaId（Song.key）
io.neta.music.lyrics.STATE    String = loading / ready / unavailable
io.neta.music.lyrics.ARTIST   String = 与此 MEDIA_ID 绑定的真实歌手，已知时提供
android.media.metadata.LYRIC String = 整首带时间戳 LRC，仅 ready 时存在
```

- 接收端监听系统 `MediaController.onExtrasChanged()`，先与同一会话 metadata 的 MEDIA_ID 核对，再解析正文；Media3 客户端读取 session extras。歌词晚到时不依赖 extras-only 的 metadata 变化通知。
- 歌词与所属歌曲 ID 一起导出，切歌撤下旧正文；后台播放和交叉淡化换播放器继续发布。仅曲目、歌词或必要元数据变化时更新，不随进度逐行发送。
- 保留整首逐行时间轴及已有翻译行，移除 `<起始时间>`、`</结束时间>` 逐字标签后导出普通 LRC；应用内部逐字显示不变。只补缺失的歌曲头信息，不修改已有身份标签冒充匹配。播放进度继续读取 PlaybackState，接收端自行决定显示行数和滚动位置。
- “通知栏歌词”可能把系统 artist 替换为歌词行；ARTIST 字段和 LRC 头仍取原歌曲歌手，接收端可在 ID 校验后用它稳定歌曲身份，避免每行重新加载。
- 空歌词、无时间轴、加载失败或正文超过 131072 个 UTF-16 代码单元（约 256 KiB）时发布 `unavailable`，不截断成几行。接收端可回退到自身缓存或在线检索；`loading` 应有界等待，建议最多 2 秒。

## 发布与更新

- 仓库：<https://github.com/neta-is-ok/neta-car-music>
- Release：<https://github.com/neta-is-ok/neta-car-music/releases>
- 普通分支推送及 PR 的 [GitHub Actions](.github/workflows/build.yml) 只编译 Debug，不再发布 Release。正式版在本地编译、签名，再上传 GitHub。
- 正式发布使用固定专用签名，附件仅包含 APK、更新清单和 SHA-256 文件，不额外上传源码包，不回退到 debug 签名。
- 应用只检查本项目的更新，清单与 APK 使用 [CDN 配置](app/src/main/assets/cdn-providers.json) 中的镜像；配置初始来自 Netamade，工程独立构建。

发布前递增 `version.properties`，整理上次正式版以来的更新说明，**将版本号和完整说明给用户查看并确认，确认后才能推送发布提交、标签或创建 Release**。准备阶段可以先完成本地构建：

```sh
# 在 main 提交本次源码和版本后，使用项目内的说明文件准备正式签名包。
python3 scripts/release.py prepare --notes build/releases/0.0.6/release-notes.md

# 用户确认说明后上传；脚本会展示本次说明，并要求输入完整版本标签确认。
python3 scripts/release.py publish
```

依赖 Python 3、已登录的 GitHub CLI（`gh`）、Git，以及本机构建环境。准备结果保存在 `dist/v0.0.6-6/`，包括正式 APK、`update.json`、`update.apk.sha256` 和本地发布说明。脚本只核对产物包名、版本和正式签名，计算一次更新所需的 SHA-256，不下载回验或重复编译。

网络操作默认最多尝试 5 次，等待时间依次为 5、10、15、20 秒；可通过 `RELEASE_RETRIES`、`RELEASE_RETRY_DELAY` 调整。上传失败后直接重跑 `publish`，复用已签名产物；附件先上传到草稿，全部成功后才公开并设为最新版。

## 源码来源与许可

本项目基于 [myMusic](https://github.com/mytv-android/myMusic) 修改，应用代码和纳入的 AGPL 组件继续按 GNU Affero General Public License v3.0 分发，保留上游及第三方版权声明。

- [LICENSE](LICENSE)：AGPL-3.0 原文。
- [NOTICE.md](NOTICE.md)：源码来源、初始版本及修改说明。
- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)：music-lib、SodaM / libresoda 等组件与服务的许可及署名。

对应版本源码通过本仓库的版本标签及 GitHub 自动生成的 Source code 归档提供。接收者可依许可证使用、修改和再分发软件，包括商业用途；音乐内容的使用仍需遵守各平台服务条款及适用法律。
