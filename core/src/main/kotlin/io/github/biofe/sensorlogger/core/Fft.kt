package io.github.biofe.sensorlogger.core

/** In-place iterative radix-2 FFT. [re] and [im] must have the same power-of-two length. */
internal fun fft(re: DoubleArray, im: DoubleArray) {
    val n = re.size
    require(n == im.size && n > 0 && n and (n - 1) == 0) { "FFT size must be a power of two" }

    // Bit-reversal permutation
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j xor bit
        if (i < j) {
            var t = re[i]; re[i] = re[j]; re[j] = t
            t = im[i]; im[i] = im[j]; im[j] = t
        }
    }

    var len = 2
    while (len <= n) {
        val ang = -2.0 * Math.PI / len
        val wRe = Math.cos(ang)
        val wIm = Math.sin(ang)
        var start = 0
        while (start < n) {
            var curRe = 1.0
            var curIm = 0.0
            for (k in 0 until len / 2) {
                val a = start + k
                val b = a + len / 2
                val tRe = re[b] * curRe - im[b] * curIm
                val tIm = re[b] * curIm + im[b] * curRe
                re[b] = re[a] - tRe
                im[b] = im[a] - tIm
                re[a] += tRe
                im[a] += tIm
                val nextRe = curRe * wRe - curIm * wIm
                curIm = curRe * wIm + curIm * wRe
                curRe = nextRe
            }
            start += len
        }
        len = len shl 1
    }
}
