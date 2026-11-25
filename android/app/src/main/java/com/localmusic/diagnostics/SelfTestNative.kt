package com.localmusic.diagnostics

/** Bindings exist only in the separate opt-in fake-backend APK. */
internal object SelfTestNative {
    init {
        check(BuildConfig.USB_SELF_TEST)
        NativeUsb.hashCode() // Load the fake library through the target app's class loader.
    }
    @JvmStatic external fun prepare(referenceFd: Int,rate: Int,bits: Int,mode: Int)
    @JvmStatic external fun release()
    @JvmStatic external fun evidence(): String
}
