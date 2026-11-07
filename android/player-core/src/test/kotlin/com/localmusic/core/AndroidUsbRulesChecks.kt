package com.localmusic.core

fun androidUsbRulesChecks() {
    var count=0
    fun verify(value:Boolean,message:String){count++;check(value){"Android USB output: $message"}}
    verify(!AndroidUsbBufferRules.eligibleRate(44100),"44.1 kHz retains Normal routing and buffering after mixed-rate clicks")
    verify(!AndroidUsbBufferRules.eligibleRate(48000),"48 kHz retains Normal routing and buffering after mixed-rate clicks")
    verify(AndroidUsbBufferRules.eligibleRate(88200)&&AndroidUsbBufferRules.eligibleRate(96000),"higher-rate USB candidates remain eligible")
    verify(!AudioSettings().preferAndroidUsb,"existing users keep Normal output")
    verify(AudioSettings(crossfadeSeconds=4,preferAndroidUsb=true).effectiveCrossfadeSeconds==0,"USB preference avoids overlapping direct streams")
    verify(AudioSettings(crossfadeSeconds=4).effectiveCrossfadeSeconds==4,"Normal retains saved crossfade")
    verify(AndroidUsbBufferRules.bytes(61504,192000,8,false,true)==192000,"disabled preference preserves steady-state buffer")
    verify(AndroidUsbBufferRules.bytes(61504,192000,8,true,false)==192000,"no suitable USB device preserves Normal buffer")
    verify(AndroidUsbBufferRules.bytes(61504,192000,8,true,true)==61504,"opt-in USB uses the tested minimum-buffer strategy")
    verify(AndroidUsbBufferRules.bytes(61505,192000,8,true,true)==61512,"nonaligned minimum rounds up to complete PCM frames")
    for(minimum in listOf(-2,0,192001,Int.MAX_VALUE))
        verify(AndroidUsbBufferRules.bytes(minimum,192000,8,true,true)==192000,"unusable minimum retains conservative buffer")
    verify(AndroidUsbBufferRules.bytes(61504,192000,0,true,true)==192000,"invalid frame size retains Normal")
    println("Android USB buffer checks passed: $count assertions.")
}
