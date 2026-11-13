package com.garuda.rtsplive

/** Embedded RTSP listener packaged in the APK as a native Android library. */
object LocalRtspServer {
    fun start(port: Int, path: String) {
        com.garuda.rtsplive.rtspserver.Rtspserver.start(port.toLong(), path)
    }

    fun stop() {
        com.garuda.rtsplive.rtspserver.Rtspserver.stop()
    }
}
