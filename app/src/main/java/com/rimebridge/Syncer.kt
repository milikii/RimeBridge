package com.rimebridge

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32

/**
 * 一轮同步：列目录 → 比对 → 写临时文件 → 回读校验 → 先删后改名 → 记清单。
 *
 * 同步范围：
 *  - **同步设备目录里的所有文件**（含子目录，递归到深度 8），不再只挑 *.userdb.txt。
 *  - 跳过以 . 开头的项（.stfolder / .stversions / .stignore 这类 Sync 自身的元数据），
 *    以及本 App 自己的 .rimebridge-*.tmp 残留。
 *  - 不传播删除：只写目标目录里的同名文件，别的文件一个都不动。
 *  - SAF 不能原子覆盖，所以顺序是「建 .tmp → "wt" 写 → 回读校验 → 删旧 → 改名 → 复核」。
 *  - 只有 *.userdb.txt 做 Rime 快照格式自检（坏快照会污染词库）；其余文件只校验长度和 CRC。
 */
class Syncer(private val ctx: Context) {

    companion object {
        const val EXT = ".userdb.txt"
        private const val TMP_PREFIX = ".rimebridge-"
        private const val OCTET = "application/octet-stream"
        private const val STABLE_MS = 10_000L
        private const val BUF = 64 * 1024
        private const val HEAD = 256
        private const val MAGIC_HEAD = "# Rime user dictionary"
        private const val MAGIC_TYPE = "#@/db_type"
    }

    data class Doc(
        val id: String,
        val name: String,
        val size: Long,
        val mtime: Long,
        val dir: Boolean
    )

    private val cr: ContentResolver = ctx.contentResolver

    @Volatile
    private var cancelled = false

    private var pushed = 0
    private var pulled = 0
    private var skipped = 0
    private var failed = 0
    private var needFollowup = false

    fun cancel() {
        cancelled = true
    }

    /** 跑一轮。返回（摘要, 是否建议 15 s 后补跑）。 */
    fun runRound(): Pair<String, Boolean> {
        val t0 = SystemClock.elapsedRealtime()
        val pvUri = Prefs.privTree(ctx)?.let { Uri.parse(it) }
        val pbUri = Prefs.pubTree(ctx)?.let { Uri.parse(it) }
        if (pvUri == null || pbUri == null) {
            Journal.w(ctx, "还没授权两个目录，本轮跳过")
            Prefs.setLast(ctx, "未授权")
            return "未授权" to false
        }
        return try {
            val privRoot = DocumentsContract.getTreeDocumentId(pvUri)
            val pubRoot = DocumentsContract.getTreeDocumentId(pbUri)

            // 顶层各列一次，后面全部复用
            val privTop = listDir(pvUri, privRoot)
            val pubTop = listDir(pbUri, pubRoot)

            val id = readInstallationId(pvUri, privTop)
            // 手填的同步 ID 优先；它既决定公共侧子目录名，也决定私有 sync/<名>
            val chosen = Prefs.syncId(ctx).ifBlank { id ?: "" }
            if (chosen.isBlank()) {
                Journal.w(ctx, "读不到 installation_id（私有目录要选 data/rime），也没手填同步 ID")
                Prefs.setLast(ctx, "无同步 ID")
                return "无同步 ID" to false
            }

            cleanTmp(pvUri, privTop)
            cleanTmp(pbUri, pubTop)

            // ---- push：私有 sync/<本机id>/ → 公共 <本机id>/ ----
            val syncDir = privTop.firstOrNull { it.dir && it.name == "sync" }
            val syncKids = syncDir?.let { listDir(pvUri, it.id) } ?: emptyList()
            // 手填 ID 与 yaml 里的 id 不一致时，两边都找一次，谁有快照就用谁
            val myDir = syncKids.firstOrNull { it.dir && it.name == chosen }
                ?: id?.let { y -> syncKids.firstOrNull { it.dir && it.name == y } }
            if (myDir == null) {
                Journal.w(ctx, "私有 sync/$chosen 不存在（先在键盘里点一次 Rime「同步」）")
            } else {
                val dst = ensureDir(pbUri, pubRoot, chosen, pubTop)
                if (dst == null) {
                    failed++
                    Journal.w(ctx, "公共侧建 $chosen 目录失败")
                } else {
                    syncTree(pvUri, myDir.id, pbUri, dst, "", "push|$chosen", true, 0)
                }
            }

            // ---- pull：公共根下其他子目录 → 私有 sync/<名>/ ----
            if (syncDir == null) {
                Journal.w(ctx, "私有 sync 目录不存在，跳过拉取")
            } else {
                val others = pubTop.filter {
                    !it.name.startsWith(".") && it.name != chosen && it.name != id &&
                        (it.dir || it.size < 0)
                }
                for (d in others) {
                    if (cancelled) break
                    val dst = ensureDir(pvUri, syncDir.id, d.name, syncKids)
                    if (dst == null) {
                        failed++
                        Journal.w(ctx, "私有侧建 sync/${d.name} 失败")
                        continue
                    }
                    syncTree(pbUri, d.id, pvUri, dst, "", "pull|${d.name}", false, 0)
                }
            }

            val ms = SystemClock.elapsedRealtime() - t0
            val summary = "推$pushed 拉$pulled 跳过$skipped 失败$failed ${ms}ms"
            Journal.w(ctx, "本轮完成：$summary")
            Prefs.setLast(ctx, summary)
            summary to needFollowup
        } catch (e: SecurityException) {
            Journal.w(ctx, "授权已失效，请重新授权：${e.message}")
            Prefs.setLast(ctx, "授权失效")
            "授权失效" to false
        } catch (e: Throwable) {
            Journal.w(ctx, "本轮失败：$e")
            Prefs.setLast(ctx, "失败：${e.javaClass.simpleName}")
            "失败" to false
        }
    }

    // ---------------- 自检 ----------------

    /**
     * 一键自检：把两端「App 实际看到了什么」写成多行文本。
     * 授权失败、ID 对不上、目录选错这类问题，看这段比猜快得多。
     */
    fun diag(): String {
        val sb = StringBuilder()
        val pv = Prefs.privTree(ctx)
        val pb = Prefs.pubTree(ctx)
        sb.append("私有 URI：").append(pv ?: "未授权").append('\n')
        sb.append("公共 URI：").append(pb ?: "未授权").append('\n')

        // 已持久化的授权到底给了读、写、还是都没有
        for ((tag, s) in listOf("私有" to pv, "公共" to pb)) {
            if (s == null) continue
            val u = runCatching { Uri.parse(s) }.getOrNull() ?: continue
            val p = ctx.contentResolver.persistedUriPermissions.firstOrNull { it.uri == u }
            sb.append(tag).append("持久授权：")
                .append(
                    when {
                        p == null -> "没有记录"
                        p.isWritePermission -> "读+写"
                        p.isReadPermission -> "只读"
                        else -> "空权限"
                    }
                ).append('\n')
        }

        val pvUri = pv?.let { u -> runCatching { Uri.parse(u) }.getOrNull() }
        val pbUri = pb?.let { u -> runCatching { Uri.parse(u) }.getOrNull() }

        if (pvUri != null) {
            val root = runCatching { DocumentsContract.getTreeDocumentId(pvUri) }.getOrNull()
            sb.append("私有 treeDocId：").append(root ?: "取不到").append('\n')
            val top = if (root == null) emptyList() else
                runCatching { listDir(pvUri, root) }.getOrElse {
                    sb.append("私有目录列不出来：").append(it.javaClass.simpleName)
                        .append(' ').append(it.message ?: "").append('\n')
                    emptyList()
                }
            sb.append("私有顶层（").append(top.size).append("）：")
                .append(top.joinToString { if (it.dir) it.name + "/" else it.name }).append('\n')
            val sync = top.firstOrNull { it.dir && it.name == "sync" }
            if (sync == null) {
                sb.append("私有 sync：不存在").append('\n')
            } else {
                val kids = runCatching { listDir(pvUri, sync.id) }.getOrElse { emptyList() }
                sb.append("私有 sync（").append(kids.size).append("）：")
                    .append(kids.joinToString { if (it.dir) it.name + "/" else it.name }).append('\n')
                for (k in kids.filter { it.dir }) {
                    val f = runCatching { filesToSync(pvUri, k.id) }.getOrElse { emptyList() }
                    sb.append("  sync/").append(k.name).append(" → ").append(f.size)
                        .append(" 个文件：").append(nameList(f)).append('\n')
                }
            }
            val yaml = top.firstOrNull { !it.dir && it.name == "installation.yaml" }
            sb.append("installation.yaml：")
                .append(if (yaml == null) "没有" else "有（mtime " + yaml.mtime + "）").append('\n')
        }

        if (pbUri != null) {
            val root = runCatching { DocumentsContract.getTreeDocumentId(pbUri) }.getOrNull()
            sb.append("公共 treeDocId：").append(root ?: "取不到").append('\n')
            val top = if (root == null) emptyList() else
                runCatching { listDir(pbUri, root) }.getOrElse {
                    sb.append("公共目录列不出来：").append(it.javaClass.simpleName)
                        .append(' ').append(it.message ?: "").append('\n')
                    emptyList()
                }
            sb.append("公共顶层（").append(top.size).append("）：")
                .append(top.joinToString { if (it.dir) it.name + "/" else it.name }).append('\n')
            for (d in top.filter { it.dir && !it.name.startsWith(".") }) {
                val f = runCatching { filesToSync(pbUri, d.id) }.getOrElse { emptyList() }
                sb.append("  ").append(d.name).append(" → ").append(f.size)
                    .append(" 个文件：").append(nameList(f)).append('\n')
            }
        }

        sb.append("自动读到 installation_id：").append(Prefs.installId(ctx) ?: "（未读取）").append('\n')
        sb.append("手填同步 ID：").append(Prefs.syncId(ctx).ifBlank { "（空，用自动值）" })
        return sb.toString()
    }

    // ---------------- 目录/文件枚举 ----------------

    private fun docUri(tree: Uri, docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    private fun childUri(tree: Uri, dirId: String): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)

    private fun listDir(tree: Uri, dirId: String): List<Doc> {
        val out = ArrayList<Doc>()
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        cr.query(childUri(tree, dirId), proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0)
                if (docId != null) {
                    val mime = if (c.isNull(4)) null else c.getString(4)
                    out.add(
                        Doc(
                            id = docId,
                            name = c.getString(1) ?: "",
                            size = if (c.isNull(2)) -1L else c.getLong(2),
                            mtime = if (c.isNull(3)) 0L else c.getLong(3),
                            dir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                        )
                    )
                }
            }
        }
        return out
    }

    /**
     * 该设备目录下需要同步的文件：**所有普通文件**，只排除隐藏项（. 开头）和临时残留。
     *
     * 注意不能再按 size 过滤：有些 provider 对刚写入 / 云端占位的文件返回 size = null（-1），
     * 旧代码的 `size >= 0` 会把它们整批静默丢掉——这正是「目录里好几个文件却只同步了一个」的成因之一。
     */
    private fun filesToSync(tree: Uri, dirId: String): List<Doc> =
        listDir(tree, dirId).filter {
            !it.dir && !it.name.startsWith(".") && !it.name.startsWith(TMP_PREFIX)
        }

    /** 自检里把文件清单压成一行，避免刷屏。 */
    private fun nameList(files: List<Doc>): String {
        if (files.isEmpty()) return "（空）"
        val head = files.take(12).joinToString("、") { it.name }
        return if (files.size > 12) "$head …共 ${files.size} 个" else head
    }

    private fun cleanTmp(tree: Uri, listing: List<Doc>) {
        for (d in listing) {
            if (!d.dir && d.name.startsWith(TMP_PREFIX)) {
                runCatching { DocumentsContract.deleteDocument(cr, docUri(tree, d.id)) }
            }
        }
    }

    private fun ensureDir(tree: Uri, parentId: String, name: String, listing: List<Doc>?): Doc? {
        val list = listing ?: listDir(tree, parentId)
        list.firstOrNull { it.dir && it.name == name }?.let { return it }
        val created = runCatching {
            DocumentsContract.createDocument(
                cr,
                docUri(tree, parentId),
                DocumentsContract.Document.MIME_TYPE_DIR,
                name
            )
        }.getOrNull() ?: return null
        val docId = runCatching { DocumentsContract.getDocumentId(created) }.getOrNull() ?: return null
        // 复核真实名字：个别 provider 重名时会加 " (2)"
        return listDir(tree, parentId).firstOrNull { it.id == docId }
            ?: Doc(docId, name, -1L, 0L, true)
    }

    private fun readInstallationId(tree: Uri, top: List<Doc>): String? {
        val cached = Prefs.installId(ctx)
        val yaml = top.firstOrNull { !it.dir && it.name == "installation.yaml" } ?: return cached
        if (cached != null && Prefs.installIdMtime(ctx) == yaml.mtime) return cached
        val text = runCatching {
            cr.openInputStream(docUri(tree, yaml.id))?.use { readCapped(it, 8192) }
        }.getOrNull()
        if (text != null) {
            val m = Regex("(?m)^\\s*installation_id:\\s*[\"']?([^\"'\\r\\n#]+)").find(text)
            val id = m?.groupValues?.get(1)?.trim()
            if (!id.isNullOrEmpty()) {
                Prefs.setInstallId(ctx, id, yaml.mtime)
                return id
            }
        }
        Journal.w(ctx, "installation.yaml 里没找到 installation_id")
        return cached
    }

    private fun readCapped(ins: InputStream, cap: Int): String {
        val buf = ByteArray(cap)
        var off = 0
        while (off < cap) {
            val n = ins.read(buf, off, cap - off)
            if (n <= 0) break
            off += n
        }
        return String(buf, 0, off, Charsets.UTF_8)
    }

    // ---------------- 同步主体 ----------------

    /**
     * 递归同步一棵目录树：先同步本层文件，再对每个可见子目录建好目标目录并递归下去。
     * [rel] 是相对设备目录的子路径（以 / 结尾），用于给 baseline 生成唯一 key。
     */
    private fun syncTree(
        srcTree: Uri,
        srcDirId: String,
        dstTree: Uri,
        dstDir: Doc,
        rel: String,
        keyPrefix: String,
        isPush: Boolean,
        depth: Int
    ) {
        if (cancelled) return
        if (depth > 8) {
            Journal.w(ctx, "目录层级超过 8 层，跳过：$rel")
            return
        }
        val all = listDir(srcTree, srcDirId)
        val files = all.filter { !it.dir && !it.name.startsWith(".") && !it.name.startsWith(TMP_PREFIX) }
        if (files.isNotEmpty()) {
            syncFiles(srcTree, srcDirId, files, dstTree, dstDir, "$keyPrefix|$rel", isPush, rel)
        } else if (depth == 0) {
            Journal.w(ctx, "${if (isPush) "源" else "对方"}目录 $rel 里没有可同步的文件")
        }
        for (sub in all) {
            if (cancelled) return
            if (!sub.dir || sub.name.startsWith(".")) continue
            val d = ensureDir(dstTree, dstDir.id, sub.name, null)
            if (d == null) {
                failed++
                Journal.w(ctx, "建子目录失败：$rel${sub.name}/")
                continue
            }
            syncTree(srcTree, sub.id, dstTree, d, "$rel${sub.name}/", keyPrefix, isPush, depth + 1)
        }
    }

    private fun syncFiles(
        srcTree: Uri,
        srcDirId: String,
        files: List<Doc>,
        dstTree: Uri,
        dstDir: Doc,
        keyPrefix: String,
        isPush: Boolean,
        rel: String
    ) {
        val dstMap = HashMap<String, Doc>()
        for (d in listDir(dstTree, dstDir.id)) {
            // 目录不参与同名比对：否则「源是文件、目标是同名目录」时会把整个目录删掉
            if (d.dir) continue
            if (d.name.startsWith(TMP_PREFIX)) {
                runCatching { DocumentsContract.deleteDocument(cr, docUri(dstTree, d.id)) }
                continue
            }
            dstMap[d.name] = d
        }

        Journal.w(
            ctx,
            "${if (isPush) "推送" else "拉取"} $rel 共 ${files.size} 个文件：" + nameList(files)
        )

        for (f in files) {
            if (cancelled) return

            // 源 mtime 太新 → 可能还在写，本轮跳过并补跑
            if (f.mtime > 0 && System.currentTimeMillis() - f.mtime < STABLE_MS) {
                needFollowup = true
                Journal.w(ctx, "暂缓（源可能还在写）：$rel${f.name}")
                continue
            }

            val old = dstMap[f.name]
            val key = keyPrefix + f.name
            val b = Prefs.baseline(ctx, key)

            // 元数据全对上就不开文件；size/mtime 拿不到（-1 / 0）时这条捷径不可信，走下面的 CRC
            val metaTrusted = f.size >= 0 && f.mtime > 0
            if (b != null && metaTrusted && b.srcSize == f.size && b.srcMtime == f.mtime &&
                old != null && old.size == f.size && b.dstSize == old.size && b.dstMtime == old.mtime
            ) {
                skipped++
                continue
            }

            // 大小一样（或两边都拿不到大小）又没基线：先比 CRC，一致就只补基线
            if (old != null && (f.size < 0 || old.size < 0 || f.size == old.size)) {
                val sc = crc(srcTree, f.id)
                val dc = crc(dstTree, old.id)
                if (sc != null && dc != null && sc.second == dc.second) {
                    Prefs.putBaseline(
                        ctx, key,
                        Baseline(sc.first, f.mtime, dc.first, old.mtime, sc.second)
                    )
                    skipped++
                    continue
                }
            }

            val ok = copyOne(srcTree, srcDirId, f, dstTree, dstDir, old, key, dstMap, isPush)
            if (ok) {
                if (isPush) pushed++ else pulled++
            } else {
                failed++
            }
        }
    }

    private fun copyOne(
        srcTree: Uri,
        srcDirId: String,
        f: Doc,
        dstTree: Uri,
        dstDir: Doc,
        old: Doc?,
        key: String,
        dstMap: MutableMap<String, Doc>,
        isPush: Boolean
    ): Boolean {
        val tmpName = TMP_PREFIX + f.name + ".tmp"
        val tmpUri = runCatching {
            DocumentsContract.createDocument(cr, docUri(dstTree, dstDir.id), OCTET, tmpName)
        }.getOrNull()
        if (tmpUri == null) {
            Journal.w(ctx, "建临时文件失败：${f.name}")
            return false
        }

        var size = 0L
        var crcVal = 0L
        var lastByte = -1
        val head = ByteArray(HEAD)
        var headLen = 0

        try {
            val sp = cr.openFileDescriptor(docUri(srcTree, f.id), "r")
                ?: throw IOException("打不开源文件")
            sp.use { srcPfd ->
                FileInputStream(srcPfd.fileDescriptor).use { ins ->
                    val tp = cr.openFileDescriptor(tmpUri, "wt")
                        ?: throw IOException("打不开临时文件")
                    tp.use { tmpPfd ->
                        FileOutputStream(tmpPfd.fileDescriptor).use { os ->
                            val buf = ByteArray(BUF)
                            val c = CRC32()
                            while (true) {
                                if (cancelled) throw IOException("已取消")
                                val n = ins.read(buf)
                                if (n <= 0) break
                                os.write(buf, 0, n)
                                c.update(buf, 0, n)
                                if (headLen < HEAD) {
                                    val k = minOf(n, HEAD - headLen)
                                    System.arraycopy(buf, 0, head, headLen, k)
                                    headLen += k
                                }
                                lastByte = buf[n - 1].toInt() and 0xFF
                                size += n
                            }
                            os.flush()
                            crcVal = c.value
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            dropQuietly(dstTree, tmpUri)
            Journal.w(ctx, "复制失败：${f.name} ${t.message}")
            return false
        }

        // 1) 只有 *.userdb.txt 做 Rime 快照格式自检（坏快照会污染词库且撤不回）；
        //    其它文件属于用户自己的数据，只校验长度和 CRC，不做内容判断。
        if (f.name.endsWith(EXT) && !formatOk(head, headLen, lastByte, size)) {
            dropQuietly(dstTree, tmpUri)
            Journal.w(ctx, "格式不符，拒推：${f.name}（${size}B）")
            return false
        }

        // 2) 回读校验：长度和 CRC 都要与刚写入的一致
        val back = crcOfUri(tmpUri)
        if (back == null || back.first != size || back.second != crcVal) {
            dropQuietly(dstTree, tmpUri)
            Journal.w(ctx, "回读校验失败：${f.name}")
            return false
        }

        // 3) 源在复制过程中变了就作废
        val nowDoc = listDir(srcTree, srcDirId).firstOrNull { it.id == f.id }
        if (nowDoc == null || nowDoc.size != f.size || nowDoc.mtime != f.mtime) {
            dropQuietly(dstTree, tmpUri)
            needFollowup = true
            Journal.w(ctx, "源在复制中变化，本轮作废：${f.name}")
            return false
        }

        // 4) SAF 不能原子覆盖：先删旧，再改名
        if (old != null) {
            val deleted = runCatching {
                DocumentsContract.deleteDocument(cr, docUri(dstTree, old.id))
            }.getOrDefault(false)
            if (!deleted) {
                dropQuietly(dstTree, tmpUri)
                Journal.w(ctx, "旧文件删不掉，放弃替换：${f.name}")
                return false
            }
        }
        val renamed = runCatching {
            DocumentsContract.renameDocument(cr, tmpUri, f.name)
        }.getOrNull()

        // 5) 改名后复核：防着被改成 "(1)"、或者改名静默失败。源大小未知（-1）时不比对大小。
        val after = listDir(dstTree, dstDir.id).firstOrNull { !it.dir && it.name == f.name }
        if (after == null || after.size != size || (f.size >= 0 && after.size != f.size)) {
            if (renamed != null) dropQuietly(dstTree, renamed)
            dropQuietly(dstTree, tmpUri)
            Journal.w(ctx, "改名后复核失败：${f.name}")
            return false
        }

        dstMap[f.name] = after
        Prefs.putBaseline(ctx, key, Baseline(size, f.mtime, after.size, after.mtime, crcVal))
        Journal.w(ctx, (if (isPush) "已推送 " else "已拉取 ") + f.name + " " + size + "B")
        return true
    }

    private fun formatOk(head: ByteArray, headLen: Int, lastByte: Int, size: Long): Boolean {
        if (size <= 0 || lastByte != '\n'.code) return false
        if (headLen < MAGIC_HEAD.length) return false
        val text = String(head, 0, headLen, Charsets.UTF_8)
        if (!text.startsWith(MAGIC_HEAD)) return false
        return text.contains("$MAGIC_TYPE\tuserdb") || text.contains("$MAGIC_TYPE userdb")
    }

    private fun crc(tree: Uri, docId: String): Pair<Long, Long>? =
        crcOfUri(docUri(tree, docId))

    private fun crcOfUri(uri: Uri): Pair<Long, Long>? = runCatching {
        var n = 0L
        val c = CRC32()
        val pfd = cr.openFileDescriptor(uri, "r") ?: return@runCatching null
        pfd.use {
            FileInputStream(it.fileDescriptor).use { ins ->
                val buf = ByteArray(BUF)
                while (true) {
                    val r = ins.read(buf)
                    if (r <= 0) break
                    c.update(buf, 0, r)
                    n += r
                }
            }
        }
        n to c.value
    }.getOrNull()

    private fun dropQuietly(tree: Uri, uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(cr, uri) }
    }
}
