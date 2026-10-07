package com.mentat.di

import okhttp3.Interceptor

/** Release builds carry no HTTP logger. */
fun debugInterceptors(): List<Interceptor> = emptyList()
