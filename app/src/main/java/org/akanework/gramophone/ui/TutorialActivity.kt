package org.akanework.gramophone.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.dpToPx
import org.akanework.gramophone.logic.enableEdgeToEdgePaddingListener
import org.akanework.gramophone.logic.ui.BaseActivity

/** Full in-app walkthrough of every feature and hidden gesture. */
class TutorialActivity : BaseActivity() {
    companion object {
        const val PREF_DONE = "p3x_tutorial_done"
    }

    private class Page(
        @DrawableRes val icon: Int,
        @StringRes val title: Int,
        @StringRes val body: Int
    )

    private val pages = listOf(
        Page(R.drawable.ic_play_arrow, R.string.p3x_tut_1_title, R.string.p3x_tut_1_body),
        Page(R.drawable.ic_folder, R.string.p3x_tut_2_title, R.string.p3x_tut_2_body),
        Page(R.drawable.ic_unfold_double, R.string.p3x_tut_3_title, R.string.p3x_tut_3_body),
        Page(R.drawable.ic_search, R.string.p3x_tut_4_title, R.string.p3x_tut_4_body),
        Page(R.drawable.ic_playlist_play, R.string.p3x_tut_5_title, R.string.p3x_tut_5_body),
        Page(R.drawable.p3x_swipe, R.string.p3x_tut_6_title, R.string.p3x_tut_6_body),
        Page(R.drawable.ic_skip_next, R.string.p3x_tut_7_title, R.string.p3x_tut_7_body),
        Page(R.drawable.ic_repeat, R.string.p3x_tut_8_title, R.string.p3x_tut_8_body),
        Page(R.drawable.p3x_heart, R.string.p3x_tut_9_title, R.string.p3x_tut_9_body),
        Page(R.drawable.ic_info, R.string.p3x_tut_10_title, R.string.p3x_tut_10_body),
        Page(R.drawable.ic_speed, R.string.p3x_tut_11_title, R.string.p3x_tut_11_body),
        Page(R.drawable.ic_article, R.string.p3x_tut_12_title, R.string.p3x_tut_12_body),
        Page(R.drawable.p3x_image, R.string.p3x_tut_13_title, R.string.p3x_tut_13_body),
        Page(R.drawable.p3x_volume, R.string.p3x_tut_14_title, R.string.p3x_tut_14_body),
        Page(R.drawable.p3x_bell, R.string.p3x_tut_15_title, R.string.p3x_tut_15_body),
        Page(R.drawable.ic_palette, R.string.p3x_tut_16_title, R.string.p3x_tut_16_body)
    )

    private lateinit var pager: ViewPager2
    private lateinit var next: MaterialButton
    private lateinit var skip: MaterialButton
    private lateinit var dots: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_tutorial)
        findViewById<View>(R.id.tut_root).enableEdgeToEdgePaddingListener(top = true)
        pager = findViewById(R.id.tut_pager)
        next = findViewById(R.id.tut_next)
        skip = findViewById(R.id.tut_skip)
        dots = findViewById(R.id.tut_dots)

        pager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(
                    LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_tutorial_page, parent, false)
                ) {}

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val page = pages[position]
                holder.itemView.findViewById<ImageView>(R.id.tut_icon).setImageResource(page.icon)
                holder.itemView.findViewById<TextView>(R.id.tut_title).setText(page.title)
                holder.itemView.findViewById<TextView>(R.id.tut_body).setText(page.body)
            }

            override fun getItemCount() = pages.size
        }

        buildDots()
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateUi(position)
            }
        })
        updateUi(0)

        next.setOnClickListener {
            if (pager.currentItem >= pages.size - 1) finishTutorial()
            else pager.setCurrentItem(pager.currentItem + 1, true)
        }
        skip.setOnClickListener { finishTutorial() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pager.currentItem > 0) pager.setCurrentItem(pager.currentItem - 1, true)
                else finishTutorial()
            }
        })
    }

    private fun buildDots() {
        dots.removeAllViews()
        val size = 8.dpToPx(this)
        val margin = 4.dpToPx(this)
        for (i in pages.indices) {
            val dot = View(this)
            dot.layoutParams = LinearLayout.LayoutParams(size, size).also {
                it.setMargins(margin, 0, margin, 0)
            }
            dots.addView(dot)
        }
    }

    private fun updateUi(position: Int) {
        val active = MaterialColors.getColor(dots, androidx.appcompat.R.attr.colorPrimary)
        val inactive = MaterialColors.getColor(dots, com.google.android.material.R.attr.colorOutlineVariant)
        for (i in 0 until dots.childCount) {
            val d = dots.getChildAt(i)
            d.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (i == position) active else inactive)
            }
        }
        val last = position >= pages.size - 1
        next.setText(if (last) R.string.p3x_tut_done else R.string.p3x_tut_next)
        skip.visibility = if (last) View.INVISIBLE else View.VISIBLE
    }

    private fun finishTutorial() {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putBoolean(PREF_DONE, true).apply()
        finish()
    }
}
