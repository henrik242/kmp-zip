package no.synth.kmpzlib

// Ported from pako (src/zlib/trees.mjs), itself a port of zlib's trees.c.
// Trees are stored as IntArray pairs: [freq or code, dad or len] per symbol.

private const val STORED_BLOCK = 0
private const val STATIC_TREES = 1
private const val DYN_TREES = 2

internal const val LENGTH_CODES = 29
internal const val LITERALS = 256
internal const val L_CODES = LITERALS + 1 + LENGTH_CODES
internal const val D_CODES = 30
internal const val BL_CODES = 19
internal const val HEAP_SIZE = 2 * L_CODES + 1
internal const val MAX_BITS = 15
private const val BUF_SIZE = 16
private const val MAX_BL_BITS = 7
private const val END_BLOCK = 256
private const val REP_3_6 = 16
private const val REPZ_3_10 = 17
private const val REPZ_11_138 = 18

private val EXTRA_LBITS = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
private val EXTRA_DBITS = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
private val EXTRA_BLBITS = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 3, 7)
private val BL_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

internal class StaticTreeDesc(
    val staticTree: IntArray?,
    val extraBits: IntArray,
    val extraBase: Int,
    val elems: Int,
    val maxLength: Int,
)

internal class TreeDesc(val dynTree: IntArray, val statDesc: StaticTreeDesc) {
    var maxCode = 0
}

private object StaticTrees {
    val ltree = IntArray((L_CODES + 2) * 2)
    val dtree = IntArray(D_CODES * 2)
    val distCode = IntArray(512)
    val lengthCode = IntArray(256)
    val baseLength = IntArray(LENGTH_CODES)
    val baseDist = IntArray(D_CODES)
    val lDesc: StaticTreeDesc
    val dDesc: StaticTreeDesc
    val blDesc: StaticTreeDesc

    init {
        var length = 0
        var code = 0
        while (code < LENGTH_CODES - 1) {
            baseLength[code] = length
            for (n in 0 until (1 shl EXTRA_LBITS[code])) lengthCode[length++] = code
            code++
        }
        // Length 258 gets its own code (28) rather than 284 + 31 extra bits.
        lengthCode[length - 1] = code

        var dist = 0
        code = 0
        while (code < 16) {
            baseDist[code] = dist
            for (n in 0 until (1 shl EXTRA_DBITS[code])) distCode[dist++] = code
            code++
        }
        dist = dist shr 7
        while (code < D_CODES) {
            baseDist[code] = dist shl 7
            for (n in 0 until (1 shl (EXTRA_DBITS[code] - 7))) distCode[256 + dist++] = code
            code++
        }

        val blCount = IntArray(MAX_BITS + 1)
        var n = 0
        while (n <= 143) { ltree[n * 2 + 1] = 8; n++; blCount[8]++ }
        while (n <= 255) { ltree[n * 2 + 1] = 9; n++; blCount[9]++ }
        while (n <= 279) { ltree[n * 2 + 1] = 7; n++; blCount[7]++ }
        while (n <= 287) { ltree[n * 2 + 1] = 8; n++; blCount[8]++ }
        genCodes(ltree, L_CODES + 1, blCount)

        for (i in 0 until D_CODES) {
            dtree[i * 2 + 1] = 5
            dtree[i * 2] = biReverse(i, 5)
        }

        lDesc = StaticTreeDesc(ltree, EXTRA_LBITS, LITERALS + 1, L_CODES, MAX_BITS)
        dDesc = StaticTreeDesc(dtree, EXTRA_DBITS, 0, D_CODES, MAX_BITS)
        blDesc = StaticTreeDesc(null, EXTRA_BLBITS, 0, BL_CODES, MAX_BL_BITS)
    }
}

private fun dCode(dist: Int): Int =
    if (dist < 256) StaticTrees.distCode[dist] else StaticTrees.distCode[256 + (dist ushr 7)]

private fun DeflateState.putShort(w: Int) {
    pendingBuf[pending++] = w.toByte()
    pendingBuf[pending++] = (w ushr 8).toByte()
}

private fun DeflateState.sendBits(value: Int, length: Int) {
    if (biValid > BUF_SIZE - length) {
        biBuf = biBuf or ((value shl biValid) and 0xffff)
        putShort(biBuf)
        biBuf = value ushr (BUF_SIZE - biValid)
        biValid += length - BUF_SIZE
    } else {
        biBuf = biBuf or ((value shl biValid) and 0xffff)
        biValid += length
    }
}

private fun DeflateState.sendCode(c: Int, tree: IntArray) {
    sendBits(tree[c * 2], tree[c * 2 + 1])
}

private fun biReverse(code: Int, len: Int): Int {
    var res = 0
    var c = code
    var l = len
    do {
        res = res or (c and 1)
        c = c ushr 1
        res = res shl 1
    } while (--l > 0)
    return res ushr 1
}

private fun DeflateState.biFlush() {
    if (biValid == 16) {
        putShort(biBuf)
        biBuf = 0
        biValid = 0
    } else if (biValid >= 8) {
        pendingBuf[pending++] = biBuf.toByte()
        biBuf = biBuf shr 8
        biValid -= 8
    }
}

private fun DeflateState.genBitlen(desc: TreeDesc) {
    val tree = desc.dynTree
    val maxCode = desc.maxCode
    val stree = desc.statDesc.staticTree
    val extra = desc.statDesc.extraBits
    val base = desc.statDesc.extraBase
    val maxLength = desc.statDesc.maxLength
    var overflow = 0

    for (bits in 0..MAX_BITS) blCount[bits] = 0

    tree[heap[heapMax] * 2 + 1] = 0
    var h = heapMax + 1
    while (h < HEAP_SIZE) {
        val n = heap[h]
        var bits = tree[tree[n * 2 + 1] * 2 + 1] + 1
        if (bits > maxLength) {
            bits = maxLength
            overflow++
        }
        tree[n * 2 + 1] = bits
        h++
        if (n > maxCode) continue
        blCount[bits]++
        var xbits = 0
        if (n >= base) xbits = extra[n - base]
        val f = tree[n * 2]
        optLen += f * (bits + xbits)
        if (stree != null) staticLen += f * (stree[n * 2 + 1] + xbits)
    }
    if (overflow == 0) return

    do {
        var bits = maxLength - 1
        while (blCount[bits] == 0) bits--
        blCount[bits]--
        blCount[bits + 1] += 2
        blCount[maxLength]--
        overflow -= 2
    } while (overflow > 0)

    var bits = maxLength
    while (bits != 0) {
        var n = blCount[bits]
        while (n != 0) {
            val m = heap[--h]
            if (m > maxCode) continue
            if (tree[m * 2 + 1] != bits) {
                optLen += (bits - tree[m * 2 + 1]) * tree[m * 2]
                tree[m * 2 + 1] = bits
            }
            n--
        }
        bits--
    }
}

private fun genCodes(tree: IntArray, maxCode: Int, blCount: IntArray) {
    val nextCode = IntArray(MAX_BITS + 1)
    var code = 0
    for (bits in 1..MAX_BITS) {
        code = (code + blCount[bits - 1]) shl 1
        nextCode[bits] = code
    }
    for (n in 0..maxCode) {
        val len = tree[n * 2 + 1]
        if (len == 0) continue
        tree[n * 2] = biReverse(nextCode[len]++, len)
    }
}

private fun DeflateState.initBlock() {
    for (n in 0 until L_CODES) dynLtree[n * 2] = 0
    for (n in 0 until D_CODES) dynDtree[n * 2] = 0
    for (n in 0 until BL_CODES) blTree[n * 2] = 0
    dynLtree[END_BLOCK * 2] = 1
    optLen = 0
    staticLen = 0
    symNext = 0
}

private fun DeflateState.biWindup() {
    if (biValid > 8) {
        putShort(biBuf)
    } else if (biValid > 0) {
        pendingBuf[pending++] = biBuf.toByte()
    }
    biBuf = 0
    biValid = 0
}

private fun smaller(tree: IntArray, n: Int, m: Int, depth: IntArray): Boolean {
    val n2 = n * 2
    val m2 = m * 2
    return tree[n2] < tree[m2] || (tree[n2] == tree[m2] && depth[n] <= depth[m])
}

private fun DeflateState.pqdownheap(tree: IntArray, start: Int) {
    var k = start
    val v = heap[k]
    var j = k shl 1
    while (j <= heapLen) {
        if (j < heapLen && smaller(tree, heap[j + 1], heap[j], depth)) j++
        if (smaller(tree, v, heap[j], depth)) break
        heap[k] = heap[j]
        k = j
        j = j shl 1
    }
    heap[k] = v
}

private fun DeflateState.compressBlock(ltree: IntArray, dtree: IntArray) {
    var sx = 0
    if (symNext != 0) {
        do {
            var dist = pendingBuf.u8(symBuf + sx++)
            dist += pendingBuf.u8(symBuf + sx++) shl 8
            var lc = pendingBuf.u8(symBuf + sx++)
            if (dist == 0) {
                sendCode(lc, ltree)
            } else {
                var code = StaticTrees.lengthCode[lc]
                sendCode(code + LITERALS + 1, ltree)
                var extra = EXTRA_LBITS[code]
                if (extra != 0) {
                    lc -= StaticTrees.baseLength[code]
                    sendBits(lc, extra)
                }
                dist--
                code = dCode(dist)
                sendCode(code, dtree)
                extra = EXTRA_DBITS[code]
                if (extra != 0) {
                    dist -= StaticTrees.baseDist[code]
                    sendBits(dist, extra)
                }
            }
        } while (sx < symNext)
    }
    sendCode(END_BLOCK, ltree)
}

private fun DeflateState.buildTree(desc: TreeDesc) {
    val tree = desc.dynTree
    val stree = desc.statDesc.staticTree
    val elems = desc.statDesc.elems
    var maxCode = -1

    heapLen = 0
    heapMax = HEAP_SIZE

    for (n in 0 until elems) {
        if (tree[n * 2] != 0) {
            heap[++heapLen] = n
            maxCode = n
            depth[n] = 0
        } else {
            tree[n * 2 + 1] = 0
        }
    }

    // The pkzip format requires at least one distance code and at least two codes
    // of non-zero frequency, so force them if needed.
    while (heapLen < 2) {
        val node = if (maxCode < 2) ++maxCode else 0
        heap[++heapLen] = node
        tree[node * 2] = 1
        depth[node] = 0
        optLen--
        if (stree != null) staticLen -= stree[node * 2 + 1]
    }
    desc.maxCode = maxCode

    for (n in (heapLen shr 1) downTo 1) pqdownheap(tree, n)

    var node = elems
    do {
        val n = heap[1]
        heap[1] = heap[heapLen--]
        pqdownheap(tree, 1)
        val m = heap[1]

        heap[--heapMax] = n
        heap[--heapMax] = m

        tree[node * 2] = tree[n * 2] + tree[m * 2]
        depth[node] = (if (depth[n] >= depth[m]) depth[n] else depth[m]) + 1
        tree[n * 2 + 1] = node
        tree[m * 2 + 1] = node

        heap[1] = node++
        pqdownheap(tree, 1)
    } while (heapLen >= 2)

    heap[--heapMax] = heap[1]

    genBitlen(desc)
    genCodes(tree, maxCode, blCount)
}

private fun DeflateState.scanTree(tree: IntArray, maxCode: Int) {
    var prevlen = -1
    var nextlen = tree[1]
    var count = 0
    var maxCount = 7
    var minCount = 4

    if (nextlen == 0) {
        maxCount = 138
        minCount = 3
    }
    tree[(maxCode + 1) * 2 + 1] = 0xffff // guard

    for (n in 0..maxCode) {
        val curlen = nextlen
        nextlen = tree[(n + 1) * 2 + 1]
        if (++count < maxCount && curlen == nextlen) {
            continue
        } else if (count < minCount) {
            blTree[curlen * 2] += count
        } else if (curlen != 0) {
            if (curlen != prevlen) blTree[curlen * 2]++
            blTree[REP_3_6 * 2]++
        } else if (count <= 10) {
            blTree[REPZ_3_10 * 2]++
        } else {
            blTree[REPZ_11_138 * 2]++
        }
        count = 0
        prevlen = curlen
        if (nextlen == 0) {
            maxCount = 138
            minCount = 3
        } else if (curlen == nextlen) {
            maxCount = 6
            minCount = 3
        } else {
            maxCount = 7
            minCount = 4
        }
    }
}

private fun DeflateState.sendTree(tree: IntArray, maxCode: Int) {
    var prevlen = -1
    var nextlen = tree[1]
    var count = 0
    var maxCount = 7
    var minCount = 4

    if (nextlen == 0) {
        maxCount = 138
        minCount = 3
    }

    for (n in 0..maxCode) {
        val curlen = nextlen
        nextlen = tree[(n + 1) * 2 + 1]
        if (++count < maxCount && curlen == nextlen) {
            continue
        } else if (count < minCount) {
            do {
                sendCode(curlen, blTree)
            } while (--count != 0)
        } else if (curlen != 0) {
            if (curlen != prevlen) {
                sendCode(curlen, blTree)
                count--
            }
            sendCode(REP_3_6, blTree)
            sendBits(count - 3, 2)
        } else if (count <= 10) {
            sendCode(REPZ_3_10, blTree)
            sendBits(count - 3, 3)
        } else {
            sendCode(REPZ_11_138, blTree)
            sendBits(count - 11, 7)
        }
        count = 0
        prevlen = curlen
        if (nextlen == 0) {
            maxCount = 138
            minCount = 3
        } else if (curlen == nextlen) {
            maxCount = 6
            minCount = 3
        } else {
            maxCount = 7
            minCount = 4
        }
    }
}

private fun DeflateState.buildBlTree(): Int {
    scanTree(dynLtree, lDesc.maxCode)
    scanTree(dynDtree, dDesc.maxCode)
    buildTree(blDesc)

    var maxBlindex = BL_CODES - 1
    while (maxBlindex >= 3) {
        if (blTree[BL_ORDER[maxBlindex] * 2 + 1] != 0) break
        maxBlindex--
    }
    optLen += 3 * (maxBlindex + 1) + 5 + 5 + 4
    return maxBlindex
}

private fun DeflateState.sendAllTrees(lcodes: Int, dcodes: Int, blcodes: Int) {
    sendBits(lcodes - 257, 5)
    sendBits(dcodes - 1, 5)
    sendBits(blcodes - 4, 4)
    for (rank in 0 until blcodes) sendBits(blTree[BL_ORDER[rank] * 2 + 1], 3)
    sendTree(dynLtree, lcodes - 1)
    sendTree(dynDtree, dcodes - 1)
}

internal fun DeflateState.trInit() {
    lDesc = TreeDesc(dynLtree, StaticTrees.lDesc)
    dDesc = TreeDesc(dynDtree, StaticTrees.dDesc)
    blDesc = TreeDesc(blTree, StaticTrees.blDesc)
    biBuf = 0
    biValid = 0
    initBlock()
}

internal fun DeflateState.trStoredBlock(buf: Int, storedLen: Int, last: Boolean) {
    sendBits((STORED_BLOCK shl 1) + (if (last) 1 else 0), 3)
    biWindup()
    putShort(storedLen)
    putShort(storedLen.inv())
    if (storedLen != 0) window.copyInto(pendingBuf, pending, buf, buf + storedLen)
    pending += storedLen
}

/** [buf] is the window offset of the block's input, or -1 if it's no longer there. */
internal fun DeflateState.trFlushBlock(buf: Int, storedLen: Int, last: Boolean) {
    var optLenb: Int
    val staticLenb: Int
    var maxBlindex = 0

    if (level > 0) {
        buildTree(lDesc)
        buildTree(dDesc)
        maxBlindex = buildBlTree()
        optLenb = (optLen + 3 + 7) ushr 3
        staticLenb = (staticLen + 3 + 7) ushr 3
        if (staticLenb <= optLenb) optLenb = staticLenb
    } else {
        optLenb = storedLen + 5
        staticLenb = optLenb
    }

    if (storedLen + 4 <= optLenb && buf != -1) {
        trStoredBlock(buf, storedLen, last)
    } else if (staticLenb == optLenb) {
        sendBits((STATIC_TREES shl 1) + (if (last) 1 else 0), 3)
        compressBlock(StaticTrees.ltree, StaticTrees.dtree)
    } else {
        sendBits((DYN_TREES shl 1) + (if (last) 1 else 0), 3)
        sendAllTrees(lDesc.maxCode + 1, dDesc.maxCode + 1, maxBlindex + 1)
        compressBlock(dynLtree, dynDtree)
    }
    initBlock()
    if (last) biWindup()
}

/** Records a literal ([dist] == 0) or a match; returns true when the block buffer is full. */
internal fun DeflateState.trTally(dist: Int, lc: Int): Boolean {
    pendingBuf[symBuf + symNext++] = dist.toByte()
    pendingBuf[symBuf + symNext++] = (dist shr 8).toByte()
    pendingBuf[symBuf + symNext++] = lc.toByte()
    if (dist == 0) {
        dynLtree[lc * 2]++
    } else {
        dynLtree[(StaticTrees.lengthCode[lc] + LITERALS + 1) * 2]++
        dynDtree[dCode(dist - 1) * 2]++
    }
    return symNext == symEnd
}
