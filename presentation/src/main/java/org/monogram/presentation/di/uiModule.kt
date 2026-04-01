package org.monogram.presentation.di

import org.koin.dsl.module
import org.monogram.presentation.di.coil.coilModule

val uiModule = module {
    includes(coilModule)
}