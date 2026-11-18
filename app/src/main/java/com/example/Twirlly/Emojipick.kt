package com.example.twirlly

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView

/** The full, ready-made Android emoji keyboard (all emojis, skin tones, recent ones). Nothing is written by hand. */
@Composable
fun EmojiPicker(modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    AndroidView(
        factory = { c -> EmojiPickerView(c).apply { setOnEmojiPickedListener { item -> onPick(item.emoji) } } },
        modifier = modifier
    )
}
