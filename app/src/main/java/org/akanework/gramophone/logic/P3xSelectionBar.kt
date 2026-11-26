package org.akanework.gramophone.logic

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ShareCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.R
import org.akanework.gramophone.ui.MainActivity
import uk.akane.libphonograph.manipulator.ItemManipulator

/** Top bar shown while songs are selected: count, select all, share and more actions. */
object P3xSelectionBar {
    private var bar: View? = null
    private var count: TextView? = null
    private var allSongs: (() -> List<MediaItem>)? = null
    private var activity: MainActivity? = null
    private var backCb: OnBackPressedCallback? = null

    fun ensure(a: MainActivity, songs: () -> List<MediaItem>) {
        allSongs = songs
        if (activity !== a) {
            // activity was recreated: forget the old bar
            (bar?.parent as? ViewGroup)?.removeView(bar)
            bar = null
            backCb?.remove()
            backCb = null
            activity = a
        }
        if (bar == null) build(a)
        if (backCb == null) {
            backCb = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    P3xSelection.clear()
                }
            }.also { a.onBackPressedDispatcher.addCallback(a, it) }
        }
        P3xSelection.onChanged = { update() }
        update()
    }

    private fun update() {
        val n = P3xSelection.items.size
        if (n == 0) {
            (bar?.parent as? ViewGroup)?.removeView(bar)
            bar = null
            backCb?.remove()
            backCb = null
            P3xSelection.onChanged = null
            return
        }
        count?.text = n.toString()
    }

    private fun icon(a: MainActivity, res: Int, desc: Int, click: (View) -> Unit): ImageButton {
        val d = a.resources.displayMetrics.density
        return ImageButton(a).apply {
            setImageResource(res)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = a.getString(desc)
            layoutParams = LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt())
            setOnClickListener(click)
        }
    }

    private fun build(a: MainActivity) {
        val d = a.resources.displayMetrics.density
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            elevation = 12 * d
            setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
            isClickable = true
        }
        row.addView(icon(a, R.drawable.ic_close, R.string.p3x_cancel_selection) {
            P3xSelection.clear()
        })
        val c = TextView(a).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding((8 * d).toInt(), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        count = c
        row.addView(c)
        row.addView(icon(a, R.drawable.ic_check, R.string.p3x_select_all) {
            allSongs?.invoke()?.let { P3xSelection.setAll(it) }
        })
        row.addView(icon(a, R.drawable.ic_share_24, R.string.share) { share(a) })
        row.addView(icon(a, R.drawable.ic_more_vert_alt, R.string.p3x_more) { v -> more(a, v) })

        val holder = FrameLayout(a)
        holder.addView(
            row, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (56 * d).toInt(), Gravity.TOP
            )
        )
        ViewCompat.setOnApplyWindowInsetsListener(row) { v, ins ->
            val top = ins.getInsets(WindowInsetsCompat.Type.systemBars()).top
            v.setPadding(v.paddingLeft, top, v.paddingRight, 0)
            v.layoutParams = v.layoutParams.also { it.height = top + (56 * d).toInt() }
            ins
        }
        val root = a.findViewById<ViewGroup>(android.R.id.content)
        root.addView(
            holder, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
            )
        )
        // lets the bar stay at the very top, above the status bar area
        holder.bringToFront()
        ViewCompat.requestApplyInsets(row)
        bar = holder
    }

    private fun selected(): List<MediaItem> = P3xSelection.items.values.toList()

    private fun share(a: MainActivity) {
        val b = ShareCompat.IntentBuilder(a).setType("audio/*").setChooserTitle("Share audio files")
        var any = false
        selected().forEach {
            val uri = it.requestMetadata.mediaUri ?: it.localConfiguration?.uri
            if (uri != null) {
                b.addStream(uri)
                any = true
            }
        }
        if (any) {
            try {
                b.startChooser()
            } catch (e: Exception) {
                P3xToast.makeText(a, "Unable to share: ${e.message}", android.widget.Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private fun more(a: MainActivity, anchor: View) {
        val m = PopupMenu(a, anchor)
        m.menu.add(0, 1, 0, R.string.play_next)
        m.menu.add(0, 2, 1, R.string.add_to_queue)
        m.menu.add(0, 3, 2, R.string.add_to_playlist)
        m.menu.add(0, 4, 3, R.string.delete)
        m.setOnMenuItemClickListener {
            val items = selected()
            when (it.itemId) {
                1 -> {
                    val p = a.getPlayer()
                    p?.addMediaItems(p.currentMediaItemIndex + 1, items)
                    P3xSelection.clear()
                    true
                }

                2 -> {
                    a.getPlayer()?.addMediaItems(items)
                    P3xSelection.clear()
                    true
                }

                3 -> {
                    a.addToPlaylistDialog(items)
                    P3xSelection.clear()
                    true
                }

                4 -> {
                    delete(a, items)
                    true
                }

                else -> false
            }
        }
        m.show()
    }

    private fun delete(a: MainActivity, items: List<MediaItem>) {
        CoroutineScope(Dispatchers.Default).launch {
            val res = ItemManipulator.deleteSongs(
                a, items.map { it.getFile()!! to it.requireMediaStoreId() }
            )
            if (res != null) {
                withContext(Dispatchers.Main) {
                    MaterialAlertDialogBuilder(a)
                        .setTitle(R.string.delete)
                        .setMessage(a.getString(R.string.delete_really, "${items.size}"))
                        .setPositiveButton(R.string.delete) { _, _ ->
                            res.invoke()
                            P3xSelection.clear()
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> }
                        .show()
                }
            }
        }
    }
}
