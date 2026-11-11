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
- 应用内更新：`data/update/UpdateRepository.kt`；本地发布：`scripts/release.py`；CI 编译：`.github/workflows/build.yml`。
- `version.properties` 是 versionName / versionCode 的唯一来源。初始版本为 `0.0.1 (1)`；每次发布递增 versionCode。

以上 Kotlin 路径均相对 `app/src/main/java/io/neta/music/`。

## 修改与验证

- 读取目标文件及直接调用方后再修改，保留并行任务的兼容改动，不清理无关工作区或暂存区。
- 复用已有 Compose、Media3、Insets 和设置结构，不引入并行播放器或额外架构。
- 改动 Kotlin、类型、依赖、Gradle 或 manifest 后，至少执行 `./gradlew :app:assembleDebug`；涉及 Release 配置时补充对应构建和产物核对。
- 正式发布执行本地 `:app:assembleRelease`，复用仍适用的编译与 UI 验证结果。发布检查仅保留产物包名、版本、正式签名及更新清单所需的 SHA-256；不重复构建、下载回验或增加额外验证流程。
- 禁止新增或运行单元测试。可按变更需要编译现有 Android 仪器测试，但不要用测试通过代替界面与运行验证。
- 同一工作区一次只运行一个 Gradle 构建，保留 daemon 和构建缓存。
- 默认不启动模拟器或实车做界面实测，仅根据本次需求对改动做代码审查，未实测的改动说明原因即可；只有用户主动说明需要模拟器实测时，才安装到模拟器验证界面与交互。
- 模拟器实测优先使用 `emulator-5554`，其不可用时改用当前在线的其他模拟器；不新建模拟器，不卸载应用或清空现有数据。安装前核对签名兼容性。

## Git 与 Release

- 使用 `main` 分支。提交、推送、打标签、发布和工作区清理需要用户明确授权；完成修改不自动提交。
- 远端为 `git@github.com:neta-is-ok/neta-car-music.git`，默认应用更新仓库为 `neta-is-ok/neta-car-music`。
- 提交使用简洁的中文 Conventional Commits，只包含本次改动。
- 每次发布先比较上次正式 Release 与当前源码，向用户展示拟发布的版本号和完整更新说明；用户确认本次更新内容后，才能推送发布提交、标签或创建 GitHub Release。最初要求“执行发布”不代替对具体更新内容的确认。
- 更新说明面向友友用户：二次元风 + emoji、短句直击要点，不写技术术语和内部改动；只保留更新内容本身，不加“适用于哪吒车机 Android 11”、许可证、上游链接等尾注。
- 发布统一由本地完成编译、正式签名及更新附件生成，再由本地脚本上传 GitHub Release。使用 `scripts/release.py prepare --notes <项目内说明文件>` 准备产物，确认后执行 `scripts/release.py publish`；更新说明存放在忽略的 `build/` 或 `dist/` 中，不另建交接文档。
- 普通分支推送及 PR 的 GitHub Actions 只编译 Debug，不再通过标签或手动工作流构建、签名或发布 Release。发布使用 `v<versionName>-<versionCode>` 标签，例如 `v0.0.1-1`；标签必须与源码版本完全一致，并指向构建该 APK 的提交。
- 发布脚本的 Git 推送、Release 创建和附件上传必须支持有上限的重试；中断后可复用本地产物重跑上传，不重新编译。先创建草稿，三个更新附件上传完成后才公开；失败时保留本地产物和草稿，不自动清理或覆盖其他版本。
- GitHub Release 的 APK 命名为 `neta-car-music-<versionName>-<versionCode>.apk`，并随 Release 发布 `update.json` 和 `update.apk.sha256`。对应源码通过版本标签及 GitHub 自动生成的 Source code 归档提供，不额外生成或上传源码包。
- 本地正式签名构建必须同时导出中文名副本 `吒友爱听-<versionName>-<versionCode>.apk`，输出到 `app/build/outputs/local-apk/release/`。直接复制同一份正式签名 APK，不重复编译或重签；中文名副本用于本地交付，不额外上传到 GitHub Release。
- Release 必须使用稳定专用签名；本地密钥放在当前仓库根目录 `keystore.jks`，配置放在 `signing.properties`，两者均忽略、不提交，不放到仓库外的配置目录。发布不依赖 CI 签名 Secrets，不回退到 debug 签名。
- 应用更新只指向构建时配置的本项目 GitHub 仓库。未配置仓库时不请求上游更新，手动检查应显示“尚未配置更新仓库”。
- 更新清单与 APK 使用 `app/src/main/assets/cdn-providers.json` 中的 CDN 镜像；该文件初始来自 Netamade，保留独立副本供本仓库构建。
