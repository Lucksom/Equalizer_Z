package com.audioeq.audio

import android.content.Context
import com.audioeq.model.HeadphoneProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class PeqFilter(
    val type: String,
    val fc: Double,
    val gain: Double,
    val q: Double
)

object AutoEqManager {
    data class Flt(val type: String, val fc: Double, val gain: Double, val q: Double) {
        fun toPeqFilter() = PeqFilter(type, fc, gain, q)
    }

    fun taper(freqHz: Double): Double = AutoEq.taper(freqHz)

    fun peqToCorrection31WithTrim(
        filters: List<Flt>,
        fullRange: Boolean = false
    ): Pair<List<Float>, Float> = AutoEq.peqToCorrection31WithTrim(filters, fullRange)

    fun parseParametricEq(text: String): ParsedPeq = AutoEq.parseParametricEq(text)
    fun parseGraphic(text: String) = AutoEq.parseGraphic(text)
    fun fromBuiltInProfile(profile: HeadphoneProfile) = AutoEq.fromBuiltInProfile(profile)
}

typealias Flt = AutoEqManager.Flt

data class ParsedPeq(val filters: List<PeqFilter>, val preamp: Double = 0.0) : List<PeqFilter> by filters

data class IndexEntry(
    val name: String,
    val relPath: String,
    val source: String?,
    val rig: String?
)

data class AutoEqFetchResult(
    val entries: List<IndexEntry>,
    val fromCache: Boolean,
    val isRateLimited: Boolean = false,
    val errorMessage: String? = null
)

object AutoEq {

    private val INDEX_LINE = Regex(
        """^\s*[-*]\s+\[([^\]]+)]\(([^)]+)\)(?:\s+by\s+([^()\n]+?))?(?:\s+on\s+([^\n]+))?\s*$"""
    )

    fun parseIndexMd(md: String): List<IndexEntry> =
        md.lineSequence().mapNotNull { line ->
            val m = INDEX_LINE.matchEntire(line) ?: return@mapNotNull null
            IndexEntry(
                name = m.groupValues[1].trim(),
                relPath = m.groupValues[2].trim().removePrefix("./"),
                source = m.groupValues[3].trim().ifEmpty { null },
                rig = m.groupValues[4].trim().ifEmpty { null }
            )
        }.toList()

    private val PEQ_LINE = Regex(
        """^Filter\s+\d+:\s+ON\s+(PK|PEQ|LSC|LS|HSC|HS)\s+Fc\s+([\d.]+)\s*Hz\s+Gain\s+([+-]?[\d.]+)\s*dB(?:\s+Q\s+([\d.]+))?""",
        RegexOption.IGNORE_CASE
    )

    fun parsePeq(text: String): ParsedPeq {
        val preampRegex = Regex("""Preamp:\s*([+-]?[\d.]+)\s*dB""", RegexOption.IGNORE_CASE)
        val preamp = preampRegex.find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val filters = text.lineSequence().mapNotNull { raw ->
            val m = PEQ_LINE.find(raw.trim()) ?: return@mapNotNull null
            val rawType = m.groupValues[1].uppercase(Locale.US)
            val normType = when (rawType) {
                "PEQ" -> "PK"
                "LS" -> "LSC"
                "HS" -> "HSC"
                else -> rawType
            }
            val fc = m.groupValues[2].toDouble()
            val gain = m.groupValues[3].toDouble()
            val q = m.groupValues[4].ifEmpty { "0.7071" }.toDouble()
            PeqFilter(normType, fc, gain, q)
        }.toList()
        return ParsedPeq(filters, preamp)
    }

    fun parseParametricEq(text: String): ParsedPeq = parsePeq(text)

    fun parseGraphic(text: String): List<Pair<Double, Double>> = parseGraphicEq(text)

    fun fromBuiltInProfile(profile: HeadphoneProfile): List<PeqFilter> = profile.peqFilters

    fun parseGraphicEq(text: String): List<Pair<Double, Double>> {
        val body = text.substringAfter("GraphicEQ:", missingDelimiterValue = "")
        if (body.isBlank()) return emptyList()
        return body.split(';')
            .mapNotNull { pair ->
                val parts = pair.trim().split(Regex("\\s+"))
                if (parts.size >= 2) {
                    val f = parts[0].toDoubleOrNull()
                    val g = parts[1].toDoubleOrNull()
                    if (f != null && g != null) f to g else null
                } else {
                    null
                }
            }
            .sortedBy { it.first }
    }

    fun biquadDb(
        type: String,
        fc: Double,
        gain: Double,
        q: Double,
        f: Double,
        fs: Double = 44100.0
    ): Double {
        if (abs(gain) < 1e-9 || q <= 0.0 || fc <= 0.0 || fc >= fs * 0.499) return 0.0
        val a = 10.0.pow(gain / 40.0)
        val w0 = 2.0 * PI * fc / fs
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0 * q)

        val b0: Double
        val b1: Double
        val b2: Double
        val a0: Double
        val a1: Double
        val a2: Double

        when (type.uppercase(Locale.US)) {
            "PK", "PEQ" -> {
                b0 = 1.0 + alpha * a
                b1 = -2.0 * cosW0
                b2 = 1.0 - alpha * a
                a0 = 1.0 + alpha / a
                a1 = -2.0 * cosW0
                a2 = 1.0 - alpha / a
            }
            "LSC", "LS" -> {
                val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
                b0 = a * ((a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha)
                b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)
                b2 = a * ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha)
                a0 = (a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha
                a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW0)
                a2 = (a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha
            }
            "HSC", "HS" -> {
                val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
                b0 = a * ((a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha)
                b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)
                b2 = a * ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha)
                a0 = (a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha
                a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW0)
                a2 = (a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha
            }
            else -> return 0.0
        }

        val w = 2.0 * PI * f / fs
        val cw = cos(w)
        val sw = sin(w)
        val c2w = cos(2.0 * w)
        val s2w = sin(2.0 * w)
        val nr = (b0 + b1 * cw + b2 * c2w) / a0
        val ni = (-b1 * sw - b2 * s2w) / a0
        val dr = 1.0 + (a1 / a0) * cw + (a2 / a0) * c2w
        val di = -(a1 / a0) * sw - (a2 / a0) * s2w
        val mag2 = (nr * nr + ni * ni) / (dr * dr + di * di)
        return 10.0 * log10(max(mag2, 1e-20))
    }

    fun taper(f: Double): Double = when {
        f <= 10000.0 -> 1.0
        f >= 14000.0 -> 0.0
        else -> 1.0 - ln(f / 10000.0) / ln(14000.0 / 10000.0)
    }

    fun peqToCorrection31(
        filters: List<PeqFilter>,
        fDp: DoubleArray = Dsp.F_DP,
        maxBoostDb: Double = 6.0,
        maxCutDb: Double = -12.0
    ): DoubleArray {
        val raw = DoubleArray(fDp.size) { k ->
            val f = fDp[k]
            filters.sumOf { biquadDb(it.type, it.fc, it.gain, it.q, f) }
        }
        return DoubleArray(fDp.size) { k ->
            (raw[k] * taper(fDp[k])).coerceIn(maxCutDb, maxBoostDb)
        }
    }

    fun peqRawUnclamped31(
        filters: List<PeqFilter>,
        fDp: DoubleArray = Dsp.F_DP
    ): DoubleArray {
        val raw = DoubleArray(fDp.size) { k ->
            val f = fDp[k]
            filters.sumOf { biquadDb(it.type, it.fc, it.gain, it.q, f) }
        }
        return DoubleArray(fDp.size) { k ->
            raw[k] * taper(fDp[k])
        }
    }

    fun graphicToCorrection31(
        pts: List<Pair<Double, Double>>,
        fDp: DoubleArray = Dsp.F_DP,
        maxBoostDb: Double = 6.0,
        maxCutDb: Double = -12.0
    ): DoubleArray {
        require(pts.size >= 2)
        val xs = DoubleArray(pts.size) { ln(pts[it].first) }
        val ys = DoubleArray(pts.size) { pts[it].second }
        val raw = DoubleArray(fDp.size) { k ->
            val x = ln(fDp[k])
            when {
                x <= xs.first() -> ys.first()
                x >= xs.last() -> ys.last()
                else -> {
                    var idx = xs.binarySearch(x)
                    if (idx < 0) idx = -idx - 2
                    val i = idx.coerceIn(0, xs.size - 2)
                    val t = (x - xs[i]) / (xs[i + 1] - xs[i])
                    ys[i] + t * (ys[i + 1] - ys[i])
                }
            }
        }
        val midMean = (11..19).map { raw[it] }.average()
        return DoubleArray(fDp.size) { k ->
            ((raw[k] - midMean) * taper(fDp[k])).coerceIn(maxCutDb, maxBoostDb)
        }
    }

    fun peqToCorrection31(
        filters: List<PeqFilter>,
        strength: Double = 1.0,
        sampleRate: Double = 44100.0,
        fullRange: Boolean = false,
        tiltBassDb: Double = 0.0,
        tiltTrebleDb: Double = 0.0
    ): DoubleArray {
        val maxBoost = if (fullRange) 12.0 else 6.0
        val base = peqToCorrection31(filters, maxBoostDb = maxBoost)
        val tilt = computeTiltCurve31(tiltBassDb, tiltTrebleDb)
        val s = strength.coerceIn(0.0, 1.0)
        return DoubleArray(base.size) { (base[it] * s + tilt[it]).coerceIn(-15.0, 15.0) }
    }

    fun graphicToCorrection31(
        pts: List<Pair<Double, Double>>,
        strength: Double = 1.0,
        fullRange: Boolean = false,
        tiltBassDb: Double = 0.0,
        tiltTrebleDb: Double = 0.0
    ): DoubleArray {
        val maxBoost = if (fullRange) 12.0 else 6.0
        val base = graphicToCorrection31(pts, maxBoostDb = maxBoost)
        val tilt = computeTiltCurve31(tiltBassDb, tiltTrebleDb)
        val s = strength.coerceIn(0.0, 1.0)
        return DoubleArray(base.size) { (base[it] * s + tilt[it]).coerceIn(-15.0, 15.0) }
    }

    fun peqToCorrection31WithTrim(filters: List<Flt>, fullRange: Boolean = false): Pair<List<Float>, Float> {
        val peqFilters = filters.map { it.toPeqFilter() }
        val maxBoost = if (fullRange) 12.0 else 6.0
        val corr = peqToCorrection31(peqFilters, maxBoostDb = maxBoost).map { it.toFloat() }
        val trim = computeTrimmedDb(peqFilters, fullRange).toFloat()
        return corr to trim
    }

    fun computePeqTrimmedDb(
        filters: List<PeqFilter>,
        sampleRate: Double = 44100.0,
        fullRange: Boolean = false,
        tiltBassDb: Double = 0.0,
        tiltTrebleDb: Double = 0.0
    ): Double = computeTrimmedDb(filters, fullRange)

    fun computeTrimmedDb(
        filters: List<PeqFilter>,
        fullRange: Boolean = false
    ): Double {
        if (filters.isEmpty()) return 0.0
        val maxBoost = if (fullRange) 10.0 else 6.0
        val raw = peqRawUnclamped31(filters)
        val limited = peqToCorrection31(filters, maxBoostDb = maxBoost)
        val maxRaw = raw.maxOrNull() ?: 0.0
        val maxLim = limited.maxOrNull() ?: 0.0
        return max(0.0, maxRaw - maxLim)
    }

    fun computeTiltCurve31(
        bassDb: Double,
        trebleDb: Double,
        fDp: DoubleArray = Dsp.F_DP
    ): DoubleArray {
        val bClamped = bassDb.coerceIn(-6.0, 6.0)
        val tClamped = trebleDb.coerceIn(-4.0, 4.0)
        if (abs(bClamped) < 1e-6 && abs(tClamped) < 1e-6) return DoubleArray(fDp.size)
        return DoubleArray(fDp.size) { k ->
            val f = fDp[k]
            val b = biquadDb("LSC", 105.0, bClamped, 0.7, f)
            val t = biquadDb("HSC", 6000.0, tClamped, 0.7, f)
            b + t
        }
    }

    fun mapCorrection31ToNativeBands(
        correction31Db: DoubleArray,
        nativeBandsHz: List<Pair<Double, Double>>,
        strength: Double = 0.8
    ): IntArray {
        val s = strength.coerceIn(0.0, 1.0)
        return IntArray(nativeBandsHz.size) { b ->
            val (fLo, fHi) = nativeBandsHz[b]
            val covered = Dsp.F_DP.indices.filter { k ->
                Dsp.F_DP[k] >= fLo && Dsp.F_DP[k] <= fHi
            }
            val meanDb = if (covered.isNotEmpty()) {
                covered.map { correction31Db[it] * s }.average()
            } else {
                val fCenter = sqrt(max(20.0, fLo) * max(20.0, fHi))
                val nearestIdx = Dsp.F_DP.indices.minByOrNull { abs(ln(Dsp.F_DP[it]) - ln(fCenter)) } ?: 0
                correction31Db[nearestIdx] * s
            }
            val clampedDb = meanDb.coerceIn(-12.0, 4.0)
            (clampedDb * 100.0).toInt()
        }
    }

    fun exportToParametricEqText(
        profileName: String,
        preampDb: Double,
        filters: List<PeqFilter>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("# AutoEq / Equalizer APO ParametricEQ export — $profileName")
        sb.appendLine(String.format(Locale.US, "Preamp: %.1f dB", preampDb))
        filters.forEachIndexed { idx, f ->
            sb.appendLine(
                String.format(
                    Locale.US,
                    "Filter %d: ON %s Fc %.1f Hz Gain %+.1f dB Q %.2f",
                    idx + 1,
                    f.type,
                    f.fc,
                    f.gain,
                    f.q
                )
            )
        }
        return sb.toString()
    }

    fun export31ToGraphicEqText(curve31Db: DoubleArray): String {
        val pairs = Dsp.F_DP.indices.joinToString("; ") { k ->
            val f = Dsp.F_DP[k]
            val fStr = if (f == f.toLong().toDouble()) f.toLong().toString() else String.format(Locale.US, "%.1f", f)
            String.format(Locale.US, "%s %.2f", fStr, curve31Db[k])
        }
        return "GraphicEQ: $pairs"
    }

    private val NOISE_WORDS = setOf(
        "anc", "off", "on", "active", "passive", "wireless", "wired", "bluetooth", "edition"
    )

    fun normalizeTokens(name: String): Set<String> {
        return name.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s-]"), " ")
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 2 && it !in NOISE_WORDS }
            .toSet()
    }

    fun matchScore(btProductName: String, candidateModelName: String): Double {
        val a = normalizeTokens(btProductName)
        val b = normalizeTokens(candidateModelName)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.intersect(b).size.toDouble()
        val union = a.union(b).size.toDouble()
        return if (union > 0.0) inter / union else 0.0
    }
}

class AutoEqRepo(private val context: Context) {

    companion object {
        const val COMMIT_OR_BRANCH = "master"
        const val BASE_RAW_URL = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/$COMMIT_OR_BRANCH/results"
        const val INDEX_URL = "$BASE_RAW_URL/INDEX.md"
        const val MAX_PROFILE_BYTES = 64 * 1024
        const val MAX_INDEX_BYTES = 8 * 1024 * 1024
        const val INDEX_TTL_MS = 24L * 60L * 60L * 1000L
        const val RATE_LIMIT_BACKOFF_MS = 60L * 60L * 1000L

        val STARTER_ENTRIES: List<IndexEntry> = listOf(
            IndexEntry("Sennheiser HD 650", "oratory1990/over-ear/Sennheiser HD 650", "oratory1990", "HMS II.3"),
            IndexEntry("Sennheiser HD 600", "oratory1990/over-ear/Sennheiser HD 600", "oratory1990", "HMS II.3"),
            IndexEntry("WH-1000XM5", "oratory1990/over-ear/WH-1000XM5", "oratory1990", "HMS II.3"),
            IndexEntry("WF-1000XM5", "oratory1990/in-ear/WF-1000XM5", "oratory1990", "HMS II.3"),
            IndexEntry("Apple AirPods Pro 2", "crinacle/711 in-ear/Apple AirPods Pro 2", "crinacle", "711"),
            IndexEntry("Apple AirPods Max", "oratory1990/over-ear/Apple AirPods Max", "oratory1990", "HMS II.3"),
            IndexEntry("Samsung Galaxy Buds2 Pro", "crinacle/711 in-ear/Samsung Galaxy Buds2 Pro", "crinacle", "711"),
            IndexEntry("Beyerdynamic DT 990 Pro", "oratory1990/over-ear/Beyerdynamic DT 990 Pro (250 Ohm)", "oratory1990", "HMS II.3"),
            IndexEntry("Beyerdynamic DT 770 Pro (80 Ohm)", "oratory1990/over-ear/Beyerdynamic DT 770 Pro (80 Ohm)", "oratory1990", "HMS II.3"),
            IndexEntry("HIFIMAN Sundara", "oratory1990/over-ear/HIFIMAN Sundara (2020 revised earpads)", "oratory1990", "HMS II.3"),
            IndexEntry("HIFIMAN Edition XS", "oratory1990/over-ear/HIFIMAN Edition XS", "oratory1990", "HMS II.3"),
            IndexEntry("Audio-Technica ATH-M50x", "oratory1990/over-ear/Audio-Technica ATH-M50x", "oratory1990", "HMS II.3"),
            IndexEntry("Moondrop Blessing 3", "crinacle/711 in-ear/Moondrop Blessing 3", "crinacle", "711"),
            IndexEntry("Truthear HEXA", "crinacle/711 in-ear/Truthear HEXA", "crinacle", "711"),
            IndexEntry("AKG K371", "oratory1990/over-ear/AKG K371", "oratory1990", "HMS II.3"),
            IndexEntry("QuietComfort Ultra", "rtings/over-ear/QuietComfort Ultra Headphones", "rtings", "HMS II.3")
        )
    }

    private val cacheDir: File by lazy {
        File(context.filesDir, "autoeq_cache").apply { mkdirs() }
    }
    private val indexCacheFile: File get() = File(cacheDir, "index_entries.json")
    private val metaPrefs by lazy {
        context.getSharedPreferences("autoeq_repo_meta", Context.MODE_PRIVATE)
    }

    private fun isAllowedUrl(url: String): Boolean {
        return url.startsWith("https://raw.githubusercontent.com/jaakkopasanen/AutoEq/") ||
            url.startsWith("https://api.github.com/repos/jaakkopasanen/AutoEq/")
    }

    suspend fun loadIndex(forceRefresh: Boolean = false): AutoEqFetchResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val backoffUntil = metaPrefs.getLong("rate_limit_until_ms", 0L)
        val lastFetch = metaPrefs.getLong("index_last_fetch_ms", 0L)
        val cachedEntries = readCachedIndex()

        if (!forceRefresh && cachedEntries.isNotEmpty() && (now - lastFetch) < INDEX_TTL_MS) {
            return@withContext AutoEqFetchResult(entries = cachedEntries, fromCache = true)
        }
        if (now < backoffUntil && cachedEntries.isNotEmpty()) {
            return@withContext AutoEqFetchResult(
                entries = cachedEntries,
                fromCache = true,
                isRateLimited = true
            )
        }

        try {
            if (!isAllowedUrl(INDEX_URL)) {
                return@withContext AutoEqFetchResult(
                    entries = cachedEntries.ifEmpty { STARTER_ENTRIES },
                    fromCache = true
                )
            }
            val conn = (URL(INDEX_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 8000
                setRequestProperty("User-Agent", "AudioEQ-Android/5.0")
                val etag = metaPrefs.getString("index_etag", null)
                if (!etag.isNullOrBlank() && cachedEntries.isNotEmpty()) {
                    setRequestProperty("If-None-Match", etag)
                }
            }
            val code = conn.responseCode
            when (code) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    metaPrefs.edit().putLong("index_last_fetch_ms", now).apply()
                    return@withContext AutoEqFetchResult(entries = cachedEntries, fromCache = true)
                }
                403, 429 -> {
                    metaPrefs.edit().putLong("rate_limit_until_ms", now + RATE_LIMIT_BACKOFF_MS).apply()
                    return@withContext AutoEqFetchResult(
                        entries = cachedEntries.ifEmpty { STARTER_ENTRIES },
                        fromCache = true,
                        isRateLimited = true
                    )
                }
                HttpURLConnection.HTTP_OK -> {
                    val bytes = conn.inputStream.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val r = input.read(buf)
                            if (r <= 0) break
                            total += r
                            if (total > MAX_INDEX_BYTES) break
                            out.write(buf, 0, r)
                        }
                        out.toByteArray()
                    }
                    val mdText = bytes.toString(Charsets.UTF_8)
                    val parsed = AutoEq.parseIndexMd(mdText)
                    if (parsed.isNotEmpty()) {
                        writeCachedIndex(parsed)
                        val newEtag = conn.getHeaderField("ETag")
                        metaPrefs.edit()
                            .putLong("index_last_fetch_ms", now)
                            .putString("index_etag", newEtag)
                            .apply()
                        return@withContext AutoEqFetchResult(entries = parsed, fromCache = false)
                    }
                }
            }
        } catch (_: Exception) {
        }

        val fallback = cachedEntries.ifEmpty { STARTER_ENTRIES }
        AutoEqFetchResult(entries = fallback, fromCache = true)
    }

    suspend fun fetchProfileForEntry(entry: IndexEntry): Pair<List<PeqFilter>, List<Pair<Double, Double>>> =
        withContext(Dispatchers.IO) {
            val safeKey = entry.relPath.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val cachedPeqFile = File(cacheDir, "${safeKey}_peq.txt")
            if (cachedPeqFile.exists()) {
                val txt = cachedPeqFile.readText()
                val peq = AutoEq.parsePeq(txt)
                if (peq.isNotEmpty()) return@withContext peq to emptyList()
                val geq = AutoEq.parseGraphicEq(txt)
                if (geq.isNotEmpty()) return@withContext emptyList<PeqFilter>() to geq
            }

            val encodedPath = entry.relPath.split("/").joinToString("/") { segment ->
                java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
            val encodedName = java.net.URLEncoder.encode(entry.name, "UTF-8").replace("+", "%20")

            val candidateSuffixes = listOf(
                "$encodedPath/$encodedName%20ParametricEQ.txt",
                "$encodedPath/$encodedName%20FixedBandEQ.txt",
                "$encodedPath/$encodedName%20GraphicEQ.txt"
            )

            for (suffix in candidateSuffixes) {
                val url = "$BASE_RAW_URL/$suffix"
                if (!isAllowedUrl(url)) continue
                try {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 5000
                        readTimeout = 6000
                        setRequestProperty("User-Agent", "AudioEQ-Android/5.0")
                    }
                    if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                        val bytes = conn.inputStream.use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val buf = ByteArray(4096)
                            var total = 0
                            while (true) {
                                val r = input.read(buf)
                                if (r <= 0) break
                                total += r
                                if (total > MAX_PROFILE_BYTES) break
                                out.write(buf, 0, r)
                            }
                            out.toByteArray()
                        }
                        val text = bytes.toString(Charsets.UTF_8)
                        val peq = AutoEq.parsePeq(text)
                        if (peq.isNotEmpty()) {
                            cachedPeqFile.writeText(text)
                            return@withContext peq to emptyList()
                        }
                        val geq = AutoEq.parseGraphicEq(text)
                        if (geq.isNotEmpty()) {
                            cachedPeqFile.writeText(text)
                            return@withContext emptyList<PeqFilter>() to geq
                        }
                    }
                } catch (_: Exception) {
                }
            }
            emptyList<PeqFilter>() to emptyList()
        }

    private fun readCachedIndex(): List<IndexEntry> {
        return try {
            if (!indexCacheFile.exists()) return emptyList()
            val arr = JSONArray(indexCacheFile.readText())
            val list = ArrayList<IndexEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    IndexEntry(
                        name = o.getString("name"),
                        relPath = o.getString("relPath"),
                        source = o.optString("source", "").ifEmpty { null },
                        rig = o.optString("rig", "").ifEmpty { null }
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeCachedIndex(entries: List<IndexEntry>) {
        try {
            val arr = JSONArray()
            entries.take(6500).forEach { e ->
                val o = JSONObject()
                o.put("name", e.name)
                o.put("relPath", e.relPath)
                if (e.source != null) o.put("source", e.source)
                if (e.rig != null) o.put("rig", e.rig)
                arr.put(o)
            }
            indexCacheFile.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }
}
