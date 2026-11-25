package com.example.s7hmi

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Errore su un singolo oggetto (DB inesistente, accesso negato...): la connessione resta valida. */
class S7ItemException(msg: String) : IOException(msg)

/**
 * Client minimale S7comm (ISO-on-TCP, porta 102) per S7-1200/1500/300/400.
 * Supporta lettura/scrittura di byte in DB e scrittura di singoli bit.
 */
class S7Client {
    @Volatile private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var pduLength = 240
    private var pduRef = 0

    val isConnected: Boolean
        get() = socket?.let { it.isConnected && !it.isClosed } == true

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun u16(a: ByteArray, i: Int) = ((a[i].toInt() and 0xFF) shl 8) or (a[i + 1].toInt() and 0xFF)
    private fun nextRef(): Int { pduRef = (pduRef + 1) and 0xFFFF; return pduRef }

    @Synchronized
    fun connect(ip: String, rack: Int, slot: Int, timeoutMs: Int = 3000, port: Int = 102) {
        close()
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(ip, port), timeoutMs)
        s.soTimeout = timeoutMs
        socket = s; input = s.getInputStream(); output = s.getOutputStream()
        try {
            // COTP Connection Request (TSAP locale 01.00, remoto 01.rack*32+slot)
            send(b(0x03, 0x00, 0x00, 0x16, 0x11, 0xE0, 0x00, 0x00, 0x00, 0x01, 0x00,
                0xC0, 0x01, 0x0A, 0xC1, 0x02, 0x01, 0x00, 0xC2, 0x02, 0x01, rack * 0x20 + slot))
            val cc = receive()
            if (cc.size < 6 || (cc[5].toInt() and 0xFF) != 0xD0) throw IOException("Connessione ISO rifiutata")
            // S7 Setup Communication: negozia la dimensione PDU
            send(b(0x03, 0x00, 0x00, 0x19, 0x02, 0xF0, 0x80,
                0x32, 0x01, 0x00, 0x00, 0x04, 0x00, 0x00, 0x08, 0x00, 0x00,
                0xF0, 0x00, 0x00, 0x01, 0x00, 0x01, 0x01, 0xE0))
            val r = receive()
            if (r.size < 27 || r[17].toInt() != 0 || r[18].toInt() != 0) throw IOException("Negoziazione PDU fallita")
            pduLength = u16(r, 25)
        } catch (e: Exception) {
            close(); throw e
        }
    }

    /** Non sincronizzato di proposito: deve poter interrompere una lettura bloccata. */
    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; input = null; output = null
    }

    private fun item(tsize: Int, count: Int, db: Int, bitAddr: Int) = b(
        0x12, 0x0A, 0x10, tsize, count shr 8, count and 0xFF, db shr 8, db and 0xFF,
        0x84, (bitAddr shr 16) and 0xFF, (bitAddr shr 8) and 0xFF, bitAddr and 0xFF
    )

    @Synchronized
    fun readBytes(db: Int, start: Int, size: Int): ByteArray {
        require(size in 1..(pduLength - 18)) { "Dimensione lettura non valida" }
        val ref = nextRef()
        val req = b(0x03, 0x00, 0x00, 0x1F, 0x02, 0xF0, 0x80,
            0x32, 0x01, 0x00, 0x00, ref shr 8, ref and 0xFF, 0x00, 0x0E, 0x00, 0x00,
            0x04, 0x01) + item(0x02, size, db, start * 8)
        send(req)
        val r = receive()
        checkHeader(r)
        if (r.size < 25 || (r[21].toInt() and 0xFF) != 0xFF)
            throw S7ItemException("Lettura DB$db.$start rifiutata (DB non ottimizzato? PUT/GET attivo?)")
        if (r.size < 25 + size) throw IOException("Risposta di lettura troppo corta")
        return r.copyOfRange(25, 25 + size)
    }

    @Synchronized
    fun writeBytes(db: Int, start: Int, data: ByteArray) {
        val n = data.size
        require(n in 1..(pduLength - 35)) { "Dimensione scrittura non valida" }
        val total = 35 + n
        val ref = nextRef()
        val req = b(0x03, 0x00, total shr 8, total and 0xFF, 0x02, 0xF0, 0x80,
            0x32, 0x01, 0x00, 0x00, ref shr 8, ref and 0xFF, 0x00, 0x0E, (4 + n) shr 8, (4 + n) and 0xFF,
            0x05, 0x01) + item(0x02, n, db, start * 8) + b(0x00, 0x04, (n * 8) shr 8, (n * 8) and 0xFF) + data
        send(req)
        checkWrite(receive(), db, start)
    }

    @Synchronized
    fun writeBit(db: Int, byteOffset: Int, bit: Int, value: Boolean) {
        val ref = nextRef()
        val req = b(0x03, 0x00, 0x00, 0x24, 0x02, 0xF0, 0x80,
            0x32, 0x01, 0x00, 0x00, ref shr 8, ref and 0xFF, 0x00, 0x0E, 0x00, 0x05,
            0x05, 0x01) + item(0x01, 1, db, byteOffset * 8 + bit) +
            b(0x00, 0x03, 0x00, 0x01, if (value) 1 else 0)
        send(req)
        checkWrite(receive(), db, byteOffset)
    }

    private fun checkHeader(r: ByteArray) {
        if (r.size < 21) throw IOException("Risposta S7 incompleta")
        if (r[17].toInt() != 0 || r[18].toInt() != 0)
            throw IOException("Errore S7 ${r[17].toInt() and 0xFF}/${r[18].toInt() and 0xFF}")
    }

    private fun checkWrite(r: ByteArray, db: Int, off: Int) {
        checkHeader(r)
        if (r.size < 22 || (r[21].toInt() and 0xFF) != 0xFF)
            throw S7ItemException("Scrittura DB$db.$off rifiutata (DB non ottimizzato? PUT/GET attivo?)")
    }

    private fun send(data: ByteArray) {
        val o = output ?: throw IOException("Non connesso")
        o.write(data); o.flush()
    }

    private fun receive(): ByteArray {
        val inp = input ?: throw IOException("Non connesso")
        val head = readFully(inp, 4)
        if (head[0].toInt() != 3) throw IOException("TPKT non valido")
        val len = u16(head, 2)
        if (len < 7) throw IOException("Pacchetto troppo corto")
        return head + readFully(inp, len - 4)
    }

    private fun readFully(inp: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            if (r < 0) throw IOException("Connessione chiusa dal PLC")
            off += r
        }
        return buf
    }
}
