package com.example.umawidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.bumptech.glide.Glide
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.*
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest
import android.text.Html

// Format seconds into readable text
private fun formatRemainingTime(seconds: Long): String {
    if (seconds <= 0) return "Max"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}

class UmaAppWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.example.umawidget.ACTION_REFRESH"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, UmaAppWidget::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

            val views = RemoteViews(context.packageName, R.layout.uma_app_widget)
            views.setTextViewText(R.id.tv_sync_status, "Updating...")
            appWidgetManager.updateAppWidget(componentName, views)

            for (appWidgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId)
            }
        }
    }
}

internal fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
    val views = RemoteViews(context.packageName, R.layout.uma_app_widget)

    val intent = Intent(context, MainActivity::class.java)
    val pendingIntent = PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    views.setOnClickPendingIntent(R.id.widget_main_layout, pendingIntent)

    val refreshIntent = Intent(context, UmaAppWidget::class.java).apply {
        action = UmaAppWidget.ACTION_REFRESH
    }
    val refreshPendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        refreshIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    views.setOnClickPendingIntent(R.id.tv_sync_status, refreshPendingIntent)

    val currentDate = Date()
    val dayFormat = SimpleDateFormat("EEE", Locale.ENGLISH)
    val dateFormat = SimpleDateFormat("MM/dd", Locale.ENGLISH)

    views.setTextViewText(R.id.widget_day, dayFormat.format(currentDate))
    views.setTextViewText(R.id.widget_date, dateFormat.format(currentDate))

    val sharedPref = context.getSharedPreferences("UmaWidgetPrefs", Context.MODE_PRIVATE)
    val pseudo = sharedPref.getString("pseudo", "")
    val pin = sharedPref.getString("pin", "")

    if (pseudo.isNullOrEmpty() || pin.isNullOrEmpty()) {
        views.setTextViewText(R.id.tv_sync_status, "Open the app")
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
            val karatsTotal = snapshot.child("karats").value?.toString() ?: "0"
            val currentTimeSeconds = System.currentTimeMillis() / 1000
            val lastUpdateTp = snapshot.child("last_update_tp").value?.toString()?.toLongOrNull() ?: currentTimeSeconds
            val lastUpdateRp = snapshot.child("last_update_rp").value?.toString()?.toLongOrNull() ?: currentTimeSeconds

            val remoteBgUrl = snapshot.child("bg_url").value?.toString()

            // Fetch user selected color preference
            val useBlackText = sharedPref.getBoolean("use_black_text", false)
            val customTextColor = if (useBlackText) android.graphics.Color.BLACK else android.graphics.Color.WHITE

            if (!remoteBgUrl.isNullOrEmpty()) {
                views.setViewVisibility(R.id.widget_background_img, android.view.View.VISIBLE)
                views.setViewVisibility(R.id.tv_no_bg_message, android.view.View.GONE)

                val alignChoice = sharedPref.getString("bg_align", "CENTER") ?: "CENTER"

                Thread {
                    try {
                        val bitmap = Glide.with(context.applicationContext)
                            .asBitmap()
                            .load(remoteBgUrl)
                            .override(800, 400)
                            .transform(AlignmentCrop(alignChoice))
                            .submit()
                            .get()

                        views.setImageViewBitmap(R.id.widget_background_img, bitmap)

                        // Apply color preference
                        views.setTextColor(R.id.widget_day, customTextColor)
                        views.setTextColor(R.id.widget_date, customTextColor)
                        views.setTextColor(R.id.tv_sync_status, customTextColor)
                        views.setTextColor(R.id.tv_tp_value, customTextColor)
                        views.setTextColor(R.id.tv_rp_value, customTextColor)
                        views.setTextColor(R.id.tv_karats_value, customTextColor)
                        views.setTextColor(R.id.tv_tp_time, customTextColor)
                        views.setTextColor(R.id.tv_rp_time, customTextColor)
                        views.setTextColor(R.id.tv_free_karats_label, customTextColor)

                        appWidgetManager.updateAppWidget(appWidgetId, views)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }.start()
            } else {
                views.setViewVisibility(R.id.widget_background_img, android.view.View.GONE)
                views.setViewVisibility(R.id.tv_no_bg_message, android.view.View.VISIBLE)
            }

            val elapsedSecondsTp = if (currentTimeSeconds - lastUpdateTp > 0) currentTimeSeconds - lastUpdateTp else 0
            val tpGagnes = (elapsedSecondsTp / 600).toInt()
            var tpActuel = tpBase + tpGagnes

            val tpTimeStr = if (tpActuel >= 100) {
                tpActuel = 100
                "Max"
            } else {
                val remainingSecondsTp = ((100 - tpActuel) * 600L) - (elapsedSecondsTp % 600)
                formatRemainingTime(remainingSecondsTp)
            }

            val elapsedSecondsRp = if (currentTimeSeconds - lastUpdateRp > 0) currentTimeSeconds - lastUpdateRp else 0
            val rpGagnes = (elapsedSecondsRp / 7200).toInt()
            var rpActuel = rpBase + rpGagnes

            val rpTimeStr = if (rpActuel >= 5) {
                rpActuel = 5
                "Max"
            } else {
                val remainingSecondsRp = ((5 - rpActuel) * 7200L) - (elapsedSecondsRp % 7200)
                formatRemainingTime(remainingSecondsRp)
            }

            views.setTextViewText(R.id.tv_tp_value, "$tpActuel/100")
            views.setTextViewText(R.id.tv_rp_value, "$rpActuel/5")
            views.setTextViewText(R.id.tv_tp_time, tpTimeStr)
            views.setTextViewText(R.id.tv_rp_time, rpTimeStr)
            views.setTextViewText(R.id.tv_karats_value, karatsTotal)

            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val syncText = "<b>⇋ Sync: ${sdf.format(Date())}</b>"
            views.setTextViewText(R.id.tv_sync_status, Html.fromHtml(syncText, Html.FROM_HTML_MODE_LEGACY))

            appWidgetManager.updateAppWidget(appWidgetId, views)
        } else {
            views.setTextViewText(R.id.tv_sync_status, "Data Error")
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}

class AlignmentCrop(private val alignment: String) : BitmapTransformation() {
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