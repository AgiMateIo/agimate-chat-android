package ru.agimate.mobile.feature.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.agimate.mobile.BuildConfig
import ru.agimate.mobile.R
import ru.agimate.mobile.core.auth.SessionManager
import ru.agimate.mobile.core.network.OriginProvider
import ru.agimate.mobile.core.ui.text.UiText
import ru.agimate.mobile.core.ui.text.uiText
import javax.inject.Inject

/**
 * Настройки, у которых есть состояние за пределами экрана. Язык и тема сюда не попадают: их
 * хранит система либо `SharedPreferences`, и переживать пересоздание они должны сами.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val origins: OriginProvider,
    private val sessionManager: SessionManager,
) : ViewModel() {

    val origin: StateFlow<String> = origins.origin

    val originEditable: Boolean = BuildConfig.ALLOW_ORIGIN_OVERRIDE

    private val _originError = MutableStateFlow<UiText?>(null)
    val originError: StateFlow<UiText?> = _originError.asStateFlow()

    /**
     * Смена адреса перечитывает сессию: на новом сервере человек либо не вошёл, либо это вообще
     * другой человек, и показывать до следующего запуска старый профиль нельзя.
     */
    fun changeOrigin(value: String) {
        if (origins.override(value)) {
            _originError.value = null
            sessionManager.refresh()
        } else {
            _originError.value = uiText(R.string.settings_bad_origin)
        }
    }
}
