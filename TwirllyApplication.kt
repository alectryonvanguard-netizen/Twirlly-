package com.example.twirlly

import android.app.Application
import android.content.pm.ApplicationInfo
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/** Starts Firebase when app/google-services.json is present. Without it, the app runs in local mode. */
class TwirllyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            if (FirebaseApp.getApps(this).isEmpty()) FirebaseApp.initializeApp(this)
            if (FirebaseApp.getApps(this).isNotEmpty()) {
                // App Check lets Firebase tell the real Twirlly app from copies and scripts.
                val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                val factory: AppCheckProviderFactory = if (debuggable) {
                    Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory")
                        .getMethod("getInstance").invoke(null) as AppCheckProviderFactory
                } else PlayIntegrityAppCheckProviderFactory.getInstance()
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(factory)
                Auth.provider = FirebaseAuthProvider
            }
        } catch (_: Throwable) {
            // stay in local mode
        }
    }
}
