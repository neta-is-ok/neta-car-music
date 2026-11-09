# 构建与发布吒友爱听

应用名为“吒友爱听”，安装包名为 `io.neta.music`，运行目标为哪吒车机 Android 11（API 30）。这是源码工程，直接使用 Gradle 构建。

## 本地构建

配置 `JAVA_HOME` 和 `ANDROID_HOME` 后运行：

```sh
./gradlew :app:assembleDebug
```

CI 使用 JDK 21。SDK、build-tools、AGP 与 Kotlin 版本以 `gradle/libs.versions.toml` 为准，Gradle 版本以 wrapper 配置为准；compileSdk 不代表最低运行版本。

初始版本在 `version.properties` 中定义：

```properties
versionName=0.0.1
versionCode=1
```

Debug APK 输出到 `app/build/outputs/apk/debug/neta-car-music-0.0.1-1.apk`。

## 让应用从自己的仓库检查更新

默认更新仓库为 `neta-is-ok/neta-car-music`。GitHub Actions 构建时使用当前仓库的 `GITHUB_REPOSITORY`；本地构建也可显式覆盖：

```sh
./gradlew :app:assembleDebug -PreleaseRepository=neta-is-ok/neta-car-music
```

更新地址为该仓库最新正式 Release 的 `update.json`，清单与 APK 下载均经 CDN 镜像，不访问 myMusic 的更新源。

CDN 配置位于 `app/src/main/assets/cdn-providers.json`，初始列表复制自 Netamade 的同名 `protected-assets` 文件。运行时读取各项 `prefix`，拼接原始 GitHub URL，沿用并发可达性探测、5 分钟排序缓存和失败换线。工程独立构建，不依赖相邻 Netamade 目录；后续更换线路时更新本项目这份 JSON。

更新清单的 schema 为 `neta.music.update`。应用比较 versionCode，下载后沿用已有的 SHA-256、包名、版本和签名校验，再调用系统安装器。

## 配置固定 Release 签名

每次发布必须使用同一个专用签名。密钥不提交到 Git，也不在脚本中记录密码。

本地把密钥放在仓库根目录 `keystore.jks`，创建被 Git 忽略的 `signing.properties`：

```properties
storeFile=keystore.jks
storePassword=你的密钥库密码
keyAlias=你的别名
keyPassword=你的密钥密码
```

也可使用既有环境变量 `KEYSTORE`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`；相对密钥路径以仓库根目录为基准。

```sh
./gradlew :app:assembleRelease
```

Release 输出到 `app/build/outputs/apk/release/`。没有配置密钥时 Gradle 只生成未签名 Release，不借用 debug 签名；这种产物不能直接发布或安装。

GitHub 仓库配置两个 Secrets，沿用原有输入名称：

- `SIGNING_PROPERTIES`：上述 properties 文件的 Base64。
- `SIGN_KEY`：JKS 文件的 Base64。

发布 job 缺少任一 Secret 就停止；构建后还会执行 `zipalign` 与 `apksigner verify`。

## 发布一个版本

1. 修改 `version.properties`；versionCode 必须高于已发布版本。
2. 提交源码后，创建与版本一致的标签，例如 `v0.0.1-1`，推送标签以触发 Release。也可在 GitHub Actions 中手动运行 `Build and Release`，发布所选源码版本。
3. CI 发布 `neta-car-music-0.0.1-1.apk`、`update.json`、`update.apk.sha256` 和对应版本的 `neta-car-music-0.0.1-1-source.zip`。更新清单指向同一 Release 内的 APK，源码包从该次构建的 Git 提交导出。

普通 `main` / `dev` 推送及 PR 只编译 Debug 并上传构建附件，不创建 Release、不运行单元测试。标签与源码版本不一致时发布会停止。

Git 远端为 `git@github.com:neta-is-ok/neta-car-music.git`。提交、推送、打标签、发布均由用户明确授权后执行；首次发布前需在该仓库配置签名 Secrets。
