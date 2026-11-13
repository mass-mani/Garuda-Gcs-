package com.arthenica.ffmpegkit;
/** Pinned FFmpegKit integration: callbacks run after addLog/addStatistics. No raw history is retained. */
public final class BoundedSession {
 public static void clearLogs(Session session) {
  if(session instanceof AbstractSession) {
   AbstractSession s=(AbstractSession)session;
   synchronized(s.logsLock) { s.logs.clear(); }
  }
 }
 public static void clearStatistics(FFmpegSession session) { session.getStatistics().clear(); }
}
