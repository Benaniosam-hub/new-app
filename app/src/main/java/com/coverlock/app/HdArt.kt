package com.coverlock.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Finds a sharper version of the album cover than the music app gives us.
 *
 *  1. Looks the song up in Apple's public iTunes catalog (no account or key needed) and downloads
 *     the artwork at up to 3000x3000.
 *  2. If that finds nothing, upgrades the music app's own cover URL (Spotify, YouTube Music...) to its largest size.
 *
 * Downloaded covers are cached so each song is only fetched once.
 */
object HdArt {

    private val misses: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())

    private fun maxSide(c: Context): Int {
        val dm = c.resources.displayMetrics
        return max(dm.widthPixels, dm.heightPixels)
    }

    private fun cacheFile(c: Context, key: String) = File(File(c.cacheDir, "hd"), "$key.jpg")

    /** A cover we already downloaded for this song, if it is bigger than [minWidth]. */
    fun cached(c: Context, title: String, artist: String, minWidth: Int): Bitmap? {
        if (title.isBlank()) return null
        val f = cacheFile(c, cacheKey(title, artist))
        if (!f.exists()) return null
        val bmp = (try { decode(f.readBytes(), maxSide(c)) } catch (e: Exception) { null }) ?: return null
        return if (bmp.width > minWidth) bmp else null
    }

    /** Downloads a sharper cover. Blocks on the network, so call it off the main thread. */
    fun fetch(
        c: Context, title: String, artist: String, album: String,
        artUris: List<String>, minWidth: Int
    ): Bitmap? {
        val key = if (title.isBlank()) null else cacheKey(title, artist)
        if (key != null && key in misses) return null

        val bytes = (if (title.isNotBlank()) fromItunes(title, artist, album) else null) ?: fromUris(artUris)
        val bmp = bytes?.let { decode(it, maxSide(c)) }
        if (bytes == null || bmp == null || bmp.width <= minWidth) {
            if (key != null) misses.add(key)
            return null
        }
        if (key != null) {
            try {
                val f = cacheFile(c, key)
                f.parentFile?.mkdirs()
                f.writeBytes(bytes)
                prune(f.parentFile)
            } catch (_: Exception) {}
        }
        return bmp
    }

    // ───────────── sources ─────────────

    private fun fromItunes(title: String, artist: String, album: String): ByteArray? {
        val countries = listOfNotNull(Locale.getDefault().country.takeIf { it.length == 2 }, "US").distinct()
        val term = (title + " " + firstArtistRaw(artist)).trim()
        for (country in countries) {
            try {
                val url = "https://itunes.apple.com/search?media=music&entity=song&limit=10" +
                    "&country=$country&term=" + URLEncoder.encode(term, "UTF-8")
                val body = httpGet(url)?.toString(Charsets.UTF_8) ?: continue
                val results = JSONObject(body).optJSONArray("results") ?: continue
                val best = pickBest(results, title, artist, album) ?: continue
                val small = best.optString("artworkUrl100")
                if (small.isBlank()) continue
                for (size in listOf(3000, 1600, 1000)) {
                    val big = small.replace(Regex("""\d+x\d+bb"""), "${size}x${size}bb")
                    httpGet(big)?.let { return it }
                }
            } catch (e: Exception) {
                // try next country
            }
        }
        return null
    }

    private fun fromUris(uris: List<String>): ByteArray? {
        for (u in uris) {
            val upgraded = upgradeKnownUrl(u)
            httpGet(upgraded)?.let { return it }
            if (upgraded != u) httpGet(u)?.let { return it }
        }
        return null
    }

    /** Known URL patterns that have a bigger variant of the same image. */
    private fun upgradeKnownUrl(u: String): String {
        var out = u
        // Spotify: 300px / 64px variants -> 640px (their maximum)
        out = out.replace("ab67616d00001e02", "ab67616d0000b273").replace("ab67616d00004851", "ab67616d0000b273")
        // Google-hosted (YouTube Music): =w120-h120-... -> =w1600-h1600
        if (out.contains("googleusercontent.com") || out.contains("ytimg.com")) {
            out = out.replace(Regex("=w\\d+-h\\d+"), "=w1600-h1600")
        }
        return out
    }

    // ───────────── matching ─────────────

    private fun pickBest(results: org.json.JSONArray, title: String, artist: String, album: String): JSONObject? {
        val t = norm(title)
        val a = norm(firstArtistRaw(artist))
        val al = norm(album)
        var fallback: JSONObject? = null
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val rt = norm(r.optString("trackName"))
            val ra = norm(r.optString("artistName"))
            val titleOk = rt.isNotEmpty() && t.isNotEmpty() &&
                (rt == t || (min(rt.length, t.length) >= 4 && (rt.contains(t) || t.contains(rt))))
            val artistOk = a.isEmpty() || ra.contains(a) || (ra.isNotEmpty() && a.contains(ra))
            if (!titleOk || !artistOk) continue
            if (al.isNotEmpty()) {
                val rc = norm(r.optString("collectionName"))
                if (rc.isNotEmpty() && (rc == al || rc.contains(al) || al.contains(rc))) return r
            }
            if (fallback == null) fallback = r
        }
        return fallback
    }

    private fun firstArtistRaw(artist: String): String =
        artist.split(Regex("[,&;/]| feat\\.? | ft\\.? | x ", RegexOption.IGNORE_CASE)).first().trim()

    private fun norm(s: String): String = s.lowercase(Locale.ROOT)
        .replace(Regex("[(\\[].*?[)\\]]"), " ")      // (feat. X) [Remastered]
        .replace(Regex("\\s[-–—]\\s.*$"), " ")        // " - Remastered 2011"
        .replace(Regex("[^\\p{L}\\p{N}]+"), "")

    private fun cacheKey(title: String, artist: String): String {
        val s = norm(title) + "|" + norm(firstArtistRaw(artist))
        return Integer.toHexString(s.hashCode()) + "_" + s.length
    }

    // ───────────── plumbing ─────────────

    private fun httpGet(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 12000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "CoverLock/1.0")
            if (conn.responseCode != 200) return null
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 15_000_000) return null
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray()
        } catch (e: Exception) {
            return null
        } finally {
            conn?.disconnect()
        }
    }

    /** Decodes without going far beyond what the screen can show (keeps memory in check). */
    private fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun prune(dir: File?) {
        val files = dir?.listFiles() ?: return
        if (files.size > 60) {
            files.sortedBy { it.lastModified() }.take(files.size - 40).forEach { it.delete() }
        }
    }
}
