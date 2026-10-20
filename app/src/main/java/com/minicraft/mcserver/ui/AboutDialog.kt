package com.minicraft.mcserver.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.minicraft.mcserver.R

/**
 * "About / Licenses" dialogs.
 *
 * Kept out of the main screens so the notices stay non-intrusive: Settings
 * shows the version, the GitHub link and a one-line disclaimer, and this
 * object displays the full license texts on demand.
 */
object AboutDialog {

    const val GITHUB_URL = "https://github.com/leonaryto/MiniCraft"

    fun openGithub(activity: Activity) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
        } catch (_: ActivityNotFoundException) {
            // No browser installed: nothing to do.
        }
    }

    /** Dialog listing the three license documents bundled in assets/licenses/. */
    fun showLicenses(activity: Activity) {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), 0)
        }
        root.addView(button(activity, activity.getString(R.string.about_license_gpl3)) {
            showAsset(activity, "licenses/GPL-3.0.txt", R.string.about_license_gpl3)
        })
        root.addView(button(activity, activity.getString(R.string.about_third_party)) {
            showAsset(activity, "licenses/THIRD-PARTY-NOTICES.txt", R.string.about_third_party)
        })
        root.addView(button(activity, activity.getString(R.string.about_license_gpl2)) {
            showAsset(activity, "licenses/GPL-2.0.txt", R.string.about_license_gpl2)
        })
        AlertDialog.Builder(activity)
            .setTitle(R.string.about_licenses_button)
            .setView(root)
            .setPositiveButton(R.string.about_close, null)
            .show()
    }

    private fun button(activity: Activity, label: String, onClick: () -> Unit): Button =
        Button(activity).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun showAsset(activity: Activity, path: String, titleRes: Int) {
        val body = try {
            activity.assets.open(path).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            activity.getString(R.string.about_asset_error)
        }
        val tv = TextView(activity).apply {
            text = body
            textSize = 11f
            setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), 8)
            setTextIsSelectable(true)
        }
        val sv = ScrollView(activity).apply { addView(tv) }
        AlertDialog.Builder(activity)
            .setTitle(titleRes)
            .setView(sv)
            .setPositiveButton(R.string.about_close, null)
            .show()
    }

    private fun dp(activity: Activity, value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), activity.resources.displayMetrics
    ).toInt()
}
