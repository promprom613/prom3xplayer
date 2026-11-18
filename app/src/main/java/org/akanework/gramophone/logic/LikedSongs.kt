package org.akanework.gramophone.logic

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import uk.akane.libphonograph.dynamicitem.Favorite
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Ids of the favorited ("liked") songs. Shared by the heart button in the full player,
 * the pink titles in the song lists and the "Liked" sort option.
 * The favorites playlist stays the source of truth, [setLocal] only makes the UI instant.
 */
object LikedSongs {
    val PINK: Int = 0xFFFF00FF.toInt()

    @Volatile
    var ids: Set<String> = emptySet()
        private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var started = false

    fun isLiked(id: String?): Boolean = id != null && ids.contains(id)

    /** Listeners are always called on the main thread. */
    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    fun setLocal(id: String, liked: Boolean) {
        val cur = ids
        if (cur.contains(id) == liked) return
        ids = if (liked) cur + id else cur - id
        notifyChanged()
    }

    fun start(app: GramophoneApplication) {
        if (started) return
        started = true
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            app.reader.playlistListFlow
                .map { lists ->
                    (lists.find { it is Favorite }?.songList ?: emptyList())
                        .map { it.mediaId }.toSet()
                }
                .distinctUntilChanged()
                .collect { set ->
                    ids = set
                    notifyChanged()
                }
        }
    }

    private fun notifyChanged() {
        main.post { listeners.forEach { it() } }
    }
}
