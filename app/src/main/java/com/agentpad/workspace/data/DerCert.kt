package com.agentpad.workspace.data

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.interfaces.RSAPublicKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.security.auth.x500.X500Principal

/**
 * 极简 DER 编码器：用于生成 ADB 认证所需的自签名 X.509 v3 证书。
 *
 * 只实现 ADB 要求的字段，不引入 BouncyCastle 这类重依赖。
 * 结构遵循 RFC 5280。
 */
internal object DerCert {

    // ---------- TLV 基础 ----------

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = content.size
        when {
            len < 0x80 -> out.write(len)
            len < 0x100 -> { out.write(0x81); out.write(len) }
            len < 0x10000 -> { out.write(0x82); out.write(len shr 8); out.write(len and 0xFF) }
            else -> {
                out.write(0x83)
                out.write(len shr 16); out.write((len shr 8) and 0xFF); out.write(len and 0xFF)
            }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun seq(vararg items: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        items.forEach { out.write(it) }
        return tlv(0x30, out.toByteArray())
    }

    private fun set(vararg items: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        items.forEach { out.write(it) }
        return tlv(0x31, out.toByteArray())
    }

    private fun integer(v: BigInteger): ByteArray {
        var b = v.toByteArray()
        if (b.isNotEmpty() && b[0].toInt() and 0x80 != 0) {
            b = byteArrayOf(0) + b
        }
        return tlv(0x02, b)
    }

    private fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toInt() }
        val out = ByteArrayOutputStream()
        out.write(parts[0] * 40 + parts[1])
        for (i in 2 until parts.size) {
            var v = parts[i]
            val stack = ArrayList<Int>()
            stack.add(v and 0x7F)
            v = v shr 7
            while (v > 0) {
                stack.add((v and 0x7F) or 0x80)
                v = v shr 7
            }
            for (j in stack.indices.reversed()) out.write(stack[j])
        }
        return tlv(0x06, out.toByteArray())
    }

    private fun utf8(s: String): ByteArray = tlv(0x0C, s.toByteArray(Charsets.UTF_8))

    private fun nullValue(): ByteArray = byteArrayOf(0x05, 0x00)

    private fun bitString(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0) // unused bits
        out.write(data)
        return tlv(0x03, out.toByteArray())
    }

    private fun utcTime(d: Date): ByteArray {
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return tlv(0x17, fmt.format(d).toByteArray(Charsets.US_ASCII))
    }

    /** [0] EXPLICIT INTEGER version (v3 = 2) */
    private fun version3(): ByteArray =
        tlv(0xA0, integer(BigInteger.valueOf(2)))

    private fun algorithmSha256Rsa(): ByteArray =
        seq(oid("1.2.840.113549.1.1.11"), nullValue())

    private fun algorithmRsa(): ByteArray =
        seq(oid("1.2.840.113549.1.1.1"), nullValue())

    private fun name(cn: String): ByteArray =
        seq(set(seq(oid("2.5.4.3"), utf8(cn))))

    private fun validity(notBefore: Date, notAfter: Date): ByteArray =
        seq(utcTime(notBefore), utcTime(notAfter))

    private fun subjectPublicKeyInfo(pub: RSAPublicKey): ByteArray {
        val rsaKey = seq(integer(pub.modulus), integer(pub.publicExponent))
        return seq(algorithmRsa(), bitString(rsaKey))
    }

    // ---------- 组装 ----------

    /**
     * 构造 TBSCertificate。
     */
    fun build(
        subject: X500Principal,
        pub: RSAPublicKey,
        notBefore: Date,
        notAfter: Date,
        serial: BigInteger = BigInteger.valueOf(System.currentTimeMillis()),
    ): ByteArray {
        // 从 X500Principal 的编码中取 CN；失败则用固定名
        val cn = extractCn(subject) ?: "agentpad"
        val subjectName = name(cn)
        return seq(
            version3(),
            integer(serial),
            algorithmSha256Rsa(),
            subjectName,          // issuer = subject（自签名）
            validity(notBefore, notAfter),
            subjectName,          // subject
            subjectPublicKeyInfo(pub),
        )
    }

    /**
     * 用签名值把 TBS 包成完整 Certificate（DER）。
     */
    fun wrap(tbs: ByteArray, signature: ByteArray): ByteArray =
        seq(tbs, algorithmSha256Rsa(), bitString(signature))

    /** 从 Subject DN 里取 CN。 */
    private fun extractCn(p: X500Principal): String? {
        val dn = runCatching { p.getName(X500Principal.RFC2253) }.getOrNull() ?: return null
        return dn.split(',')
            .map { it.trim() }
            .firstOrNull { it.startsWith("CN=", ignoreCase = true) }
            ?.substring(3)
            ?.let { if (it.startsWith("#")) null else it }
    }
}