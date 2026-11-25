package com.localmusic.core

/** Bounded evidence for asynchronous Android AudioTrack release. */
class OutputRetirement {
    private val evidence=ArrayList<()->Boolean>(8)
    private var unknown=false
    private fun retired(probe: ()->Boolean)=try {probe()}catch(_:Exception){false}
    @Synchronized fun remember(released: ()->Boolean) {
        evidence.removeAll{retired(it)}
        if(evidence.size<8)evidence.add(released)else unknown=true
    }
    @Synchronized fun isRetired(): Boolean=!unknown && evidence.all{retired(it)}
}
