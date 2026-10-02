package com.example.sonyremote

data class Btn(val name: String, val addrBits: Int, val address: Int, val command: Int)

// Codes taken from the Sony Amp and Sony STR-DH590 IR files.
// SIRC15 = 8-bit address, SIRC20 = 13-bit address. Command is always 7 bits.
object Buttons {
    val power = Btn("Power", 8, 0x30, 0x15)
    val input = Btn("Input", 8, 0xB0, 0x69)
    val volUp = Btn("Vol up", 8, 0x30, 0x12)
    val volDn = Btn("Vol down", 8, 0x30, 0x13)
    val rewind = Btn("Rewind", 13, 0x110, 0x33)
    val playPause = Btn("Play/Pause", 13, 0x110, 0x3A)
    val fastForward = Btn("Fast forward", 13, 0x110, 0x34)
    val skipBack = Btn("Skip back", 13, 0x110, 0x30)
    val skipForward = Btn("Skip forward", 13, 0x110, 0x31)
}

object Sirc {
    const val CARRIER_HZ = 40000

    // Builds the on/off microsecond pattern for ConsumerIrManager.transmit().
    // Sony remotes send each press 3 times, 45 ms apart (start to start).
    fun pattern(b: Btn, repeats: Int = 3): IntArray {
        val out = ArrayList<Int>()
        for (r in 0 until repeats) {
            val f = ArrayList<Int>()
            f.add(2400); f.add(600)
            fun bits(v: Int, n: Int) {
                for (i in 0 until n) {
                    f.add(if (((v shr i) and 1) == 1) 1200 else 600)
                    f.add(600)
                }
            }
            bits(b.command, 7)
            bits(b.address, b.addrBits)
            if (r < repeats - 1) {
                f[f.size - 1] = f[f.size - 1] + (45000 - f.sum())
            } else {
                f.removeAt(f.size - 1)
            }
            out.addAll(f)
        }
        return out.toIntArray()
    }
}
