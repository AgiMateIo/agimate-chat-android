package ru.agimate.mobile.core.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Политика страницы: строка CSP и allow-list origin — неразрывная пара.
 *
 * Заголовок отдаётся из `shouldInterceptRequest`, а allow-list решает, какие запросы перехватчик
 * пропускает в сеть. Разъедься они — и вью либо не загрузится, либо получит лишний домен. Поэтому
 * обе половины строятся одной функцией и проверяются вместе.
 */
class ViewCspTest {

    private val strict =
        "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
            "img-src data: blob:; font-src data:; media-src data: blob:; connect-src 'none'; " +
            "frame-src 'none'; base-uri 'none'; object-src 'none'"

    @Test
    fun `null domains keep today's strict header`() {
        assertEquals(strict, ViewCsp.policy(null).header)
    }

    @Test
    fun `empty domains also keep the strict header`() {
        assertEquals(strict, ViewCsp.policy(ViewExternalDomains()).header)
    }

    @Test
    fun `resource domains go to every resource directive`() {
        val policy = ViewCsp.policy(
            ViewExternalDomains(resourceDomains = listOf("https://esm.sh")),
        )

        assertEquals(
            "default-src 'none'; script-src 'unsafe-inline' https://esm.sh; " +
                "style-src 'unsafe-inline' https://esm.sh; " +
                "img-src data: blob: https://esm.sh; font-src data: https://esm.sh; " +
                "media-src data: blob: https://esm.sh; connect-src 'none'; " +
                "frame-src 'none'; base-uri 'none'; object-src 'none'",
            policy.header,
        )
        assertEquals(setOf(ViewOrigin("https://esm.sh")), policy.allowedOrigins)
    }

    @Test
    fun `connect domains are https and wss`() {
        val policy = ViewCsp.policy(
            ViewExternalDomains(connectDomains = listOf("https://api.example.com")),
        )

        assertTrue(
            policy.header.contains(
                "connect-src https://api.example.com wss://api.example.com",
            ),
        )
        assertEquals(setOf(ViewOrigin("https://api.example.com")), policy.allowedOrigins)
    }

    @Test
    fun `resource and connect domains share one allow-list`() {
        val policy = ViewCsp.policy(
            ViewExternalDomains(
                resourceDomains = listOf("https://esm.sh"),
                connectDomains = listOf("https://api.example.com"),
            ),
        )

        assertEquals(
            setOf(ViewOrigin("https://esm.sh"), ViewOrigin("https://api.example.com")),
            policy.allowedOrigins,
        )
    }

    @Test
    fun `malformed origins are dropped`() {
        val policy = ViewCsp.policy(
            ViewExternalDomains(
                resourceDomains = listOf(
                    "http://esm.sh",
                    "https://*.*",
                    "https://esm.sh/path",
                    "https://user@esm.sh",
                    "esm.sh",
                ),
            ),
        )

        assertEquals(strict, policy.header)
        assertTrue(policy.allowedOrigins.isEmpty())
    }

    @Test
    fun `the allow-list matches the exact origin and treats 443 as default`() {
        val policy = ViewCsp.policy(
            ViewExternalDomains(resourceDomains = listOf("https://esm.sh")),
        )

        assertTrue(policy.allows("https", "esm.sh", -1))
        assertTrue(policy.allows("https", "esm.sh", 443))
        assertFalse(policy.allows("https", "esm.sh", 8443))
        assertFalse(policy.allows("http", "esm.sh", -1))
        assertFalse(policy.allows("https", "evil.example", -1))
        assertFalse(policy.allows(null, null, -1))
    }

    @Test
    fun `a non-default port is part of the origin`() {
        val policy = ViewCsp.policy(
            ViewExternalDomains(resourceDomains = listOf("https://esm.sh:8443")),
        )

        assertTrue(policy.header.contains("https://esm.sh:8443"))
        assertTrue(policy.allows("https", "esm.sh", 8443))
        assertFalse(policy.allows("https", "esm.sh", -1))
    }
}
