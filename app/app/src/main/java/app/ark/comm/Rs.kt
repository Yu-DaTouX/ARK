// 文件职责：Reed-Solomon 编解码。调用方：AcousticModem（头部 nsym=4、正文每块 nsym=12），ModemSim。
// 约束：码字总长必须 ≤ 255（GF(256)），调用方负责保证；纠错超过 nsym/2 时可能“误纠”成另一个合法码字，所以上层还要再用 CRC16 检验。
package app.ark.comm

/**
 * Reed–Solomon（GF(256)，本原多项式 0x11d，生成多项式根 α^0..α^(nsym-1)）。纯 Kotlin，无 Android 依赖。
 * 每块最多纠正 nsym/2 个字节错误；系统码：码字 = 数据 + 校验。
 */
object Rs {
    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x; LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11d
        }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    // GF(256) 乘/除：查对数表；EXP 表复制一份到 512 项，免取模。
    private fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]
    private fun div(a: Int, b: Int): Int = if (a == 0) 0 else EXP[(LOG[a] + 255 - LOG[b]) % 255]

    private val gens = HashMap<Int, IntArray>()

    private fun gen(nsym: Int): IntArray = synchronized(gens) {
        gens.getOrPut(nsym) {
            var g = intArrayOf(1)
            for (i in 0 until nsym) {
                val r = IntArray(g.size + 1)
                for (j in g.indices) {
                    r[j] = r[j] xor g[j]
                    r[j + 1] = r[j + 1] xor mul(g[j], EXP[i])
                }
                g = r
            }
            g
        }
    }

    /** 返回 [data] 的 [nsym] 个校验字节（多项式长除法余式）。线程安全。 */
    fun parity(data: ByteArray, nsym: Int): ByteArray {
        val g = gen(nsym)
        val out = IntArray(data.size + nsym)
        for (i in data.indices) out[i] = data[i].toInt() and 0xFF
        for (i in data.indices) {
            val c = out[i]
            if (c != 0) for (j in 1 until g.size) out[i + j] = out[i + j] xor mul(g[j], c)
        }
        return ByteArray(nsym) { out[data.size + it].toByte() }
    }

    /**
     * 原地纠错 [cw]=数据+校验；成功返回纠正的字节数（写回 [cw]），无法纠正返回 -1（此时 [cw] 不被修改）。
     * 流程：算校验子 → Berlekamp-Massey 求错误定位多项式 → Chien 搜索定位 → Forney 求错误值 → 复查。
     */
    fun correct(cw: ByteArray, nsym: Int): Int {
        val n = cw.size
        val r = IntArray(n) { cw[it].toInt() and 0xFF }
        val s = IntArray(nsym)
        var any = false
        for (j in 0 until nsym) {
            var acc = 0
            val x = EXP[j]
            for (i in 0 until n) acc = mul(acc, x) xor r[i]
            s[j] = acc
            if (acc != 0) any = true
        }
        if (!any) return 0
        // Berlekamp–Massey（多项式按“低次在前”）
        var c = IntArray(nsym + 1).also { it[0] = 1 }
        var b = IntArray(nsym + 1).also { it[0] = 1 }
        var l = 0; var m = 1; var bb = 1
        for (i in 0 until nsym) {
            var d = s[i]
            for (k in 1..l) d = d xor mul(c[k], s[i - k])
            if (d == 0) { m++; continue }
            val t = c.copyOf()
            val coef = div(d, bb)
            for (k in 0 until nsym + 1 - m) c[k + m] = c[k + m] xor mul(coef, b[k])
            if (2 * l <= i) { l = i + 1 - l; b = t; bb = d; m = 1 } else m++
        }
        if (l * 2 > nsym) return -1
        // Chien 搜索：Λ(α^-p)=0 ⇒ 位置 p（次数），数组下标 n-1-p
        val pos = IntArray(l); var cnt = 0
        for (p in 0 until n) {
            val xinv = EXP[(255 - p % 255) % 255]
            var acc = 0
            for (k in l downTo 0) acc = mul(acc, xinv) xor c[k]
            if (acc == 0) { if (cnt >= l) return -1; pos[cnt++] = p }
        }
        if (cnt != l) return -1
        // Ω = S·Λ mod x^nsym
        val om = IntArray(nsym)
        for (i in 0 until nsym) { var a = 0; for (k in 0..minOf(i, l)) a = a xor mul(c[k], s[i - k]); om[i] = a }
        for (p in pos) {
            val xk = EXP[p % 255]
            val xinv = EXP[(255 - p % 255) % 255]
            var ov = 0
            for (k in nsym - 1 downTo 0) ov = mul(ov, xinv) xor om[k]
            var dv = 0 // Λ'(x) = Σ_{k 奇} c[k] x^(k-1)
            var k = 1
            while (k <= l) { var pw = 1; for (q in 0 until k - 1) pw = mul(pw, xinv); dv = dv xor mul(c[k], pw); k += 2 }
            if (dv == 0) return -1
            val mag = mul(xk, div(ov, dv))
            r[n - 1 - p] = r[n - 1 - p] xor mag
        }
        // 复查校验子
        for (j in 0 until nsym) {
            var acc = 0
            val x = EXP[j]
            for (i in 0 until n) acc = mul(acc, x) xor r[i]
            if (acc != 0) return -1
        }
        for (i in 0 until n) cw[i] = r[i].toByte()
        return l
    }
}
