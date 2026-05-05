package org.monogram.app.di

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp

class FirebaseGmsRuntime(
    private val context: Context
) : GmsRuntime {
    override val isGmsAvailable: Boolean
        get() = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    override val isFcmConfigured: Boolean
        get() = FirebaseApp.getApps(context).isNotEmpty()
}
