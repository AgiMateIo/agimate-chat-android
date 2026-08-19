package ru.agimate.mobile

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import ru.agimate.mobile.core.push.AppVisibility
import ru.agimate.mobile.core.push.PushSync
import javax.inject.Inject

@HiltAndroidApp
class AgiMateApp : Application() {

    @Inject
    lateinit var visibility: AppVisibility

    @Inject
    lateinit var push: PushSync

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(visibility)
        // Пуш на закрытом приложении поднимает процесс и приходит сюда: слушатели должны стоять к
        // этому моменту, а экранов может не быть вовсе.
        push.start()
    }
}
