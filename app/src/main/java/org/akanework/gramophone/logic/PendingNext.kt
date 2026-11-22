package org.akanework.gramophone.logic

import androidx.media3.common.Player
import java.util.concurrent.CopyOnWriteArraySet

/** Songs queued by swipe-right that still wait in the play-next line (after the current item). */
object PendingNext {
    const val KEY = "p3x_swipe"
    val BLUE: Int = 0xFF1E88E5.toInt()

    @Volatile
    var ids: Set<String> = emptySet()
        private set

    private val listeners = CopyOnWriteArraySet<(Set<String>) -> Unit>()

    fun isPending(id: String?): Boolean = id != null && ids.contains(id)

    fun addListener(l: (Set<String>) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (Set<String>) -> Unit) {
        listeners.remove(l)
    }

    /** Main thread only. Listeners get the ids whose state flipped. */
    fun update(player: Player) {
        val set = HashSet<String>()
        val count = player.mediaItemCount
        var i = player.currentMediaItemIndex + 1
        if (player.currentMediaItemIndex >= 0) {
            while (i < count) {
                val item = player.getMediaItemAt(i)
                if (item.mediaMetadata.extras?.getBoolean(KEY) == true) set.add(item.mediaId)
                i++
            }
        }
        val old = ids
        if (old == set) return
        ids = set
        val changed = (old - set) + (set - old)
        listeners.forEach { it(changed) }
    }
}
