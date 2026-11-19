package com.example.twirlly

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.webkit.MimeTypeMap
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream

/** Sharing with other people: posts, pictures, profiles, likes, comments, follows. Needs a signed-in, confirmed account. */
object CloudShare {
    private fun db() = FirebaseFirestore.getInstance()

    private fun postId(docId: String): Long = 2_000_000_000_000L + (docId.hashCode().toLong() and 0x7fffffffL)

    suspend fun uidOf(username: String): String? = try {
        db().collection("usernames").document(username).get().await().getString("uid")
    } catch (e: Exception) { null }

    /** Someone's public profile: name, bio, picture, banner, badge and follower counts. */
    suspend fun person(username: String): PersonInfo? {
        return try {
            val uid = uidOf(username) ?: return null
            val d = db().collection("users").document(uid).get().await()
            if (!d.exists()) return null
            val followers = db().collection("follows").whereEqualTo("following", uid).count().get(AggregateSource.SERVER).await().count.toInt()
            val following = db().collection("follows").whereEqualTo("follower", uid).count().get(AggregateSource.SERVER).await().count.toInt()
            PersonInfo(
                uid, username, d.getString("displayName") ?: "", d.getString("bio") ?: "",
                d.getString("avatarUrl"), d.getString("bannerUrl"), d.getBoolean("verified") == true, followers, following
            )
        } catch (e: Exception) { null }
    }

    suspend fun search(prefix: String): List<PersonInfo> {
        return try {
            val snap = db().collection("usernames").orderBy(FieldPath.documentId())
                .startAt(prefix).endAt(prefix + "\uf8ff").limit(8).get().await()
            snap.documents.mapNotNull { person(it.id) }
        } catch (e: Exception) { emptyList() }
    }

    private fun toVideo(d: DocumentSnapshot): Video? {
        val uid = d.getString("uid") ?: return null
        val user = d.getString("username") ?: return null
        val key = d.getString("seriesKey")
        return Video(
            id = postId(d.id), url = d.getString("cover") ?: "", isImage = false, local = false, user = user,
            caption = d.getString("caption") ?: "", music = d.getString("music") ?: "Original sound - $user",
            likes = (d.getLong("likesCount") ?: 0L).toInt(),
            comments = emptyList(), createdAt = d.getTimestamp("createdAt")?.toDate()?.time ?: 0L,
            project = d.getString("project"),
            seriesTitle = d.getString("seriesTitle"),
            seriesId = key?.let { 2_000_000_000_000L + (it.hashCode().toLong() and 0x7fffffffL) },
            episodeNo = (d.getLong("episodeNo") ?: 0L).toInt(),
            cloudId = d.id, uid = uid
        )
    }

    suspend fun loadFeed(limit: Long): List<Video> = try {
        db().collection("posts").orderBy("createdAt", Query.Direction.DESCENDING).limit(limit)
            .get().await().documents.mapNotNull { toVideo(it) }
    } catch (e: Exception) { emptyList() }

    // ---------- uploads ----------

    private suspend fun uploadSource(ctx: Context, path: String, dest: String): String {
        val uri = mediaUri(path)
        var mime: String? = if (path.startsWith("content:")) ctx.contentResolver.getType(uri) else null
        if (mime == null) {
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(path).lowercase())
        }
        val m = mime ?: "application/octet-stream"
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(m) ?: "bin"
        val ref = FirebaseStorage.getInstance().reference.child("$dest.$ext")
        ref.putFile(uri, StorageMetadata.Builder().setContentType(m).build()).await()
        return ref.downloadUrl.await().toString()
    }

    private suspend fun uploadBytes(bytes: ByteArray, dest: String, mime: String): String {
        val ref = FirebaseStorage.getInstance().reference.child(dest)
        ref.putBytes(bytes, StorageMetadata.Builder().setContentType(mime).build()).await()
        return ref.downloadUrl.await().toString()
    }

    private fun makeCover(ctx: Context, c: Clip): ByteArray? = try {
        val uri = mediaUri(c.path)
        var bmp: Bitmap? = if (c.isVideo) {
            val r = MediaMetadataRetriever()
            r.setDataSource(ctx, uri)
            val f = r.getFrameAtTime(maxOf(c.inMs, 0L) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            r.release()
            f
        } else {
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 }) }
        }
        if (bmp == null) null else {
            val scale = 720f / maxOf(bmp.width, bmp.height)
            if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        }
    } catch (e: Exception) { null }

    /** Profile picture or banner, small picture only. Returns its web address. */
    suspend fun uploadProfileImage(bytes: ByteArray, banner: Boolean): String? {
        val uid = Cloud.uid() ?: return null
        return try {
            val name = if (banner) "banner" else "avatar"
            val url = uploadBytes(bytes, "users/$uid/profile/$name.jpg", "image/jpeg") + "&v=" + System.currentTimeMillis()
            db().collection("users").document(uid).set(mapOf((if (banner) "bannerUrl" else "avatarUrl") to url), SetOptions.merge()).await()
            url
        } catch (e: Exception) { null }
    }

    /** Uploads the pictures, videos and sounds of a post and publishes it. Returns the online id, or null if it failed. */
    suspend fun publishPost(ctx: Context, v: Video, profile: Profile): String? {
        val uid = Cloud.uid() ?: return null
        return try {
            val ref = db().collection("posts").document()
            val pid = ref.id
            val proj = projectFromJson(v.project ?: return null)
            var n = 0
            suspend fun up(path: String): String {
                if (path.startsWith("http")) return path
                n++
                return uploadSource(ctx, path, "users/$uid/media/${pid}_$n")
            }
            val clips = proj.clips.map { c -> c.copy(path = up(c.path)) }
            val layers = proj.layers.map { l -> val im = l.image; if (im != null) l.copy(image = up(im)) else l }
            val audios = proj.audios.map { a -> a.copy(path = up(a.path)) }
            val cover = makeCover(ctx, proj.clips[0])?.let { uploadBytes(it, "users/$uid/media/${pid}_cover.jpg", "image/jpeg") }
            val data = hashMapOf<String, Any>(
                "uid" to uid, "username" to profile.name, "caption" to v.caption, "music" to v.music,
                "project" to proj.copy(clips = clips, layers = layers, audios = audios).toJson(),
                "cover" to (cover ?: ""), "likesCount" to 0, "commentsCount" to 0,
                "createdAt" to FieldValue.serverTimestamp(), "episodeNo" to v.episodeNo
            )
            if (v.seriesTitle != null) {
                data["seriesTitle"] = v.seriesTitle
                data["seriesKey"] = uid + "_" + (v.seriesId ?: v.id)
            }
            ref.set(data).await()
            pid
        } catch (e: Exception) { null }
    }

    suspend fun deletePost(cloudId: String) {
        val uid = Cloud.uid() ?: return
        try {
            db().collection("posts").document(cloudId).delete().await()
            val files = FirebaseStorage.getInstance().reference.child("users/$uid/media").listAll().await()
            files.items.filter { it.name.startsWith(cloudId + "_") }.forEach { try { it.delete().await() } catch (_: Exception) {} }
        } catch (_: Exception) {}
    }

    suspend fun deleteMyPosts() {
        val uid = Cloud.uid() ?: return
        try {
            db().collection("posts").whereEqualTo("uid", uid).get().await().documents.forEach { deletePost(it.id) }
        } catch (_: Exception) {}
    }

    // ---------- likes, comments, follows, reports ----------

    suspend fun like(cloudId: String, on: Boolean) {
        val uid = Cloud.uid() ?: return
        try {
            val post = db().collection("posts").document(cloudId)
            val like = post.collection("likes").document(uid)
            val b = db().batch()
            if (on) b.set(like, mapOf("at" to FieldValue.serverTimestamp())) else b.delete(like)
            b.update(post, "likesCount", FieldValue.increment(if (on) 1L else -1L))
            b.commit().await()
        } catch (_: Exception) {}
    }

    suspend fun addComment(cloudId: String, text: String, username: String, media: String? = null, gif: Boolean = false, video: Boolean = false) {
        val uid = Cloud.uid() ?: return
        try {
            val post = db().collection("posts").document(cloudId)
            val b = db().batch()
            val cm = hashMapOf<String, Any>("uid" to uid, "username" to username, "text" to text.take(3000), "createdAt" to FieldValue.serverTimestamp())
            if (media != null && (media.startsWith("https://") || media.startsWith("anim:"))) { cm["media"] = media; cm["mediaKind"] = if (video) "video" else if (gif) "gif" else "photo" }
            b.set(post.collection("comments").document(), cm)
            b.update(post, "commentsCount", FieldValue.increment(1L))
            b.commit().await()
        } catch (_: Exception) {}
    }

    suspend fun loadComments(cloudId: String): List<Pair<String, Comment>> = try {
        db().collection("posts").document(cloudId).collection("comments").orderBy("createdAt").limit(200).get().await().documents.map { d ->
            Pair(d.id, Comment(d.getString("username") ?: "user", d.getString("text") ?: "", d.id.hashCode().toLong(), d.getTimestamp("createdAt")?.toDate()?.time ?: 0L, media = d.getString("media"), gif = d.getString("mediaKind") == "gif", video = d.getString("mediaKind") == "video"))
        }
    } catch (e: Exception) { emptyList() }

    suspend fun loadPost(cloudId: String): Video? = try {
        val d = db().collection("posts").document(cloudId).get().await()
        if (d.exists()) toVideo(d) else null
    } catch (e: Exception) { null }

    /** A photo for a comment: shrunk to 1080 pixels and uploaded. Returns its web address. */
    suspend fun uploadCommentImage(ctx: Context, uri: android.net.Uri): String? {
        val uid = Cloud.uid() ?: return null
        return try {
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 }) } ?: return null
            val scale = 1080f / maxOf(bmp.width, bmp.height)
            val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            val bytes = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, 82, bytes)
            uploadBytes(bytes.toByteArray(), "users/$uid/media/c_" + System.currentTimeMillis() + ".jpg", "image/jpeg")
        } catch (e: Exception) { null }
    }

    /** A short video (5 seconds or less, up to 15 MB) that plays like a GIF in comments. */
    suspend fun uploadCommentVideo(ctx: Context, uri: android.net.Uri): String? {
        val uid = Cloud.uid() ?: return null
        return try {
            val len = ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
            if (len > 15_000_000L) return null
            val ref = FirebaseStorage.getInstance().reference.child("users/$uid/media/v_" + System.currentTimeMillis() + ".mp4")
            ref.putFile(uri, StorageMetadata.Builder().setContentType("video/mp4").build()).await()
            ref.downloadUrl.await().toString()
        } catch (e: Exception) { null }
    }

    /** A GIF (or animated picture) up to 6 MB, uploaded as it is so it keeps moving. */
    suspend fun uploadCommentGif(ctx: Context, uri: android.net.Uri): String? {
        val uid = Cloud.uid() ?: return null
        return try {
            val mime = ctx.contentResolver.getType(uri) ?: "image/gif"
            val len = ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
            if (len > 6_000_000L) return null
            val ext = if (mime.contains("webp")) "webp" else if (mime.contains("png")) "png" else if (mime.contains("jpeg")) "jpg" else "gif"
            val ref = FirebaseStorage.getInstance().reference.child("users/$uid/media/g_" + System.currentTimeMillis() + "." + ext)
            ref.putFile(uri, StorageMetadata.Builder().setContentType(mime).build()).await()
            ref.downloadUrl.await().toString()
        } catch (e: Exception) { null }
    }

    suspend fun deleteComment(cloudId: String, commentDocId: String) {
        try {
            val post = db().collection("posts").document(cloudId)
            val b = db().batch()
            b.delete(post.collection("comments").document(commentDocId))
            b.update(post, "commentsCount", FieldValue.increment(-1L))
            b.commit().await()
        } catch (_: Exception) {}
    }

    suspend fun follow(targetUid: String, on: Boolean) {
        val me = Cloud.uid() ?: return
        try {
            val ref = db().collection("follows").document(me + "_" + targetUid)
            if (on) ref.set(mapOf("follower" to me, "following" to targetUid, "at" to FieldValue.serverTimestamp())).await() else ref.delete().await()
        } catch (_: Exception) {}
    }

    /** Saved for review. Later, your moderation AI can read this collection. */
    suspend fun report(cloudId: String, reason: String) {
        val me = Cloud.uid() ?: return
        try {
            db().collection("reports").add(mapOf("post" to cloudId, "reporter" to me, "reason" to reason, "at" to FieldValue.serverTimestamp())).await()
        } catch (_: Exception) {}
    }
}
