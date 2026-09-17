package org.monogram.core.models

/** Current account capabilities shared by the application components. */
class AccountState {
    @Volatile
    var isPremium: Boolean = false
}
