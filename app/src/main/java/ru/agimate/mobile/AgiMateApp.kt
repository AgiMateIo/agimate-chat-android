package ru.agimate.mobile

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.os.UserManagerCompat
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import ru.agimate.mobile.core.push.AppVisibility
import ru.agimate.mobile.core.push.PushSync
import ru.agimate.mobile.core.ui.locale.AppLanguages
import javax.inject.Inject

@HiltAndroidApp
class AgiMateApp : Application() {

    @Inject
    lateinit var visibility: AppVisibility

    /**
     * [Lazy], а не сам объект: за [PushSync] тянется граф до токенов входа, а те лежат за ключом
     * пользователя и до первой разблокировки после перезагрузки недоступны. Собери Hilt этот граф
     * прямо на поле — процесс, поднятый пушем на запертом телефоне, падал бы на внедрении, не дойдя
     * ни до одной нашей строки.
     */
    @Inject
    lateinit var push: Lazy<PushSync>

    /**
     * Выбранный язык до Android 13 приходится подставлять самим, и контексту приложения — тоже:
     * из него берёт строки уведомление, а оно рисуется, когда ни одного экрана может не быть.
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguages.applyTo(base))
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(visibility)
        // Пуш на закрытом приложении поднимает процесс и приходит сюда: слушатели должны стоять к
        // этому моменту, а экранов может не быть вовсе.
        if (UserManagerCompat.isUserUnlocked(this)) push.get().start() else startPushOnUnlock()
    }

    /**
     * Запертый телефон: поднимать пуши нечем — и вход, и отметки о подписках лежат за ключом
     * пользователя. Ждём разблокировку и начинаем тогда: процесс, поднятый в этом окне, живёт
     * дальше как обычный, и без этого дожил бы до конца без единой подписки.
     *
     * `RECEIVER_NOT_EXPORTED` — потому что отправитель здесь только система; чужим приложениям
     * будить нас этим незачем, да и `ACTION_USER_UNLOCKED` они послать не могут.
     */
    private fun startPushOnUnlock() {
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    unregisterReceiver(this)
                    push.get().start()
                }
            },
            IntentFilter(Intent.ACTION_USER_UNLOCKED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
