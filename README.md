# RimeBridge

Rime 同步桥接：把手机 fcitx5-android 里该设备同步目录（`data/rime/sync/<本机id>/`）下的**全部文件**
推到公共目录给 Syncthing 带走，再把电脑那边同步过来的文件拉回手机。
**不发通知、不常驻、不联网、不申请存储权限**，只走 SAF。

方案与取舍见 [`桥接 App 方案.md`](桥接%20App%20方案.md)。本目录是它的实现。

```
键盘点 Rime「同步」 ─► 私有 data/rime/sync/<本机id>/（全部文件，递归子目录）
周期作业 / 磁贴 ─► SyncJobService ─► Syncer
     push（仅本机 id）─► 公共 Documents/fcitx/fcitx-rime/<本机id>/
     pull（其他 id）  ◄─ 公共 <电脑id>/ ◄─ Syncthing-Fork ◄─ 电脑
```

## 组件

| 文件 | 职责 |
|---|---|
| `MainActivity.kt` | 单屏界面：状态、同步 ID（可手填）、同步间隔（15/30/60 或任意 15–1440 分钟）、立即同步；目录授权首次显示、之后收进「高级」 |
| `Syncer.kt` | 一轮同步：递归列目录 → 比元数据 → 流式拷贝（边拷边算 CRC32）→ userdb 格式自检 → 回读校验 → 先删后改名 → 记清单 |
| `SyncJobService.kt` | `JobService`：主线程只起线程立刻返回，工作线程 `finally` 里 `jobFinished` |
| `SyncTileService.kt` | 快捷设置磁贴：点一下排 expedited 一次性作业；副标题显示上次结果 |
| `Sched.kt` | 持久化周期作业 + `ensureScheduled` 不重置计时；一次性作业 expedited → 普通作业两级回落 |
| `Prefs.kt` | SharedPreferences：tree URI、installation_id 缓存、每文件基线（size/mtime/CRC） |
| `Journal.kt` | `filesDir/log.txt` 环形日志，≤ 32 KB；`crash.txt` 存未捕获异常 |
| `App.kt` | 安装 `UncaughtExceptionHandler`，崩溃堆栈先落盘，下次打开在状态区顶部提示 |

## 构建

需要 JDK 17、Android SDK `platforms;android-36` + `build-tools;36.0.0`。
AGP 8.13.0 / Kotlin 2.2.20 / Gradle 8.14.3。

```bash
# local.properties 里写 sdk.dir，然后：
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

签名用自签的 `keystore/rimebridge.jks`，**它不在仓库里，口令也不在**。构建前在你自己的
`~/.gradle/gradle.properties`（或命令行 `-P`）里补上：

```properties
RB_STORE_PASSWORD=***
RB_KEY_PASSWORD=***
RB_KEY_ALIAS=rimebridge
```

没给口令时 release 会回落 **debug 签名**——能构建、能安装，只是签名不同；签名一变就必须先卸载旧版。
Release 页里的 APK 用的是同一把自签证书，可以直接覆盖安装。

## 安装与首次配置

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

1. 打开 App → 「① 选择私有目录」：侧栏选 **fcitx5-android** 那一项，进入后**往上退一级选 `data/rime`**
   （要这一点是为了能读 `installation.yaml` 里的 `installation_id`；Android 11+ 的选择器不让直接选 `Android/data`）。
2. 「② 选择公共目录」：选 `Documents/fcitx/fcitx-rime`。两个都授权后这块自动收起，要改再进「高级设置」。
   授权后会实测「读了多少项、能不能写」，结果直接弹出来；写不进去会当场告诉你。
3. 「同步 ID」：填公共目录下属于这台手机的文件夹名（例如 `IQOO`），留空则用 `installation.yaml` 的
   `installation_id`。填完立刻同步一次。**别和电脑那台的目录同名**，否则同名文件会互相覆盖。
   该目录里的所有文件都会同步（含子目录），只跳过 `.` 开头的隐藏项。
4. 「电池设为不受限制」，并在应用详情里关掉「暂停未使用应用的活动」。
5. vivo/OriginOS：再放行「自启动/关联启动」「后台高耗电」，并在最近任务里锁定本应用。
6. 下拉快捷设置 → 编辑 → 把「Rime 同步」拖进面板。

## 验收（对应方案第 7 节）

```bash
# 作业是否在排队、是不是 persisted
adb shell dumpsys jobscheduler | grep -A 12 com.rimebridge

# 强制跑一次周期作业
adb shell cmd jobscheduler run -f com.rimebridge 1001

# 待机桶（要 5 = EXEMPTED）
adb shell am get-standby-bucket com.rimebridge

# 耗电
adb shell dumpsys batterystats com.rimebridge | head -40
```

手工验收要点：

- **push**：手机学一个新词 → 键盘里点 Rime「同步」→ 磁贴/等一轮 → 公共 `<本机id>/` 里快照的
  长度和 CRC 与私有一致，电脑同步后能打出这个词。
- **pull**：电脑学词 → 小狼毫同步 → Syncthing 显示 Up to Date → 手机点一次 → 私有 `sync/<电脑id>/` 一致 → 能打出。
- **截断**：在公共快照末尾追几行垃圾 → 下一轮被替换，逐字节一致。
- **防半截**：在私有本机 id 目录放一个删掉末尾换行的快照 → 拒推（日志「格式不符，拒推」），公共旧文件不动。
- **范围**：`sync/<id>/` 下的文件**全部**同步（含子目录），只跳过 `.` 开头的项（`.stversions`/`.stfolder`/`.stignore`）
  与本 App 的 `.rimebridge-*.tmp`；删公共文件不会删私有副本。

## 已知边界

- SAF 不能原子覆盖：替换是「建 `.tmp` → `"wt"` 写 → 回读校验 → 删旧 → 改名」，中间有毫秒级文件不存在。
  进程被杀只会留下 `.rimebridge-*.tmp`，下一轮开头清掉。
- 不改输入法、不注入数据、不与 Syncthing 通信、不用 Shizuku。
- 源文件 mtime 距今 < 10 s 视为可能还在写：本轮跳过，15 s 后补跑一次。
- 兜底方案（只有真机证明 SAF 替换不可靠时才用）：公共侧申请「所有文件访问权限」，
  用 `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`。

## 与方案的差异（几处实现决定）

1. **添加磁贴**走 `StatusBarManager.requestAddTileService`（API 33+），比让用户去面板里手动拖更省事；
   API < 33 时退回文字引导。
2. **补跑加了 60 s 防抖**：源文件 mtime 太新时排 15 s 后的补跑，但 60 s 内只补一次，
   避免「补跑 → 又发现不稳 → 再补跑」自己把自己叫醒成循环。
3. **界面直接显示电池白名单状态**（`isIgnoringBatteryOptimizations`）与 `getPendingJob` 的
   周期/flex/persisted；API 36 的 `getPendingJobReasons` 用反射取，编不过就算了，不影响主流程。
4. **间隔可自定义**：除 15/30/60 三档，还能直接填 15–1440 分钟（JobScheduler 周期下限是 15 分钟）；
   改完立即重排作业，不等下一轮。
5. **音量**：release 构建 `isMinifyEnabled + isShrinkResources + -repackageclasses`，
   R8 后只剩 49 个类，入口三个组件保留原名，其余全被重打包为 `a`/`b`/`c`…。

## 实测数据（本机构建）

| 项 | 值 |
|---|---|
| release APK | **80.0 KB**（上限 100 KB） |
| 条目 | 7 个：`classes.dex` 72 KB（62 个类）、`resources.arsc`、manifest、2 个 res、2 个 META-INF |
| 权限 | 只有 `RECEIVE_BOOT_COMPLETED`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| 依赖 | 无（含 AndroidX 也没有），无 `lib/` |
| 签名 | v3（v1 关闭） |
| Lint | 0 error（仅剩「Gradle 有新版本」提示） |

## v1.1 修复（真机反馈的三个问题）

### 1. 点「立即同步」闪退 —— 根因是作业窗口太短

Android 15 起，`JobInfo.Builder.build()` 会检查 `deadline - minLatency`：**小于 15 分钟直接抛
`IllegalArgumentException`**（进程内就是闪退）。旧代码的兜底作业正是 `minLatency=0` +
`deadline=2 分钟`，而它会在这条路径上被走到：

```
点「立即同步」→ 排 expedited 作业 → iQOO 的省电策略把 expedited 拒了（RESULT_FAILURE）
            → 回落「普通作业 + 2 分钟 deadline」→ build() 抛异常 → 闪退
```

这也解释了为什么打开 App 没事（周期作业没有 deadline）、只有点按钮才崩。

修法（`Sched.kt`）：

- 兜底作业的 deadline 改成 `延迟 + 15 分钟 + 1 秒`，不再踩窗口检查；
- 每一级 `build()`、`schedule()` 都单独 `runCatching`，参数被 ROM 拒绝最多是「排队失败」，不再崩；
- **「立即同步」不再走 JobScheduler**：直接在 App 进程里起线程跑 `Syncer.runRound()`，结果回主线程刷界面。
  作业调度只剩磁贴和周期任务在用。

### 2. 公共目录授权不上

旧代码用 `data.flags and (READ|WRITE)` 当门槛，`== 0` 就直接 `return`——不少 ROM 返回的
`Intent.flags` 就是 0（授权其实已经给了），于是永远卡在「② 选择公共目录」。

现在不去猜 flag，而是**试完再实测**：依次按「intent 说的 → 读写全要 → 只读」尝试
`takePersistableUriPermission`，然后真的去列一次顶层条目、再建一个 `.rimebridge-probe` 探针文件删掉，
把「可读 N 项 / 可写 / 写不了：原因」直接弹出来并写进日志。持久化失败但能读写时仍然可用（只是重启后要重选）。

### 3. 同步 ID 没有输入的地方

新增「同步 ID」输入框（主界面，不在高级里）。它决定公共目录下的子目录名，也决定私有 `sync/<名>`：

- 留空 = 用 `installation.yaml` 里的 `installation_id`（原来的行为）；
- 填 `IQOO` = 推送写到公共 `IQOO/`，读取优先找私有 `sync/IQOO/`，找不到再退回 `sync/<installation_id>/`；
- 拉取时把自己这个名字排除掉；填完立刻同步一次，不用等周期。

注意：别和电脑那台的目录同名，否则两边写同一批 `*.userdb.txt` 会互相覆盖。

### 附加：崩溃不再无声

`App.kt` 装了 `UncaughtExceptionHandler`，未捕获异常先写 `filesDir/crash.txt` 再交给系统；
下次打开 App 状态区顶部会出现「⚠ 上次崩溃：…」，完整堆栈在「高级设置 → 日志」里，点「复制」即可发出来。
所有按钮回调、界面渲染、`buildRoot`、`onStartJob` 都各自加了兜底，单个操作出错不会拖垮整个 App。

排查用：「高级设置 → 一键自检」会把两端 URI、持久授权（读/写）、顶层目录、`sync/` 下每个子目录的
`*.userdb.txt` 数量、`installation.yaml` 是否存在、自动 ID 与手填 ID 全写进日志。

## v1.2：同步范围改为「目录下全部文件」

上一版只挑 `*.userdb.txt`，真机上表现为「sync 目录里好几个文件，只有一个过去了」。这里有三个收窄点被一次放开：

| 旧行为 | 问题 | 现在 |
|---|---|---|
| `name.endsWith(".userdb.txt")` | 只认 userdb 快照，同目录的其它文件（`.txt` 导出、`*.yaml`、自建子目录）全被丢掉 | 目录里**所有非隐藏文件**都同步 |
| `size >= 0` | 部分 provider 对刚写入 / 云端占位的文件返回 `size = null`，整批被静默丢弃 | 不再用 size 当门槛；`-1` 时走 CRC 比对，复核时也跳过大小比较 |
| `formatOk()` 对所有文件生效 | 非 userdb 文件一律「格式不符，拒推」 | 只有 `*.userdb.txt` 做格式自检，其它文件只校验长度 + CRC |

另外加了**递归**（深度上限 8）：`sync/<id>/` 下如果有子目录也照搬，`baseline` 的 key 用相对路径
（`push|IQOO|sub/xxx.txt`），不会和根层文件撞车。

日志也改了：每轮会把「推送/拉取 <目录> 共 N 个文件：a、b、c」写一行，能直接看出某个文件是被跳过了
还是压根没被列出来；「一键自检」里每个设备目录后面也带上文件名清单。

> 注意：范围放开后，公共 `<id>/` 里同名文件会被覆盖（这是同步的本意）。别把两台设备的 ID 取成同名。
