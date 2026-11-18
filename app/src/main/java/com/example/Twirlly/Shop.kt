package com.example.twirlly

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Coin shop for the whole app (Settings, LIVE, gifts). Payment is handled by Google Play and checked by the server. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoinShopSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF14141C)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MonetizationOn, null, tint = Color(0xFFFFC107), modifier = Modifier.size(30.dp))
                Text("  " + s.coins + " coins", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            }
            Text("Coins are used to send gifts. Your balance is kept in your account.", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
            Pay.COIN_IDS.forEach { (coins, id) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.MonetizationOn, null, tint = Color(0xFFFFC107))
                    Text("  $coins coins", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Button(onClick = {
                        val act = ctx as? Activity
                        if (act != null) { val err = Pay.buy(act, id); if (err != null) Toast.makeText(ctx, err, Toast.LENGTH_LONG).show() }
                    }) { Text(Pay.prices[id] ?: "Buy") }
                }
            }
            TextButton(onClick = { Pay.restore() }) { Text("Restore purchases") }
            Text("Billed by Google Play. Purchases are checked by the Twirlly server before coins are added.", color = Color.Gray, fontSize = 11.sp)
        }
    }
}
