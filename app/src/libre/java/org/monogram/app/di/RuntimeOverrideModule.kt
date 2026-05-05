package org.monogram.app.di

import org.koin.dsl.module

val runtimeOverrideModule = module {
    single<GmsRuntime> { LibreGmsRuntime() }
}
