package com.localai.app.data.tools

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Phone actions that need no permission. SMS and contacts access were removed (ADR-0013, 2026-11-17 update):
 * Google Play Protect blocks sideloaded apps that request READ_SMS / READ_CONTACTS.
 */
object PhoneAccess {
    /** Opens the dialer with the number filled in. The user still presses Call. */
    fun openDialer(context: Context, number: String) {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
