package com.example.twirlly

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/** Everything the host can switch on during LIVE: poll, goal, countdown, slow mode, blocked words, frames, title, games. */
class LiveExtras {
    var pollOn by mutableStateOf(false)
    var pollQ by mutableStateOf("")
    val pollOpts = mutableStateListOf<String>()
    val pollVotes = mutableStateListOf<Int>()
    var goalTitle by mutableStateOf("")
    var goalTarget by mutableIntStateOf(0)
    var goalDone by mutableIntStateOf(0)
    var countdownEnd by mutableLongStateOf(0L)
    var slowSecs by mutableIntStateOf(0)
    val banned = mutableStateListOf<String>()
    var frame by mutableIntStateOf(0)
    var title by mutableStateOf("")
    var result by mutableStateOf<String?>(null)
    var blockedMessage = ""
    private var lastSent = 0L

    /** False when the message uses a blocked word or comes too fast for slow mode. */
    fun allow(text: String): Boolean {
        val low = text.lowercase()
        if (banned.any { it.isNotBlank() && low.contains(it.lowercase()) }) { blockedMessage = "That word is blocked in your LIVE."; return false }
        if (slowSecs > 0) {
            val now = System.currentTimeMillis()
            if (now - lastSent < slowSecs * 1000L) { blockedMessage = "Slow mode: wait a few seconds."; return false }
            lastSent = now
        }
        return true
    }
}

private val FRAMES = listOf("None", "Neon", "Gold", "Film", "Dots", "Rainbow")

/** Things drawn over the camera: frame, title, poll, goal, countdown and game results. */
@Composable
fun LiveExtrasOverlay(e: LiveExtras) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(e.countdownEnd) {
        while (e.countdownEnd > System.currentTimeMillis()) { now = System.currentTimeMillis(); delay(500) }
        now = System.currentTimeMillis()
    }
    LaunchedEffect(e.result) { if (e.result != null) { delay(4500); e.result = null } }
    Box(Modifier.fillMaxSize()) {
        if (e.frame != 0) Canvas(Modifier.fillMaxSize()) {
            when (e.frame) {
                1 -> drawRect(Brush.linearGradient(listOf(Color(0xFF00E5FF), Color(0xFFFF2DD1), Color(0xFF00E5FF))), style = Stroke(16f))
                2 -> drawRect(Brush.linearGradient(listOf(Color(0xFFFFF3B0), Color(0xFFFFC93C), Color(0xFFB8741A))), style = Stroke(22f))
                3 -> {
                    drawRect(Color.Black, Offset(0f, 0f), Size(38f, size.height))
                    drawRect(Color.Black, Offset(size.width - 38f, 0f), Size(38f, size.height))
                    var y = 20f
                    while (y < size.height) {
                        drawRect(Color.White.copy(alpha = 0.85f), Offset(10f, y), Size(18f, 26f))
                        drawRect(Color.White.copy(alpha = 0.85f), Offset(size.width - 28f, y), Size(18f, 26f))
                        y += 60f
                    }
                }
                4 -> {
                    var x = 0f
                    var k = 0
                    while (x < size.width) {
                        val c = if (k % 2 == 0) Color(0xFFFF2D87) else Color(0xFFFFD166)
                        drawCircle(c, 9f, Offset(x, 6f)); drawCircle(c, 9f, Offset(x, size.height - 6f))
                        x += 34f; k++
                    }
                    var y = 0f
                    while (y < size.height) {
                        val c = if (k % 2 == 0) Color(0xFF4CC9F0) else Color(0xFFFF2D87)
                        drawCircle(c, 9f, Offset(6f, y)); drawCircle(c, 9f, Offset(size.width - 6f, y))
                        y += 34f; k++
                    }
                }
                5 -> drawRect(
                    Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)),
                    style = Stroke(16f)
                )
            }
        }
        if (e.title.isNotBlank()) Text(
            e.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 120.dp).clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(Color(0xCCFF2D87), Color(0xCC7B2FF7)))).padding(horizontal = 16.dp, vertical = 6.dp)
        )
        val left = (e.countdownEnd - now) / 1000
        if (left > 0) Text(
            "⏱ %d:%02d".format(left / 60, left % 60), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 165.dp).clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 16.dp, vertical = 6.dp)
        )
        if (e.pollOn && e.pollOpts.isNotEmpty()) {
            val total = e.pollVotes.sum().coerceAtLeast(1)
            Column(
                Modifier.align(Alignment.TopStart).padding(top = 200.dp, start = 12.dp).width(230.dp)
                    .clip(RoundedCornerShape(16.dp)).background(Color(0x77000000)).padding(12.dp)
            ) {
                Text(e.pollQ.ifBlank { "Poll" }, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                e.pollOpts.forEachIndexed { i, o ->
                    val pct = e.pollVotes.getOrElse(i) { 0 } * 100 / total
                    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(30.dp).clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF)).clickable { e.pollVotes[i] = e.pollVotes[i] + 1 }) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(pct / 100f).background(Brush.horizontalGradient(listOf(Color(0xFFFF2D87), Color(0xFF7B2FF7)))))
                        Text(o + "  " + pct + "%", color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
                    }
                }
            }
        }
        if (e.goalTarget > 0) Column(Modifier.align(Alignment.TopEnd).padding(top = 200.dp, end = 62.dp).width(150.dp)) {
            Text("🎯 " + e.goalTitle.ifBlank { "Goal" } + "  " + e.goalDone + "/" + e.goalTarget, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(
                progress = { (e.goalDone.toFloat() / e.goalTarget).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)), color = Color(0xFFFFC93C), trackColor = Color(0x44FFFFFF)
            )
        }
        AnimatedVisibility(e.result != null, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
            Text(
                e.result ?: "", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 30.sp,
                modifier = Modifier.clip(RoundedCornerShape(28.dp)).background(Brush.horizontalGradient(listOf(Color(0xE6FF2D87), Color(0xE67B2FF7)))).padding(horizontal = 26.dp, vertical = 16.dp)
            )
        }
    }
}

/** More LIVE features in one sheet: interact, games and looks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveMore(e: LiveExtras, commenters: List<String>, onWelcome: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf(e.pollQ) }
    val opts = remember { mutableStateListOf("", "") }
    var goalTitle by remember { mutableStateOf(e.goalTitle) }
    var goalTarget by remember { mutableStateOf(if (e.goalTarget > 0) e.goalTarget.toString() else "") }
    var word by remember { mutableStateOf("") }
    var welcome by remember { mutableStateOf("") }
    var wheel by remember { mutableStateOf("Truth, Dare, Dance, Sing") }
    var title by remember { mutableStateOf(e.title) }
    val brand = Brush.horizontalGradient(listOf(Color(0xFFFF2D87), Color(0xFF7B2FF7)))

    fun slot(final: String, list: List<String>, prefix: String) {
        scope.launch {
            for (i in 0 until 12) { e.result = prefix + list.random(); delay(80L + i * 18L) }
            e.result = prefix + final
        }
    }
    @Composable fun Section(t: String) { Text(t, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)) }
    @Composable fun Btn(t: String, onClick: () -> Unit) {
        Box(Modifier.padding(end = 8.dp, top = 4.dp).clip(RoundedCornerShape(50)).background(brand).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(t, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }

    Column(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Interact", "Games", "Look").forEachIndexed { i, n ->
                FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(n) })
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 6.dp, bottom = 12.dp)) {
            when (tab) {
                0 -> {
                    Section("Poll")
                    OutlinedTextField(q, { q = it.take(60) }, label = { Text("Question") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    opts.forEachIndexed { i, o ->
                        OutlinedTextField(o, { opts[i] = it.take(30) }, label = { Text("Option " + (i + 1)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (opts.size < 4) TextButton(onClick = { opts.add("") }) { Text("+ Option") }
                        Btn("Start poll") {
                            val list = opts.filter { it.isNotBlank() }
                            if (list.size >= 2) { e.pollQ = q; e.pollOpts.clear(); e.pollOpts.addAll(list); e.pollVotes.clear(); list.forEach { _ -> e.pollVotes.add(0) }; e.pollOn = true }
                            else Toast.makeText(ctx, "Add at least 2 options", Toast.LENGTH_SHORT).show()
                        }
                        TextButton(onClick = { e.pollOn = false }) { Text("Stop") }
                    }
                    Text("Tap an option on the screen to count a vote. Votes from viewers come when LIVE is connected.", color = Color.Gray, fontSize = 11.sp)

                    Section("Gift goal")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(goalTitle, { goalTitle = it.take(20) }, label = { Text("Title") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(goalTarget, { goalTarget = it.filter { c -> c.isDigit() }.take(6) }, label = { Text("Coins") }, singleLine = true, modifier = Modifier.width(100.dp))
                    }
                    Row {
                        Btn("Start goal") { e.goalTitle = goalTitle; e.goalTarget = goalTarget.toIntOrNull() ?: 0; e.goalDone = 0 }
                        TextButton(onClick = { e.goalTarget = 0 }) { Text("Clear") }
                    }

                    Section("Countdown")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 3, 5, 10).forEach { m -> FilterChip(selected = false, onClick = { e.countdownEnd = System.currentTimeMillis() + m * 60_000L }, label = { Text("$m min") }) }
                        TextButton(onClick = { e.countdownEnd = 0L }) { Text("Stop") }
                    }

                    Section("Chat rules")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Slow off", 5 to "5 s", 10 to "10 s", 30 to "30 s").forEach { (s, n) -> FilterChip(selected = e.slowSecs == s, onClick = { e.slowSecs = s }, label = { Text(n) }) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(word, { word = it.take(20) }, label = { Text("Block a word") }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(onClick = { if (word.isNotBlank()) { e.banned.add(word.trim()); word = "" } }) { Text("Add") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        e.banned.toList().forEach { w -> AssistChip(onClick = { e.banned.remove(w) }, label = { Text(w + "  ✕") }) }
                    }

                    Section("Welcome message")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(welcome, { welcome = it.take(100) }, label = { Text("Pinned welcome") }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(onClick = { if (welcome.isNotBlank()) { onWelcome(welcome.trim()); welcome = "" } }) { Text("Pin") }
                    }

                    Section("Giveaway")
                    Btn("Pick a winner from the chat") {
                        e.result = if (commenters.isEmpty()) "Nobody has commented yet" else "🎉 @" + commenters.random()
                    }
                }
                1 -> {
                    Section("Games")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Btn("🎲 Roll dice") { val n = (1..6).random(); slot(n.toString(), (1..6).map { it.toString() }, "🎲 ") }
                        Btn("🪙 Flip coin") { slot(listOf("Heads", "Tails").random(), listOf("Heads", "Tails"), "🪙 ") }
                        Btn("🎡 Spin wheel") {
                            val list = wheel.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            if (list.size >= 2) slot(list.random(), list, "🎡 ") else Toast.makeText(ctx, "Add at least 2 choices", Toast.LENGTH_SHORT).show()
                        }
                    }
                    OutlinedTextField(wheel, { wheel = it.take(120) }, label = { Text("Wheel choices (comma between)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Section("Soundboard")
                    Text("Plays out loud, so your viewers hear it through the microphone.", color = Color.Gray, fontSize = 11.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("👏 Applause", "🥁 Drum roll", "📢 Air horn", "🔔 Ding", "💨 Whoosh", "🦘 Boing").forEachIndexed { i, n -> Btn(n) { Sounds.play(i) } }
                    }
                }
                else -> {
                    Section("Frame")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FRAMES.forEachIndexed { i, n -> FilterChip(selected = e.frame == i, onClick = { e.frame = i }, label = { Text(n) }) }
                    }
                    Section("Title on screen")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(title, { title = it.take(40) }, label = { Text("Text") }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(onClick = { e.title = title.trim() }) { Text("Show") }
                        TextButton(onClick = { e.title = ""; title = "" }) { Text("Hide") }
                    }
                }
            }
        }
    }
}

/** Short sounds made by the app itself, no files needed. */
object Sounds {
    private const val SR = 22050

    private fun gen(kind: Int): ShortArray {
        val r = java.util.Random(kind.toLong())
        val secs = when (kind) { 0 -> 1.8; 1 -> 1.6; 2 -> 1.0; 3 -> 0.9; 4 -> 0.7; else -> 0.6 }
        val d = ShortArray((secs * SR).toInt())
        var phase = 0.0
        for (i in d.indices) {
            val t = i / SR.toDouble()
            val v: Double = when (kind) {
                0 -> { val burst = 0.5 + 0.5 * sin(t * 38.0); (r.nextDouble() * 2 - 1) * burst * 0.55 * (if (t > 1.3) (1.8 - t) / 0.5 else 1.0) }
                1 -> {
                    val rate = 14.0 + t * 12.0
                    val hit = (t * rate) % 1.0
                    val thump = (r.nextDouble() * 2 - 1) * Math.exp(-hit * 9.0) * 0.6 * (0.4 + t / secs)
                    if (t > 1.35) thump + sin(2 * PI * 90.0 * t) * Math.exp(-(t - 1.35) * 6.0) * 0.7 + (r.nextDouble() * 2 - 1) * Math.exp(-(t - 1.35) * 5.0) * 0.5 else thump
                }
                2 -> { phase += 2 * PI * (430.0 + 12.0 * sin(t * 40.0)) / SR; (sin(phase) + 0.5 * sin(phase * 2) + 0.3 * sin(phase * 3)) * 0.3 * (if (t > 0.8) (1.0 - t) / 0.2 else 1.0) }
                3 -> sin(2 * PI * 1318.5 * t) * Math.exp(-t * 4.5) * 0.6 + sin(2 * PI * 1975.5 * t) * Math.exp(-t * 6.0) * 0.25
                4 -> (r.nextDouble() * 2 - 1) * sin(PI * t / secs) * 0.5 * (0.3 + 0.7 * t / secs)
                else -> { phase += 2 * PI * (180.0 + 520.0 * Math.exp(-t * 7.0)) / SR; sin(phase) * Math.exp(-t * 3.5) * 0.7 }
            }
            d[i] = (v.coerceIn(-1.0, 1.0) * 32000).toInt().toShort()
        }
        return d
    }

    fun play(kind: Int) {
        Thread {
            try {
                val data = gen(kind)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(data.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
                track.write(data, 0, data.size)
                track.play()
                Thread.sleep(data.size * 1000L / SR + 150)
                track.release()
            } catch (_: Exception) {}
        }.start()
    }
}
