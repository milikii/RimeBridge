# Rime 同步桥接 App 方案（暂名 RimeBridge）

> opus 5.5 · 2026-11-08。路径相对本目录：`R/`＝rimesync@b9fb992、`F/`＝fcitx5-android@eb5b502、`L/`＝librime 1.16.1（de4700e，与手机同版）、`FR/`＝fcitx5-rime 5.1.14，都在 `work/sources/` 下；`R/…/`＝`R/android/app/src/main/kotlin/com/king011/android/rimesync/`，`F/…/`＝`F/app/src/main/java/org/fcitx/fcitx5/android/`。**☆＝依据来自官方文档、AOSP 或第三方站点；本会话的联网工具全部被权限拦截，没能当场复核，动手前请抽查（链接见文末）。** 查不到的写“未找到”。

**结论**：用 Kotlin 只调 SDK 写一个小 App（目标 < 100 KB；rimesync 是 46,053,963 B），靠 JobScheduler 持久化周期作业，外加快捷设置磁贴。只复制有变化的 `*.userdb.txt`，流程是写临时文件、回读校验、再替换。不发通知、不常驻、不申请网络权限。编码约 3 天，另加真机 24 h 验收。

## 1 语言选定与理由

**主推 Kotlin，只用 Android SDK，不引第三方依赖**（不用 AndroidX、协程、Compose）。理由：
- SAF、`JobScheduler`、`TileService` 都是 Java 框架 API，Kotlin 可以直接调。
- R8 只留下用到的标准库部分。
- 没有 `.so`，不受 16 KB 页对齐要求影响☆。

瓶颈在 Binder IPC 和闪存 I/O，不在 CPU。rimesync 慢是做法问题（见 3.3），不是 Flutter 的问题。

- **备选 Java**：API 和架构都一样，也没有标准库开销。出现下面任一情况就换：R8 之后 `kotlin.*` 还残留 > 20 KB；或者构建机上 AGP 和 Kotlin 插件版本配不起来。
- **不选**：
  - Rust：SAF 只能经 JNI 回调 Java，而且每个 ABI 都要一个做了 16 KB 对齐的 `.so`☆。以后如果要在 App 里自己解析或合并 userdb，再重新评估。
  - Go/gomobile：要多带一个运行时 `.so`。
  - Flutter：rimesync 就是反例。

## 2 架构与模块/文件清单

```
键盘点 Rime「同步」/ fcitx 关机 save ─► 私有 data/rime/sync/<本机id>/*.userdb.txt
周期作业 / 磁贴 ─► SyncJobService ─► Syncer ─ push（仅本机id）─► 公共 Documents/fcitx/fcitx-rime/<本机id>/
                                           └ pull（其他id）◄─ 公共 <电脑id>/ ◄─ Syncthing-Fork ◄─ 电脑
```

| 文件 | 职责（估计行数） |
|---|---|
| `AndroidManifest.xml` | 只申请 `RECEIVE_BOOT_COMPLETED` 和 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`；不要网络、存储、通知权限 |
| `MainActivity.kt` | 单屏界面：两个目录的授权、installation_id（自动读）、周期 15/30/60 min、立即同步、省电设置入口、添加磁贴、最近 50 条日志（~180） |
| `Syncer.kt` | 列目录、比对、复制并校验、替换、维护清单、写日志（~250） |
| `SyncJobService.kt` | `BIND_JOB_SERVICE`、不导出；另起线程跑 Syncer，finally 里调 `jobFinished`（~50） |
| `SyncTileService.kt` | 点一下就排一个 expedited 一次性作业；副标题显示上次结果；`onStartListening` 时顺带补排周期作业（~60） |
| `Sched.kt` | `ensureScheduled()`：先 `getPendingJob(id)`，没有作业或参数变了才 `schedule`，避免重置周期计时（~40） |
| 资源与状态 | 两个矢量图标、`strings.xml`、主题 `android:Theme.DeviceDefault`。`SharedPreferences` 存 tree URI 和清单（每个文件记源和目标的 size+mtime，加 CRC32）。`filesDir/log.txt` 是 ≤ 32 KB 的环形日志 |

边界：
- 只通过 fcitx 公开的 DocumentsProvider 访问它的目录。这和 rimesync、系统“文件”App 用的是同一个入口；不修改输入法，也不往里注入东西。
- 不和 Syncthing 通信。
- 不用 Shizuku：无 root 时重启后要重新激活（`rimesync-plugin-feasibility.md:17`）。

## 3 关键机制

### 3.1 SAF 授权（沿用 rimesync）
- 用 `ACTION_OPEN_DOCUMENT_TREE` 选目录，再调 `takePersistableUriPermission(读|写)`（`R/…/MainActivity.kt:98-128`）。授权重启后仍然有效，后台作业可以直接用。
- **私有侧选 `data/rime`，比 rimesync 高一级。**
  - 选择器侧栏里的小企鹅根由 fcitx 自带的 provider 提供：根就是 `getExternalFilesDir`，带 `FLAG_SUPPORTS_IS_CHILD`（`F/…/provider/FcitxDataProvider.kt:80-100`）。Android 11+ 的选择器不让选 `Android/data`☆，所以只能走这个入口。
  - 选高一级，是为了能自动读 `installation.yaml` 里的 `installation_id: "…"`（`work/new/rime/installation.yaml:5`）。改了 id 也会自动跟上；文件 mtime 变了才重读。
- **公共侧选 `Documents/fcitx/fcitx-rime`。** Android 11+ 只禁止选存储根、Download、Android/data 等目录☆。
- 这座桥省不掉，原因有两个：
  - 在公共目录里，fcitx 只能读写它自己建的文件。依据是 #825 里维护者引用的 #660 评论（`F/../comment-2768906083.json`）。
  - 在 Android 16 上把 sync_dir 直接指向公共目录，合并会失败（`F/issue-825.json`）。

### 3.2 推拉顺序与过滤（与 rimesync 的 sync 模式一致，`R/README.md:19-22`）
1. **push**：私有 `sync/<本机id>/` → 公共 `<本机id>/`。**pull**：公共根下的其他子目录 → 私有 `sync/<id>/`，**跳过本机 id**（`R/lib/main.dart:192-209,245-253`）。两个方向碰的文件不重叠，沿用先推后拉。
2. **只处理 `*.userdb.txt`，且不以 `.` 开头。** rimesync 实际是递归全量复制（`main.dart:174-189`）。但 Rime 只合并各子目录里的 `<词库>.userdb.txt`（`L/src/rime/lever/user_dict_manager.cc:178-208`），yaml/txt 只是单向备份（`L/src/rime/lever/deployment_tasks.cc:541-586`）。
3. **跳过 `.` 开头的目录。** 两端都开了 Syncthing 版本控制（`CHECKLIST.md:122`），所以公共根下会有 `.stversions`、`.stfolder`☆。rimesync 会把它们当成设备目录拉回来。
4. **不传播删除。** 私有的本机 id 目录、公共的其他 id 目录，永远不写。私有侧的目标目录，有快照要放时才建。
5. Rime 的 Restore 是合并，不是替换（`user_dict_manager.cc:73-105`、`migrated/IMPORT-README.md:152`）。坏快照会污染词库，而且撤不回来，所以 3.4 的校验必须严。

### 3.3 只在变化时复制
- **每个目录只查一次**：`query(buildChildDocumentsUriUsingTree, [id,名,size,mtime])`。fcitx provider 处理这次查询就是一次 `listFiles`，同时带回 size/mtime（`FcitxDataProvider.kt:107-115,287-294`）。rimesync 则不同：它每次都把源和目标整个读进来逐字节比较（`main.dart:151-172`），还逐段调用 `DocumentFile.findFile`，每一段都要列一遍目录（`MainActivity.kt:319-347`；官方文档也说 DocumentFile 开销大☆）。
- **元数据没变就不开文件**：源和目标的 size/mtime 都与清单一致，就跳过。首轮没有清单时，size 相同再比 CRC，一致就只记清单。没有变化的一轮大约只有 6 次查询，读写 0 字节。
- **流式拷贝**：打开后拿到的是文件描述符（fcitx 侧直接用 `ParcelFileDescriptor.open` 打开真实文件，`:117-126`），之后读写不走 Binder。用一块 64 KB 缓冲反复读写，边拷边算 CRC32，内存占用与文件大小无关。rimesync 是整个文件 `readBytes()`（`MainActivity.kt:235-236`）。
- **源文件稳定性**：
  - mtime 距今不到 10 s 的，视为可能还在写：本轮跳过，排一个 15 s 后的补跑。拷完再查一次源的 size/mtime，变了就作废这次复制。
  - Rime 用 TsvWriter 写快照（`L/src/rime/dict/user_db.cc:115-127`）。它是不是原地覆盖写，本地没有 `tsv.cc`，**未核实**，按最坏情况防。
  - 即使用户没点同步，快照也可能变：fcitx 关机时会调 `save()`（`F/…/FcitxApplication.kt:38-46`），而 fcitx5-rime 的 `save()` 就是一次完整同步（`FR/src/rimeengine.cpp:484,632-639`）。

### 3.4 截断写 + 临时文件 + 回读校验

| 操作 | 私有侧 `FcitxDataProvider` | 公共侧 ExternalStorageProvider |
|---|---|---|
| 改名 | 支持（`:275-282`）；**目标已存在就抛错**，而且不检查 `renameTo` 的返回值（`:204-213`） | 支持；目标已存在时会改成 `rime_ice.userdb (1).txt`，**不会覆盖**☆ |
| 移动 | 重名时会落成 `原名 (2)`，也不检查返回值（`:215-225,248-257`） | 目标已存在会抛错☆ |
| 新建 | 已存在时建成 `原名 (2)`（`:140-159`），所以已有的快照绝不能再“新建” | 可能按 MIME 补扩展名☆，所以一律用 `application/octet-stream`（rimesync 也这样，`MainActivity.kt:287`） |
| 写模式 | mode 原样交给 `ParcelFileDescriptor.parseMode`（`:117-126`） | 官方文档：`"w"` 可能截断，也可能不截断☆ |

**结论：SAF 能改名，但不能原子覆盖。“临时文件＋改名”可行，只是必须先删后改。** 步骤：
1. 在目标同目录建 `.rimebridge-<名>.tmp`。Rime 和本 App 都不会把它当成快照。
2. 用 **`"wt"`** 模式写入。
   - rimesync 用的是默认的 `"w"`（`MainActivity.kt:298`），内容变短时可能残留旧文件的尾巴。`DIAGNOSIS-id-rename.md:12` 里那个 `rime_version: 1.12.0c05dfd6c7cf"` 的形态正好吻合，但这只是推测。
   - 边写边验格式：首行是 `# Rime user dictionary`，含 `#@/db_type\tuserdb`，末字节是 `\n`。真实快照就是这个格式（`work/new/rime/sync/e87b…/rime_ice.userdb.txt:1-6`），3 MB 的旧快照末字节也是 `\n`。格式不对就不推。
3. 关闭后**重新打开回读**，长度和 CRC32 都必须与源一致。
4. 删掉旧目标，调 `renameDocument`，再 `query` 一次。名字和大小都对，才记入清单。这一步防的是被改成 `(1)`、或者改名静默失败。
5. 每轮清理残留的 `.rimebridge-*.tmp`。

效果：从删除到改名只有毫秒级的“文件不存在”。读的一方只会看到旧的完整版、没有文件、或新的完整版，**看不到半截**。进程中途被杀，也只会留下 `.tmp`。

可选：在手机的 `.stignore` 里加一行 `.rimebridge-*`☆。

兜底方案，只有真机证明 SAF 替换不可靠时才用：公共侧申请“所有文件访问权限”，用 `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` 做原子替换。

### 3.5 后台定时组合

| 机制 | 在 Android 16 上的实际情况 | 用不用 |
|---|---|---|
| JobScheduler 周期作业 | 最短 15 min，flex ≥ 5 min☆；`setPersisted` 需要 `RECEIVE_BOOT_COMPLETED`，重启后由系统恢复☆；系统批量调度，运行时代持唤醒锁☆ | **主力**：30 min，flex 10 min，不加约束 |
| expedited 一次性作业 | API 31，用户触发后立即运行☆ | 用于磁贴、立即同步、补跑 |
| 内容 URI 触发 | 不能和周期、持久化同时用☆；fcitx provider 不发 `notifyChange`；监听 MediaStore 会被全机任意文件变动唤醒 | 不用 |
| WorkManager | 是 JobScheduler 的封装，最短同样是 15 min☆；会带进 Room/SQLite，体积远超目标（未实测） | 不用 |
| AlarmManager | Doze 会推迟普通闹钟，allow-while-idle 有配额☆；精确闹钟从 Android 14 起默认不授予☆；重启或强停就清空 | 不用 |
| 前台服务 | 必须挂常驻通知；Android 14 起要声明类型，15 起 `dataSync` 每 24 h 最多 6 h，且不能从开机广播启动☆ | 不用 |
| 开机自启 | `setPersisted` 已经覆盖 | 不写开机广播接收器 |
| 电池“不受限制” | 加入白名单的应用进 EXEMPTED 待机桶☆（AOSP `AppStandbyController`）；Doze 期间设备醒来时，它的作业可以跑☆（`DeviceIdleJobsController`）。不加的话，本 App 几乎不会被打开，会降到 rare/restricted 桶，作业被推迟、按天限额☆ | **必须** |
| vivo/OriginOS | 进程被杀没关系，关键是允许系统把它拉起来：打开自启动/关联启动、允许后台高耗电☆（dontkillmyapp 的 vivo 页）。**OriginOS 6 的菜单名未找到**，以真机为准 | 做设置引导，并实测 |

**推荐组合**：持久化周期作业，加磁贴触发 expedited 作业，加电池“不受限制”，加关闭应用休眠，加 vivo 自启动。默认 30 min（系统下限 15 min），在延迟和唤醒次数之间折中。急用时：先点一次磁贴拉取，再在键盘里点同步，最后再点一次磁贴推送。

## 4 Android 16 / 国行限制与对策

| 限制 | 对策 |
|---|---|
| Android 16 收紧作业运行配额：按待机桶计，在前台启动或与前台服务同时运行的作业也计入☆ | 单次运行 < 1 s；放进 EXEMPTED 桶 |
| Android 16 新增“作业被遗弃”停止原因：没调 `jobFinished`，JobParameters 就被回收了☆ | 工作线程持有 params，finally 里一定调 `jobFinished` |
| Android 14 起 `onStartJob/onStopJob` 在主线程超时直接 ANR☆ | 回调里只起线程，立即返回 |
| 应用休眠（Android 12+）：长期不用会停掉作业、收回权限☆ | 引导用户关闭“暂停未使用应用的活动”；用 `isAutoRevokeWhitelisted()` 读状态☆ |
| 强行停止（包括部分“一键清理”）会清空作业，直到下次打开☆ | 在最近任务里锁定；打开 App 或下拉面板时调 `ensureScheduled()` |
| targetSdk 36 强制 edge-to-edge☆ | 单屏界面处理好 insets |
| 作业迟迟不跑，不知道原因 | 显示 `JobScheduler.getPendingJobReasons(id)`（API 36☆，先判断版本） |
| 侧载开发者验证新规是否适用于国行无 GMS 的手机 | **未核实**；装自签 APK，打开“允许安装未知应用” |
| Syncthing-Fork 多久能察觉经 SAF 写入的文件 | **未核实**；验收时实测，慢就调短扫描间隔 |

## 5 构建环境

- **工具链**：JDK 17☆；SDK `platforms;android-36`、`build-tools;36.x`、cmdline-tools；Gradle Wrapper，加一个支持 compileSdk 36 的 AGP 稳定版和配套 Kotlin 插件。AGP 最低版本以构建当天的官方兼容表为准，本会话无法确认。
- **SDK 版本**：`minSdk 31`（用 expedited 作业不必判断版本）、`targetSdk 36`、`compileSdk 36`。
- **release 配置**：
  - 打开 `isMinifyEnabled`、`isShrinkResources`。
  - v2+v3 签名，关掉 v1。
  - `dependenciesInfo { includeInApk = false }`。
  - 排除 `kotlin/**`、`META-INF/*.kotlin_module`、`**/*.kotlin_builtins`、`META-INF/*.version`。
  - `dependencies {}` 留空。
- **在本 VM 上构建**：出站代理会做 TLS 中间人（`~/AGENTS.md`），要先把 Hatch Sandbox Egress CA 导入 JDK 信任库，再给 Gradle 配好代理。不方便的话，就在 Windows 上用 Android Studio。
- **签名与安装**：自建 keystore；APK 拷到手机安装，或用无线调试 `adb install`。

## 6 分步实施

1. 空壳加构建链（manifest、空 Activity、R8、签名），量一下体积，预期 < 50 KB（0.5 d）。
2. 授权页：选两个 tree、持久授权、读 installation_id（0.5 d）。
3. `Syncer`：列目录、比对、写临时文件、回读、替换、清单、日志；用“立即同步”跑通推和拉；故意造坏文件，确认会拒推（1 d）。
4. 调度：周期作业、磁贴、`ensureScheduled`；用 `adb shell cmd jobscheduler run -f <包名> <jobId>` 强制跑一次☆（0.5 d）。
5. 省电引导：电池“不受限制”、关闭应用休眠、跳到应用详情页（vivo 自启动在这里或在 i管家）（0.5 d）。
6. 真机 24 h 验收。按 `~/AGENTS.md`：代码交给代码模型写，肥鱼负责构建、复审和真机验收。

## 7 验收清单

| # | 验什么 | 怎么做 | 通过标准 |
|---|---|---|---|
| 1 | 体积 | 看 release APK 大小，解压看目录 | < 100 KB（上限 150 KB）；没有 `lib/`、`androidx/` |
| 2 | 无感 | 看应用信息页和通知栏 | 没有网络、存储、通知权限；没有常驻通知 |
| 3 | 授权与作业 | 重启后打开 App；跑 `adb shell dumpsys jobscheduler` | 两个目录仍是已授权；有 30 min 周期作业，且是 persisted |
| 4 | 待机桶 | `adb shell am get-standby-bucket <包名>` | 输出 `5`（EXEMPTED）☆ |
| 5 | 定时确实在跑 | 正常用机 2 h，不打开 App | 亮屏时段大约每 30–40 min 有一条 periodic 日志 |
| 6 | 杀后台/重启 | 在最近任务里划掉；再重启手机且不打开 App | 1 h 内出现 periodic 日志；没有就补 vivo 设置后重测 |
| 7 | push | 学一个新词 → 在键盘里点 Rime「同步」→ 点磁贴或等一个周期 | 公共 `<本机id>/` 里快照的长度和 CRC 与私有一致；电脑同步后能打出这个词 |
| 8 | pull | 电脑学一个新词 → 小狼毫同步 → 等 Syncthing 显示 Up to Date → 点磁贴或等一个周期 → 在键盘里点同步 | 私有 `sync/<电脑id>/` 与公共一致；手机能打出这个词 |
| 9 | 截断 | 在公共某个快照末尾追加几行垃圾 | 下一轮被替换，结果与源逐字节一致 |
| 10 | 防半截 | 在私有本机 id 目录放一个删掉末尾换行的测试快照 | 拒绝推送，公共的旧文件不变（测完删掉测试文件） |
| 11 | 过滤与不传播删除 | 看私有 `sync/`；删掉一个公共快照 | 只有 `*.userdb.txt`，没有 `.stversions` 和 yaml；私有副本没被删 |
| 12 | 耗电与开销 | 24 h 后看电池用量、跑 `adb shell dumpsys batterystats <包名>`☆、看日志 | 电池用量约 0%；作业累计只有几秒；无变化的轮次读写 0 字节，耗时 < 100 ms（不含冷启动） |
| 13 | Syncthing 延迟 | push 后看手机 Syncthing 多久开始上传 | 记下实测值，太慢就调短扫描间隔 |

**☆ 链接**（本会话未能打开）：
- developer.android.com：`reference/android/app/job/JobInfo.Builder`、`…/JobScheduler`、`reference/androidx/work/PeriodicWorkRequest`、`training/monitoring-device-state/doze-standby`、`topic/performance/appstandby`、`topic/performance/power/power-details`、`about/versions/14/changes/schedule-exact-alarms`、`about/versions/{14,15,16}/behavior-changes-*`、`topic/performance/app-hibernation`、`training/data-storage/shared/documents-files`、`reference/android/content/ContentResolver`、`reference/androidx/documentfile/provider/DocumentFile`、`guide/practices/page-sizes`
- AOSP：`FileSystemProvider.java`、`DeviceIdleJobsController.java`、`AppStandbyController.java`
- 其他：`dontkillmyapp.com/vivo`；`docs.syncthing.net/users/ignoring.html`、`/versioning.html`
