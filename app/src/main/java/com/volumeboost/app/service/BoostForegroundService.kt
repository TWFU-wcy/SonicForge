package com.volumeboost.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.volumeboost.app.R
import com.volumeboost.app.audio.AudioEngine
import com.volumeboost.app.audio.AudioEngineProvider
import com.volumeboost.app.audio.Preset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台服务：保证效果链在切后台/锁屏后不被系统回收。
 * Android 14+ 要求显式声明 foregroundServiceType=mediaPlayback（Manifest 已声明）。
 */
class BoostForegroundService : Service() {

    private lateinit var engine: AudioEngine
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        engine = AudioEngineProvider.get(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ActionEnable -> {
                val presetId = intent.getStringExtra(ExtraPresetId) ?: Preset.Default.id
                val preset = Preset.All.firstOrNull { it.id == presetId } ?: Preset.Default
                startForegroundWithNotification()
                serviceScope.launch { engine.enable(preset) }
            }
            ActionDisable -> {
                serviceScope.launch { engine.disable() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NotificationId, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                ChannelId,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val builder = NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (contentIntent != null) builder.setContentIntent(contentIntent)
        return builder.build()
    }

    companion object {
        private const val ChannelId = "boost_service"
        const val NotificationId = 1
        const val ActionEnable = "com.volumeboost.app.ENABLE"
        const val ActionDisable = "com.volumeboost.app.DISABLE"
        const val ExtraPresetId = "preset_id"

        fun enable(context: Context, presetId: String) {
            val intent = Intent(context, BoostForegroundService::class.java)
                .setAction(ActionEnable)
                .putExtra(ExtraPresetId, presetId)
            ContextCompatStart(context, intent)
        }

        /** 静默取消通知（效果链已由用户关闭但服务可能仍在的情形）。 */
        fun cancelNotification(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.cancel(NotificationId)
        }

        /**
         * 关闭：直接停掉服务。
         *
         * 不能用 startForegroundService() 拉起一个「只为自杀」的服务——系统要求
         * 被 startForegroundService() 拉起的服务必须在 5 秒内调用 startForeground()，
         * 否则抛 ForegroundServiceDidNotStartInTimeException 直接崩掉进程。
         */
        fun disable(context: Context) {
            runCatching {
                context.stopService(Intent(context, BoostForegroundService::class.java))
            }
            cancelNotification(context)
        }

        private fun ContextCompatStart(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

