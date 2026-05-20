package com.example.umawidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.RemoteViews
import com.bumptech.glide.Glide
import com.google.firebase.database.FirebaseDatabase
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest
import android.text.Html

class UmaAppWidgetSmall : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH_SMALL = "com.example.umawidget.ACTION_REFRESH_SMALL"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateSmallAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH_SMALL) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, UmaAppWidgetSmall::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

            val views = RemoteViews(context.packageName, R.layout.uma_app_widget_small)
            views.setTextViewText(R.id.tv_small_sync_status, "...")
            appWidgetManager.updateAppWidget(componentName, views)

            for (appWidgetId in appWidgetIds) {
                updateSmallAppWidget(context, appWidgetManager, appWidgetId)
            }
        }
    }
}

private fun formatRemainingTimeSmall(seconds: Long): String {
    if (seconds <= 0) return "Max"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}

internal fun updateSmallAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
    val views = RemoteViews(context.packageName, R.layout.uma_app_widget_small)

    val intent = Intent(context, MainActivity::class.java)
    val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    views.setOnClickPendingIntent(R.id.widget_small_main_layout, pendingIntent)

    val refreshIntent = Intent(context, UmaAppWidgetSmall::class.java).apply { action = UmaAppWidgetSmall.ACTION_REFRESH_SMALL }
    val refreshPendingIntent = PendingIntent.getBroadcast(context, 1, refreshIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    views.setOnClickPendingIntent(R.id.tv_small_sync_status, refreshPendingIntent)

    val sharedPref = context.getSharedPreferences("UmaWidgetPrefs", Context.MODE_PRIVATE)
    val pseudo = sharedPref.getString("pseudo", "")
    val pin = sharedPref.getString("pin", "")

    if (pseudo.isNullOrEmpty() || pin.isNullOrEmpty()) {
        views.setTextViewText(R.id.tv_small_sync_status, "Off")
        appWidgetManager.updateAppWidget(appWidgetId, views)
        return
    }

    val userId = "$pseudo-$pin"
    val databaseUrl = "https://uma-widget-default-rtdb.europe-west1.firebasedatabase.app/"
    val dbRef = FirebaseDatabase.getInstance(databaseUrl).getReference("users").child(userId)

    dbRef.get().addOnSuccessListener { snapshot ->
        if (snapshot.exists()) {
            val tpBase = snapshot.child("tp").value?.toString()?.toIntOrNull() ?: 0
            val rpBase = snapshot.child("rp").value?.toString()?.toIntOrNull() ?: 0
            val currentTimeSeconds = System.currentTimeMillis() / 1000
            val lastUpdateTp = snapshot.child("last_update_tp").value?.toString()?.toLongOrNull() ?: currentTimeSeconds
            val lastUpdateRp = snapshot.child("last_update_rp").value?.toString()?.toLongOrNull() ?: currentTimeSeconds

            val remoteBgUrl = snapshot.child("bg_url").value?.toString()

            // Fetch color preference
            val useBlackText = sharedPref.getBoolean("use_black_text", false)
            val customTextColor = if (useBlackText) Color.BLACK else Color.WHITE

            if (!remoteBgUrl.isNullOrEmpty()) {
                views.setViewVisibility(R.id.widget_small_background_img, android.view.View.VISIBLE)
                views.setInt(R.id.widget_small_main_layout, "setBackgroundResource", 0)

                val alignChoice = sharedPref.getString("bg_align", "CENTER") ?: "CENTER"

                Thread {
                    try {
                        val bitmap = Glide.with(context.applicationContext)
                            .asBitmap()
                            .load(remoteBgUrl)
                            .override(300, 300)
                            .transform(AlignmentCropSmall(alignChoice))
                            .submit()
                            .get()

                        views.setImageViewBitmap(R.id.widget_small_background_img, bitmap)

                        // Apply color preference
                        views.setTextColor(R.id.tv_small_sync_status, customTextColor)
                        views.setTextColor(R.id.tv_small_tp_value, customTextColor)
                        views.setTextColor(R.id.tv_small_tp_time, customTextColor)
                        views.setTextColor(R.id.tv_small_rp_value, customTextColor)
                        views.setTextColor(R.id.tv_small_rp_time, customTextColor)

                        appWidgetManager.updateAppWidget(appWidgetId, views)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }.start()
            } else {
                views.setViewVisibility(R.id.widget_small_background_img, android.view.View.GONE)
                views.setInt(R.id.widget_small_main_layout, "setBackgroundResource", R.drawable.widget_shape)
                views.setTextColor(R.id.tv_small_sync_status, Color.WHITE)
                views.setTextColor(R.id.tv_small_tp_value, Color.WHITE)
                views.setTextColor(R.id.tv_small_tp_time, Color.WHITE)
                views.setTextColor(R.id.tv_small_rp_value, Color.WHITE)
                views.setTextColor(R.id.tv_small_rp_time, Color.WHITE)
            }

            val elapsedTp = if (currentTimeSeconds - lastUpdateTp > 0) currentTimeSeconds - lastUpdateTp else 0
            var tpActuel = tpBase + (elapsedTp / 600).toInt()
            val tpTimeStr = if (tpActuel >= 100) { tpActuel = 100; "Max" } else formatRemainingTimeSmall(((100 - tpActuel) * 600L) - (elapsedTp % 600))

            val elapsedRp = if (currentTimeSeconds - lastUpdateRp > 0) currentTimeSeconds - lastUpdateRp else 0
            var rpActuel = rpBase + (elapsedRp / 7200).toInt()
            val rpTimeStr = if (rpActuel >= 5) { rpActuel = 5; "Max" } else formatRemainingTimeSmall(((5 - rpActuel) * 7200L) - (elapsedRp % 7200))

            views.setTextViewText(R.id.tv_small_tp_value, "$tpActuel/100")
            views.setTextViewText(R.id.tv_small_tp_time, tpTimeStr)
            views.setTextViewText(R.id.tv_small_rp_value, "$rpActuel/5")
            views.setTextViewText(R.id.tv_small_rp_time, rpTimeStr)

            views.setTextViewText(R.id.tv_small_sync_status, Html.fromHtml("⇋ <b>Sync</b>", Html.FROM_HTML_MODE_LEGACY))

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}

class AlignmentCropSmall(private val alignment: String) : BitmapTransformation() {
    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(("align_$alignment").toByteArray())
    }

    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val scale = Math.max(outWidth.toFloat() / toTransform.width, outHeight.toFloat() / toTransform.height)
        val scaledWidth = scale * toTransform.width
        val scaledHeight = scale * toTransform.height
        val dx = (outWidth - scaledWidth) / 2f
        val dy = when (alignment) {
            "TOP" -> 0f
            "BOTTOM" -> outHeight - scaledHeight
            else -> (outHeight - scaledHeight) / 2f
        }
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        val result = pool.get(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        result.setHasAlpha(toTransform.hasAlpha())
        Canvas(result).drawBitmap(toTransform, matrix, Paint(Paint.ANTI_ALIAS_FLAG))
        return result
    }
}