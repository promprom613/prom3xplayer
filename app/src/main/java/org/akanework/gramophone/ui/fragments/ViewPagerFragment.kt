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

package org.akanework.gramophone.ui.fragments

import org.akanework.gramophone.logic.P3xToast

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil3.SingletonImageLoader
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import androidx.media3.session.SessionCommand
import org.akanework.gramophone.logic.GramophonePlaybackService
import org.akanework.gramophone.ui.components.TabRail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.clone
import org.akanework.gramophone.logic.enableEdgeToEdgePaddingListener
import org.akanework.gramophone.logic.needsManualSnackBarInset
import org.akanework.gramophone.logic.updateMargin
import org.akanework.gramophone.logic.utils.SdScanner
import org.akanework.gramophone.logic.setMediaItemsWithTitle
import org.akanework.gramophone.ui.MainActivity
import org.akanework.gramophone.ui.adapters.ViewPager2Adapter
import org.akanework.gramophone.ui.components.PlayerBottomSheet
import org.akanework.gramophone.ui.fragments.settings.MainSettingsActivity
import org.nift4.mediastorecompat.MediaStoreCompat

/**
 * ViewPagerFragment:
 *   A fragment that's in charge of displaying tabs
 * and is connected to the drawer.
 *
 * @author AkaneTan
 */
class ViewPagerFragment : BaseFragment(true) {
    override val allowImageBackground: Boolean
        get() = true

    lateinit var appBarLayout: AppBarLayout
        private set
    val recycledViewPool = RecyclerView.RecycledViewPool()
    private var menuHandler: androidx.appcompat.widget.Toolbar.OnMenuItemClickListener? = null
    private var homeToolbar: MaterialToolbar? = null

    /** Runs a home_menu action (used by the library header's own search/menu buttons). */
    fun runMenuItem(id: Int) {
        val item = homeToolbar?.menu?.findItem(id) ?: return
        menuHandler?.onMenuItemClick(item)
    }
    private lateinit var viewPager2: ViewPager2
    private lateinit var adapter: ViewPager2Adapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val rootView = inflater.inflate(R.layout.fragment_viewpager, container, false)
        val topAppBar = rootView.findViewById<MaterialToolbar>(R.id.topAppBar)
        viewPager2 = rootView.findViewById(R.id.fragment_viewpager)

        appBarLayout = rootView.findViewById(R.id.appbarlayout)
        appBarLayout.enableEdgeToEdgePaddingListener()
        topAppBar.overflowIcon =
            AppCompatResources.getDrawable(requireContext(), R.drawable.ic_more_vert_alt_topappbar)

        homeToolbar = topAppBar
        val menuClick = androidx.appcompat.widget.Toolbar.OnMenuItemClickListener { it ->
            val activity = requireActivity() as MainActivity
            when (it.itemId) {
                R.id.search -> {
                    activity.startFragment(SearchFragment())
                }

                R.id.equalizer -> {
                    val intent =
                        Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                            // EXTRA_PACKAGE_NAME is probably not needed but might as well add for good measure
                            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, requireContext().packageName)
                            putExtra(
                                AudioEffect.EXTRA_AUDIO_SESSION,
                                activity.getPlayer()?.audioSessionId
                            )
                            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                        }
                    try {
                        if (Settings.System.getString(
                                requireContext().contentResolver,
                                "firebase.test.lab"
                            ) != "true"
                        ) {
                            activity.startingActivity.launch(intent)
                        }
                    } catch (_: ActivityNotFoundException) {
                        // Let's show a toast here if no system inbuilt EQ was found.
                        P3xToast.makeText(
                            requireContext(),
                            R.string.equalizer_not_found,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                R.id.quick_refresh -> {
                    val imageLoader = SingletonImageLoader.get(requireContext())
                    imageLoader.memoryCache?.clear()
                    val playerLayout = activity.playerBottomSheet
                    activity.updateLibrary {
                        showRefreshDoneSnackBar(
                            playerLayout,
                            runBlocking { activity.reader.songListFlow.first().size })
                    }
                }

                R.id.refresh -> {
                    val context = requireContext()
                    val imageLoader = SingletonImageLoader.get(context)
                    imageLoader.memoryCache?.clear()
                    val playerLayout = activity.playerBottomSheet
                    MaterialAlertDialogBuilder(context)
                        .setIcon(R.drawable.ic_refresh)
                        .setTitle(R.string.did_you_know)
                        .setMessage(R.string.refresh_did_you_know)
                        .setPositiveButton(android.R.string.ok) { _, _ -> }
                        .show()
                    P3xToast.makeText(context, R.string.refreshing_wait, Toast.LENGTH_LONG).show()
                    CoroutineScope(Dispatchers.Default).launch {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            SdScanner.scanEverything(context, 5000) { progress ->
                                if (progress.step != SdScanner.SimpleProgress.Step.DONE) {
                                    val str = if (progress.percentage == null)
                                        context.getString(R.string.refreshing_wait)
                                    else context.getString(
                                        R.string.still_refreshing,
                                        progress.step.ordinal,
                                        SdScanner.SimpleProgress.Step.DONE.ordinal - 1,
                                        "${progress.percentage}%"
                                    )
                                    CoroutineScope(Dispatchers.Main).launch {
                                        P3xToast.makeText(context, str, Toast.LENGTH_SHORT).show()
                                    }
                                    return@scanEverything
                                }
                                activity.updateLibrary(false) {
                                    showRefreshDoneSnackBar(
                                        playerLayout,
                                        runBlocking { activity.reader.songListFlow.first().size })
                                }
                            }
                        } else {
                            val job = launch(Dispatchers.IO) {
                                MediaStoreCompat.scanEverything(context)
                            }
                            while (!job.isCompleted) {
                                delay(5000)
                                CoroutineScope(Dispatchers.Main).launch {
                                    P3xToast.makeText(context, context.getString(
                                        R.string.refreshing_wait),
                                        Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }

                R.id.settings -> {
                    activity.startActivity(Intent(activity, MainSettingsActivity::class.java))
                }

                R.id.shuffle -> {
                    ShortcutManagerCompat.reportShortcutUsed(requireContext(), "shuffle_all")
                    val controller = activity.getPlayer()
                    runBlocking { activity.reader.songListFlow.first() }.takeIf { it.isNotEmpty() }
                        ?.also {
                            controller?.setMediaItemsWithTitle(
                                it,
                                title = requireContext().getString(R.string.category_songs),
                                shuffleEnabled = true,
                            )
                            controller?.prepare()
                            controller?.play()
                        } ?: controller?.setMediaItems(listOf())
                }

                else -> throw IllegalStateException()
            }
            true
        }
        menuHandler = menuClick
        topAppBar.setOnMenuItemClickListener(menuClick)

        // Connect ViewPager2.

        viewPager2.offscreenPageLimit = 99999 // TODO is 99999 a good value?
        adapter =
            ViewPager2Adapter(
                childFragmentManager,
                viewLifecycleOwner.lifecycle,
                requireContext(),
                viewPager2
            )
        viewPager2.adapter = adapter

        val rail = rootView.findViewById<TabRail>(R.id.p3x_rail)
        val rebuildRail = {
            rail.setTabs(List(adapter.itemCount) { i ->
                TabRail.Entry(
                    TabRail.iconFor(adapter.getItemId(i).toInt()),
                    adapter.getLabelResId(i)
                )
            })
            rail.select(viewPager2.currentItem)
        }
        rail.onSelect = { pos ->
            if (pos == viewPager2.currentItem) {
                (childFragmentManager.findFragmentByTag("f${adapter.getItemId(pos)}")
                        as AdapterFragment?)?.onTabReselected()
            } else {
                viewPager2.setCurrentItem(pos, true)
            }
        }
        rail.onPower = {
            val activity = requireActivity() as MainActivity
            val player = activity.getPlayer()
            if (player == null) {
                activity.finishAndRemoveTask()
            } else {
                val fut = player.sendCustomCommand(
                    SessionCommand(GramophonePlaybackService.SERVICE_POWER_OFF, Bundle.EMPTY),
                    Bundle.EMPTY
                )
                fut.addListener(
                    { activity.finishAndRemoveTask() },
                    androidx.core.content.ContextCompat.getMainExecutor(activity)
                )
            }
        }
        adapter.onTabsChanged = rebuildRail
        rebuildRail()
        viewPager2.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                rail.select(position)
            }
        })

        viewPager2.isUserInputEnabled = false // the rail is the only way to switch tabs

        return rootView
    }

    fun maybeReportFullyDrawn(itemId: Int) {
        if (view != null && adapter.getItemId(viewPager2.currentItem).toInt() == itemId)
            mainActivity.maybeReportFullyDrawn()
    }

    private fun showRefreshDoneSnackBar(playerLayout: PlayerBottomSheet, count: Int) {
        val view = view
        if (view == null) return
        val snackBar =
            Snackbar.make(
                view,
                getString(
                    R.string.refreshed_songs,
                    count,
                ),
                Snackbar.LENGTH_LONG,
            )
        snackBar.setAction(R.string.dismiss) {
            snackBar.dismiss()
        }

        /*
		 * Let's override snack bar's color here so it would
		 * adapt dark mode.
		 */
        snackBar.setBackgroundTint(
            MaterialColors.getColor(
                snackBar.view,
                com.google.android.material.R.attr.colorSurface,
            ),
        )
        snackBar.setActionTextColor(
            MaterialColors.getColor(
                snackBar.view,
                androidx.appcompat.R.attr.colorPrimary,
            ),
        )
        snackBar.setTextColor(
            MaterialColors.getColor(
                snackBar.view,
                com.google.android.material.R.attr.colorOnSurface,
            ),
        )

        // Set an anchor for snack bar.
        if (playerLayout.visible && playerLayout.actuallyVisible)
            snackBar.anchorView = playerLayout
        else if (needsManualSnackBarInset()) {
            val activity = requireActivity() as MainActivity
            // snack bar only implements proper insets handling for Q+
            snackBar.view.updateMargin {
                val i = ViewCompat.getRootWindowInsets(activity.window.decorView)
                if (i != null) {
                    bottom += i.clone()
                        .getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                }
            }
        }
        snackBar.show()
    }
}
