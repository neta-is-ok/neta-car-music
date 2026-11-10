# 吒友爱听

基于 [myMusic](https://github.com/mytv-android/myMusic) 开发的哪吒车机音乐客户端，直接维护 Android 源码。

- 应用名：吒友爱听
- 包名：`io.neta.music`
- 版本：`0.0.5 (5)`，由 [version.properties](version.properties) 统一维护
- 运行目标：Android 11（API 30）
- 技术栈：Kotlin、Jetpack Compose / Material 3、Media3

## 构建

配置 `JAVA_HOME`、`ANDROID_HOME` 后运行：

```sh
./gradlew :app:assembleDebug
```

APK 输出到 `app/build/outputs/apk/debug/neta-car-music-0.0.5-5.apk`。SDK 与依赖版本以 `gradle/libs.versions.toml` 为准。

正式签名包执行：

```sh
./gradlew :app:assembleRelease
```

配置仓库根目录的 `keystore.jks`、`signing.properties` 或对应签名环境变量后，Release 构建会完成签名并自动输出：

- 英文发布包：`app/build/outputs/apk/release/neta-car-music-0.0.5-5.apk`
- 中文名副本：`app/build/outputs/local-apk/release/吒友爱听-0.0.5-5.apk`

两份 APK 的内容和签名相同，文件名随 `version.properties` 自动变化。中文名副本只在配置 Release 签名后生成，不影响原有 Debug 构建或英文发布包。密钥和密码不入库，开发约定见 [AGENTS.md](AGENTS.md)。

## 发布与更新

- 仓库：<https://github.com/neta-is-ok/neta-car-music>
- Release：<https://github.com/neta-is-ok/neta-car-music/releases>
- 普通分支推送及 PR 只编译 Debug；版本标签（如 `v0.0.5-5`）或手动运行[发布工作流](.github/workflows/build.yml)才创建 Release。
- 正式发布使用固定专用签名，附件仅包含 APK、更新清单和 SHA-256 文件，不额外上传源码包，不回退到 debug 签名。
- 应用只检查本项目的更新，清单与 APK 使用 [CDN 配置](app/src/main/assets/cdn-providers.json) 中的镜像；配置初始来自 Netamade，工程独立构建。

## 源码来源与许可

本项目基于 [myMusic](https://github.com/mytv-android/myMusic) 修改，应用代码和纳入的 AGPL 组件继续按 GNU Affero General Public License v3.0 分发，保留上游及第三方版权声明。

- [LICENSE](LICENSE)：AGPL-3.0 原文。
- [NOTICE.md](NOTICE.md)：源码来源、初始版本及修改说明。
- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)：music-lib、SodaM / libresoda 等组件与服务的许可及署名。

对应版本源码通过本仓库的版本标签及 GitHub 自动生成的 Source code 归档提供。接收者可依许可证使用、修改和再分发软件，包括商业用途；音乐内容的使用仍需遵守各平台服务条款及适用法律。
