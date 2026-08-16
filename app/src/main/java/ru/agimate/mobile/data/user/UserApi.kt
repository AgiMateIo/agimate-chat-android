package ru.agimate.mobile.data.user

import kotlinx.serialization.Serializable
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Path
import ru.agimate.mobile.core.network.ApiEnvelope
import ru.agimate.mobile.core.network.InstantSerializer
import java.time.Instant

/**
 * Роль аккаунта. Новый аккаунт заводится с [GUEST] и до одобрения администратором в продукт не
 * попадает — это не ошибка авторизации: вход прошёл, токены выданы, а агентов завести нельзя.
 */
enum class UserRole {
    GUEST, USER, ADMIN, UNKNOWN;

    val approved: Boolean get() = this == USER || this == ADMIN

    companion object {
        fun parse(raw: String?): UserRole =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: UNKNOWN
    }
}

@Serializable
data class UserDto(
    val id: String,
    val email: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val displayName: String? = null,
    val role: String? = null,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant? = null,
)

data class UserProfile(
    val id: String,
    val email: String?,
    val displayName: String,
    val role: UserRole,
) {
    companion object {
        fun from(dto: UserDto): UserProfile = UserProfile(
            id = dto.id,
            email = dto.email,
            displayName = listOfNotNull(
                dto.displayName?.takeIf { it.isNotBlank() },
                listOfNotNull(dto.firstName, dto.lastName).joinToString(" ").takeIf { it.isNotBlank() },
                dto.email,
            ).firstOrNull().orEmpty(),
            role = UserRole.parse(dto.role),
        )
    }
}

@Serializable
data class DeviceSessionDto(
    val id: String,
    /** `NATIVE` или `WEB` — браузерные входы видны в том же списке. */
    val client: String? = null,
    val deviceLabel: String? = null,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant? = null,
    @Serializable(with = InstantSerializer::class)
    val lastSeenAt: Instant? = null,
)

interface UserApi {

    /** Да, `user` дважды — так исторически устроен путь профиля, это не опечатка. */
    @GET("user/user/me")
    suspend fun me(): ApiEnvelope<UserDto>

    @GET("user/sessions/")
    suspend fun sessions(): ApiEnvelope<List<DeviceSessionDto>>

    @DELETE("user/sessions/{id}")
    suspend fun revokeSession(@Path("id") id: String): ApiEnvelope<String>
}
