package eu.kanade.tachiyomi.util.lang

import kotlin.math.floor

/**
 * Replaces the given string to have at most [count] characters using [replacement] at its end.
 * If [replacement] is longer than [count] an exception will be thrown when `length > count`.
 */
fun String.chop(
    count: Int,
    replacement: String = "…",
): String =
    if (length > count) {
        take(count - replacement.length) + replacement
    } else {
        this
    }

/**
 * Replaces the given string to have at most [count] characters using [replacement] near the center.
 * If [replacement] is longer than [count] an exception will be thrown when `length > count`.
 */
fun String.truncateCenter(
    count: Int,
    replacement: String = "...",
): String {
    if (length <= count) {
        return this
    }

    val pieceLength: Int = floor((count - replacement.length).div(2.0)).toInt()

    return "${take(pieceLength)}$replacement${takeLast(pieceLength)}"
}

/**
 * Case-insensitive natural comparator for strings.
 *
 * Compares strings naturally so that e.g. "Chapter 2" < "Chapter 10".
 */
fun String.compareToCaseInsensitiveNaturalOrder(other: String): Int {
    val a = this.lowercase()
    val b = other.lowercase()
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            // Skip leading zeros and compare numeric segments by value
            var ai = i
            var bj = j
            while (ai < a.length && a[ai] == '0') ai++
            while (bj < b.length && b[bj] == '0') bj++
            var ae = ai
            var be = bj
            while (ae < a.length && a[ae].isDigit()) ae++
            while (be < b.length && b[be].isDigit()) be++
            val lenA = ae - ai
            val lenB = be - bj
            if (lenA != lenB) return lenA - lenB
            for (k in 0 until lenA) {
                val diff = a[ai + k] - b[bj + k]
                if (diff != 0) return diff
            }
            i = ae
            j = be
        } else {
            if (ca != cb) return ca - cb
            i++
            j++
        }
    }
    return a.length - b.length
}

/**
 * Returns the size of the string as the number of bytes.
 */
fun String.byteSize(): Int = toByteArray(Charsets.UTF_8).size

/**
 * Returns a string containing the first [n] bytes from this string, or the entire string if this
 * string is shorter.
 */
fun String.takeBytes(n: Int): String {
    val bytes = toByteArray(Charsets.UTF_8)
    return if (bytes.size <= n) {
        this
    } else {
        bytes.decodeToString(endIndex = n).replace("\uFFFD", "")
    }
}
