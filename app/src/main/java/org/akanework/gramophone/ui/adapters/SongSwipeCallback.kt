package org.akanework.gramophone.ui.adapters

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.LikedSongs
import org.akanework.gramophone.logic.dpToPx
import kotlin.math.abs

/**
 * Swipe a song row left to like it, right to play it next.
 * The row holds where it was released, then springs back to its place while the colored panel
 * fades out. The panel is drawn by an item decoration from the row's translation, so it follows
 * both the finger and the spring.
 */
class SongSwipeCallback(
    private val context: Context,
    private val songAt: (Int) -> MediaItem?,
    private val canSwipe: () -> Boolean,
    private val onLike: (MediaItem) -> Unit,
    private val onPlayNext: (MediaItem) -> Unit,
    private val onSnapBack: (Int) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val likeIcon = AppCompatResources.getDrawable(context, R.drawable.p3x_heart)?.mutate()
    private val nextIcon =
        AppCompatResources.getDrawable(context, R.drawable.ic_playlist_play)?.mutate()
    private val iconSize = 24.dpToPx(context)
    private val iconPad = 24.dpToPx(context)
    private val edge = 28.dpToPx(context)
    private var helper: ItemTouchHelper? = null
    private var touchListener: RecyclerView.OnItemTouchListener? = null
    private var inScrollZone = false

    private val decoration = object : RecyclerView.ItemDecoration() {
        override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            for (i in 0 until parent.childCount) {
                val v = parent.getChildAt(i)
                val tx = v.translationX
                if (tx == 0f) continue
                if (parent.getChildViewHolder(v) !is BaseAdapter.ViewHolder) continue
                drawPanel(c, v, tx, 1f)
            }
        }
    }

    private fun drawPanel(c: Canvas, v: View, tx: Float, strength: Float) {
        val towardsLeft = tx < 0
        val base = if (towardsLeft) LikedSongs.PINK else 0xFF1E88E5.toInt()
        paint.color = ColorUtils.setAlphaComponent(base, (230 * strength).toInt())
        if (towardsLeft) {
            c.drawRect(v.right + tx, v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat(), paint)
        } else {
            c.drawRect(v.left.toFloat(), v.top.toFloat(), v.left + tx, v.bottom.toFloat(), paint)
        }
        val icon = if (towardsLeft) likeIcon else nextIcon
        if (icon != null && abs(tx) > iconPad + iconSize) {
            icon.setTint(Color.WHITE)
            icon.alpha = (255 * strength).toInt()
            val cy = (v.top + v.bottom) / 2
            val l = if (towardsLeft) v.right - iconPad - iconSize else v.left + iconPad
            icon.setBounds(l, cy - iconSize / 2, l + iconSize, cy + iconSize / 2)
            icon.draw(c)
        }
    }

    fun attach(recyclerView: RecyclerView) {
        detach(recyclerView)
        helper = ItemTouchHelper(this).also { it.attachToRecyclerView(recyclerView) }
        recyclerView.addItemDecoration(decoration)
        // Swiping a song sideways must not turn the pages of the tabs: claim the gesture unless
        // it starts at the very edge of the screen, which stays free for changing tabs.
        touchListener = object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    // a touch on the fast scroll bar must never start a row swipe
                    inScrollZone = e.x > rv.width - 36 * rv.resources.displayMetrics.density
                }
                if (e.actionMasked == MotionEvent.ACTION_DOWN && canSwipe()) {
                    rv.parent?.requestDisallowInterceptTouchEvent(
                        e.x > edge && e.x < rv.width - edge
                    )
                }
                return false
            }
        }.also { recyclerView.addOnItemTouchListener(it) }
    }

    fun detach(recyclerView: RecyclerView) {
        helper?.attachToRecyclerView(null)
        helper = null
        recyclerView.removeItemDecoration(decoration)
        touchListener?.let { recyclerView.removeOnItemTouchListener(it) }
        touchListener = null
    }

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        if (inScrollZone || viewHolder !is BaseAdapter.ViewHolder || !canSwipe()) return 0
        val pos = viewHolder.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION || songAt(pos) == null) return 0
        return super.getMovementFlags(recyclerView, viewHolder)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ) = false

    // Rows are never "swiped away": the action fires when the finger is released past the
    // threshold and ItemTouchHelper plays its normal, smooth return animation to the row's place.
    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = 2f
    override fun getSwipeEscapeVelocity(defaultValue: Float) = Float.MAX_VALUE
    override fun getSwipeVelocityThreshold(defaultValue: Float) = Float.MAX_VALUE

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

    private var lastDx = 0f
    private var holding = false

    override fun onChildDraw(
        c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
        dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
    ) {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
            if (isCurrentlyActive) {
                lastDx = dX
                holding = true
            } else if (holding) {
                holding = false
                val pos = viewHolder.bindingAdapterPosition
                val song = if (pos == RecyclerView.NO_POSITION) null else songAt(pos)
                if (song != null && abs(lastDx) > viewHolder.itemView.width * 0.35f) {
                    if (lastDx < 0) onLike(song) else onPlayNext(song)
                }
            }
        }
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }
}
