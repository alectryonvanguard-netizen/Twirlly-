package com.example.twirlly

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

private fun durText(secs: Long): String {
    val h = secs / 3600
    val m = (secs % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/** Watch time for the last 7 days. */
@Composable
fun ScreenTimeDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val log = remember { vm.watchLogMap() }
    val f = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val days = remember {
        (6 downTo 0).map { off ->
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, -off)
            f.format(c.time) to "SMTWTFS"[c.get(Calendar.DAY_OF_WEEK) - 1].toString()
        }
    }
    val secs = days.map { log[it.first] ?: 0L }
    val maxSecs = maxOf(secs.maxOrNull() ?: 0L, 60L)
    val brand = Brush.verticalGradient(listOf(Color(0xFFFF2D87), Color(0xFF7B2FF7)))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Screen time") },
        text = {
            Column {
                Text("Today: " + durText(secs.last()), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("Daily average: " + durText(secs.sum() / 7), color = Color.Gray, fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth().height(150.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom
                ) {
                    days.forEachIndexed { i, d ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (secs[i] >= 60) durText(secs[i]) else "", fontSize = 9.sp, color = Color.Gray)
                            Box(
                                Modifier.width(28.dp).height((100f * secs[i] / maxSecs).coerceAtLeast(4f).dp)
                                    .clip(RoundedCornerShape(8.dp)).background(brand)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(d.second, fontSize = 12.sp, fontWeight = if (i == 6) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Asks the account owner to prove it is them (password, or Google). Used to reset the PIN and to delete the account. */
@Composable
fun ConfirmIdentityDialog(title: String, actionText: String, onBack: () -> Unit, onVerified: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val provider = Auth.provider
    val signedIn = provider.isCloud && provider.currentEmail() != null
    val google = provider.usesGoogle()
    var pw by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onBack() },
        title = { Text(title) },
        text = {
            Column {
                when {
                    !signedIn -> Text("This phone is not signed in to an account, so this cannot be confirmed here. Sign in with your account first.")
                    google -> Text("Confirm that it is you by choosing your Google account again.")
                    else -> {
                        Text("Enter your account password to confirm it is you.")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            pw, { pw = it; err = null }, singleLine = true, label = { Text("Password") },
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }
                }
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = {
            if (signedIn) {
                TextButton(
                    enabled = !busy && (google || pw.isNotEmpty()),
                    onClick = {
                        scope.launch {
                            busy = true
                            err = provider.reauthenticate(ctx, if (google) null else pw)
                            busy = false
                            if (err == null) onVerified()
                        }
                    }
                ) { Text(if (busy) "Please wait..." else actionText) }
            }
        },
        dismissButton = { TextButton(onClick = onBack, enabled = !busy) { Text("Cancel") } }
    )
}

/**
 * Asks for the 4-digit PIN. Three wrong tries lock it for a while (longer each time).
 * "Forgot PIN?" lets the account owner set a new one after confirming it is them.
 */
@Composable
fun PinDialog(title: String, vm: AppViewModel, onDismiss: () -> Unit, onUnlocked: () -> Unit) {
    val settings by vm.settings.collectAsState()
    var pin by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var recover by remember { mutableStateOf(false) }
    var setNew by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val locked = remember(tick, settings.pinLockUntil) { vm.pinLockedSeconds() }

    if (setNew) {
        SetPinDialog(onDismiss = { setNew = false }) { p -> vm.setKids(p); setNew = false; onUnlocked() }
        return
    }
    if (recover) {
        ConfirmIdentityDialog("Reset the PIN", "Confirm", onBack = { recover = false }, onVerified = { recover = false; setNew = true })
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("Type the 4-digit PIN to continue.")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    pin, { pin = it.filter { c -> c.isDigit() }.take(4); msg = null }, singleLine = true, enabled = locked == 0L,
                    label = { Text("PIN") }, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                if (locked > 0L) Text("Too many wrong tries. Try again in $locked seconds.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                else msg?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                TextButton(onClick = { recover = true }) { Text("Forgot PIN?") }
            }
        },
        confirmButton = {
            TextButton(
                enabled = locked == 0L && pin.length == 4,
                onClick = {
                    val r = vm.checkPin(pin)
                    if (r.ok) onUnlocked()
                    else {
                        pin = ""
                        msg = if (r.lockedSeconds > 0) "Wrong PIN. Locked for ${r.lockedSeconds} seconds." else "Wrong PIN. ${r.attemptsLeft} tries left before it locks."
                    }
                }
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun SetPinDialog(onDismiss: () -> Unit, warning: String? = null, onSet: (String) -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a PIN for kids mode") },
        text = {
            Column {
                Text("Create, LIVE, settings and profile editing will need this PIN.", fontSize = 13.sp, color = Color.Gray)
                warning?.let { Text(it, fontSize = 12.sp, color = Color(0xFFFFB300), modifier = Modifier.padding(top = 6.dp)) }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    a, { a = it.filter { c -> c.isDigit() }.take(4); err = null }, singleLine = true, label = { Text("New 4-digit PIN") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    b, { b = it.filter { c -> c.isDigit() }.take(4); err = null }, singleLine = true, label = { Text("Repeat PIN") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (a.length != 4) err = "The PIN needs 4 digits."
                else if (a != b) err = "The two PINs are different."
                else onSet(a)
            }) { Text("Save PIN") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** A row with - and + buttons for choosing an hour. */
@Composable
fun HourRow(label: String, hour: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 15.sp)
        IconButton(onClick = { onChange((hour + 23) % 24) }) { Icon(Icons.Default.Remove, "Earlier") }
        Text("%02d:00".format(hour), fontWeight = FontWeight.Bold)
        IconButton(onClick = { onChange((hour + 1) % 24) }) { Icon(Icons.Default.Add, "Later") }
    }
}
