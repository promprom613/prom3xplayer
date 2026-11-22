package org.akanework.gramophone.ui.adapters

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
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
    private var lastActiveDx = 0f
    private var springView: View? = null
    private var springStart = 0f
    private var springAnim: ValueAnimator? = null
    private var pendingClear: Pair<RecyclerView, RecyclerView.ViewHolder>? = null

    private val decoration = object : RecyclerView.ItemDecoration() {
        override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            for (i in 0 until parent.childCount) {
                val v = parent.getChildAt(i)
                val tx = v.translationX
                if (tx == 0f) continue
                if (parent.getChildViewHolder(v) !is BaseAdapter.ViewHolder) continue
                var strength = 1f
                if (v === springView) {
                    if ((tx < 0) != (springStart < 0)) continue // overshoot to the other side
                    strength = (abs(tx) / abs(springStart)).coerceIn(0f, 1f)
                }
                drawPanel(c, v, tx, strength)
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
        springAnim?.cancel()
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

    // finish the "fly out" part almost instantly, the row is held in place by onChildDraw
    override fun getAnimationDuration(
        recyclerView: RecyclerView, animationType: Int, animateDx: Float, animateDy: Float
    ): Long =
        if (animationType == ItemTouchHelper.ANIMATION_TYPE_SWIPE_SUCCESS) 40L
        else super.getAnimationDuration(recyclerView, animationType, animateDx, animateDy)

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        if (viewHolder != null && actionState == ItemTouchHelper.ACTION_STATE_SWIPE)
            springAnim?.cancel()
        super.onSelectedChanged(viewHolder, actionState)
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val pos = viewHolder.bindingAdapterPosition
        val song = songAt(pos)
        if (pos != RecyclerView.NO_POSITION) startSpring(viewHolder, pos)
        if (song == null) return
        if (direction == ItemTouchHelper.LEFT) onLike(song) else onPlayNext(song)
    }

    private fun startSpring(vh: RecyclerView.ViewHolder, pos: Int) {
        springAnim?.cancel()
        val v = vh.itemView
        val start = if (v.translationX != 0f) v.translationX else lastActiveDx
        if (start == 0f) {
            onSnapBack(pos)
            return
        }
        springView = v
        springStart = start
        val interp = OvershootInterpolator(2.4f)
        val rv = v.parent as? RecyclerView
        var finished = false
        val finish = {
            if (!finished) {
                finished = true
                v.translationX = 0f
                if (springView === v) springView = null
                springAnim = null
                rv?.invalidate()
                pendingClear?.let { (r, h) ->
                    if (h.itemView === v) {
                        pendingClear = null
                        superClear(r, h)
                    }
                }
                if (vh.bindingAdapterPosition == pos) onSnapBack(pos)
            }
        }
        springAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340L
            addUpdateListener {
                if (!v.isAttachedToWindow || vh.bindingAdapterPosition != pos) {
                    cancel()
                    return@addUpdateListener
                }
                v.translationX = start * (1f - interp.getInterpolation(it.animatedFraction))
                rv?.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    finish()
                }

                override fun onAnimationCancel(animation: Animator) {
                    finish()
                }
            })
            start()
        }
    }

    private fun superClear(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
        super.clearView(rv, vh)
    }

    // ItemTouchHelper would snap the row back to zero here, we do it ourselves with the spring
    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        if (springView != null && viewHolder.itemView === springView) {
            pendingClear = Pair(recyclerView, viewHolder)
            return
        }
        super.clearView(recyclerView, viewHolder)
    }

    override fun onChildDraw(
        c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
        dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
    ) {
        var x = dX
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
            if (isCurrentlyActive) lastActiveDx = dX
            else if (abs(dX) > abs(lastActiveDx)) x = lastActiveDx // hold, do not fly off screen
        }
        super.onChildDraw(c, recyclerView, viewHolder, x, dY, actionState, isCurrentlyActive)
    }
}
