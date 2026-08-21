package ru.agimate.mobile.core.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import ru.agimate.mobile.MainActivity
import ru.agimate.mobile.R
import javax.inject.Inject
import javax.inject.Singleton

/** Ключи, которыми переписка передаётся из уведомления в активность. */
object PushExtras {
    const val SESSION_ID = "push.sessionId"
    const val AGENT_ID = "push.agentId"
    const val AGENT_NAME = "push.agentName"

    /** Переписка из intent'а, если он пришёл тапом по уведомлению. */
    fun target(intent: Intent?): PushChatTarget? {
        val sessionId = intent?.getStringExtra(SESSION_ID)?.takeIf { it.isNotBlank() } ?: return null
        return PushChatTarget(
            sessionId = sessionId,
            agentId = intent.getStringExtra(AGENT_ID)?.takeIf { it.isNotBlank() },
            agentName = intent.getStringExtra(AGENT_NAME).orEmpty(),
        )
    }
}

/** Уведомление о сообщении агента: канал, показ и адрес, куда ведёт тап. */
@Singleton
class PushNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /**
     * Канал создаётся заранее, а не при первом уведомлении: до создания канала система показывает
     * настройки уведомлений приложения пустыми, и человек не может выключить их до первого пуша.
     */
    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.push_channel_name),
            // Ответ агента — то, чего человек ждёт, поэтому со звуком и всплытием.
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.push_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    fun show(message: PushMessage) {
        if (!allowed()) return

        // Имя нужно и заголовку, и extra: чат, открытый из шторки, должен назваться так же.
        val agentName = message.agentName ?: context.getString(R.string.push_unknown_agent)

        val intent = Intent(context, MainActivity::class.java).apply {
            // Активность объявлена singleTask: intent приедет в onNewIntent уже запущенной, а не
            // поднимет второй экземпляр поверх открытого приложения.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(PushExtras.SESSION_ID, message.sessionId)
            putExtra(PushExtras.AGENT_ID, message.agentId)
            putExtra(PushExtras.AGENT_NAME, agentName)
        }
        val requestCode = message.sessionId.hashCode()
        val pending = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = message.preview ?: context.getString(R.string.push_default_text)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(agentName)
            .setContentText(text)
            // Ответ агента бывает длиннее строки, и обрезанный он бесполезен.
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        // Идентификатор — по переписке, а не по сообщению: второе сообщение подряд заменяет первое,
        // а не копится в шторке. Заодно это и дедупликация: доставка at-least-once, и тот же пуш
        // может прийти дважды.
        NotificationManagerCompat.from(context).notify(message.sessionId.hashCode(), notification)
    }

    /**
     * На Android 13+ уведомления без выданного разрешения молча отбрасываются. Проверяем сами,
     * чтобы это было видно в коде, а не выглядело как потерянный пуш.
     */
    private fun allowed(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val CHANNEL_ID = "agent_messages"
    }
}
