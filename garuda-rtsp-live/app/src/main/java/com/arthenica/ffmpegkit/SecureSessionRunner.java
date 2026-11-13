package com.arthenica.ffmpegkit;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Adapter for pinned FFmpegKit 8.1.9. Its public execution wrapper logs the full
 * command (including credentials) when JNI throws a Java exception. Invoke the
 * same library JNI bridge with a sanitized failure path. No external executable.
 * Re-test this adapter before changing FFmpegKit; fail closed if its ABI changes.
 */
public final class SecureSessionRunner {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);
    private static Method bridge(String name) throws ReflectiveOperationException {
        Method method = FFmpegKitConfig.class.getDeclaredMethod(name, long.class, String[].class);
        method.setAccessible(true);
        return method;
    }
    public static void execute(FFmpegSession session) {
        session.setFuture(EXECUTOR.submit(() -> {
            session.startRunning();
            try {
                int result = (Integer) bridge("nativeFFmpegExecute").invoke(null, session.getSessionId(), session.getArguments());
                session.complete(new ReturnCode(result));
            } catch (Throwable failure) {
                session.fail(new Exception("Native FFmpeg execution failed"));
            } finally {
                if (session.getCompleteCallback() != null) session.getCompleteCallback().apply(session);
            }
        }));
    }
    public static void execute(FFprobeSession session) {
        session.setFuture(EXECUTOR.submit(() -> {
            session.startRunning();
            try {
                int result = (Integer) bridge("nativeFFprobeExecute").invoke(null, session.getSessionId(), session.getArguments());
                session.complete(new ReturnCode(result));
            } catch (Throwable failure) {
                session.fail(new Exception("Native FFprobe execution failed"));
            } finally {
                if (session.getCompleteCallback() != null) session.getCompleteCallback().apply(session);
            }
        }));
    }
}
