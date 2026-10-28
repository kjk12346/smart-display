package dev.smartdisplay.app.ui.ambient

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import coil3.SingletonImageLoader
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Where and when a wallpaper photo was taken, shown in its corner of the clock screen. Either may be unknown. */
data class PhotoInfo(val place: String?, val taken: LocalDate?)

/** Reads EXIF's "2019:06:14 18:30:05" as a date; null for a missing, blank or zeroed one ("0000:00:00 …"). */
fun parseExifDate(value: String?): LocalDate? {
    val date = value?.trim()?.take(10) ?: return null
    return try {
        LocalDate.parse(date, EXIF_DATE)
    } catch (e: DateTimeParseException) {
        null
    }
}

private val EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd", Locale.ROOT)

/** The parts of a place that [placeName] uses, as Android's Geocoder gives them. */
data class Place(
    val locality: String?,
    val subAdminArea: String?,
    val adminArea: String?,
    val countryName: String?,
    val countryCode: String?,
)

/**
 * A short name for where a photo was taken, the way a photo frame shows it: the town and, in [homeCountry] (an ISO
 * code), its state or region ("Austin, Texas"), or abroad its country ("Lisbon, Portugal"). Without a town, the
 * county stands in; null if nothing is known.
 */
fun placeName(place: Place, homeCountry: String?): String? {
    val town = place.locality ?: place.subAdminArea
    val abroad = homeCountry.isNullOrBlank() || !place.countryCode.equals(homeCountry, ignoreCase = true)
    val region = if (abroad) place.countryName ?: place.adminArea else place.adminArea
    return listOfNotNull(town, region)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString(", ")
        .ifEmpty { null }
}

/**
 * Reads a wallpaper photo's [PhotoInfo]: the EXIF date and GPS position, the position named with Android's Geocoder
 * (on devices that have one; it isn't on Fire tablets, which then show only the date). The photo is read from Coil's
 * disk cache, where it was just downloaded to be shown; if it isn't there, only its start is downloaded again, which
 * is where JPEGs keep EXIF. Named places are remembered to about a kilometre, so a folder from one trip is looked up
 * once.
 */
class PhotoInfoReader(private val context: Context, private val http: OkHttpClient) {
    private val places = mutableMapOf<String, String?>()

    /** The photo at [url]'s place and date, or null if it has neither (or couldn't be read). */
    suspend fun read(url: String): PhotoInfo? {
        val exif = withContext(Dispatchers.IO) { cachedExif(url) ?: downloadedExif(url) } ?: return null
        val taken = parseExifDate(
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
        )
        val position = exif.latLong?.takeUnless { it[0] == 0.0 && it[1] == 0.0 }
        val place = position?.let { placeAt(it[0], it[1]) }
        // Says which part is missing when a photo shows no place: the photo's position, or the name for it.
        Log.i(TAG, "Taken $taken, position ${if (position != null) "known" else "unknown"}, place $place")
        return if (place == null && taken == null) null else PhotoInfo(place, taken)
    }

    private fun cachedExif(url: String): ExifInterface? = try {
        val cache = SingletonImageLoader.get(context).diskCache
        cache?.openSnapshot(url)?.use { ExifInterface(it.data.toFile()) }
    } catch (e: Exception) {
        // ExifInterface throws more than IOException on a damaged file.
        Log.w(TAG, "EXIF from the cached photo failed", e)
        null
    }

    private fun downloadedExif(url: String): ExifInterface? = try {
        val request = Request.Builder().url(url).header("Range", "bytes=0-${EXIF_BYTES - 1}").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            // A server that ignores the range sends the whole photo; only the start is read.
            val source = response.body.source()
            source.request(EXIF_BYTES)
            val bytes = source.buffer.readByteArray(minOf(source.buffer.size, EXIF_BYTES))
            ExifInterface(ByteArrayInputStream(bytes))
        }
    } catch (e: Exception) {
        Log.w(TAG, "EXIF from the photo's start failed", e)
        null
    }

    private suspend fun placeAt(latitude: Double, longitude: Double): String? {
        if (!Geocoder.isPresent()) return null
        val key = String.format(Locale.ROOT, "%.2f,%.2f", latitude, longitude)
        if (key in places) return places[key]
        val address = withTimeoutOrNull(GEOCODE_TIMEOUT_MS) { lookUp(latitude, longitude) } ?: return null
        val place = Place(
            address.locality,
            address.subAdminArea,
            address.adminArea,
            address.countryName,
            address.countryCode,
        )
        // Only answers are remembered: a failure (no network, say) is tried again next time.
        return placeName(place, Locale.getDefault().country).also { places[key] = it }
    }

    private suspend fun lookUp(latitude: Double, longitude: Double): Address? {
        val geocoder = Geocoder(context, Locale.getDefault())
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocation(
                        latitude,
                        longitude,
                        1,
                        object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) =
                                continuation.resume(addresses.firstOrNull())

                            override fun onError(errorMessage: String?) {
                                Log.w(TAG, "Geocoder: $errorMessage")
                                continuation.resume(null)
                            }
                        },
                    )
                }
            } else {
                runInterruptible(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // IOException without a network; IllegalArgumentException for an impossible position.
            Log.w(TAG, "Geocoder failed", e)
            null
        }
    }

    private companion object {
        const val TAG = "PhotoInfo"
        /** JPEG keeps EXIF in its first segment, at most 64 KB, near the start of the file. */
        const val EXIF_BYTES = 256 * 1024L
        const val GEOCODE_TIMEOUT_MS = 10_000L
    }
}
