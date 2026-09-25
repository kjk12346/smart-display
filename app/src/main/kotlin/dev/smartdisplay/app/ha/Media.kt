package dev.smartdisplay.app.ha

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * An entry in Home Assistant's media browser (`media_source/browse_media`): a folder, a photo, a sound, or anything
 * else a media source offers (local media, Immich, Synology Photos and so on).
 */
data class MediaItem(
    val title: String,
    /** A `media-source://` ID, used to browse into a folder or resolve a file. */
    val contentId: String,
    /** "directory", "image", "music", "video" and so on. */
    val mediaClass: String?,
    /** A MIME type for files, such as "image/jpeg". */
    val contentType: String?,
    val canExpand: Boolean,
    val canPlay: Boolean,
) {
    val isImage: Boolean get() = mediaClass == "image" || contentType?.startsWith("image/") == true
    val isAudio: Boolean get() = mediaClass == "music" || contentType?.startsWith("audio/") == true
}

/** A folder's listing: the folder itself, and what's in it. */
data class MediaFolder(val item: MediaItem, val children: List<MediaItem>)

/** A playable URL for a media file, from `media_source/resolve_media`. */
data class ResolvedMedia(val url: String, val mimeType: String?)

internal fun parseMediaFolder(json: JsonElement): MediaFolder {
    val obj = json as? JsonObject ?: throw ProtocolException("browse_media: not an object")
    val item = parseMediaItem(obj) ?: throw ProtocolException("browse_media: no content ID")
    val children = (obj["children"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseMediaItem) }
    return MediaFolder(item, children)
}

private fun parseMediaItem(obj: JsonObject): MediaItem? = MediaItem(
    title = obj.string("title") ?: "",
    contentId = obj.string("media_content_id") ?: return null,
    mediaClass = obj.string("media_class"),
    contentType = obj.string("media_content_type"),
    canExpand = (obj["can_expand"] as? JsonPrimitive)?.booleanOrNull == true,
    canPlay = (obj["can_play"] as? JsonPrimitive)?.booleanOrNull == true,
)

/**
 * Reads `resolve_media`'s answer. Home Assistant gives local media as a signed path on itself
 * (`/media/local/x.jpg?authSig=…`), which is made absolute on [server]; other sources give full URLs.
 */
internal fun parseResolvedMedia(json: JsonElement, server: String): ResolvedMedia {
    val obj = json as? JsonObject ?: throw ProtocolException("resolve_media: not an object")
    val url = obj.string("url") ?: throw ProtocolException("resolve_media: no url")
    val absolute = if (url.startsWith("http://") || url.startsWith("https://")) url else server.trimEnd('/') + url
    return ResolvedMedia(absolute, obj.string("mime_type"))
}
