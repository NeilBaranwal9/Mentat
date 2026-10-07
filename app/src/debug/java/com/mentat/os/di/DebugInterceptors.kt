package com.mentat.os.di

import okhttp3.Interceptor
import okhttp3.logging.HttpLoggingInterceptor

/** Debug-only OkHttp logger. The Authorization header (Groq key) is always redacted. */
fun debugInterceptors(): List<Interceptor> = listOf(
    HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
        redactHeader("Authorization")
    },
)
