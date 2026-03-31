package org.monogram.presentation.di

import com.google.i18n.phonenumbers.PhoneNumberUtil
import org.koin.android.ext.koin.androidContext
import org.monogram.presentation.di.coil.coilModule
import org.koin.dsl.module

val uiModule = module {
    includes(coilModule)
    single {
        PhoneNumberUtil.init(androidContext())
        PhoneNumberUtil.getInstance()
    }
}