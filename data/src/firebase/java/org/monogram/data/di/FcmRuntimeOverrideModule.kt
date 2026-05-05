package org.monogram.data.di

import org.koin.dsl.module
import org.monogram.data.push.FcmRuntime
import org.monogram.data.push.FirebaseFcmRuntime

val fcmRuntimeOverrideModule = module {
    single<FcmRuntime> { FirebaseFcmRuntime() }
}
