# 吒友爱听协作规范

## 项目与长期范围

- 本项目是基于 [myMusic](https://github.com/mytv-android/myMusic) 的 Android 源码工程，直接修改 Kotlin、Compose、资源和 Gradle 配置；不采用 APK 反编译、smali 注入或 `NETA-PORT.md` 移植档案流程。
- 应用名为“吒友爱听”，安装包名为 `io.neta.music`，面向哪吒车机 Android 11（API 30）。高版本 API 必须有版本判断及 API 30 可用路径。
- **长期不做头枕出声、多屏、音乐区悬浮窗**。不为这三项新增实现、设置入口或依赖，也不再将其列为后续适配候选。
- 平板与车机主界面固定使用底部播放条，不随宽度切换成右侧常驻播放窗格。列表与详情的双栏、用户主动打开的完整播放页不受此约束影响。
- 应用图标使用 `app/src/main/res/` 中已导出的资源。默认配色为图标中的朱红暖金，界面、播放页和小组件共用 `ui/theme/Palette.kt`；保留用户主动选择的封面、壁纸和自选配色。
- 保留上游 AGPL-3.0 许可证、作者归属和第三方声明。仓库只保留源码、构建配置、必要资源、最小 README 和协作入口；不提交交接文档、设计资料、美术母版或独立网页。
- README 与关于页只保留本项目入口、源码来源和必要许可说明，不增加 Telegram 等群组及无关推广内容。

## 工程入口

- 主应用：`app/`；applicationId、namespace 与源码包名统一为 `io.neta.music`，目录、测试、组件类名与 Baseline Profile 同步维护。
- 界面外壳：`ui/AppNavigation.kt`；窗口规则：`ui/components/Window.kt`；底部播放条：`ui/adaptive/LargeScreen.kt`。
- 播放服务：`playback/PlaybackService.kt`；播放状态与操作：`ui/MusicViewModel.kt`。
- 应用内更新：`data/update/UpdateRepository.kt`；发布流程：`.github/workflows/build.yml`。
- `version.properties` 是 versionName / versionCode 的唯一来源。初始版本为 `0.0.1 (1)`；每次发布递增 versionCode。

以上 Kotlin 路径均相对 `app/src/main/java/io/neta/music/`。

## 修改与验证

- 读取目标文件及直接调用方后再修改，保留并行任务的兼容改动，不清理无关工作区或暂存区。
- 复用已有 Compose、Media3、Insets 和设置结构，不引入并行播放器或额外架构。
- 改动 Kotlin、类型、依赖、Gradle 或 manifest 后，至少执行 `./gradlew :app:assembleDebug`；涉及 Release 配置时补充对应构建和产物核对。
- 禁止新增或运行单元测试。可按变更需要编译现有 Android 仪器测试，但不要用测试通过代替界面与运行验证。
- 同一工作区一次只运行一个 Gradle 构建，保留 daemon 和构建缓存。
- UI 改动检查实际渲染与关键交互；区分编译、模拟器与实车结果，未验证的项目明确说明。
- 模拟器只使用 `emulator-5554`；不新建模拟器，不卸载应用或清空现有数据。安装前核对签名兼容性。

## Git 与 Release

- 使用 `main` 分支。提交、推送、打标签、发布和工作区清理需要用户明确授权；完成修改不自动提交。
- 远端为 `git@github.com:neta-is-ok/neta-car-music.git`，默认应用更新仓库为 `neta-is-ok/neta-car-music`。
- 提交使用简洁的中文 Conventional Commits，只包含本次改动。
- 普通分支推送及 PR 只编译，不创建 Release。发布使用 `v<versionName>-<versionCode>` 标签，例如 `v0.0.1-1`；标签必须与源码版本完全一致。
- APK 命名为 `neta-car-music-<versionName>-<versionCode>.apk`，并随 Release 发布 `update.json`、`update.apk.sha256` 和同一提交的源码包。
- Release 必须使用稳定专用签名；本地密钥放在当前仓库根目录 `keystore.jks`，配置放在 `signing.properties`，两者均忽略、不提交，不放到仓库外的配置目录。CI 通过 GitHub Secrets 提供签名，不回退到 debug 签名发布。
- 应用更新只指向构建时配置的本项目 GitHub 仓库。未配置仓库时不请求上游更新，手动检查应显示“尚未配置更新仓库”。
- 更新清单与 APK 使用 `app/src/main/assets/cdn-providers.json` 中的 CDN 镜像；该文件初始来自 Netamade，保留独立副本供本仓库构建。
