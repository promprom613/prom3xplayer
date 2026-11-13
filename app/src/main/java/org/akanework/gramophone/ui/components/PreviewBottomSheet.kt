/*
 *     Copyright (C) 2024 Akane Foundation
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Gramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.gramophone.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import coil3.request.Disposable
import com.google.android.material.button.MaterialButton
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.playOrPause
import org.akanework.gramophone.ui.MainActivity
import kotlin.math.abs

/**
 * Mini player: text only, blurred dimmed cover as background, round play/pause button.
 * Swipe left = next song, swipe right = previous (restarts the song if past a few seconds).
 */
class PreviewBottomSheet(
    context: Context,
    attrs: AttributeSet?,
    defStyleAttr: Int,
    defStyleRes: Int
) :
    ConstraintLayout(context, attrs, defStyleAttr, defStyleRes), Player.Listener {
    private val activity
        get() = context as MainActivity
    private val instance: MediaController?
        get() = activity.getPlayer()
    private val bottomSheetPreviewTitle: TextView
    private val bottomSheetPreviewSubtitle: TextView
    private val bottomSheetPreviewControllerButton: MaterialButton
    private val bottomSheetPreviewBg: ImageView
    private val textGroup: View
    private var bgDisposable: Disposable? = null
    private var lastArt: Any? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = context.resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    private var downTime = 0L

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
            this(context, attrs, defStyleAttr, 0)

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    init {
        inflate(context, R.layout.preview_player, this)
        bottomSheetPreviewTitle = findViewById(R.id.preview_song_name)
        bottomSheetPreviewSubtitle = findViewById(R.id.preview_artist_name)
        bottomSheetPreviewControllerButton = findViewById(R.id.preview_control)
        bottomSheetPreviewBg = findViewById(R.id.preview_bg)
        textGroup = findViewById(R.id.preview_text)

        // clickable so that this view owns the touch stream (needed for swiping)
        setOnClickListener { (parent as? PlayerBottomSheet)?.open() }

        bottomSheetPreviewControllerButton.setOnClickListener {
            ViewCompat.performHapticFeedback(it, HapticFeedbackConstantsCompat.CONTEXT_CLICK)
            instance?.playOrPause()
        }
        // floating card: ONE rounded clip around everything + solid black base
        // (no per-view clipping = no double edge lines, no see-through)
        val cardR = 28 * density
        setBackgroundColor(android.graphics.Color.BLACK)
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                val l = view.paddingLeft
                val t = view.paddingTop
                val r = view.width - view.paddingRight
                val b = view.height - view.paddingBottom
                if (r > l && b > t) outline.setRoundRect(l, t, r, b, cardR) else outline.setEmpty()
            }
        }
        clipToOutline = true
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> invalidateOutline() }
        // long press: stop playback and empty the queue, so the idle shuffle circle comes back
        bottomSheetPreviewControllerButton.setOnLongClickListener {
            ViewCompat.performHapticFeedback(it, HapticFeedbackConstantsCompat.LONG_PRESS)
            instance?.let { c ->
                c.stop()
                c.clearMediaItems()
            }
            true
        }

        activity.controllerViewModel.addRecreationalPlayerListener(activity.lifecycle, this) {
            onPlaybackStateChanged(instance?.playbackState ?: Player.STATE_IDLE)
            onMediaItemTransition(
                instance?.currentMediaItem,
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = event.eventTime
                swiping = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!swiping && abs(dx) > slop && abs(dx) > abs(dy) * 1.2f) {
                    swiping = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    // cancel the pending click/press on ourselves
                    val c = MotionEvent.obtain(event)
                    c.action = MotionEvent.ACTION_CANCEL
                    super.onTouchEvent(c)
                    c.recycle()
                }
                if (swiping) {
                    textGroup.translationX = dx * 0.6f
                    textGroup.alpha = (1f - abs(dx) / (width.coerceAtLeast(1) * 1.2f)).coerceIn(0.2f, 1f)
                    return true
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!swiping && abs(event.x - downX) < slop && abs(event.y - downY) < slop &&
                    event.eventTime - downTime < ViewConfiguration.getLongPressTimeout()
                ) {
                    // a clean tap anywhere on the bar (sides, text, empty space) expands the player
                    (parent as? PlayerBottomSheet)?.open()
                }
                if (!swiping && downY - event.y > 20 * density &&
                    downY - event.y > abs(event.x - downX) * 1.2f
                ) {
                    // swipe up from anywhere on the card, even if the sheet drag did not engage
                    (parent as? PlayerBottomSheet)?.open()
                }
                if (swiping) {
                    val dx = event.x - downX
                    swiping = false
                    textGroup.animate().translationX(0f).alpha(1f).setDuration(160).start()
                    if (abs(dx) > 72 * density) {
                        if (dx < 0) instance?.seekToNext() else instance?.seekToPrevious()
                    }
                    return true
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (swiping) {
                    swiping = false
                    textGroup.animate().translationX(0f).alpha(1f).setDuration(160).start()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        onPlaybackStateChanged(instance?.playbackState ?: Player.STATE_IDLE)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_BUFFERING) return
        val myTag = bottomSheetPreviewControllerButton.getTag(R.id.play_next) as Int?
        if (instance?.isPlaying == true && myTag != 1) {
            bottomSheetPreviewControllerButton.icon =
                AppCompatResources.getDrawable(context, R.drawable.ic_pause_filled)
            bottomSheetPreviewControllerButton.setTag(R.id.play_next, 1)
        } else if (instance?.isPlaying == false && myTag != 2) {
            bottomSheetPreviewControllerButton.icon =
                AppCompatResources.getDrawable(context, R.drawable.ic_play_arrow_filled)
            bottomSheetPreviewControllerButton.setTag(R.id.play_next, 2)
        }
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: @Player.MediaItemTransitionReason Int
    ) {
        if ((instance?.mediaItemCount ?: 0) > 0) {
            bottomSheetPreviewTitle.text = mediaItem?.mediaMetadata?.title
            bottomSheetPreviewSubtitle.text =
                mediaItem?.mediaMetadata?.artist ?: context.getString(R.string.unknown_artist)
            val uri = mediaItem?.mediaMetadata?.artworkUri
            if (uri != lastArt || bottomSheetPreviewBg.drawable == null) {
                lastArt = uri
                bgDisposable?.dispose()
                bgDisposable = ArtBlur.load(context, uri) { d ->
                    ArtBlur.crossfadeInto(bottomSheetPreviewBg, d)
                }
            }
        } else {
            bgDisposable?.dispose()
            lastArt = null
        }
    }
}
