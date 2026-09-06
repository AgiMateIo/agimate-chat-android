package ru.agimate.mobile.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Отсекает ответы, пришедшие не от нашего API.
 *
 * Портал гостиничного или аэропортового Wi-Fi перехватывает запрос и отвечает **200 и страницей
 * входа**. Формально это успех, и разбор доходит до сериализатора, который на первом же `<` бросает
 * `SerializationException` — исключение, которого не ждёт никто: у `apiCall` в перехвате только
 * ввод-вывод и HTTP-ошибки, у обновления токенов — тоже. Дальше оно уходит в чужой поток и убивает
 * процесс. То же делает балансировщик, отдающий свою страницу вместо ответа сервиса.
 *
 * Поэтому подмена распознаётся там, где она случилась, — на транспорте, — и переводится в понятие,
 * которое остальной код уже умеет обрабатывать: связи нет. Это правда и по сути: до сервера запрос
 * не дошёл, а токены целы и разлогинивать по такому нельзя.
 *
 * Смотрим только на тип содержимого и только у успешных ответов. Тело не читаем: у скачивания
 * вложений свой клиент без интерцепторов, но и здесь платить буферизацией за проверку незачем.
 * Ответ без тела (`204`) законен и проходит.
 */
@Singleton
class ForeignResponseInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) return response

        val type = response.body.contentType()
        if (type == null || type.subtype.equals("json", ignoreCase = true)) return response

        // Тело закрываем сами: дальше по цепочке его уже никто не возьмёт, а незакрытое держит
        // соединение вне пула.
        response.close()
        throw IOException("ответ не от API: ${response.code}, Content-Type $type")
    }
}
