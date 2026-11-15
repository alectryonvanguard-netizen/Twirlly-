package com.example.twirlly

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// BEFORE PUBLISHING: replace the three placeholders below and have a qualified lawyer review both documents
// for the countries where you operate (EU/UK GDPR, California CCPA, COPPA, app-store and AdMob requirements).
const val LEGAL_ENTITY = "Twirlly"
const val LEGAL_ADDRESS = "Postal address available on request by email"
const val GOVERNING_LAW = "the country where Twirlly's operator is established"
const val SUPPORT_EMAIL = "samsangacom249@gmail.com"

const val PRIVACY_POLICY = """PRIVACY POLICY
Effective date: November 17, 2026

1. WHO WE ARE
${LEGAL_ENTITY} ("Twirlly", "we", "us") makes the Twirlly app for creating, editing and watching short videos. Questions about this policy: ${SUPPORT_EMAIL}.

2. WHAT WE STORE
Account: your email address and how you sign in (Google or email and password). Passwords are handled by Google Firebase Authentication. Twirlly never sees or stores your password, and we never see your Google password.
Profile: your name, username, bio and privacy setting. Other signed-in people can see your name, username, bio and badge.
Your content: your posts and drafts (as edits that point to files in your gallery), stories, playlists, favorites, blocked list, rewards and settings. This is saved in your own private space online and on your phone. Nobody else can read it.
Kids mode: the PIN is stored only as a salted, scrambled value, never the PIN itself.
Your gallery photos and videos are not copied by Twirlly. A post only remembers where the file is on your phone.

3. WHERE IT IS KEPT AND HOW IT IS PROTECTED
Your online data is kept with Google Firebase (Google Cloud). It travels encrypted (HTTPS) and is encrypted where it is stored. Security rules allow only you to read or write your private data. Firebase App Check helps make sure requests come from the real Twirlly app. Only accounts with a confirmed email can save data online.
We do not use advertising, tracking or analytics tools in the app, and we do not sell your information.

4. PERMISSIONS
Photos and videos: you choose the files in the system picker. Camera and microphone: used on the LIVE screen and for voice-overs. Notifications: used to tell you when something happens in the app. Internet: used to sign in and save your data. You can turn each permission off in your phone settings.

5. SHARING
We share something only when you press Share, and then it goes to the app you choose. Your public profile details can be seen by other signed-in people. We may disclose information if the law requires it.

6. YOUR CONTROL
You can edit your profile (name and username can change once every 7 days), export your data, log out (this clears the phone, your data stays in your account), or delete your account in Settings. Deleting your account removes your account, username, profile and everything saved online, and clears this phone. Copies in Firebase backups are removed on Firebase's normal schedule.
If you use Twirlly as a guest, your data stays only on your phone and is deleted when you log out or uninstall the app.

7. KIDS MODE AND CHILDREN
Twirlly is not for children under 13. Kids mode locks create, LIVE, settings and profile editing behind a PIN. Three wrong PINs lock it for a while. If the PIN is forgotten, the account owner can reset it after confirming the account password or Google account.

8. SECURITY
We use encrypted connections, private storage rules, confirmed emails, limits on wrong tries and checks that requests come from the real app. No system is perfectly secure, so please use a strong password and keep your phone locked.

9. KEEPING DATA
We keep your data while your account exists. After you delete it, we remove it as described above.

10. CHANGES
We may update this policy. The effective date at the top shows the latest version.

11. CONTACT
${LEGAL_ENTITY}
${LEGAL_ADDRESS}
Email: ${SUPPORT_EMAIL}"""

const val TERMS = """TERMS OF SERVICE
Effective date: November 6, 2026

1. ACCEPTING THESE TERMS
By using Twirlly you agree to these Terms. If you do not agree, please do not use the app.

2. WHO CAN USE TWIRLLY
You must be at least 13 years old, and old enough in your country to agree to these Terms.

3. YOUR CONTENT
You own the photos, videos, music and text you use. You are responsible for having the right to use them. Do not use Twirlly for content that is illegal, hateful, harassing, violent, sexual involving minors, misleading, or that copies other people's work without permission. Be careful with music: only use songs you are allowed to use.

4. USING THE APP
Twirlly is provided for personal use. Do not copy, reverse engineer, sell or misuse the app, and do not try to break its security.

5. PREMIUM
Premium unlocks extra tools, stickers and colours. In this version Premium can be switched on for testing. When paid subscriptions are added through Google Play, the price, renewal and refund rules shown by Google Play will apply, and you can cancel in Google Play at any time.

6. LIVE
The LIVE screen lets you try your camera, microphone, effects and chat. If live streaming to other people is added later, extra community rules will apply and we will show them to you first.

7. ENDING USE
You can stop using Twirlly at any time by deleting the app. We may remove features or end the service with reasonable notice where possible.

8. NO WARRANTY
Twirlly is provided "as is" and "as available". We do not promise that it will always work, be free of errors, or keep your data safe if your phone is lost or reset.

9. LIMIT OF LIABILITY
To the extent allowed by law, ${LEGAL_ENTITY} is not responsible for indirect or accidental losses, or for loss of content, arising from your use of the app.

10. CHANGES
We may change these Terms. If you keep using Twirlly after a change, you accept the new Terms.

11. GOVERNING LAW
These Terms are governed by ${GOVERNING_LAW}.

12. CONTACT
${LEGAL_ENTITY}
${LEGAL_ADDRESS}
Email: ${SUPPORT_EMAIL}"""

const val LICENSES = """Twirlly is built with these open-source libraries.

Kotlin and the Android Jetpack libraries (Compose, Activity, Lifecycle, Core, CameraX) - Apache License 2.0.
Media3 / ExoPlayer - Apache License 2.0.
Coil image loading - Apache License 2.0.

Each library is copyright of its authors. The full Apache License 2.0 text is available at https://www.apache.org/licenses/LICENSE-2.0."""

@Composable
fun LegalDialog(kind: String, onDismiss: () -> Unit) {
    val title = when (kind) {
        "privacy" -> "Privacy Policy"
        "terms" -> "Terms of Service"
        else -> "Open-source licenses"
    }
    val body = when (kind) {
        "privacy" -> PRIVACY_POLICY
        "terms" -> TERMS
        else -> LICENSES
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(8.dp)) {
                    Text(body, fontSize = 14.sp, lineHeight = 21.sp)
                }
            }
        }
    }
}
