package org.monogram.presentation.di

import com.google.i18n.phonenumbers.PhoneNumberUtil
import org.monogram.presentation.di.coil.coilModule
import org.koin.dsl.module

val uiModule = module {
    includes(coilModule)
    single {
        PhoneNumberUtil.getInstance()
    }
}