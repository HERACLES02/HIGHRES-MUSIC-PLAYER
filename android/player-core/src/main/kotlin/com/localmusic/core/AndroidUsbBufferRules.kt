package com.localmusic.core

/** Only an explicit preference and a suitable connected USB route may reduce buffering. */
object AndroidUsbBufferRules {
    // Low-rate captures stayed in the Android mixer despite smaller buffers.
    // Retain the known Normal path there while testing high-rate transitions.
    fun eligibleRate(sampleRateHz:Int):Boolean = sampleRateHz>48000
    fun bytes(minimum:Int,normal:Int,frameBytes:Int,enabled:Boolean,suitableUsb:Boolean):Int {
        if(!enabled||!suitableUsb||minimum<=0||normal<=0||frameBytes<=0||minimum>normal)return normal
        val aligned=(minimum.toLong()+frameBytes-1)/frameBytes*frameBytes
        return if(aligned<=normal)aligned.toInt()else normal
    }
}
