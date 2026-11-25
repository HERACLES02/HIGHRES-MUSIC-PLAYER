package com.localmusic.usb

/** This class is absent from Local Music; export exists only in fake library. */
object NativeDirectTest {
    init { NativeDirect.initialize(true) }
    @JvmStatic external fun open(fd: Int): Long
    @JvmStatic external fun referenceFrame(frame: Long,bits: Int)
    @JvmStatic external fun armReads()
    @JvmStatic external fun waitReads()
    @JvmStatic external fun releaseReads()
    @JvmStatic external fun snapshot(): String
    @JvmStatic external fun stop()
}
