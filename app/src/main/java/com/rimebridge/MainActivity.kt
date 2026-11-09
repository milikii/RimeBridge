package com.rimebridge

import android.annotation.SuppressLint
import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.service.quicksettings.TileService
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单屏界面，全代码构建（不引 AndroidX，也就不需要 layout XML 和 ViewBinding）。
 * 只做四件事：授权两个目录、选周期、立即同步、看日志 + 省电引导。
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_PRIV = 11
        private const val REQ_PUB = 12
        private const val ID_15 = 101
        private const val ID_30 = 102
        private const val ID_60 = 103
        private const val ADV_TITLE = "高级设置（目录 / 省电 / 磁贴 / 日志）"
    }

    private lateinit var root: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var intervalGroup: RadioGroup
    private lateinit var privBtn: Button
    private lateinit var pubBtn: Button
    private lateinit var authBox: LinearLayout
    private lateinit var advBox: LinearLayout
    private lateinit var advToggle: Button
    private lateinit var batteryBtn: Button
    private lateinit var customInput: EditText
    private lateinit var idInput: EditText
    private lateinit var syncBtn: Button
    private var advOpen = false

    @Volatile
    private var syncing = false

    private val ui = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
    private var rendering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = runCatching { buildRoot() }.getOrElse { e ->
            Journal.crash(this, e)
            errorView(e)
        }
        setContentView(view)
        // 强停会清空作业；每次启动都补排一次（参数没变就不会重置计时）
        runCatching { Sched.ensureScheduled(this) }
            .onFailure { Journal.crash(this, it) }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------- UI ----------------

    private fun buildRoot(): View {
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            v.setPadding(0, b.top, 0, b.bottom)
            insets
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(28))
        }
        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(label("Rime 同步桥接", 21f, true))
        root.addView(note("把手机 Rime 的快照推到公共目录，把电脑的快照拉回来。不发通知、不常驻、不联网。"))

        statusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(10), 0, dp(10))
            setTextIsSelectable(true)
        }
        root.addView(statusView)

        // ---- 首次：两个目录授权（都授权之后这块自动收起） ----
        authBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        authBox.addView(sectionTitle("先授权两个目录（只需一次，重启后仍有效）"))
        privBtn = action("① 选择私有目录（data/rime）") { pick(REQ_PRIV) }
        pubBtn = action("② 选择公共目录（fcitx-rime）") { pick(REQ_PUB) }
        authBox.addView(privBtn)
        authBox.addView(pubBtn)
        authBox.addView(
            note(
                "私有目录在侧栏「fcitx5-android」这一项里，往上退一级选到 data/rime；" +
                    "公共目录选 Documents/fcitx/fcitx-rime。"
            )
        )
        root.addView(authBox)

        // ---- 同步 ID（公共目录下的子目录名，可手填） ----
        root.addView(sectionTitle("同步 ID"))
        val idRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        idInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "留空 = 用自动读到的 ID"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    safe { saveSyncId() }
                    true
                } else {
                    false
                }
            }
        }
        idRow.addView(
            idInput,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        idRow.addView(action("保存 ID") { saveSyncId() })
        root.addView(idRow)
        root.addView(
            note(
                "填公共目录（Documents/fcitx/fcitx-rime）下属于这台手机的文件夹名，例如 IQOO；" +
                    "留空则用 installation.yaml 里的 installation_id。" +
                    "该目录里的所有文件都会同步（含子目录），只跳过 . 开头的隐藏项。" +
                    "不要和电脑那台的目录同名，否则同名文件会互相覆盖。改完立刻同步一次。"
            )
        )

        // ---- 同步间隔 ----
        root.addView(sectionTitle("同步间隔"))
        intervalGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for (item in listOf(ID_15 to "15 分钟", ID_30 to "30 分钟", ID_60 to "60 分钟")) {
            intervalGroup.addView(
                RadioButton(this).apply {
                    id = item.first
                    text = item.second
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                }
            )
        }
        intervalGroup.setOnCheckedChangeListener { _, checkedId ->
            if (rendering) return@setOnCheckedChangeListener
            safe {
                when (checkedId) {
                    ID_15 -> applyInterval(15)
                    ID_30 -> applyInterval(30)
                    ID_60 -> applyInterval(60)
                }
            }
        }
        root.addView(intervalGroup)

        val customRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        customInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "分钟"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    safe { applyCustom() }
                    true
                } else {
                    false
                }
            }
        }
        customRow.addView(
            customInput,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        customRow.addView(action("用这个间隔") { applyCustom() })
        root.addView(customRow)
        root.addView(note("也可以自己填分钟数（15–1440）。改完立刻重排作业，不用等下一轮。"))

        // ---- 主操作 ----
        root.addView(sectionTitle("动作"))
        syncBtn = action("立即同步") { startSync() }
        root.addView(syncBtn)
        batteryBtn = action("电池设为「不受限制」（否则定时几乎不跑）") { batteryUnrestricted() }
        root.addView(batteryBtn)
        root.addView(
            note(
                "「立即同步」在 App 进程里直接跑，不经过 JobScheduler。急用时先在键盘里点 Rime「同步」，" +
                    "再点这里推一次；拉取同理。"
            )
        )

        // ---- 高级（默认收起） ----
        advToggle = action(ADV_TITLE) {
            advOpen = !advOpen
            render()
        }
        root.addView(advToggle)

        advBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        advBox.addView(sectionTitle("目录授权"))
        advBox.addView(action("重选私有目录（data/rime）") { pick(REQ_PRIV) })
        advBox.addView(action("重选公共目录（fcitx-rime）") { pick(REQ_PUB) })
        advBox.addView(sectionTitle("省电"))
        advBox.addView(action("电池设为「不受限制」") { batteryUnrestricted() })
        advBox.addView(action("应用详情（关掉「暂停未使用应用的活动」）") { appDetails() })
        advBox.addView(
            note("vivo/OriginOS 还要在「自启动/关联启动」「后台高耗电」里放行，并在最近任务里锁定本应用。")
        )
        advBox.addView(sectionTitle("磁贴"))
        advBox.addView(action("把「Rime 同步」加到快捷设置") { requestTile() })
        advBox.addView(note("Android 13+ 可以一键请求添加；更早的下拉快捷设置 → 编辑 → 手动拖入。"))
        advBox.addView(sectionTitle("排查"))
        advBox.addView(action("一键自检（两端实际看到什么，写进日志）") { runDiag() })
        advBox.addView(action("测试作业调度（后台定时用）") { testJob() })
        advBox.addView(sectionTitle("日志（最近 50 条）"))
        val logBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        logBox.addView(action("刷新") { render() }, weightParams())
        logBox.addView(action("复制") {
            copyToClipboard(logView.text.toString())
            toast("已复制到剪贴板")
        }, weightParams())
        logBox.addView(action("清空") {
            Journal.clear(this)
            Journal.clearCrash(this)
            toast("日志已清空")
            render()
        }, weightParams())
        advBox.addView(logBox)
        val advRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        advRow2.addView(action("重置授权") { resetAll() }, weightParams())
        advRow2.addView(action("应用详情") { appDetails() }, weightParams())
        advBox.addView(advRow2)

        logView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setTextIsSelectable(true)
            movementMethod = LinkMovementMethod.getInstance()
        }
        advBox.addView(logView)
        root.addView(advBox)

        return scroll
    }

    /** buildRoot 崩了时的兜底页：至少能把异常文本截给别人。 */
    @SuppressLint("SetTextI18n")
    private fun errorView(e: Throwable): View {
        val sv = ScrollView(this)
        val tv = TextView(this).apply {
            text = "界面初始化失败\n\n" + e.stackTraceToString()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        sv.addView(tv)
        return sv
    }

    private fun label(text: String, size: Float, bold: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun sectionTitle(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(18), 0, dp(6))
        }

    private fun note(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.75f
            setPadding(0, dp(4), 0, dp(8))
        }

    private fun action(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isAllCaps = false
            // 任何按钮出错都不该把 App 弄崩：记日志 + 弹一句，界面照常
            setOnClickListener { safe(onClick) }
        }

    /** 兜底：把回调里的异常吞掉并记录，绝不冒泡到主线程。 */
    private fun safe(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            runCatching { Journal.crash(this, t) }
            runCatching { toast("操作出错：${t.javaClass.simpleName}（详情见日志）") }
            runCatching { render() }
        }
    }

    private fun weightParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(6)
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------------- 状态 ----------------

    @SuppressLint("SetTextI18n")
    private fun render() {
        try {
            renderInner()
        } catch (t: Throwable) {
            rendering = false
            runCatching { Journal.crash(this, t) }
            runCatching {
                statusView.text = "界面渲染出错：" + t.javaClass.simpleName + " " +
                    (t.message ?: "") + "\n（详情见高级设置里的日志）"
            }
        }
    }

    private fun renderInner() {
        rendering = true

        val autoId = Prefs.installId(this)
        val manual = Prefs.syncId(this)
        val effective = manual.ifBlank { autoId ?: "" }
        val pv = Prefs.privTree(this)
        val pb = Prefs.pubTree(this)
        val granted = pv != null && pb != null
        val cur = Prefs.interval(this)

        val pm = getSystemService(PowerManager::class.java)
        val exempt = pm?.isIgnoringBatteryOptimizations(packageName) == true

        val sb = StringBuilder()
        Journal.readCrash(this)?.let {
            val first = it.lineSequence().firstOrNull { l -> l.isNotBlank() } ?: ""
            sb.append("⚠ 上次崩溃：").append(first).append("\n（完整堆栈在「高级设置 → 日志」里，点「复制」可发给开发者）\n\n")
        }
        sb.append("同步 ID：").append(if (effective.isEmpty()) "（未设置）" else effective)
        if (manual.isBlank()) {
            sb.append("（自动）")
        } else {
            sb.append("（手填")
            if (autoId != null && autoId != manual) sb.append("，自动读到的是 ").append(autoId)
            sb.append("）")
        }
        sb.append("\n私有目录：").append(describe(pv))
        sb.append("\n公共目录：").append(describe(pb))
        sb.append("\n间隔：每 ").append(cur).append(" 分钟")
        sb.append("\n").append(Sched.statusText(this))
        val at = Prefs.lastAt(this)
        if (at > 0L) {
            sb.append("\n上次：").append(Prefs.lastResult(this) ?: "")
            sb.append("（").append(fmt.format(Date(at))).append("）")
        }
        sb.append("\n电池：")
            .append(if (exempt) "不受限制" else "受限制（点下面的按钮）")
        statusView.text = sb.toString()

        privBtn.text = if (pv == null) "① 选择私有目录（data/rime）" else "① 私有目录已授权 · 点击重选"
        pubBtn.text = if (pb == null) "② 选择公共目录（fcitx-rime）" else "② 公共目录已授权 · 点击重选"

        if (!idInput.hasFocus() && idInput.text.toString() != manual) {
            idInput.setText(manual)
        }

        // 都授权之后，目录那块收起来；高级默认收起
        authBox.visibility = if (granted) View.GONE else View.VISIBLE
        advBox.visibility = if (advOpen) View.VISIBLE else View.GONE
        advToggle.text = if (advOpen) "收起高级设置" else ADV_TITLE
        batteryBtn.visibility = if (exempt) View.GONE else View.VISIBLE
        syncBtn.text = if (syncing) "同步中…" else "立即同步"
        syncBtn.isEnabled = !syncing

        when (cur) {
            15 -> intervalGroup.check(ID_15)
            30 -> intervalGroup.check(ID_30)
            60 -> intervalGroup.check(ID_60)
            else -> intervalGroup.clearCheck()
        }
        if (cur != 15 && cur != 30 && cur != 60 && !customInput.hasFocus()) {
            val text = cur.toString()
            if (customInput.text.toString() != text) customInput.setText(text)
        }

        val crashText = Journal.readCrash(this, 4000)
        logView.text = if (crashText.isNullOrBlank()) {
            Journal.read(this, 50)
        } else {
            "=== 上次崩溃 ===\n" + crashText + "\n=== 运行日志 ===\n" + Journal.read(this, 50)
        }
        rendering = false
    }

    private fun describe(s: String?): String {
        if (s == null) return "未授权"
        val uri = runCatching { Uri.parse(s) }.getOrNull() ?: return "URI 有问题：$s"
        val p = contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        val state = when {
            p == null -> "授权已失效（要重选）"
            p.isWritePermission -> "已授权 读+写"
            p.isReadPermission -> "只授权了读（推不出去）"
            else -> "授权为空"
        }
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: s
        return "$state · $docId"
    }

    // ---------------- 动作 ----------------

    private fun pick(req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            putExtra("android.content.extra.SHOW_ADVANCED", true)
        }
        runCatching { startActivityForResult(i, req) }
            .onFailure { toast("打不开目录选择器：${it.message}") }
    }

    @Deprecated("平台 API 仍可用，且不想为它引 AndroidX")
    @SuppressLint("WrongConstant")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val tag = if (requestCode == REQ_PRIV) "私有" else "公共"
        if (resultCode != RESULT_OK) {
            Journal.w(this, "${tag}目录：选择被取消（rc=$resultCode）")
            return
        }
        val uri = data?.data
        if (uri == null) {
            toast("选择器没返回目录")
            Journal.w(this, "${tag}目录：RESULT_OK 但没有 data")
            return
        }

        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val fromIntent = data.flags and rw

        // 不要拿 data.flags 当门槛：不少 ROM 返回的 flags 是 0，但授权其实已经给了。
        // 先按 intent 说的试，再按读写全要试，最后退到只读；全失败才算真失败。
        val attempts = LinkedHashSet<Int>()
        if (fromIntent != 0) attempts.add(fromIntent)
        attempts.add(rw)
        attempts.add(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        var persistOk = false
        var err = ""
        for (a in attempts) {
            val r = runCatching { contentResolver.takePersistableUriPermission(uri, a) }
            if (r.isSuccess) {
                persistOk = true
                break
            }
            err = r.exceptionOrNull()?.let { it.javaClass.simpleName + ": " + (it.message ?: "") } ?: ""
        }

        // 持久化成不成，都实测一遍真实能不能用
        val probe = probeTree(uri)
        Journal.w(
            this,
            "${tag}目录：$uri\n  flags=0x${Integer.toHexString(data.flags)} " +
                "持久授权=${if (persistOk) "成功" else "失败（$err）"} 探测=$probe"
        )

        if (!persistOk) {
            if (probe.startsWith("读不了")) {
                toast("${tag}目录不可用：$probe")
                return
            }
            toast("${tag}目录只拿到临时授权，重启后可能要重选")
        }

        when (requestCode) {
            REQ_PRIV -> Prefs.setPrivTree(this, uri.toString())
            REQ_PUB -> Prefs.setPubTree(this, uri.toString())
        }
        toast("${tag}目录：$probe")
        render()
        if (Prefs.privTree(this) != null && Prefs.pubTree(this) != null) startSync()
    }

    /** 真去读一下顶层条目，再试着建一个探针文件——权限到底能不能用，一问便知。 */
    private fun probeTree(uri: Uri): String {
        val root = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (t: Throwable) {
            return "读不了：${t.javaClass.simpleName}"
        }
        val n = try {
            contentResolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(uri, root),
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null
            )?.use { c -> c.count }
        } catch (t: Throwable) {
            return "读不了：${t.javaClass.simpleName} ${t.message ?: ""}".trim()
        } ?: return "读不了：查询返回 null"

        // 写探针：建一个空文件马上删掉。写不了的话推不出去，早点告诉用户。
        val write = try {
            val f = DocumentsContract.createDocument(
                contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(uri, root),
                "text/plain",
                ".rimebridge-probe"
            )
            if (f == null) {
                "写不了（provider 返回 null）"
            } else {
                val del = runCatching { DocumentsContract.deleteDocument(contentResolver, f) }
                if (del.isSuccess) "可写" else "可写（但探针文件删不掉：$f）"
            }
        } catch (t: Throwable) {
            "写不了：${t.javaClass.simpleName} ${t.message ?: ""}".trim()
        }

        return "可读 $n 项，$write"
    }

    private fun startSync() {
        if (Prefs.privTree(this) == null || Prefs.pubTree(this) == null) {
            toast("先把两个目录授权了")
            return
        }
        if (syncing) {
            toast("正在同步，稍等")
            return
        }
        syncing = true
        render()
        val app = applicationContext
        Thread({
            val r = runCatching { Syncer(app).runRound() }
            val crash = r.exceptionOrNull()
            if (crash != null) Journal.crash(app, crash)
            ui.post {
                syncing = false
                toast(
                    when {
                        crash != null -> "同步出错：${crash.javaClass.simpleName}（详情见日志）"
                        else -> "完成：" + (r.getOrNull()?.first ?: "")
                    }
                )
                runCatching { render() }
            }
        }, "rimebridge-manual").start()
    }

    private fun saveSyncId() {
        val raw = idInput.text.toString().trim()
        if (raw.contains('/') || raw.contains('\\') || raw.startsWith(".")) {
            toast("ID 不能带斜杠，也不能以点开头")
            return
        }
        if (raw.length > 64) {
            toast("ID 太长（最多 64 字符）")
            return
        }
        Prefs.setSyncId(this, raw)
        hideKeyboard(idInput)
        toast(if (raw.isEmpty()) "已清空，改用自动读到的 ID" else "同步 ID 已设为 $raw")
        render()
        if (Prefs.privTree(this) != null && Prefs.pubTree(this) != null) startSync()
    }

    private fun runDiag() {
        val text = runCatching { Syncer(applicationContext).diag() }
            .getOrElse { "自检失败：${it.javaClass.simpleName} ${it.message ?: ""}" }
        for (line in text.split('\n')) Journal.w(this, "【自检】$line")
        advOpen = true
        render()
        toast("自检结果已写进日志")
    }

    private fun testJob() {
        val r = runCatching { Sched.runOnce(this) }
        r.exceptionOrNull()?.let { Journal.crash(this, it) }
        toast(
            when {
                r.isFailure ->
                    "排作业抛异常：${r.exceptionOrNull()?.javaClass?.simpleName}（已记日志）"
                r.getOrDefault(false) -> "作业已排队"
                else -> "作业被系统拒绝"
            }
        )
    }

    private fun copyToClipboard(s: String) {
        val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(android.content.ClipData.newPlainText("RimeBridge", s))
    }

    private fun applyCustom() {
        val v = customInput.text.toString().trim().toIntOrNull()
        if (v == null) {
            toast("先填分钟数")
            return
        }
        applyInterval(v)
    }

    /** 写进偏好并立刻重排作业——JobScheduler 周期下限 15 分钟。 */
    private fun applyInterval(min: Int) {
        if (min < 15) {
            toast("系统下限 15 分钟")
            return
        }
        if (min > 1440) {
            toast("最多 1440 分钟（24 小时）")
            return
        }
        Prefs.setInterval(this, min)
        Sched.ensureScheduled(this)
        hideKeyboard(customInput)
        ui.postDelayed({ render() }, 300)
    }

    private fun hideKeyboard(v: View) {
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    @SuppressLint("BatteryLife")
    private fun batteryUnrestricted() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(packageName) == true) {
            toast("已经是「不受限制」")
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            return
        }
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        if (runCatching { startActivity(direct) }.isFailure) {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.onFailure { toast("请手动到「电池 → 应用耗电管理」里设为不受限制") }
        }
    }

    /** Android 13+ 允许应用直接请求把磁贴加进快捷设置；更早版本只能给文字引导。 */
    @SuppressLint("NewApi")
    private fun requestTile() {
        if (Build.VERSION.SDK_INT < 33) {
            toast("请下拉快捷设置 → 编辑 → 把「Rime 同步」拖进面板")
            return
        }
        val sbm = getSystemService(StatusBarManager::class.java)
        if (sbm == null) {
            toast("系统没有状态栏服务，请手动添加磁贴")
            return
        }
        val r = runCatching {
            sbm.requestAddTileService(
                ComponentName(this, SyncTileService::class.java),
                getString(R.string.tile_label),
                Icon.createWithResource(this, R.drawable.ic_tile),
                mainExecutor
            ) { result: Int ->
                val msg = when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "磁贴已添加"
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "磁贴已经在面板里了"
                    else -> "未添加（结果码 $result），可手动添加"
                }
                ui.post { toast(msg) }
            }
        }
        if (r.isFailure) toast("请求失败：${r.exceptionOrNull()?.message}")
    }

    private fun appDetails() {
        val i = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(i) }.onFailure { toast("打不开应用详情页") }
    }

    @SuppressLint("WrongConstant")
    private fun resetAll() {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        for (p in contentResolver.persistedUriPermissions) {
            val s = p.uri.toString()
            if (s == Prefs.privTree(this) || s == Prefs.pubTree(this)) {
                runCatching {
                    contentResolver.releasePersistableUriPermission(p.uri, rw)
                }
            }
        }
        Prefs.reset(this)
        toast("已重置，请重新授权两个目录")
        render()
    }
}
