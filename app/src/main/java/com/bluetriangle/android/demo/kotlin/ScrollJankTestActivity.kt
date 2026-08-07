package com.bluetriangle.android.demo.kotlin

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bluetriangle.android.demo.R
import com.bluetriangle.android.demo.databinding.ActivityScrollJankTestBinding
import com.bluetriangle.android.demo.databinding.ItemScrollJankRowBinding

/**
 * Demo screen that reproduces **user-visible** hitches and hangs the way a real app does: a long
 * feed whose rows each do too much work on the main thread while you scroll.
 *
 * Unlike [JankTestActivity] - which blocks a synthetic animation frame to prove the SDK's counters
 * move - nothing here sleeps. Every row's bind does real pixel work (procedural bitmap + box blur)
 * plus string work, so slow frames come from the scroll itself and the stutter you see on screen is
 * the same stutter the SDK reports in this screen's timer beacon.
 *
 * Three load modes:
 * - **Smooth**: light rows, the baseline - scrolling should stay at the display's frame rate.
 * - **Hitchy**: every row costs ~[LoadMode.HITCH] ms to bind, so most scroll frames overrun their
 *   budget but stay well under the hang threshold - visible as continuous choppiness.
 * - **Hang**: hitchy rows plus one very expensive row every [HANG_ROW_INTERVAL] rows, whose bind
 *   overruns past the SDK's 750ms hang threshold - visible as the list freezing mid-scroll.
 *
 * Scroll by hand, or use *Auto-scroll* to drive a steady scroll from a [Choreographer] callback so
 * the hitch/hang pattern is reproducible without a human finger.
 */
class ScrollJankTestActivity : AppCompatActivity() {

    /**
     * How much main-thread work each row's bind performs.
     *
     * @param rowWorkMs work every row does while binding.
     * @param hangRowWorkMs work the occasional heavy row does *instead* of [rowWorkMs]; 0 disables
     * heavy rows.
     */
    private enum class LoadMode(val rowWorkMs: Long, val hangRowWorkMs: Long = 0L) {
        SMOOTH(rowWorkMs = 0L),
        HITCH(rowWorkMs = 40L),
        HANG(rowWorkMs = 100L, hangRowWorkMs = 900L)
    }

    private lateinit var binding: ActivityScrollJankTestBinding
    private val adapter = FeedAdapter()

    private var autoScrolling = false

    private val autoScrollCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!autoScrolling) return

            if (binding.feed.canScrollVertically(1)) {
                binding.feed.scrollBy(0, AUTO_SCROLL_PX_PER_FRAME)
            } else {
                // Wrap around so the scroll - and the jank it causes - keeps going.
                binding.feed.scrollToPosition(0)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScrollJankTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setTitle(R.string.scroll_jank_test)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.feed.layoutManager = LinearLayoutManager(this).apply {
            // RecyclerView normally pre-binds the next row in the slack *after* a frame's draw, so
            // half the cost lands between frames instead of in one. Off, so every row's work falls
            // inside the scroll frame that needs it - which is what a screen full of heavy rows,
            // heavy onLayout or heavy onDraw actually does to a frame.
            isItemPrefetchEnabled = false
        }
        binding.feed.adapter = adapter

        binding.loadModeGroup.setOnCheckedChangeListener { _, checkedId ->
            adapter.loadMode = when (checkedId) {
                R.id.mode_hitch -> LoadMode.HITCH
                R.id.mode_hang -> LoadMode.HANG
                else -> LoadMode.SMOOTH
            }
            updateStatus()
        }

        binding.autoScroll.setOnClickListener { setAutoScrolling(!autoScrolling) }

        updateStatus()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onPause() {
        setAutoScrolling(false)
        super.onPause()
    }

    private fun setAutoScrolling(enabled: Boolean) {
        if (autoScrolling == enabled) return

        autoScrolling = enabled
        if (enabled) {
            Choreographer.getInstance().postFrameCallback(autoScrollCallback)
        } else {
            Choreographer.getInstance().removeFrameCallback(autoScrollCallback)
        }
        binding.autoScroll.setText(
            if (enabled) R.string.scroll_jank_auto_scroll_stop else R.string.scroll_jank_auto_scroll
        )
        updateStatus()
    }

    private fun updateStatus() {
        val mode = when (adapter.loadMode) {
            LoadMode.SMOOTH -> getString(R.string.scroll_jank_status_smooth)
            LoadMode.HITCH -> getString(R.string.scroll_jank_status_hitch, LoadMode.HITCH.rowWorkMs)
            LoadMode.HANG -> getString(
                R.string.scroll_jank_status_hang,
                LoadMode.HANG.hangRowWorkMs,
                HANG_ROW_INTERVAL
            )
        }
        binding.statusText.text = getString(
            if (autoScrolling) R.string.scroll_jank_status_auto else R.string.scroll_jank_status_manual,
            mode
        )
    }

    private inner class FeedAdapter : RecyclerView.Adapter<RowViewHolder>() {

        var loadMode = LoadMode.SMOOTH
            @SuppressLint("NotifyDataSetChanged")
            set(value) {
                field = value
                // Rebind what's on screen so the new cost shows up without scrolling first.
                notifyDataSetChanged()
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
            return RowViewHolder(
                ItemScrollJankRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }

        override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
            holder.bind(position, loadMode)
        }

        override fun getItemCount(): Int = ROW_COUNT
    }

    private class RowViewHolder(
        private val binding: ItemScrollJankRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        /**
         * Does the row's work synchronously, on the main thread, during the scroll frame that
         * brought the row on screen - which is exactly how a real feed janks.
         */
        fun bind(position: Int, mode: LoadMode) {
            val isHangRow = mode.hangRowWorkMs > 0L && position % HANG_ROW_INTERVAL == 0
            val workMs = if (isHangRow) mode.hangRowWorkMs else mode.rowWorkMs

            val startMs = SystemClock.uptimeMillis()
            binding.thumbnail.setImageBitmap(renderThumbnail(position, workMs))
            binding.body.text = buildBody(position)
            val actualWorkMs = SystemClock.uptimeMillis() - startMs

            val context = binding.root.context
            binding.title.text = context.getString(
                if (isHangRow) R.string.scroll_jank_row_title_heavy else R.string.scroll_jank_row_title,
                position + 1
            )
            binding.subtitle.text =
                context.getString(R.string.scroll_jank_row_subtitle, actualWorkMs)
        }
    }

    private companion object {
        /** Long enough that scrolling never runs out of unbound rows. */
        const val ROW_COUNT = 400

        /** Every Nth row is the expensive one in [LoadMode.HANG]. */
        const val HANG_ROW_INTERVAL = 6

        /** Steady auto-scroll speed - roughly a brisk drag, so rows bind every few frames. */
        const val AUTO_SCROLL_PX_PER_FRAME = 60

        /** Small enough that the baseline mode's fill cost is negligible; the view scales it up. */
        const val THUMBNAIL_SIZE_PX = 64

        /**
         * Paints a procedural thumbnail and box-blurs it repeatedly for [workMs] of real pixel
         * work. Looping to a wall-clock target rather than a fixed pass count keeps a hitch a
         * hitch and a hang a hang on both a flagship phone and a slow emulator.
         */
        fun renderThumbnail(seed: Int, workMs: Long): Bitmap {
            val size = THUMBNAIL_SIZE_PX
            var pixels = IntArray(size * size) { index ->
                val x = index % size
                val y = index / size
                val r = (x * 2 + seed * 7) and 0xFF
                val g = (y * 2 + seed * 13) and 0xFF
                val b = ((x xor y) + seed * 29) and 0xFF
                0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            }

            if (workMs > 0L) {
                var scratch = IntArray(pixels.size)
                val startMs = SystemClock.uptimeMillis()
                do {
                    boxBlur(pixels, scratch, size)
                    val blurred = scratch
                    scratch = pixels
                    pixels = blurred
                } while (SystemClock.uptimeMillis() - startMs < workMs)
            }

            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        }

        /** 3x3 box blur of [source] into [destination]; edge pixels are copied through. */
        fun boxBlur(source: IntArray, destination: IntArray, size: Int) {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val index = y * size + x
                    if (x == 0 || y == 0 || x == size - 1 || y == size - 1) {
                        destination[index] = source[index]
                        continue
                    }

                    var r = 0
                    var g = 0
                    var b = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val pixel = source[index + dy * size + dx]
                            r += (pixel shr 16) and 0xFF
                            g += (pixel shr 8) and 0xFF
                            b += pixel and 0xFF
                        }
                    }
                    destination[index] =
                        0xFF000000.toInt() or ((r / 9) shl 16) or ((g / 9) shl 8) or (b / 9)
                }
            }
        }

        /** The text half of a too-heavy bind: allocate, sort and join a token list per row. */
        fun buildBody(seed: Int): String {
            val tokens = MutableList(TOKEN_COUNT) { index ->
                "tag-${(seed * 31 + index * 17) % 997}"
            }
            tokens.sort()
            return tokens.joinToString(separator = " ")
        }

        const val TOKEN_COUNT = 16
    }
}
