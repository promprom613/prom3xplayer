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

package org.akanework.gramophone.ui.adapters

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ShareCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.GregorianCalendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.LikedSongs
import org.akanework.gramophone.logic.P3xToast
import org.akanework.gramophone.logic.getBooleanStrict
import org.akanework.gramophone.logic.getFile
import org.akanework.gramophone.logic.gramophoneApplication
import org.akanework.gramophone.logic.requireMediaStoreId
import org.akanework.gramophone.logic.setMediaItemsSeamlessly
import org.akanework.gramophone.logic.ui.MyRecyclerView
import org.akanework.gramophone.ui.MainActivity
import org.akanework.gramophone.ui.MediaControllerViewModel
import org.akanework.gramophone.ui.SongPickerActivity
import org.akanework.gramophone.ui.components.NowPlayingDrawable
import org.akanework.gramophone.ui.fragments.ArtistSubFragment
import org.akanework.gramophone.ui.fragments.DetailDialogFragment
import org.akanework.gramophone.ui.fragments.GeneralSubFragment
import org.akanework.gramophone.ui.fragments.SearchFragment
import uk.akane.libphonograph.items.addDate
import uk.akane.libphonograph.items.albumId
import uk.akane.libphonograph.items.artistId
import uk.akane.libphonograph.items.modifiedDate
import uk.akane.libphonograph.manipulator.ItemManipulator

fun p3xSongMenu(mainActivity: MainActivity, item: MediaItem, popupMenu: PopupMenu) {
        val context: android.content.Context = mainActivity
        popupMenu.inflate(R.menu.more_menu)
        popupMenu.menu.findItem(R.id.set_ringtone)?.isVisible = true
        popupMenu.menu.findItem(R.id.set_notification)?.isVisible = true

        popupMenu.setOnMenuItemClickListener { it1 ->
            when (it1.itemId) {
                R.id.set_ringtone -> {
                    org.akanework.gramophone.logic.RingtoneHelper.set(
                        mainActivity, item, android.media.RingtoneManager.TYPE_RINGTONE
                    )
                    true
                }

                R.id.set_notification -> {
                    org.akanework.gramophone.logic.RingtoneHelper.set(
                        mainActivity, item, android.media.RingtoneManager.TYPE_NOTIFICATION
                    )
                    true
                }

                R.id.play_next -> {
                    val mediaController = mainActivity.getPlayer()
                    mediaController?.addMediaItem(
                        mediaController.currentMediaItemIndex + 1,
                        item,
                    )
                    true
                }

                R.id.add_to_queue -> {
                    val mediaController = mainActivity.getPlayer()
                    mediaController?.addMediaItem(
                        item,
                    )
                    true
                }

                R.id.album -> {
                    mainActivity.startFragment(GeneralSubFragment()) {
                        putString("Id", item.mediaMetadata.albumId?.toString())
                        putInt("Item", R.id.album)
                    }
                    true
                }

                R.id.artist -> {
                    mainActivity.startFragment(ArtistSubFragment()) {
                        putString("Id", item.mediaMetadata.artistId?.toString())
                        putInt("Item", R.id.artist)
                    }
                    true
                }

                R.id.details -> {
                    mainActivity.startFragment(DetailDialogFragment()) {
                        putString("Id", item.mediaId)
                    }
                    true
                }

                R.id.delete -> {
                    CoroutineScope(Dispatchers.Default).launch {
                        val res = ItemManipulator.deleteSongs(
                            mainActivity,
                            listOf(item.getFile()!! to item.requireMediaStoreId())
                        )
                        if (res != null) {
                            withContext(Dispatchers.Main) {
                                MaterialAlertDialogBuilder(context)
                                    .setTitle(R.string.delete)
                                    .setMessage(
                                        context.getString(
                                            R.string.delete_really,
                                            item.mediaMetadata.title
                                        )
                                    )
                                    .setPositiveButton(R.string.delete) { _, _ ->
                                        res.invoke()
                                    }
                                    .setNegativeButton(android.R.string.cancel) { _, _ -> }
                                    .show()
                            }
                        }
                    }
                    true
                }

                R.id.share -> {
                    val uri = item.requestMetadata.mediaUri
                        ?: item.localConfiguration?.uri
                        ?: return@setOnMenuItemClickListener true

                    val mimeType = item.localConfiguration?.mimeType ?: "audio/*"

                    try {
                        ShareCompat.IntentBuilder(mainActivity)
                            .setType(mimeType)
                            .setStream(uri)
                            .setChooserTitle("Share audio file")
                            .startChooser()
                    } catch (e: Exception) {
                        P3xToast.makeText(
                            mainActivity,
                            "Unable to share: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    true
                }

                R.id.add_to_playlist -> {
                    mainActivity.addToPlaylistDialog(item)
                    true
                }

                else -> false
            }
        }
    }
