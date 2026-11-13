# Third-party notices

FFmpegKit maintained fork 8.1.9 / FFmpeg n8.1.3 is dynamically linked from the HTTPS (LGPL) distribution, without GPL/nonfree build flags. Copyright the FFmpeg developers, Taner Sener and the maintained fork contributors. License: LGPL v3 or later for this distribution; see bundled LGPL-3.0 and GPL-3.0 texts.

- Maintained fork, build scripts and source: https://github.com/ffmpegkit-maintained/ffmpeg
- Versioned artifacts and Java sources: https://repo.maven.apache.org/maven2/dev/ffmpegkit-maintained/ffmpeg-kit-https/8.1.9/
- FFmpeg source: https://github.com/FFmpeg/FFmpeg/tree/n8.1.3
- FFmpeg licensing: https://ffmpeg.org/legal.html

The HTTPS variant also contains GMP/GnuTLS; consult the maintained fork build scripts and upstream component notices for their source and licenses. No native library was modified by this app. The complete Android project permits replacement/relinking of the FFmpeg dependency; the small Java integration adapters are included as source. Preserve these notices and provide the corresponding library source and relinking materials when distributing binaries under the applicable licenses.

AndroidX / Jetpack / Media3 and Android Gradle components are Apache-2.0. Kotlin and kotlinx.coroutines are Apache-2.0. FFmpegKit's transitive smart-exception utilities are LGPL-3.0; their source is available via the same Maven Central source-artifact mechanism. See individual Maven POMs for authoritative attribution and component versions.

The embedded RTSP server is built from `github.com/bluenviron/gortsplib/v5` v5.6.6 and Pion RTP/media dependencies. The pinned module versions are in `app/src/main/go/localrtsp/go.mod` and `go.sum`. Their MIT/BSD license texts and the Go runtime license are included in `app/src/main/go/localrtsp/assets/RTSP-SERVER-LICENSES.txt` and bundled in `app/libs/localrtsp.aar`.
