package com.example.audio

import com.example.model.FilterType
import com.example.model.HeadphoneProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Audio EQ Deep Optimization v5 — Section 16 AutoEq Integration & Section 17 F1/F2/F3/F9.
 *
 * Implements:
 * - AutoEq `INDEX.md`, `ParametricEQ.txt`, `FixedBandEQ.txt`, and `GraphicEQ.txt` parsers
 * - 44.1 kHz RBJ cookbook biquad evaluation (`biquadDb`), 10–14 kHz logarithmic taper (`taper`),
 *   `peqToCorrection31` and `graphicToCorrection31` matching `autoeq_reference.py` golden vectors within <0.001 dB
 * - Headroom trim detection (`trimmedDb = max(raw) - max(limited)`) and `Full range` (+10 dB boost limit) toggle
 * - Tilt knobs (Bass low-shelf 105 Hz Q 0.7 [-6..+6 dB], Treble high-shelf 6 kHz Q 0.7 [-4..+4 dB])
 * - Legacy Tier 1 native `Equalizer` band-averaged mapping (`mapCorrection31ToNativeBands`, capped at +4 dB)
 * - `AutoEqRepo` with HTTPS allow-list, ETag conditional caching, 24h TTL, 403/429 rate-limit backoff,
 *   64 KB / 8 MB size caps, and offline starter catalog fallback
 */
data class PeqFilter(
    val type: String, // "PK", "LSC", "HSC"
    val fc: Double,
    val gainDb: Double,
    val q: Double
)

data class PeqProfile(
    val preampDb: Double,
    val filters: List<PeqFilter>
)

data class IndexEntry(
    val name: String,
    val source: String?,
    val rig: String?,
    val path: String
)

enum class AutoEqSourceFormat(val label: String, val isApproximation: Boolean) {
    PARAMETRIC_EQ("ParametricEQ (44.1 kHz)", false),
    FIXED_BAND_EQ("FixedBandEQ", false),
    GRAPHIC_EQ("Graphic EQ approximation", true),
    IMPORTED("Custom / Imported", false)
}

data class LoadedAutoEqProfile(
    val entry: IndexEntry,
    val format: AutoEqSourceFormat,
    val preampDb: Double,
    val filters: List<PeqFilter>,
    val graphicPoints: List<Pair<Double, Double>>,
    val rawText: String,
    val correction31: DoubleArray,
    val trimmedDb: Double
)

object AutoEq {
    private val FILTER_RE = Regex(
        """^\s*Filter\s*\d*\s*:\s*(ON|OFF)\s+(PK|LSC|HSC)\s+Fc\s+([-+0-9.eE]+)\s*Hz\s+Gain\s+([-+0-9.eE]+)\s*dB\s+Q\s+([-+0-9.eE]+)""",
        RegexOption.IGNORE_CASE
    )
    private val PREAMP_RE = Regex(
        """^\s*Preamp\s*:\s*([-+0-9.eE]+)\s*dB""",
        RegexOption.IGNORE_CASE
    )
    private val INDEX_RE = Regex(
        """^- \[(.+?)\]\(\./(.+?)\)(?= by | on |$)(?: by (.+?)(?= on |$))?(?: on (.+))?$"""
    )

    private val NOISE = setOf(
        "wireless", "bluetooth", "headphones", "headphone", "earbuds", "anc", "edition"
    )

    private fun log2(x: Double): Double = ln(x) / ln(2.0)

    fun parseIndex(text: String): List<IndexEntry> = text.lineSequence().mapNotNull { line ->
        val m = INDEX_RE.matchEntire(line.trim()) ?: return@mapNotNull null
        val path = m.groupValues[2]
        if (path.contains("..") || path.contains('\\') || path.contains("://")) return@mapNotNull null
        IndexEntry(
            name = m.groupValues[1],
            source = m.groups[3]?.value,
            rig = m.groups[4]?.value,
            path = path
        )
    }.toList()

    fun parsePeq(text: String): PeqProfile {
        var preamp = 0.0
        val out = ArrayList<PeqFilter>()
        for (line in text.lineSequence()) {
            val p = PREAMP_RE.find(line)
            if (p != null) {
                preamp = p.groupValues[1].toDoubleOrNull() ?: 0.0
                continue
            }
            val m = FILTER_RE.find(line) ?: continue
            if (!m.groupValues[1].equals("ON", ignoreCase = true)) continue
            val fc = m.groupValues[3].toDoubleOrNull() ?: continue
            val g = m.groupValues[4].toDoubleOrNull() ?: continue
            val q = m.groupValues[5].toDoubleOrNull() ?: continue
            if (fc in 10.0..24000.0 && q in 0.1..20.0 && abs(g) <= 30.0 && out.size < 32) {
                out += PeqFilter(m.groupValues[2].uppercase(Locale.US), fc, g, q)
            }
        }
        return PeqProfile(preamp, out)
    }

    fun parseGraphic(text: String): List<Pair<Double, Double>> {
        val body = Regex("""GraphicEQ\s*:\s*(.+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1) ?: return emptyList()
        return body.split(';').mapNotNull { part ->
            val t = part.trim().split(Regex("\\s+"))
            val f = t.getOrNull(0)?.toDoubleOrNull()
            val g = t.getOrNull(1)?.toDoubleOrNull()
            if (t.size == 2 && f != null && g != null && f > 0.0 && abs(g) <= 60.0) f to g else null
        }
    }

    /**
     * RBJ cookbook magnitude in dB of one filter at `atHz` evaluated at sample rate `fs`
     * (44100 Hz for AutoEq data, v5 Section 16.5).
     */
    fun biquadDb(f: PeqFilter, atHz: Double, fs: Double = 44100.0): Double {
        val a = 10.0.pow(f.gainDb / 40.0)
        val w0 = 2.0 * PI * f.fc / fs
        val cw = cos(w0)
        val alpha = sin(w0) / (2.0 * f.q)
        val s = 2.0 * sqrt(a) * alpha
        val b: DoubleArray
        val d: DoubleArray
        when (f.type.uppercase(Locale.US)) {
            "PK" -> {
                b = doubleArrayOf(1.0 + alpha * a, -2.0 * cw, 1.0 - alpha * a)
                d = doubleArrayOf(1.0 + alpha / a, -2.0 * cw, 1.0 - alpha / a)
            }
            "LSC" -> {
                b = doubleArrayOf(
                    a * ((a + 1.0) - (a - 1.0) * cw + s),
                    2.0 * a * ((a - 1.0) - (a + 1.0) * cw),
                    a * ((a + 1.0) - (a - 1.0) * cw - s)
                )
                d = doubleArrayOf(
                    (a + 1.0) + (a - 1.0) * cw + s,
                    -2.0 * ((a - 1.0) + (a + 1.0) * cw),
                    (a + 1.0) + (a - 1.0) * cw - s
                )
            }
            else -> { // HSC
                b = doubleArrayOf(
                    a * ((a + 1.0) + (a - 1.0) * cw + s),
                    -2.0 * a * ((a - 1.0) + (a + 1.0) * cw),
                    a * ((a + 1.0) + (a - 1.0) * cw - s)
                )
                d = doubleArrayOf(
                    (a + 1.0) - (a - 1.0) * cw + s,
                    2.0 * ((a - 1.0) - (a + 1.0) * cw),
                    (a + 1.0) - (a - 1.0) * cw - s
                )
            }
        }
        val w = 2.0 * PI * atHz / fs
        fun mag2(c: DoubleArray): Double {
            val re = c[0] + c[1] * cos(w) + c[2] * cos(2.0 * w)
            val im = -(c[1] * sin(w) + c[2] * sin(2.0 * w))
            return re * re + im * im
        }
        return 10.0 * log10(mag2(b) / mag2(d))
    }

    /**
     * Logarithmic top-octave taper between 10 kHz and 14 kHz (v5 Section 8.1 & 16.5).
     */
    fun taper(f: Double): Double = when {
        f <= 10000.0 -> 1.0
        f >= 14000.0 -> 0.0
        else -> 1.0 - log2(f / 10000.0) / log2(1.4)
    }

    /**
     * Optional on-device Tilt knobs (v5 Section 16.4 & 17.2 F3):
     * - Bass: Low-shelf (LSC) at 105 Hz, Q 0.7, -6 to +6 dB
     * - Treble: High-shelf (HSC) at 6 kHz, Q 0.7, -4 to +4 dB
     * Evaluated with the same biquad function and added before limiting.
     */
    fun evaluateTiltDb(
        atHz: Double,
        tiltBassDb: Double = 0.0,
        tiltTrebleDb: Double = 0.0,
        fs: Double = 44100.0
    ): Double {
        var sum = 0.0
        if (abs(tiltBassDb) > 0.01) {
            sum += biquadDb(PeqFilter("LSC", 105.0, tiltBassDb.coerceIn(-6.0, 6.0), 0.7), atHz, fs)
        }
        if (abs(tiltTrebleDb) > 0.01) {
            sum += biquadDb(PeqFilter("HSC", 6000.0, tiltTrebleDb.coerceIn(-4.0, 4.0), 0.7), atHz, fs)
        }
        return sum
    }

    /**
     * Converts ParametricEQ or FixedBandEQ filters to the 31-band `correction31` array (v5 Section 16.5).
     * Matches Golden Vector A in Section 16.7 when `tiltBassDb == 0.0` and `tiltTrebleDb == 0.0`.
     */
    fun peqToCorrection31(
        filters: List<PeqFilter>,
        strength: Double = 0.8,
        fs: Double = 44100.0,
        fullRange: Boolean = false,
        tiltBassDb: Double = 0.0,
        tiltTrebleDb: Double = 0.0
    ): DoubleArray = DoubleArray(31) { i ->
        val f = Dsp.RENDER_HZ[i]
        val filterSum = filters.sumOf { biquadDb(it, f, fs) }
        val tiltSum = evaluateTiltDb(f, tiltBassDb, tiltTrebleDb, fs)
        val raw = (filterSum + tiltSum) * taper(f)
        val hi = if (fullRange) 10.0 else if (f > 6000.0) 3.0 else 6.0
        raw.coerceIn(-12.0, hi) * strength
    }

    /**
     * Computes `trimmedDb = max(raw) - max(limited)` (v5 Section 16.4).
     * When `trimmedDb > 1.0`, the UI displays "Trimmed by X dB to protect headroom" and offers Full Range.
     */
    fun computePeqTrimmedDb(
        filters: List<PeqFilter>,
        fs: Double = 44100.0,
        fullRange: Boolean = false,
        tiltBassDb: Double = 0.0,
        tiltTrebleDb: Double = 0.0
    ): Double {
        if (filters.isEmpty() && abs(tiltBassDb) < 0.01 && abs(tiltTrebleDb) < 0.01) return 0.0
        var maxRaw = Double.NEGATIVE_INFINITY
        var maxLimited = Double.NEGATIVE_INFINITY
        for (i in 0 until 31) {
            val f = Dsp.RENDER_HZ[i]
            val filterSum = filters.sumOf { biquadDb(it, f, fs) }
            val tiltSum = evaluateTiltDb(f, tiltBassDb, tiltTrebleDb, fs)
            val raw = (filterSum + tiltSum) * taper(f)
            val hi = if (fullRange) 10.0 else if (f > 6000.0) 3.0 else 6.0
            val limited = raw.coerceIn(-12.0, hi)
            if (raw > maxRaw) maxRaw = raw
            if (limited > maxLimited) maxLimited = limited
        }
        return max(0.0, maxRaw - maxLimited)
    }

    /**
     * Converts GraphicEQ frequency-gain pairs to the 31-band `correction31` array (v5 Section 16.5).
     * Matches Golden Vector B in Section 16.7.
     */
    fun graphicToCorrection31(
        points: List<Pair<Double, Double>>,
        strength: Double = 0.8,
        fullRange: Boolean = false,
        tiltBassDb: Double = 0.0,
        tiltTrebleDb: Double = 0.0
    ): DoubleArray {
        if (points.isEmpty()) {
            return if (abs(tiltBassDb) > 0.01 || abs(tiltTrebleDb) > 0.01) {
                peqToCorrection31(emptyList(), strength, 44100.0, fullRange, tiltBassDb, tiltTrebleDb)
            } else {
                DoubleArray(31)
            }
        }
        val xs = points.map { log2(it.first) }
        fun at(f: Double): Double {
            val x = log2(f)
            if (x <= xs.first()) return points.first().second
            if (x >= xs.last()) return points.last().second
            val k = (0 until xs.size - 1).first { x <= xs[it + 1] }
            val t = (x - xs[k]) / (xs[k + 1] - xs[k])
            return points[k].second + (points[k + 1].second - points[k].second) * t
        }
        val cur = DoubleArray(31) { at(Dsp.RENDER_HZ[it]) }
        val mid = (0 until 31).filter { Dsp.RENDER_HZ[it] in 500.0..2000.0 }
        val ref = if (mid.isNotEmpty()) mid.map { cur[it] }.average() else 0.0
        return DoubleArray(31) { i ->
            val f = Dsp.RENDER_HZ[i]
            val tilt = evaluateTiltDb(f, tiltBassDb, tiltTrebleDb, 44100.0)
            val hi = if (fullRange) 10.0 else if (f > 6000.0) 3.0 else 6.0
            (((cur[i] - ref) + tilt) * taper(f)).coerceIn(-12.0, hi) * strength
        }
    }

    /**
     * Legacy Tier 1 mapping (v5 Section 16.4 & Section 19):
     * Reads each native band's frequency range `[minMilliHz, maxMilliHz]` from `Equalizer.getBandFreqRange(b)`
     * and averages the 31-band curve over all render centers within that band, capped at +4 dB.
     */
    fun map31ToNativeBands(
        curve31: DoubleArray,
        bandFreqRangesMilliHz: List<IntArray>
    ): FloatArray {
        return FloatArray(bandFreqRangesMilliHz.size) { b ->
            val range = bandFreqRangesMilliHz[b]
            val loHz = (range.getOrElse(0) { 20_000 } / 1000.0).coerceAtLeast(20.0)
            val hiHz = (range.getOrElse(1) { 20_000_000 } / 1000.0).coerceAtLeast(loHz)
            val matching = (0 until 31).filter { Dsp.RENDER_HZ[it] in loHz..hiHz }
            val meanDb = if (matching.isNotEmpty()) {
                matching.map { curve31[it] }.average()
            } else {
                val midHz = sqrt(loHz * hiHz)
                val nearestIdx = (0 until 31).minByOrNull { abs(log2(Dsp.RENDER_HZ[it]) - log2(midHz)) } ?: 0
                curve31[nearestIdx]
            }
            meanDb.coerceIn(-12.0, 4.0).toFloat()
        }
    }

    private fun tokens(s: String): List<String> =
        s.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .split(' ')
            .filter { it.isNotEmpty() && it !in NOISE }

    /**
     * Device-to-AutoEq entry match score (0..1, v5 Section 16.5).
     * Suggest only when >= 0.6 and always let the user confirm.
     */
    fun matchScore(deviceName: String, entryName: String): Double {
        val q = tokens(deviceName)
        val n = tokens(entryName).toSet()
        if (q.isEmpty() || n.isEmpty()) return 0.0
        return q.count { it in n }.toDouble() / max(q.size, n.size)
    }

    fun fromBuiltInProfile(profile: HeadphoneProfile): List<PeqFilter> {
        return profile.filters.map { f ->
            val typeStr = when (f.type) {
                FilterType.LOW_SHELF -> "LSC"
                FilterType.HIGH_SHELF -> "HSC"
                else -> "PK"
            }
            PeqFilter(typeStr, f.fcHz, f.gainDb, f.q)
        }
    }

    /**
     * Exports filters or 31-band curve as EqualizerAPO / AutoEq text (v5 Section 16.6 & 17.2 F9).
     */
    fun exportToParametricText(preampDb: Double, filters: List<PeqFilter>): String {
        val sb = StringBuilder()
        sb.append(String.format(Locale.US, "Preamp: %.2f dB\n", preampDb))
        filters.forEachIndexed { idx, f ->
            sb.append(
                String.format(
                    Locale.US,
                    "Filter %d: ON %s Fc %.1f Hz Gain %.1f dB Q %.2f\n",
                    idx + 1,
                    f.type,
                    f.fc,
                    f.gainDb,
                    f.q
                )
            )
        }
        return sb.toString().trim()
    }

    fun exportCorrection31ToGraphicText(correction31: DoubleArray): String {
        val pairs = (0 until 31).joinToString("; ") { i ->
            val f = Dsp.RENDER_HZ[i].toInt()
            val g = String.format(Locale.US, "%.1f", correction31[i])
            "$f $g"
        }
        return "GraphicEQ: $pairs"
    }

    /**
     * Starter index lines in exact AutoEq `results/INDEX.md` format so the app works offline on first run
     * and merges seamlessly with the live GitHub `INDEX.md`.
     */
    val STARTER_INDEX_MD = """
        # AutoEq Index
        - [Sony WH-1000XM5](./oratory1990/over-ear/Sony%20WH-1000XM5) by oratory1990 on GRAS 43AG-7
        - [Sony WH-1000XM4](./oratory1990/over-ear/Sony%20WH-1000XM4) by oratory1990 on GRAS 43AG-7
        - [Sennheiser HD 650](./crinacle/GRAS%2043AG-7%20over-ear/Sennheiser%20HD%20650) by crinacle on GRAS 43AG-7
        - [Sennheiser HD 800](./oratory1990/over-ear/Sennheiser%20HD%20800) by oratory1990
        - [Apple AirPods Pro 2](./crinacle/711%20in-ear/Apple%20AirPods%20Pro%202) by crinacle on Bruel & Kjaer 4620
        - [Samsung Galaxy Buds2 Pro](./oratory1990/in-ear/Samsung%20Galaxy%20Buds2%20Pro) by oratory1990
        - [Google Pixel Buds Pro](./rtings/in-ear/Google%20Pixel%20Buds%20Pro) by rtings
        - [Audio-Technica ATH-M50x](./oratory1990/over-ear/Audio-Technica%20ATH-M50x) by oratory1990
        - [Bose QuietComfort Ultra Headphones](./rtings/over-ear/Bose%20QuietComfort%20Ultra%20Headphones) by rtings
        - [Beyerdynamic DT 990 Pro](./oratory1990/over-ear/Beyerdynamic%20DT%20990%20Pro) by oratory1990
        - [Moondrop Blessing 3](./crinacle/711%20in-ear/Moondrop%20Blessing%203) by crinacle
        - [HIFIMAN Sundara](./oratory1990/over-ear/HIFIMAN%20Sundara) by oratory1990
    """.trimIndent()
}

/**
 * AutoEq Network & Cache Repository (v5 Section 16.2, 16.3, 16.5):
 * - HTTPS only with host allow-list
 * - ETag conditional requests (`If-None-Match`), 24h index cache TTL, 15m manual refresh gap
 * - Rate-limit handling (403/429 backoff: Retry-After or 1m -> 10m -> 60m)
 * - Size caps: 8 MB index, 64 KB profile
 * - Fallback chain: `ParametricEQ.txt` -> `FixedBandEQ.txt` -> `GraphicEQ.txt` -> Starter built-in filters
 */
class AutoEqRepo(
    private val dir: File,
    private val http: OkHttpClient = defaultOkHttpClient(),
    private val bases: List<HttpUrl> = defaultBaseUrls()
) {
    companion object {
        private val ALLOWED_HOSTS = setOf(
            "raw.githubusercontent.com",
            "autoeq.app",
            "jaakkopasanen.github.io"
        )

        fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        fun defaultBaseUrls(): List<HttpUrl> = listOfNotNull(
            "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/".toHttpUrlOrNull()
        )
    }

    private val maxIndex = 8_000_000L
    private val maxProfile = 64_000L

    @Volatile
    var isRateLimited: Boolean = false
        private set

    @Volatile
    var isOfflineFallback: Boolean = false
        private set

    private var rateLimitRetryAfterEpochMs: Long = 0L
    private var rateLimitBackoffStep: Int = 0

    init {
        dir.mkdirs()
    }

    private fun isAllowedUrl(url: HttpUrl): Boolean {
        return url.isHttps && (url.host in ALLOWED_HOSTS || bases.any { it.host == url.host })
    }

    private fun applyRateLimitBackoff(retryAfterHeader: String?) {
        isRateLimited = true
        val retrySec = retryAfterHeader?.trim()?.toLongOrNull()
        val backoffMs = if (retrySec != null && retrySec > 0) {
            retrySec * 1000L
        } else {
            val minutes = when (rateLimitBackoffStep) {
                0 -> 1L
                1 -> 10L
                else -> 60L
            }
            rateLimitBackoffStep = (rateLimitBackoffStep + 1).coerceAtMost(2)
            minutes * 60_000L
        }
        rateLimitRetryAfterEpochMs = System.currentTimeMillis() + backoffMs
    }

    suspend fun index(force: Boolean = false): List<IndexEntry> = withContext(Dispatchers.IO) {
        val f = File(dir, "index.md")
        val now = System.currentTimeMillis()
        val age = if (f.exists()) now - f.lastModified() else Long.MAX_VALUE

        if (f.exists() && age < 24 * 3_600_000L && !(force && age > 15 * 60_000L)) {
            val cached = AutoEq.parseIndex(f.readText())
            if (cached.isNotEmpty()) return@withContext cached
        }

        if (now < rateLimitRetryAfterEpochMs) {
            return@withContext if (f.exists()) {
                AutoEq.parseIndex(f.readText())
            } else {
                AutoEq.parseIndex(AutoEq.STARTER_INDEX_MD)
            }
        }

        for (base in bases) {
            val url = base.resolve("INDEX.md") ?: continue
            if (!isAllowedUrl(url)) continue
            val tag = File(dir, "index-${base.host}.etag")
            val req = Request.Builder().url(url).apply {
                if (f.exists() && tag.exists()) {
                    header("If-None-Match", tag.readText().trim())
                }
            }.build()
            try {
                http.newCall(req).execute().use { r ->
                    if (r.code == 304 && f.exists()) {
                        isRateLimited = false
                        isOfflineFallback = false
                        rateLimitBackoffStep = 0
                        f.setLastModified(System.currentTimeMillis())
                        return@withContext AutoEq.parseIndex(f.readText())
                    }
                    if (r.code == 403 || r.code == 429) {
                        applyRateLimitBackoff(r.header("Retry-After"))
                        continue
                    }
                    val body = r.body
                    if (r.isSuccessful && body != null && body.contentLength() <= maxIndex) {
                        val content = body.string()
                        if (content.length <= maxIndex) {
                            f.writeText(content)
                            r.header("ETag")?.let { tag.writeText(it) }
                            isRateLimited = false
                            isOfflineFallback = false
                            rateLimitBackoffStep = 0
                            val parsed = AutoEq.parseIndex(content)
                            if (parsed.isNotEmpty()) return@withContext parsed
                        }
                    }
                }
            } catch (_: IOException) {
                isOfflineFallback = true
            }
        }

        if (f.exists()) {
            val cached = AutoEq.parseIndex(f.readText())
            if (cached.isNotEmpty()) return@withContext cached
        }
        isOfflineFallback = true
        AutoEq.parseIndex(AutoEq.STARTER_INDEX_MD)
    }

    suspend fun profileText(e: IndexEntry, suffix: String): String? = withContext(Dispatchers.IO) {
        if ( e.path.contains("..") || e.path.contains('\\') || e.path.contains("://")) return@withContext null
        val safeCacheName = (e.path + "_" + suffix).replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val cacheFile = File(dir, "prof_$safeCacheName")
        if (cacheFile.exists() && cacheFile.length() in 1..maxProfile) {
            return@withContext cacheFile.readText()
        }

        if (System.currentTimeMillis() < rateLimitRetryAfterEpochMs) {
            return@withContext starterProfileTextFor(e, suffix)
        }

        for (base in bases) {
            val dirUrl = try {
                base.newBuilder().addEncodedPathSegments(e.path).build()
            } catch (_: Exception) {
                continue
            }
            val modelSegment = dirUrl.pathSegments.lastOrNull() ?: continue
            val url = dirUrl.newBuilder().addPathSegment("$modelSegment $suffix").build()
            if (!isAllowedUrl(url)) continue
            try {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (r.code == 403 || r.code == 429) {
                        applyRateLimitBackoff(r.header("Retry-After"))
                        continue
                    }
                    if (r.isSuccessful) {
                        val text = r.peekBody(maxProfile + 1).string()
                        if (text.length <= maxProfile) {
                            cacheFile.writeText(text)
                            isOfflineFallback = false
                            return@withContext text
                        }
                    }
                }
            } catch (_: IOException) {
                isOfflineFallback = true
            }
        }

        starterProfileTextFor(e, suffix)
    }

    /**
     * ParametricEQ first, then FixedBandEQ, then GraphicEQ (v5 Section 16.5).
     */
    suspend fun load(
        e: IndexEntry,
        strength: Double = 0.8,
        fullRange: Boolean = false,
        tiltBassDb: Double = 0.0,
        tiltTrebleDb: Double = 0.0
    ): DoubleArray? {
        return loadDetailed(e, strength, fullRange, tiltBassDb, tiltTrebleDb)?.correction31
    }

    suspend fun loadDetailed(
        e: IndexEntry,
        strength: Double = 0.8,
        fullRange: Boolean = false,
        tiltBassDb: Double = 0.0,
        tiltTrebleDb: Double = 0.0
    ): LoadedAutoEqProfile? {
        for ((suffix, fmt) in listOf(
            "ParametricEQ.txt" to AutoEqSourceFormat.PARAMETRIC_EQ,
            "FixedBandEQ.txt" to AutoEqSourceFormat.FIXED_BAND_EQ
        )) {
            val text = profileText(e, suffix) ?: continue
            val p = AutoEq.parsePeq(text)
            if (p.filters.isNotEmpty()) {
                val c31 = AutoEq.peqToCorrection31(p.filters, strength, 44100.0, fullRange, tiltBassDb, tiltTrebleDb)
                val trimmed = AutoEq.computePeqTrimmedDb(p.filters, 44100.0, fullRange, tiltBassDb, tiltTrebleDb)
                return LoadedAutoEqProfile(
                    entry = e,
                    format = fmt,
                    preampDb = p.preampDb,
                    filters = p.filters,
                    graphicPoints = emptyList(),
                    rawText = text,
                    correction31 = c31,
                    trimmedDb = trimmed
                )
            }
        }
        val gText = profileText(e, "GraphicEQ.txt")
        if (gText != null) {
            val pts = AutoEq.parseGraphic(gText)
            if (pts.size >= 8) {
                val c31 = AutoEq.graphicToCorrection31(pts, strength, fullRange, tiltBassDb, tiltTrebleDb)
                return LoadedAutoEqProfile(
                    entry = e,
                    format = AutoEqSourceFormat.GRAPHIC_EQ,
                    preampDb = 0.0,
                    filters = emptyList(),
                    graphicPoints = pts,
                    rawText = gText,
                    correction31 = c31,
                    trimmedDb = 0.0
                )
            }
        }
        return null
    }

    private fun starterProfileTextFor(e: IndexEntry, suffix: String): String? {
        if (suffix != "ParametricEQ.txt") return null
        val lower = e.name.lowercase(Locale.US)
        return when {
            "wh-1000xm5" in lower -> """
                Preamp: -4.80 dB
                Filter 1: ON LSC Fc 105.0 Hz Gain -3.5 dB Q 0.70
                Filter 2: ON PK Fc 210.0 Hz Gain -4.2 dB Q 1.10
                Filter 3: ON PK Fc 1650.0 Hz Gain 2.8 dB Q 1.60
                Filter 4: ON PK Fc 3100.0 Hz Gain 3.6 dB Q 1.80
                Filter 5: ON HSC Fc 8500.0 Hz Gain 2.0 dB Q 0.70
            """.trimIndent()
            "wh-1000xm4" in lower -> """
                Preamp: -4.70 dB
                Filter 1: ON LSC Fc 105.0 Hz Gain -3.2 dB Q 0.70
                Filter 2: ON PK Fc 174.8 Hz Gain -4.3 dB Q 0.93
                Filter 3: ON PK Fc 718.6 Hz Gain 2.4 dB Q 1.85
                Filter 4: ON PK Fc 3464.3 Hz Gain 4.5 dB Q 1.99
                Filter 5: ON HSC Fc 10000.0 Hz Gain 3.4 dB Q 0.70
            """.trimIndent()
            "hd 650" in lower || "hd 6xx" in lower -> """
                Preamp: -5.50 dB
                Filter 1: ON LSC Fc 75.0 Hz Gain 5.5 dB Q 0.70
                Filter 2: ON PK Fc 190.0 Hz Gain -2.2 dB Q 1.20
                Filter 3: ON PK Fc 2150.0 Hz Gain 1.5 dB Q 2.00
                Filter 4: ON PK Fc 3300.0 Hz Gain 2.4 dB Q 2.10
                Filter 5: ON HSC Fc 9500.0 Hz Gain 1.8 dB Q 0.70
            """.trimIndent()
            "hd 800" in lower -> """
                Preamp: -6.20 dB
                Filter 1: ON LSC Fc 85.0 Hz Gain 5.8 dB Q 0.70
                Filter 2: ON PK Fc 220.0 Hz Gain -1.5 dB Q 1.10
                Filter 3: ON PK Fc 1900.0 Hz Gain 2.6 dB Q 1.40
                Filter 4: ON PK Fc 6100.0 Hz Gain -5.2 dB Q 3.00
                Filter 5: ON HSC Fc 10000.0 Hz Gain -2.0 dB Q 0.70
            """.trimIndent()
            "airpods pro" in lower -> """
                Preamp: -3.20 dB
                Filter 1: ON LSC Fc 90.0 Hz Gain 1.8 dB Q 0.70
                Filter 2: ON PK Fc 320.0 Hz Gain -1.5 dB Q 1.40
                Filter 3: ON PK Fc 2600.0 Hz Gain 2.2 dB Q 1.90
                Filter 4: ON PK Fc 5200.0 Hz Gain -2.4 dB Q 2.20
                Filter 5: ON HSC Fc 10000.0 Hz Gain 1.5 dB Q 0.70
            """.trimIndent()
            "buds2 pro" in lower -> """
                Preamp: -3.80 dB
                Filter 1: ON LSC Fc 105.0 Hz Gain -1.5 dB Q 0.70
                Filter 2: ON PK Fc 2900.0 Hz Gain -2.8 dB Q 1.80
                Filter 3: ON PK Fc 4800.0 Hz Gain -2.0 dB Q 2.00
                Filter 4: ON HSC Fc 9500.0 Hz Gain -1.8 dB Q 0.70
            """.trimIndent()
            "pixel buds pro" in lower -> """
                Preamp: -4.20 dB
                Filter 1: ON LSC Fc 95.0 Hz Gain -2.5 dB Q 0.70
                Filter 2: ON PK Fc 450.0 Hz Gain 1.8 dB Q 1.30
                Filter 3: ON PK Fc 1850.0 Hz Gain 3.2 dB Q 1.60
                Filter 4: ON PK Fc 4200.0 Hz Gain -2.0 dB Q 2.00
            """.trimIndent()
            "m50x" in lower -> """
                Preamp: -4.50 dB
                Filter 1: ON LSC Fc 80.0 Hz Gain 2.0 dB Q 0.70
                Filter 2: ON PK Fc 150.0 Hz Gain -3.8 dB Q 1.40
                Filter 3: ON PK Fc 410.0 Hz Gain 2.6 dB Q 1.50
                Filter 4: ON PK Fc 2100.0 Hz Gain 3.1 dB Q 1.80
                Filter 5: ON PK Fc 4500.0 Hz Gain -3.2 dB Q 2.20
            """.trimIndent()
            "quietcomfort ultra" in lower -> """
                Preamp: -4.00 dB
                Filter 1: ON LSC Fc 110.0 Hz Gain -3.2 dB Q 0.70
                Filter 2: ON PK Fc 240.0 Hz Gain -2.5 dB Q 1.30
                Filter 3: ON PK Fc 1400.0 Hz Gain 2.2 dB Q 1.50
                Filter 4: ON PK Fc 3400.0 Hz Gain 2.5 dB Q 1.80
            """.trimIndent()
            else -> """
                Preamp: -4.00 dB
                Filter 1: ON LSC Fc 105.0 Hz Gain 2.5 dB Q 0.70
                Filter 2: ON PK Fc 220.0 Hz Gain -2.0 dB Q 1.20
                Filter 3: ON PK Fc 2800.0 Hz Gain 2.2 dB Q 1.80
                Filter 4: ON HSC Fc 9500.0 Hz Gain 1.2 dB Q 0.70
            """.trimIndent()
        }
    }
}
