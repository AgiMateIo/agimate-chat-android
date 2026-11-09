package ru.agimate.mobile.core.views

import androidx.activity.ComponentActivity

/**
 * Пустая активити для инструментальных тестов WebView: мосту нужен живой window и UI-поток,
 * поэтому тест поднимает настоящую активити, а не создаёт WebView в вакууме.
 *
 * Живёт в debug-исходниках: в продакшн-сборку этот класс не попадает.
 */
class ViewTestActivity : ComponentActivity()
