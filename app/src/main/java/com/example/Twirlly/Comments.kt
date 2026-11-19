package com.example.twirlly

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Put your free GIPHY API key here (developers.giphy.com) to turn on the GIF search. */
private const val GIPHY_KEY = ""

private val QUICK = listOf("❤️", "🔥", "😂", "😮", "😢", "👏")
private val URL_REGEX = Regex("https?://\\S+")

/** Turns web addresses in a comment into links that open in the app (twirlly links) or the browser. */
private fun linkify(text: String, color: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in URL_REGEX.findAll(text)) {
        append(text.substring(last, m.range.first))
        val url = m.value.trimEnd('.', ',', ')', '!', '?')
        withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline)))) { append(url) }
        append(m.value.substring(url.length))
        last = m.range.last + 1
    }
    append(text.substring(last))
}

private fun timeAgo(ms: Long): String {
    if (ms <= 0) return "now"
    val s = (System.currentTimeMillis() - ms) / 1000
    return when {
        s < 60 -> "now"
        s < 3600 -> (s / 60).toString() + "m"
        s < 86400 -> (s / 3600).toString() + "h"
        else -> (s / 86400).toString() + "d"
    }
}

private suspend fun searchGifs(q: String): List<String> = withContext(Dispatchers.IO) {
    if (GIPHY_KEY.isBlank()) return@withContext emptyList<String>()
    try {
        val base = if (q.isBlank()) "https://api.giphy.com/v1/gifs/trending?" else "https://api.giphy.com/v1/gifs/search?q=" + URLEncoder.encode(q, "UTF-8") + "&"
        val conn = URL(base + "api_key=" + GIPHY_KEY + "&limit=30&rating=pg-13").openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        val arr = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONArray("data")
        (0 until arr.length()).mapNotNull { i ->
            arr.getJSONObject(i).optJSONObject("images")?.optJSONObject("fixed_height")?.optString("url")?.takeIf { it.startsWith("https://") }
        }
    } catch (e: Exception) { emptyList() }
}

/** Comments: photos, GIFs, links, replies, likes, sorting. Shared comments are saved in Firebase. */
@Composable
fun CommentsSheet(v: Video, vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile by vm.profile.collectAsState()
    val people by vm.people.collectAsState()
    val videos by vm.videos.collectAsState()
    val remote by vm.remote.collectAsState()
    val cur = (videos + remote).firstOrNull { it.id == v.id } ?: v
    LaunchedEffect(v.id) { vm.loadComments(v.id) }
    var text by remember { mutableStateOf("") }
    var newest by remember { mutableStateOf(false) }
    var gifOpen by remember { mutableStateOf(false) }
    var gifQuery by remember { mutableStateOf("") }
    var gifs by remember { mutableStateOf<List<String>>(emptyList()) }
    var sending by remember { mutableStateOf(false) }
    var emojiOpen by remember { mutableStateOf(false) }
    val link = MaterialTheme.colorScheme.primary
    val loader = remember {
        ImageLoader.Builder(ctx).components { if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory()) }.build()
    }
    LaunchedEffect(gifOpen, gifQuery) { if (gifOpen) gifs = searchGifs(gifQuery) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        if (u != null) scope.launch {
            sending = true
            val isGif = ctx.contentResolver.getType(u)?.contains("gif") == true
            val url = if (isGif) CloudShare.uploadCommentGif(ctx, u) else CloudShare.uploadCommentImage(ctx, u)
            sending = false
            if (url != null) { vm.addComment(v.id, text, url, isGif); text = "" }
            else Toast.makeText(ctx, "Could not upload the photo", Toast.LENGTH_SHORT).show()
        }
    }
    val videoPick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        if (u != null) scope.launch {
            sending = true
            val ms = withContext(Dispatchers.IO) { videoDuration(ctx, u.toString()) }
            if (ms > 5500L) {
                sending = false
                Toast.makeText(ctx, "Short videos can be up to 5 seconds", Toast.LENGTH_LONG).show()
            } else {
                val url = CloudShare.uploadCommentVideo(ctx, u)
                sending = false
                if (url != null) { vm.addComment(v.id, text, url, false, true); text = "" }
                else Toast.makeText(ctx, "Could not upload it. Keep it under 15 MB.", Toast.LENGTH_LONG).show()
            }
        }
    }
    val items = cur.comments.withIndex().toList().let { l -> if (newest) l.sortedByDescending { it.value.time } else l.sortedByDescending { it.value.likes } }

    Column(Modifier.fillMaxWidth().heightIn(min = 420.dp, max = 640.dp).navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Comments (" + cur.comments.size + ")", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color.White, modifier = Modifier.weight(1f))
            FilterChip(selected = !newest, onClick = { newest = false }, label = { Text("Top") })
            Spacer(Modifier.width(6.dp))
            FilterChip(selected = newest, onClick = { newest = true }, label = { Text("Newest") })
        }
        if (gifOpen) {
            GifPanel(vm, loader, Modifier.weight(1f), onPick = { m -> vm.addComment(v.id, text, m, true); text = ""; gifOpen = false }, onClose = { gifOpen = false })
        } else {
            if (items.isEmpty()) Text("No comments yet. Be the first!", color = Color.Gray, modifier = Modifier.padding(24.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(items) { (idx, c) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Avatar(if (c.user == profile.name) profile.avatar else people[c.user]?.avatarUrl, c.user, 36.dp)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("@" + c.user, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                                Text("  " + timeAgo(c.time), color = Color.Gray, fontSize = 11.sp)
                            }
                            if (c.text.isNotBlank()) Text(linkify(c.text, link), fontSize = 14.sp, color = Color.White)
                            c.media?.let { m ->
                                if (c.video) CommentVideo(m)
                                else if (m.startsWith("anim:")) {
                                    val p = m.split(":")
                                    AnimatedEmoji(p.getOrElse(1) { "✨" }, p.getOrNull(2)?.toIntOrNull() ?: 0, 72.dp)
                                } else AsyncImage(
                                    model = m, imageLoader = loader, contentDescription = if (c.gif) "GIF" else "Photo", contentScale = ContentScale.Fit,
                                    modifier = Modifier.padding(top = 6.dp).heightIn(max = 220.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x22888888))
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { text = "@" + c.user + " " }, contentPadding = PaddingValues(0.dp)) { Text("Reply", fontSize = 12.sp, color = Color.Gray) }
                                if (c.user == profile.name || cur.local) {
                                    Spacer(Modifier.width(14.dp))
                                    TextButton(onClick = { vm.deleteComment(v.id, idx) }, contentPadding = PaddingValues(0.dp)) { Text("Delete", fontSize = 12.sp, color = Color(0xFFFF6B6B)) }
                                }
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { vm.toggleCommentLike(v.id, idx) }) {
                            Icon(Icons.Default.Favorite, "Like", tint = if (c.liked) Color(0xFFFF2D55) else Color.Gray, modifier = Modifier.size(20.dp))
                            if (c.likes > 0) Text(c.likes.toString(), fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                }
            }
            if (emojiOpen) EmojiPicker(Modifier.fillMaxWidth().height(260.dp)) { text += it }
            else Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                QUICK.forEach { e -> Text(e, fontSize = 22.sp, modifier = Modifier.clickable { text += e }) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(profile.avatar, profile.name, 32.dp)
            Spacer(Modifier.width(8.dp))
            TextField(
                text, { text = it.take(2000) }, Modifier.weight(1f), singleLine = false, maxLines = 3,
                placeholder = { Text("Add a comment...") }, shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = Color(0x22FFFFFF), unfocusedContainerColor = Color(0x22FFFFFF)
                )
            )
            IconButton(onClick = {
                if (!Cloud.online()) Toast.makeText(ctx, "Sign in to send photos", Toast.LENGTH_SHORT).show()
                else pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, enabled = !sending) { Icon(Icons.Default.Image, "Photo", tint = Color.White) }
            IconButton(onClick = { emojiOpen = !emojiOpen }) { Icon(Icons.Default.EmojiEmotions, "Emoji", tint = Color.White) }
            Text("GIF", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { gifOpen = !gifOpen }.padding(horizontal = 6.dp, vertical = 8.dp))
            IconButton(onClick = {
                if (!Cloud.online()) Toast.makeText(ctx, "Sign in to send a short video", Toast.LENGTH_SHORT).show()
                else videoPick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            }, enabled = !sending) { Icon(Icons.Default.PlayArrow, "Short video", tint = Color.White, modifier = Modifier.size(22.dp)) }
            IconButton(onClick = { if (text.isNotBlank()) { vm.addComment(v.id, text.trim()); text = "" } }, enabled = text.isNotBlank() && !sending) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (text.isNotBlank()) MaterialTheme.colorScheme.primary else Color.Gray)
            }
        }
    }
}


private val ANIMATED = listOf(
    "🔥", "❤️", "😂", "😍", "🤩", "😭", "😱", "🥳", "👏", "🙌", "💯", "✨",
    "🎉", "💀", "🤯", "😎", "🥺", "😘", "👀", "🚀", "💪", "🍿", "🌈", "⚡"
)

/** Animated reactions made by the app itself. They move on their own, no internet needed. */
@Composable
fun AnimatedEmoji(emoji: String, style: Int, dp: Dp) {
    val inf = rememberInfiniteTransition(label = "ae")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val s = sin(t * 2f * PI.toFloat())
    val move = when (style % 6) {
        0 -> Modifier.graphicsLayer { scaleX = 1f + 0.2f * s; scaleY = 1f + 0.2f * s }
        1 -> Modifier.graphicsLayer { rotationZ = 14f * s }
        2 -> Modifier.graphicsLayer { translationY = -dp.toPx() * 0.18f * abs(s) }
        3 -> Modifier.graphicsLayer { rotationY = t * 360f }
        4 -> Modifier.graphicsLayer { translationX = 8f * s; scaleX = 1f + 0.1f * abs(s) }
        else -> Modifier.graphicsLayer { val k = 1f + 0.25f * abs(s); scaleX = k; scaleY = k; rotationZ = 8f * s }
    }
    Box(Modifier.size(dp).then(move), contentAlignment = Alignment.Center) { Text(emoji, fontSize = (dp.value * 0.7f).sp) }
}

/** GIFs and animated reactions: built-in ones, your own, and (with a key) a web search. */
@Composable
private fun GifPanel(vm: AppViewModel, loader: ImageLoader, modifier: Modifier, onPick: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mine by vm.myGifs.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(tab, query) { if (tab == 2) found = searchGifs(query) }
    val add = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        if (u != null) scope.launch {
            busy = true
            val url = CloudShare.uploadCommentGif(ctx, u)
            busy = false
            if (url != null) vm.addMyGif(url) else Toast.makeText(ctx, "Could not add it. Use a GIF under 6 MB and sign in.", Toast.LENGTH_LONG).show()
        }
    }
    Column(modifier) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Animated", "My GIFs", "Search").forEachIndexed { i, n -> FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(n) }) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
        }
        when (tab) {
            0 -> LazyVerticalGrid(GridCells.Fixed(4), Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                gridItems(ANIMATED.withIndex().toList()) { (i, e) ->
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x22888888)).clickable { onPick("anim:" + e + ":" + i) }.padding(8.dp), contentAlignment = Alignment.Center) {
                        AnimatedEmoji(e, i, 56.dp)
                    }
                }
            }
            1 -> Column(Modifier.weight(1f)) {
                Text("Add your own GIFs. They are saved in your account and you can send them any time. Press and hold one to remove it.", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        Box(
                            Modifier.height(100.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x33FF2D87))
                                .clickable(enabled = !busy) {
                                    if (!Cloud.online()) Toast.makeText(ctx, "Sign in to add your own GIFs", Toast.LENGTH_SHORT).show()
                                    else add.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                            contentAlignment = Alignment.Center
                        ) { Text(if (busy) "..." else "+ Add GIF", color = Color.White, fontWeight = FontWeight.Bold) }
                    }
                    gridItems(mine) { g ->
                        AsyncImage(
                            model = g, imageLoader = loader, contentDescription = "GIF", contentScale = ContentScale.Crop,
                            modifier = Modifier.height(100.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x22888888))
                                .pointerInput(g) { detectTapGestures(onTap = { onPick(g) }, onLongPress = { vm.removeMyGif(g) }) }
                        )
                    }
                }
            }
            else -> Column(Modifier.weight(1f)) {
                OutlinedTextField(
                    query, { query = it.take(40) }, singleLine = true, placeholder = { Text("Search GIFs on the web") },
                    leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(50)
                )
                if (GIPHY_KEY.isBlank()) Text("Optional: add a free GIPHY key at the top of Comments.kt (GIPHY_KEY) to search the web. The built-in and your own GIFs work without it.", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
                LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    gridItems(found) { g ->
                        AsyncImage(
                            model = g, imageLoader = loader, contentDescription = "GIF", contentScale = ContentScale.Crop,
                            modifier = Modifier.height(110.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x22888888)).clickable { onPick(g) }
                        )
                    }
                }
            }
        }
    }
}


/** A short video in a comment: it loops by itself without sound, like a GIF. Tap it to hear it. Only a small play triangle shows. */
@Composable
private fun CommentVideo(url: String) {
    val ctx = LocalContext.current
    var sound by remember { mutableStateOf(false) }
    val player = remember(url) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(sound) { player.volume = if (sound) 1f else 0f }
    Box(
        Modifier.padding(top = 6.dp).size(150.dp, 190.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x22888888)).clickable { sound = !sound }
    ) {
        AndroidView(
            factory = { c -> android.view.LayoutInflater.from(c).inflate(R.layout.banner_player, null, false) as PlayerView },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize()
        )
        if (!sound) Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).size(18.dp))
    }
}
