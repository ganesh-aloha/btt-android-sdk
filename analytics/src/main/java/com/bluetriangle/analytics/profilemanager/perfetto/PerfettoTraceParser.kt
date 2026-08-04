package com.bluetriangle.analytics.profilemanager.perfetto

import java.io.File

/**
 * Parses a Perfetto trace (`*.perfetto-trace`) from stack-sampling profiling and extracts
 * symbolized call stacks (main-thread samples are the usual ANR interest).
 *
 * **Note:** System-triggered ANR traces often contain only ftrace / process_stats /
 * frametimeline and **no** Java/native call stacks. Stacks appear when the profile used
 * stack sampling (`linux.perf` / [android.os.ProfilingManager] stack-sampling request).
 *
 * Not thread-safe — use a fresh instance per parse, or confine to one thread.
 */
class PerfettoTraceParser {

    data class Frame(
        val functionName: String,
        val mappingPath: String?,
        val relPc: Long,
        val lineNumber: Int?,
    ) {
        fun format(): String {
            val loc = lineNumber?.let { ":$it" } ?: ""
            val where = mappingPath?.let { " [${it.substringAfterLast('/')}]" } ?: ""
            return if (functionName.isNotEmpty()) "$functionName$loc$where"
            else "0x${relPc.toString(16)}$where"
        }
    }

    /**
     * One call-stack sample. [frames] are leaf-first (innermost at index 0), matching
     * Android ANR thread-dump order.
     */
    data class StackTrace(
        val pid: Int,
        val tid: Int,
        val threadName: String?,
        val processName: String?,
        val timestampNs: Long,
        val frames: List<Frame>,
    ) {
        val isMainThread: Boolean get() = pid != 0 && tid == pid

        fun format(): String = buildString {
            append("\"${threadName ?: "?"}\" tid=$tid pid=$pid")
            processName?.let { append(" ($it)") }
            append(" @ ${timestampNs}ns\n")
            if (frames.isEmpty()) append("  <no frames>\n")
            frames.forEach { append("  at ${it.format()}\n") }
        }
    }

    private class FrameInfo(
        val functionNameId: Long,
        val mappingId: Long,
        val relPc: Long,
        val lineNumber: Int,
    )

    private class SequenceState {
        val functionNames = HashMap<Long, String>()
        val mappingPaths = HashMap<Long, String>()
        val mappings = HashMap<Long, List<Long>>()
        val frames = HashMap<Long, FrameInfo>()
        val callstacks = HashMap<Long, List<Long>>()
        var pid: Int = 0
        var tid: Int = 0
        var threadName: String? = null

        fun clearInterned() {
            functionNames.clear()
            mappingPaths.clear()
            mappings.clear()
            frames.clear()
            callstacks.clear()
        }
    }

    private val sequences = HashMap<Long, SequenceState>()
    private val processNames = HashMap<Int, String>()
    private val samples = ArrayList<StackTrace>()

    private object F {
        const val TRACE_PACKET = 1
        const val TIMESTAMP = 8
        const val SEQUENCE_ID = 10
        const val INTERNED_DATA = 12
        const val SEQUENCE_FLAGS = 13
        const val INCREMENTAL_STATE_CLEARED = 41
        const val PROCESS_DESCRIPTOR = 43
        const val THREAD_DESCRIPTOR = 44
        const val STREAMING_PROFILE_PACKET = 54
        const val PERF_SAMPLE = 66
        const val SEQ_INCREMENTAL_STATE_CLEARED = 0x1
        const val ID_FUNCTION_NAMES = 5
        const val ID_FRAMES = 6
        const val ID_CALLSTACKS = 7
        const val ID_MAPPING_PATHS = 17
        const val ID_MAPPINGS = 19
        const val STR_IID = 1
        const val STR_STR = 2
        const val FRAME_IID = 1
        const val FRAME_FUNCTION_NAME_ID = 2
        const val FRAME_MAPPING_ID = 3
        const val FRAME_REL_PC = 4
        const val FRAME_LINE_NUMBER = 6
        const val CS_IID = 1
        const val CS_FRAME_IDS = 2
        const val MAP_IID = 1
        const val MAP_PATH_STRING_IDS = 7
        const val PROC_PID = 1
        const val PROC_NAME = 6
        const val THREAD_PID = 1
        const val THREAD_TID = 2
        const val THREAD_NAME = 5
        const val PS_PID = 2
        const val PS_TID = 3
        const val PS_CALLSTACK_IID = 4
        const val SPP_CALLSTACK_IID = 1
    }

    fun parse(file: File): List<StackTrace> = parse(file.readBytes())

    fun parse(bytes: ByteArray): List<StackTrace> {
        sequences.clear()
        processNames.clear()
        samples.clear()
        val reader = PerfettoTraceReader(bytes)
        while (reader.hasMore) {
            val field = reader.readField()
            if (field.number == F.TRACE_PACKET &&
                field.wireType == PerfettoTraceReader.WIRE_LENGTH_DELIMITED
            ) {
                handlePacket(bytes, field)
            }
        }
        return samples.map { s ->
            if (s.processName == null && processNames.containsKey(s.pid)) {
                s.copy(processName = processNames[s.pid])
            } else {
                s
            }
        }
    }

    fun extractLastStackTrace(file: File, pid: Int? = null): StackTrace? =
        extractMainThreadStacks(parse(file), pid).maxByOrNull { it.timestampNs }

    fun extractMainThreadStacks(allSamples: List<StackTrace>, pid: Int? = null): List<StackTrace> =
        allSamples.filter { it.isMainThread && (pid == null || it.pid == pid) }

    /**
     * Formats the best main-thread stack from [file], or null if the trace has no stack samples
     * (common for system ANR system-traces without stack sampling).
     */
    fun extractFormattedMainThreadStack(
        file: File,
        pid: Int? = null,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): String? {
        val last = extractLastStackTrace(file, pid) ?: return null
        val text = last.format()
        return if (text.length <= maxChars) text
        else text.take(maxChars) + "\n…(truncated)"
    }

    private fun handlePacket(bytes: ByteArray, packet: PerfettoTraceReader.Field) {
        var sequenceId = 0L
        var timestamp = 0L
        var incrementalCleared = false
        var internedData: PerfettoTraceReader.Field? = null
        var perfSample: PerfettoTraceReader.Field? = null
        var streamingProfile: PerfettoTraceReader.Field? = null
        var processDescriptor: PerfettoTraceReader.Field? = null
        var threadDescriptor: PerfettoTraceReader.Field? = null

        val reader = PerfettoTraceReader(bytes, packet.payloadStart, packet.payloadEnd)
        while (reader.hasMore) {
            val f = reader.readField()
            when (f.number) {
                F.TIMESTAMP -> timestamp = f.value
                F.SEQUENCE_ID -> sequenceId = f.value
                F.SEQUENCE_FLAGS ->
                    if (f.value.toInt() and F.SEQ_INCREMENTAL_STATE_CLEARED != 0) {
                        incrementalCleared = true
                    }
                F.INCREMENTAL_STATE_CLEARED -> if (f.value != 0L) incrementalCleared = true
                F.INTERNED_DATA -> internedData = f
                F.PERF_SAMPLE -> perfSample = f
                F.STREAMING_PROFILE_PACKET -> streamingProfile = f
                F.PROCESS_DESCRIPTOR -> processDescriptor = f
                F.THREAD_DESCRIPTOR -> threadDescriptor = f
            }
        }

        val seq = sequences.getOrPut(sequenceId) { SequenceState() }
        if (incrementalCleared) seq.clearInterned()

        processDescriptor?.let { parseProcessDescriptor(bytes, it) }
        threadDescriptor?.let { parseThreadDescriptor(bytes, it, seq) }
        internedData?.let { parseInternedData(bytes, it, seq) }

        perfSample?.let { parsePerfSample(bytes, it, seq, timestamp) }
        streamingProfile?.let { parseStreamingProfile(bytes, it, seq) }
    }

    private fun parseProcessDescriptor(bytes: ByteArray, field: PerfettoTraceReader.Field) {
        val r = PerfettoTraceReader(bytes, field.payloadStart, field.payloadEnd)
        var pid = 0
        var name: String? = null
        while (r.hasMore) {
            val f = r.readField()
            when (f.number) {
                F.PROC_PID -> pid = f.value.toInt()
                F.PROC_NAME ->
                    if (f.wireType == PerfettoTraceReader.WIRE_LENGTH_DELIMITED) name = r.stringOf(f)
            }
        }
        if (pid != 0 && name != null) processNames[pid] = name
    }

    private fun parseThreadDescriptor(
        bytes: ByteArray,
        field: PerfettoTraceReader.Field,
        seq: SequenceState,
    ) {
        val r = PerfettoTraceReader(bytes, field.payloadStart, field.payloadEnd)
        while (r.hasMore) {
            val f = r.readField()
            when (f.number) {
                F.THREAD_PID -> seq.pid = f.value.toInt()
                F.THREAD_TID -> seq.tid = f.value.toInt()
                F.THREAD_NAME ->
                    if (f.wireType == PerfettoTraceReader.WIRE_LENGTH_DELIMITED) {
                        seq.threadName = r.stringOf(f)
                    }
            }
        }
    }

    private fun parseInternedData(bytes: ByteArray, field: PerfettoTraceReader.Field, seq: SequenceState) {
        val r = PerfettoTraceReader(bytes, field.payloadStart, field.payloadEnd)
        while (r.hasMore) {
            val f = r.readField()
            if (f.wireType != PerfettoTraceReader.WIRE_LENGTH_DELIMITED) continue
            when (f.number) {
                F.ID_FUNCTION_NAMES ->
                    parseInternedString(bytes, f)?.let { seq.functionNames[it.first] = it.second }
                F.ID_MAPPING_PATHS ->
                    parseInternedString(bytes, f)?.let { seq.mappingPaths[it.first] = it.second }
                F.ID_FRAMES -> parseFrame(bytes, f)?.let { (iid, info) -> seq.frames[iid] = info }
                F.ID_CALLSTACKS -> parseCallstack(bytes, f)?.let { (iid, ids) -> seq.callstacks[iid] = ids }
                F.ID_MAPPINGS -> parseMapping(bytes, f)?.let { (iid, ids) -> seq.mappings[iid] = ids }
            }
        }
    }

    private fun parseInternedString(bytes: ByteArray, field: PerfettoTraceReader.Field): Pair<Long, String>? {
        val r = PerfettoTraceReader(bytes, field.payloadStart, field.payloadEnd)
        var iid = -1L
        var str: String? = null
        while (r.hasMore) {
            val f = r.readField()
            when (f.number) {
                F.STR_IID -> iid = f.value
                F.STR_STR ->
                    if (f.wireType == PerfettoTraceReader.WIRE_LENGTH_DELIMITED) str = r.stringOf(f)
            }
        }
        return if (iid >= 0 && str != null) iid to str else null
    }

    private fun parseFrame(bytes: ByteArray, field: PerfettoTraceReader.Field): Pair<Long, FrameInfo>? {
        val r = PerfettoTraceReader(bytes, field.payloadStart, field.payloadEnd)
        var iid = -1L
        var fnId = 0L
        var mapId = 0L
        var relPc = 0L
        var line = 0
        while (r.hasMore) {
            val f = r.readField()
            when (f.number) {
                F.FRAME_IID -> iid = f.value
                F.FRAME_FUNCTION_NAME_ID -> fnId = f.value
                F.FRAME_MAPPING_ID -> mapId = f.value
                F.FRAME_REL_PC -> relPc = f.value
                F.FRAME_LINE_NUMBER -> line = f.value.toInt()
            }
        }
        return if (iid >= 0) iid to FrameInfo(fnId, mapId, relPc, line) else null
    }

    private fun parseCallstack(bytes: ByteArray, field: PerfettoTraceReader.Field): Pair<Long, List<Long>>? {
        val r = PerfettoTraceReader(bytes, field.payloadStart, field.payloadEnd)
        var iid = -1L
        val frameIds = ArrayList<Long>()
        while (r.hasMore) {
            val f = r.readField()
            when (f.number) {
                F.CS_IID -> iid = f.value
                F.CS_FRAME_IDS -> readRepeatedVarints(bytes, f, frameIds)
            }
        }
        return if (iid >= 0) iid to frameIds else null
    }

    private fun parseMapping(bytes: ByteArray, field: PerfettoTraceReader.Field): Pair<Long, List<Long>>? {
        val r = PerfettoTraceReader(bytes, field.payloadStart, field.payloadEnd)
        var iid = -1L
        val pathIds = ArrayList<Long>()
        while (r.hasMore) {
            val f = r.readField()
            when (f.number) {
                F.MAP_IID -> iid = f.value
                F.MAP_PATH_STRING_IDS -> readRepeatedVarints(bytes, f, pathIds)
            }
        }
        return if (iid >= 0) iid to pathIds else null
    }

    private fun readRepeatedVarints(
        bytes: ByteArray,
        field: PerfettoTraceReader.Field,
        out: MutableList<Long>,
    ) {
        if (field.wireType == PerfettoTraceReader.WIRE_LENGTH_DELIMITED) {
            val packed = PerfettoTraceReader(bytes, field.payloadStart, field.payloadEnd)
            while (packed.hasMore) {
                out.add(packed.readVarintValue())
            }
        } else {
            out.add(field.value)
        }
    }

    private fun parsePerfSample(
        bytes: ByteArray,
        field: PerfettoTraceReader.Field,
        seq: SequenceState,
        timestamp: Long,
    ) {
        val r = PerfettoTraceReader(bytes, field.payloadStart, field.payloadEnd)
        var pid = 0
        var tid = 0
        var callstackIid = -1L
        while (r.hasMore) {
            val f = r.readField()
            when (f.number) {
                F.PS_PID -> pid = f.value.toInt()
                F.PS_TID -> tid = f.value.toInt()
                F.PS_CALLSTACK_IID -> callstackIid = f.value
            }
        }
        if (callstackIid < 0) return
        addSample(seq, pid, tid, timestamp, callstackIid)
    }

    private fun parseStreamingProfile(
        bytes: ByteArray,
        field: PerfettoTraceReader.Field,
        seq: SequenceState,
    ) {
        val r = PerfettoTraceReader(bytes, field.payloadStart, field.payloadEnd)
        while (r.hasMore) {
            val f = r.readField()
            if (f.number == F.SPP_CALLSTACK_IID) {
                addSample(seq, seq.pid, seq.tid, 0L, f.value)
            }
        }
    }

    private fun addSample(
        seq: SequenceState,
        pid: Int,
        tid: Int,
        timestampNs: Long,
        callstackIid: Long,
    ) {
        val frameIds = seq.callstacks[callstackIid] ?: return
        val frames = frameIds.mapNotNull { fid -> resolveFrame(seq, fid) }
        samples.add(
            StackTrace(
                pid = if (pid != 0) pid else seq.pid,
                tid = if (tid != 0) tid else seq.tid,
                threadName = seq.threadName,
                processName = null,
                timestampNs = timestampNs,
                frames = frames,
            ),
        )
    }

    private fun resolveFrame(seq: SequenceState, frameId: Long): Frame? {
        val info = seq.frames[frameId] ?: return null
        val fnName = seq.functionNames[info.functionNameId] ?: ""
        val mappingPath = seq.mappings[info.mappingId]
            ?.firstOrNull()
            ?.let { seq.mappingPaths[it] }
        return Frame(
            functionName = fnName,
            mappingPath = mappingPath,
            relPc = info.relPc,
            lineNumber = info.lineNumber.takeIf { it > 0 },
        )
    }

    companion object {
        const val DEFAULT_MAX_CHARS = 8_192
    }
}
