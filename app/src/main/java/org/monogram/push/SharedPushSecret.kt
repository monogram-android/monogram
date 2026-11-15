package org.monogram.push

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

class SharedPushSecret(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("shared_push_secret", Context.MODE_PRIVATE)

    @Synchronized
    fun get(): ByteArray {
        val stored = prefs.getString("secret", null)
        if (stored != null) {
            return Base64.decode(stored, Base64.NO_WRAP).also {
                require(it.size == 256) { "Invalid push secret" }
            }
        }
        val secret = ByteArray(256).also { SecureRandom().nextBytes(it) }
        check(
            prefs.edit().putString("secret", Base64.encodeToString(secret, Base64.NO_WRAP)).commit()
        ) {
            "Unable to persist push secret"
        }
        return secret
    }
}