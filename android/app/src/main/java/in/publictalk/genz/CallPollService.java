package in.publictalk.genz;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * v7.43: "when locked screen not coming" — root cause: this app is a plain
 * Capacitor WebView wrapper (not Chrome), and Android freezes a
 * backgrounded/locked WebView's JavaScript — timers, fetch, all of it —
 * same as it would freeze any background browser tab. That means
 * app.js's own call polling (startGlobalCallListener) simply stops
 * running the moment the screen locks, no matter how fast its interval
 * is tuned. The real, durable fix is native push (Firebase/FCM), which
 * this project doesn't have set up yet (needs a Firebase project + its
 * credentials).
 *
 * Short of that, this is the strongest fallback available with zero
 * external setup: a genuinely separate native Android background
 * service. It polls the EXACT SAME backend/api/calls/poll.php endpoint
 * app.js already does, but from native code that Android's WebView-
 * freezing rules don't apply to (a foreground service is specifically
 * exempted from that freezing/Doze treatment). The moment it sees an
 * incoming call, it posts a real Android notification with a full-screen
 * intent — the same mechanism real calling apps use to wake a locked
 * screen — with Answer/Decline actions right on it, exactly mirroring
 * what the Web Push notification (sw.js) already offers on the browser/
 * PWA side. Nothing in backend/api/calls/ is touched or even aware this
 * exists; it just calls the same public endpoints app.js does.
 *
 * Trade-off, stated plainly: this keeps a low-priority "GenZ — Listening
 * for calls" notification visible at all times while logged in (Android
 * requires this for any foreground service — it's what's allowed to keep
 * running in the background at all) and polls every few seconds, which
 * uses somewhat more battery than a pure push-based approach would.
 * That's the real cost of not having FCM wired up yet.
 */
public class CallPollService extends Service {
    static final String PREFS = "genz_call_listener";
    static final String PREF_USER_ID = "user_id";

    private static final String SERVICE_CHANNEL = "genz_service";
    private static final String CALL_CHANNEL = "genz_incoming_calls";
    private static final int SERVICE_NOTIF_ID = 9001;
    static final int CALL_NOTIF_ID = 9002;
    private static final long POLL_INTERVAL_MS = 3000;
    private static final String POLL_URL = "https://publictalk.in/backend/api/calls/poll.php?user_id=";

    private HandlerThread thread;
    private Handler bgHandler;
    private volatile boolean running = false;
    private String lastNotifiedCallId = null;

    // v7.62, Part B (FCM): lets FcmService "poke" whichever instance of
    // this service is currently alive, so an incoming FCM data push can
    // make it poll RIGHT NOW instead of waiting for its next scheduled
    // tick — see pokeNow() below. Purely additive to the v7.43 polling
    // loop; nothing here changes how or what it polls.
    private static volatile CallPollService activeInstance;

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("GenZCallPoll");
        thread.start();
        bgHandler = new Handler(thread.getLooper());
        activeInstance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification serviceNotif = buildServiceNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(this, SERVICE_NOTIF_ID, serviceNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(SERVICE_NOTIF_ID, serviceNotif);
        }
        if (!running) {
            running = true;
            bgHandler.post(pollLoop);
        }
        return START_STICKY;
    }

    private final Runnable pollLoop = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                pollOnce();
            } catch (Exception e) {
                // Network/server hiccup, or logged out mid-poll — never crash
                // the service over it, just try again next tick.
            }
            bgHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private void pollOnce() throws Exception {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String userId = prefs.getString(PREF_USER_ID, null);
        if (userId == null || userId.isEmpty()) return;

        URL url = new URL(POLL_URL + URLEncoder.encode(userId, "UTF-8"));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestMethod("GET");
        try {
            int code = conn.getResponseCode();
            if (code != 200) return;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject json = new JSONObject(sb.toString());
            if (json.isNull("incoming")) {
                // v7.43.2: the call stopped ringing (answered on some
                // device, declined, or timed out) — the notification we
                // posted for it needs to be cleared too, otherwise it just
                // sits there forever (this was the "still showing after
                // call lift" bug).
                // v7.50: "call end aiyyaka kuda notification alage vuntundi"
                // — this was STILL happening because the cancel above was
                // gated behind lastNotifiedCallId != null, which is only
                // true in THIS SAME service instance's memory. If Android
                // (MIUI/OEM battery management especially) kills and then
                // restarts this foreground service (it's START_STICKY) at
                // any point between posting the notification and the call
                // ending, the fresh instance's lastNotifiedCallId resets to
                // null — so the very first poll after restart sees
                // incoming:null (call already over) but skipped the cancel,
                // permanently orphaning an ONGOING (non-swipeable)
                // notification. nm.cancel() is a harmless no-op when
                // there's nothing posted, so just always call it here —
                // removes the possibility of a stuck notification
                // regardless of whether this instance remembers posting it.
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                nm.cancel(CALL_NOTIF_ID);
                lastNotifiedCallId = null; // clear so a genuinely new call always notifies again
                return;
            }
            JSONObject incoming = json.getJSONObject("incoming");
            String callId = String.valueOf(incoming.getLong("id"));
            if (callId.equals(lastNotifiedCallId)) return; // already showing this exact call

            String callerName = incoming.optString("display_name", "");
            if (callerName.isEmpty() || "null".equals(callerName)) {
                callerName = incoming.optString("username", "Someone");
            }
            String callType = incoming.optString("call_type", "voice");
            lastNotifiedCallId = callId;
            showIncomingCallNotification(callId, callerName, callType);
        } finally {
            conn.disconnect();
        }
    }

    private void showIncomingCallNotification(String callId, String callerName, String callType) {
        ensureChannels();

        Intent answerIntent = new Intent(this, MainActivity.class);
        answerIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        answerIntent.putExtra(MainActivity.EXTRA_CALL_ACTION, "answer");
        answerIntent.putExtra(MainActivity.EXTRA_CALL_ID, callId);
        PendingIntent answerPi = PendingIntent.getActivity(this, callId.hashCode(), answerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent declineIntent = new Intent(this, CallActionReceiver.class);
        declineIntent.setAction(CallActionReceiver.ACTION_DECLINE);
        declineIntent.putExtra(MainActivity.EXTRA_CALL_ID, callId);
        PendingIntent declinePi = PendingIntent.getBroadcast(this, ("decline" + callId).hashCode(), declineIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = "video".equals(callType) ? "📹 Incoming video call" : "📞 Incoming call";
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CALL_CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(callerName + " is calling…")
                // v7.43.2: "call chestunte locked lo ravadam ledu, unlock
                // chesinappude vastundi" — the notification WAS posting
                // (it showed up fine once unlocked/pulled down), just never
                // actually waking/appearing over the lock screen itself.
                // Two real gaps here: PRIORITY_HIGH is the ceiling for a
                // notification CHANNEL, but the individual notification's
                // own priority can still go to PRIORITY_MAX — pre-Oreo
                // devices (and some OEM skins) key the full-screen-intent
                // behavior off this rather than the channel; and with no
                // explicit visibility set, a locked-screen privacy setting
                // can suppress the heads-up/full-screen behavior entirely
                // even though the notification itself still exists.
                // VISIBILITY_PUBLIC is what a real incoming-call UI needs.
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(answerPi, true)
                .setContentIntent(answerPi)
                .setOngoing(true)
                .addAction(0, "Answer", answerPi)
                .addAction(0, "Decline", declinePi)
                .setAutoCancel(true)
                .setVibrate(new long[]{0, 500, 250, 500, 250, 500});

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(CALL_NOTIF_ID, b.build());
    }

    private Notification buildServiceNotification() {
        ensureChannels();
        return new NotificationCompat.Builder(this, SERVICE_CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("GenZ")
                .setContentText("Listening for calls")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(SERVICE_CHANNEL) == null) {
            NotificationChannel c = new NotificationChannel(SERVICE_CHANNEL, "GenZ background service", NotificationManager.IMPORTANCE_MIN);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
        if (nm.getNotificationChannel(CALL_CHANNEL) == null) {
            NotificationChannel c = new NotificationChannel(CALL_CHANNEL, "Incoming calls", NotificationManager.IMPORTANCE_HIGH);
            c.setDescription("Rings for an incoming GenZ call, even when the phone is locked");
            c.enableVibration(true);
            c.setVibrationPattern(new long[]{0, 500, 250, 500, 250, 500});
            nm.createNotificationChannel(c);
        }
    }

    /**
     * v7.62, Part B (FCM): called by FcmService.onMessageReceived() the
     * instant a high-priority "check for a call now" data push arrives,
     * so the popup appears immediately instead of after up to
     * POLL_INTERVAL_MS of waiting. Purely a reschedule of the SAME
     * pollLoop that already runs every 3s — no new poll logic, no new
     * notification logic. A harmless no-op if this service isn't running
     * (activeInstance null) — FcmService always also calls
     * startForegroundService() first, which covers that case by starting
     * the normal loop right away on its own.
     */
    static void pokeNow() {
        CallPollService svc = activeInstance;
        if (svc == null || svc.bgHandler == null) return;
        svc.bgHandler.removeCallbacks(svc.pollLoop);
        svc.bgHandler.post(svc.pollLoop);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (activeInstance == this) activeInstance = null;
        if (bgHandler != null) bgHandler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
