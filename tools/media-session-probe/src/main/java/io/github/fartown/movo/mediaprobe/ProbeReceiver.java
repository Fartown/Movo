package io.github.fartown.movo.mediaprobe;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.List;

/**
 * 媒体会话探测：adb shell am broadcast -n io.github.fartown.movo.mediaprobe/.ProbeReceiver --es cmd <cmd> [--el ms N] [--ef speed F] [--es pkg P]
 * cmd = status | seek | speed | next | previous | pause | play。动作前后各打一行状态（tag MediaProbe），动作后等 1.5 秒再读。
 */
public class ProbeReceiver extends BroadcastReceiver {
    private static final String TAG = "MediaProbe";

    @Override
    public void onReceive(Context context, Intent intent) {
        String cmd = intent.getStringExtra("cmd");
        if (cmd == null) cmd = "status";
        String pkg = intent.getStringExtra("pkg");
        MediaController controller = pick(context, pkg);
        if (controller == null) {
            Log.i(TAG, "cmd=" + cmd + " result=NO_SESSION");
            return;
        }
        Log.i(TAG, "cmd=" + cmd + " before " + describe(controller));
        if (cmd.equals("status")) return;
        MediaController.TransportControls t = controller.getTransportControls();
        switch (cmd) {
            case "seek": t.seekTo(intent.getLongExtra("ms", 0L)); break;
            case "speed": t.setPlaybackSpeed(intent.getFloatExtra("speed", 1f)); break;
            case "next": t.skipToNext(); break;
            case "previous": t.skipToPrevious(); break;
            case "pause": t.pause(); break;
            case "play": t.play(); break;
            default: Log.i(TAG, "unknown cmd " + cmd); return;
        }
        final String done = cmd;
        final PendingResult pending = goAsync();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Log.i(TAG, "cmd=" + done + " after " + describe(controller));
            pending.finish();
        }, 1500);
    }

    private static MediaController pick(Context context, String pkg) {
        MediaSessionManager manager = context.getSystemService(MediaSessionManager.class);
        List<MediaController> sessions;
        try {
            sessions = manager.getActiveSessions(new ComponentName(context, ProbeListener.class));
        } catch (SecurityException e) {
            Log.i(TAG, "result=NO_PERMISSION " + e.getMessage());
            return null;
        }
        for (MediaController c : sessions) {
            Log.i(TAG, "session " + c.getPackageName());
        }
        for (MediaController c : sessions) {
            if (pkg == null || pkg.equals(c.getPackageName())) return c;
        }
        return null;
    }

    private static String describe(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        MediaMetadata m = c.getMetadata();
        StringBuilder b = new StringBuilder("pkg=").append(c.getPackageName());
        if (s == null) return b.append(" state=null").toString();
        long now = SystemClock.elapsedRealtime();
        long live = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING && s.getLastPositionUpdateTime() > 0) {
            live += (long) ((now - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        b.append(" state=").append(s.getState())
            .append(" pos_ms=").append(s.getPosition())
            .append(" live_pos_ms=").append(live)
            .append(" updated_ago_ms=").append(now - s.getLastPositionUpdateTime())
            .append(" speed=").append(s.getPlaybackSpeed())
            .append(" actions=").append(s.getActions());
        if (m != null) {
            b.append(" duration_ms=").append(m.getLong(MediaMetadata.METADATA_KEY_DURATION))
                .append(" title=").append(m.getString(MediaMetadata.METADATA_KEY_TITLE))
                .append(" desc=").append(m.getDescription() == null ? "" : m.getDescription().getDescription());
        }
        return b.toString();
    }
}
