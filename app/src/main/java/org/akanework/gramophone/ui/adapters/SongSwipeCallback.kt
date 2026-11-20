package org.akanework.gramophone.ui.adapters

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
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
 * The row always snaps back, the action is shown by a colored background with an icon.
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

    fun attach(recyclerView: RecyclerView) {
        detach(recyclerView)
        helper = ItemTouchHelper(this).also { it.attachToRecyclerView(recyclerView) }
        // Swiping a song sideways must not turn the pages of the tabs: claim the gesture unless
        // it starts at the very edge of the screen, which stays free for changing tabs.
        touchListener = object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
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
        touchListener?.let { recyclerView.removeOnItemTouchListener(it) }
        touchListener = null
    }

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        if (viewHolder !is BaseAdapter.ViewHolder || !canSwipe()) return 0
        val pos = viewHolder.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION || songAt(pos) == null) return 0
        return super.getMovementFlags(recyclerView, viewHolder)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ) = false

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = 0.35f

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val pos = viewHolder.bindingAdapterPosition
        val song = songAt(pos)
        if (pos != RecyclerView.NO_POSITION) onSnapBack(pos)
        if (song == null) return
        if (direction == ItemTouchHelper.LEFT) onLike(song) else onPlayNext(song)
    }

    override fun onChildDraw(
        c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
        dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
    ) {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
            val v = viewHolder.itemView
            val towardsLeft = dX < 0
            paint.color = if (towardsLeft) ColorUtils.setAlphaComponent(LikedSongs.PINK, 230)
            else 0xE61E88E5.toInt()
            if (towardsLeft) {
                c.drawRect(v.right + dX, v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat(), paint)
            } else {
                c.drawRect(v.left.toFloat(), v.top.toFloat(), v.left + dX, v.bottom.toFloat(), paint)
            }
            val icon = if (towardsLeft) likeIcon else nextIcon
            if (icon != null && abs(dX) > iconPad + iconSize) {
                icon.setTint(Color.WHITE)
                val cy = (v.top + v.bottom) / 2
                val l = if (towardsLeft) v.right - iconPad - iconSize else v.left + iconPad
                icon.setBounds(l, cy - iconSize / 2, l + iconSize, cy + iconSize / 2)
                icon.draw(c)
            }
        }
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }
}
