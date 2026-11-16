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
    val link = MaterialTheme.colorScheme.primary
    val loader = remember {
        ImageLoader.Builder(ctx).components { if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory()) }.build()
    }
    LaunchedEffect(gifOpen, gifQuery) { if (gifOpen) gifs = searchGifs(gifQuery) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        if (u != null) scope.launch {
            sending = true
            val url = CloudShare.uploadCommentImage(ctx, u)
            sending = false
            if (url != null) { vm.addComment(v.id, text, url, false); text = "" }
            else Toast.makeText(ctx, "Could not upload the photo", Toast.LENGTH_SHORT).show()
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
            OutlinedTextField(
                gifQuery, { gifQuery = it.take(40) }, singleLine = true, placeholder = { Text("Search GIFs") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { IconButton(onClick = { gifOpen = false }) { Icon(Icons.Default.Close, "Close") } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(50)
            )
            if (GIPHY_KEY.isBlank()) Text("GIFs are off. Add your free GIPHY key at the top of Comments.kt (GIPHY_KEY).", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
            LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                gridItems(gifs) { g ->
                    AsyncImage(
                        model = g, imageLoader = loader, contentDescription = "GIF", contentScale = ContentScale.Crop,
                        modifier = Modifier.height(110.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x22888888))
                            .clickable { vm.addComment(v.id, text, g, true); text = ""; gifOpen = false }
                    )
                }
            }
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
                                AsyncImage(
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
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
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
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).border(1.5.dp, Color.White, RoundedCornerShape(8.dp)).clickable { gifOpen = !gifOpen }.padding(horizontal = 6.dp, vertical = 2.dp)
            ) { Text("GIF", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            IconButton(onClick = { if (text.isNotBlank()) { vm.addComment(v.id, text.trim()); text = "" } }, enabled = text.isNotBlank() && !sending) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (text.isNotBlank()) MaterialTheme.colorScheme.primary else Color.Gray)
            }
        }
    }
}
