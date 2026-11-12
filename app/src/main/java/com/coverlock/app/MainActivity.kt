package com.coverlock.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var accessStatus: TextView
    private lateinit var wallpaperStatus: TextView
    private lateinit var preview: ImageView
    private val exec = Executors.newSingleThreadExecutor()

    private var curSize = 66
    private var curTop = 36
    private var curFocus = 50

    private lateinit var sizeBox: View
    private lateinit var topBox: View
    private lateinit var focusBox: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val gap = (14 * d).toInt()
        curSize = LockArt.getSize(this)
        curTop = LockArt.getTop(this)
        curFocus = LockArt.getFocus(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        fun lp() = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = gap }

        root.addView(TextView(this).apply {
            text = "CoverLock"
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = "Shows the album cover of whatever is playing as your lock screen wallpaper. " +
                "When playback pauses or stops, your normal wallpaper comes back."
        }, lp())

        // ── live preview ──
        val dm = resources.displayMetrics
        val aspect = dm.heightPixels.toFloat() / dm.widthPixels
        val pvH = (400 * d).toInt()
        val pvW = (pvH / aspect).toInt()
        val frame = FrameLayout(this)
        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(Color.DKGRAY)
        }
        frame.addView(preview, FrameLayout.LayoutParams(pvW, pvH))
        // rough stand-ins for your phone's clock and music player so you can judge placement
        frame.addView(
            TextView(this).apply {
                text = "10:09"
                textSize = 44f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            },
            FrameLayout.LayoutParams(pvW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (pvH * 0.08f).toInt()
            }
        )
        frame.addView(
            View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 40 * d
                    setColor(0xAA202020.toInt())
                }
            },
            FrameLayout.LayoutParams((pvW * 0.6f).toInt(), (pvH * 0.075f).toInt()).apply {
                topMargin = (pvH * 0.70f).toInt()
                gravity = Gravity.CENTER_HORIZONTAL
            }
        )
        root.addView(frame, LinearLayout.LayoutParams(pvW, pvH).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = gap
        })
        root.addView(TextView(this).apply {
            text = "Preview (approximate). The clock and player shown are stand-ins for your phone's own."
            textSize = 12f
            gravity = Gravity.CENTER
        }, lp())

        // ── style picker ──
        root.addView(TextView(this).apply {
            text = "Style"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }, lp())
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val current = LockArt.getStyle(this)
        CoverStyle.values().forEachIndexed { i, st ->
            group.addView(RadioButton(this).apply {
                id = 1000 + i
                text = st.label
                isChecked = st == current
            })
        }
        group.setOnCheckedChangeListener { _, id ->
            val i = id - 1000
            if (i in CoverStyle.values().indices) {
                val st = CoverStyle.values()[i]
                LockArt.setStyle(this, st)
                updateSliders(st)
                refreshPreview()
            }
        }
        root.addView(HorizontalScrollView(this).apply { addView(group) }, lp())

        // ── sliders ──
        sizeBox = slider("Size", 35, 90, curSize, { v ->
            curSize = v
            refreshPreview()
        }, { v -> LockArt.setSize(this, v) })
        topBox = slider("Position (up / down)", 22, 58, curTop, { v ->
            curTop = v
            refreshPreview()
        }, { v -> LockArt.setTop(this, v) })
        focusBox = slider("Focus (which part of the cover stays in view: left ↔ right)", 0, 100, curFocus, { v ->
            curFocus = v
            refreshPreview()
        }, { v -> LockArt.setFocus(this, v) })
        root.addView(sizeBox, lp())
        root.addView(topBox, lp())
        root.addView(focusBox, lp())
        updateSliders(current)
        root.addView(TextView(this).apply {
            text = "Full screen and Dreamy fill the whole screen, so only Focus applies to them. " +
                "For the other styles, use Size and Position to keep the picture clear of your widgets and player. " +
                "Changes apply to your lock screen automatically while music is playing."
            textSize = 12f
        }, lp())

        // ── HD covers ──
        root.addView(Switch(this).apply {
            text = "Fetch sharp HD cover from the internet"
            isChecked = LockArt.isHdEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on -> LockArt.setHdEnabled(this@MainActivity, on) }
        }, lp())
        root.addView(TextView(this).apply {
            text = "Looks the song up in Apple's public iTunes catalog (it sends the song and artist name) and " +
                "downloads a high-resolution cover, up to 3000×3000. If nothing matches, the player's own cover is used."
            textSize = 12f
        }, lp())

        // ── setup ──
        accessStatus = TextView(this)
        root.addView(accessStatus, lp())

        root.addView(Button(this).apply {
            text = "1. Grant notification access"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }, lp())

        wallpaperStatus = TextView(this)
        root.addView(wallpaperStatus, lp())

        root.addView(Button(this).apply {
            text = "2. Choose my normal lock screen wallpaper"
            setOnClickListener {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }
                startActivityForResult(i, REQ_PICK)
            }
        }, lp())

        root.addView(Switch(this).apply {
            text = "Show album cover on lock screen"
            isChecked = LockArt.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                LockArt.setEnabled(this@MainActivity, on)
                if (!on) Thread { LockArt.restore(this@MainActivity) }.start()
            }
        }, lp())

        root.addView(Button(this).apply {
            text = "Restore my wallpaper now"
            setOnClickListener {
                Thread { LockArt.restore(this@MainActivity) }.start()
                Toast.makeText(this@MainActivity, "Restoring…", Toast.LENGTH_SHORT).show()
            }
        }, lp())

        root.addView(TextView(this).apply {
            text = "Tip: if the app was installed from outside the Play Store and Android blocks " +
                "notification access, open Settings → Apps → CoverLock → ⋮ → \"Allow restricted settings\", " +
                "then try again. Also set the app's battery usage to \"Unrestricted\" so it keeps running."
            textSize = 13f
        }, lp())

        setContentView(ScrollView(this).apply { addView(root) })
        refreshPreview()
    }

    override fun onDestroy() {
        exec.shutdown()
        super.onDestroy()
    }

    private fun slider(
        label: String, lo: Int, hi: Int, value: Int,
        live: (Int) -> Unit, commit: (Int) -> Unit
    ): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply { text = label })
        val bar = SeekBar(this)
        bar.max = hi - lo
        bar.progress = value - lo
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) live(p + lo)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                commit((seekBar?.progress ?: 0) + lo)
            }
        })
        box.addView(bar)
        return box
    }

    private fun updateSliders(style: CoverStyle) {
        val fullScreen = style == CoverStyle.FULL || style == CoverStyle.DREAMY
        focusBox.visibility = if (fullScreen) View.VISIBLE else View.GONE
        sizeBox.visibility = if (fullScreen) View.GONE else View.VISIBLE
        topBox.visibility = if (fullScreen) View.GONE else View.VISIBLE
    }

    private fun refreshPreview() {
        val style = LockArt.getStyle(this)
        val size = curSize
        val top = curTop
        val focus = curFocus
        exec.execute {
            try {
                val bmp = LockArt.renderPreview(this, style, size, top, focus)
                runOnUiThread { preview.setImageBitmap(bmp) }
            } catch (e: Exception) {
                // preview is optional
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val granted = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        accessStatus.text = if (granted) "Notification access: granted ✓" else "Notification access: NOT granted yet"
        if (granted) {
            NotificationListenerService.requestRebind(ComponentName(this, MediaWatcherService::class.java))
        }
        refreshWallpaperStatus()
        refreshPreview()
    }

    private fun refreshWallpaperStatus() {
        wallpaperStatus.text = if (LockArt.hasNormalWallpaper(this))
            "Normal wallpaper: saved ✓"
        else
            "Normal wallpaper: not saved (lock screen will follow your home wallpaper when music stops)"
    }

    @Deprecated("Fine for a simple one-screen app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val uri = data?.data
            val ok = uri != null && LockArt.saveUserWallpaper(this, uri)
            Toast.makeText(this, if (ok) "Saved" else "Couldn't read that image", Toast.LENGTH_SHORT).show()
            refreshWallpaperStatus()
        }
    }

    companion object {
        private const val REQ_PICK = 1
    }
}
