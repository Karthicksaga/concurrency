package com.localai.app

/**
 * Minimal reader for Android's tombstone protobuf (system/core/debuggerd/proto/tombstone.proto), enough to show
 * where a native crash happened. Only the fields used here are decoded; everything else is skipped.
 */
object Tombstone {
    private class Reader(val b: ByteArray, var pos: Int = 0, val end: Int = b.size) {
        fun more() = pos < end
        fun varint(): Long { var shift = 0; var r = 0L; while (true) { val x = b[pos++].toInt() and 0xff; r = r or ((x and 0x7f).toLong() shl shift); if (x < 0x80) return r; shift += 7 } }
        /** Calls [f] for each field: (number, wireType, value-as-long or -1, sub-reader for length-delimited). */
        fun fields(f: (Int, Long, Reader?) -> Unit) {
            while (more()) {
                val key = varint(); val num = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> f(num, varint(), null)
                    1 -> { pos += 8; f(num, -1, null) }
                    5 -> { pos += 4; f(num, -1, null) }
                    2 -> { val len = varint().toInt(); val sub = Reader(b, pos, pos + len); pos += len; f(num, -1, sub) }
                    else -> return
                }
            }
        }
        fun string() = String(b, pos, end - pos, Charsets.UTF_8)
    }

    private class Frame(var fn: String = "", var off: Long = 0, var file: String = "", var relPc: Long = 0)
    private class Thread(var id: Long = 0, var name: String = "", val frames: ArrayList<Frame> = ArrayList())

    fun summarize(bytes: ByteArray): List<String> {
        var tid = 0L; var signal = ""; var abort = ""; val causes = ArrayList<String>()
        val threads = HashMap<Long, Thread>(); val logs = ArrayList<String>()
        Reader(bytes).fields { n, v, sub ->
            when (n) {
                6 -> tid = v
                10 -> sub?.fields { sn, _, ss -> if ((sn == 2 || sn == 4) && ss != null) signal += (if (signal.isEmpty()) "" else " ") + ss.string() }
                14 -> abort = sub?.string().orEmpty()
                15 -> sub?.fields { cn, _, cs -> if (cn == 1 && cs != null) causes += cs.string() }
                16 -> sub?.fields { mn, mv, ms ->                       // map entry: key=1, value=2 (Thread)
                    if (mn == 2 && ms != null) {
                        val t = Thread()
                        ms.fields { tn, tv, ts ->
                            when (tn) {
                                1 -> t.id = tv
                                2 -> t.name = ts?.string().orEmpty()
                                4 -> if (ts != null && t.frames.size < 64) { val fr = Frame()
                                    ts.fields { fn, fv, fs -> when (fn) { 1 -> fr.relPc = fv; 4 -> fr.fn = fs?.string().orEmpty(); 5 -> fr.off = fv; 6 -> fr.file = fs?.string().orEmpty() } }
                                    t.frames += fr }
                            }
                        }
                        threads[t.id] = t
                    }
                }
                18 -> sub?.fields { ln, _, ls ->                          // LogBuffer: logs = 2 (LogMessage)
                    if (ln == 2 && ls != null) { var prio = 0L; var tag = ""; var msg = ""
                        ls.fields { mn, mv, ms -> when (mn) { 4 -> prio = mv; 5 -> tag = ms?.string().orEmpty(); 6 -> msg = ms?.string().orEmpty() } }
                        if (prio >= 5 || tag.startsWith("LocalAI")) logs += "$tag: ${msg.trim().take(240)}" }
                }
            }
        }
        val out = ArrayList<String>()
        if (signal.isNotEmpty()) out += "Signal: $signal"
        if (abort.isNotEmpty()) out += "Abort: ${abort.take(400)}"
        causes.take(2).forEach { out += "Cause: $it" }
        val crashed = threads[tid] ?: threads.values.firstOrNull()
        crashed?.let { t ->
            out += "Crashed thread: ${t.name} ($tid)"
            t.frames.take(18).forEachIndexed { i, f ->
                val lib = f.file.substringAfterLast('/')
                out += "#%02d %s %s".format(i, lib, if (f.fn.isNotEmpty()) "${f.fn.take(140)}+${f.off}" else "pc 0x%x".format(f.relPc))
            }
        }
        if (logs.isNotEmpty()) { out += "Last log lines:"; out += logs.takeLast(12) }
        return out
    }
}
