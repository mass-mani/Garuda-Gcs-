package com.garuda.rtsplive

import android.media.MediaCodecList
import android.os.Build

object HardwareEncoder {
    fun choose(config: Config, info: VideoInfo): String? {
        val bounds = config.resolution.split('x').mapNotNull { it.toIntOrNull() }
        val factor =
            if (bounds.size == 2)
                minOf(bounds[0].toDouble() / info.width, bounds[1].toDouble() / info.height)
            else 1.0
        val width = (info.width * factor).toInt() / 2 * 2
        val height = (info.height * factor).toInt() / 2 * 2
        val fps = if (config.fps > 0) config.fps.toDouble() else info.fps.coerceAtLeast(1.0)
        return MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .codecInfos
            .firstOrNull { codec ->
                val hardware =
                    if (Build.VERSION.SDK_INT >= 29)
                        codec.isHardwareAccelerated && !codec.isSoftwareOnly
                    else
                        listOf(
                                "OMX.qcom.",
                                "OMX.Exynos.",
                                "OMX.MTK.",
                                "OMX.rk.",
                                "OMX.hisi.",
                                "OMX.amlogic.",
                                "OMX.Intel.",
                            )
                            .any { codec.name.startsWith(it, true) }
                codec.isEncoder &&
                    hardware &&
                    codec.supportedTypes.any { it.equals("video/avc", true) } &&
                    runCatching {
                            codec
                                .getCapabilitiesForType("video/avc")
                                .videoCapabilities
                                ?.areSizeAndRateSupported(width, height, fps) == true
                        }
                        .getOrDefault(false)
            }
            ?.name
    }
}
