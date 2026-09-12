package in.publictalk.genz;

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * v7.63: "whatsapp/telegram calling laga kavali" — the user's live test of
 * v7.62's FCM wiring turned up the REAL gap this whole 3-part plan was
 * always heading toward: sound/vibration were reaching a locked phone
 * fine (CallPollService's notification channel already had that), but no
 * actual full-screen ringing UI ever appeared over the lock screen, and
 * tapping "Answer" on the notification did nothing useful while locked.
 *
 * Root cause: the notification's full-screen intent, its content-tap, and
 * its "Answer" action all pointed straight at MainActivity (the WebView
 * app itself) with an instruction to auto-answer. MainActivity has never
 * requested permission to draw over the keyguard or turn the screen on —
 * without that, Android just queues the launch behind the lock screen
 * instead of showing it, so all the user ever got was the notification's
 * sound. This is that missing piece: a small, separate, 100% native
 * Activity whose only job is to actually appear over the lock screen with
 * a real Accept/Decline choice, exactly like a real calling app.
 *
 * This activity does NOT set up any WebRTC media itself — it can't; that
 * logic only exists in app.js. Accept here means "launch MainActivity
 * with the same answer instruction as before" (now that the screen is
 * genuinely unlocked/visible), reusing the existing, already-debugged
 * tryDeliverPendingCallAction()/window.__genzNativeCallAction() bridge
 * and calls/accept.php flow untouched. Decline posts straight to
 * calls/decline.php, mirroring CallActionReceiver exactly. Neither of
 * those existing endpoints, nor CallPollService's polling/notification
 * logic, is modified by this file — this only changes WHICH screen the
 * notification points to and adds the two OS-level flags that let that
 * screen actually show up.
 */
public class IncomingCallActivity extends AppCompatActivity {

    static final String EXTRA_AUTO_ANSWER = "auto_answer";
    static final String EXTRA_CALLER_NAME = "caller_name";
    static final String EXTRA_CALL_TYPE = "call_type";

    private static final String DECLINE_URL = "https://publictalk.in/backend/api/calls/decline.php";
    private static final String POLL_URL = "https://publictalk.in/backend/api/calls/poll.php?user_id=";
    private static final long SELF_POLL_INTERVAL_MS = 3000;
    private static final long RING_TIMEOUT_MS = 45000; // matches app.js's own caller-side ring timeout

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private String callId;
    private boolean resolved = false; // true once Accept/Decline/timeout/self-poll has acted, guards against double-firing

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The actual fix: make this Activity able to draw over the lock
        // screen and wake the display, the same capability every real
        // calling app uses for its incoming-call UI. Manifest also
        // declares android:showWhenLocked/turnScreenOn (API 27+); doing it
        // here too covers devices where the manifest attributes alone
        // aren't honored, and requestDismissKeyguard() additionally offers
        // to drop an insecure (no PIN/pattern) lock screen entirely.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null) km.requestDismissKeyguard(this, null);
        } else {
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            );
        }

        setContentView(R.layout.activity_incoming_call);

        callId = getIntent().getStringExtra(MainActivity.EXTRA_CALL_ID);
        String callerName = getIntent().getStringExtra(EXTRA_CALLER_NAME);
        String callType = getIntent().getStringExtra(EXTRA_CALL_TYPE);
        boolean autoAnswer = getIntent().getBooleanExtra(EXTRA_AUTO_ANSWER, false);

        if (callerName == null || callerName.isEmpty()) callerName = "Someone";
        TextView nameView = findViewById(R.id.caller_name);
        nameView.setText(callerName);

        TextView avatar = findViewById(R.id.caller_avatar);
        avatar.setText(callerName.substring(0, 1).toUpperCase());

        TextView subtitle = findViewById(R.id.call_subtitle);
        subtitle.setText("video".equals(callType) ? "Incoming video call…" : "Incoming voice call…");

        findViewById(R.id.btn_accept).setOnClickListener(v -> accept());
        findViewById(R.id.btn_decline).setOnClickListener(v -> decline());

        // v7.62's "Answer" notification action (the small button on the
        // collapsed/heads-up notification) now routes here too instead of
        // straight to MainActivity — see CallPollService.showIncomingCallNotification().
        // Passing EXTRA_AUTO_ANSWER=true from there means: still show this
        // real over-the-keyguard screen first (so it works identically
        // whether the phone is locked or not), then immediately proceed as
        // if Accept had been tapped, with no extra step for the user.
        if (autoAnswer) {
            accept();
            return;
        }

        // Safety nets, same standing lesson this project has applied
        // before (v7.50's poll-based orphan-notification cleanup, v7.60's
        // onResume cancel-as-safety-net): this screen must not get stuck
        // showing a call that already ended somewhere else (answered on
        // another device, caller hung up, or simply timed out). A light
        // self-poll of the SAME calls/poll.php endpoint CallPollService
        // already uses, plus a hard 45s cap matching app.js's own
        // caller-side ring timeout, whichever comes first.
        uiHandler.postDelayed(selfPollRunnable, SELF_POLL_INTERVAL_MS);
        uiHandler.postDelayed(this::finishIfUnresolved, RING_TIMEOUT_MS);
    }

    private final Runnable selfPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (resolved) return;
            Executors.newSingleThreadExecutor().execute(() -> {
                boolean stillRinging = pollStillRinging();
                if (!stillRinging) {
                    uiHandler.post(IncomingCallActivity.this::finishIfUnresolved);
                } else if (!resolved) {
                    uiHandler.postDelayed(selfPollRunnable, SELF_POLL_INTERVAL_MS);
                }
            });
        }
    };

    /** Mirrors CallPollService.pollOnce()'s own read of the same endpoint — true if this exact call is still the active incoming call. */
    private boolean pollStillRinging() {
        try {
            SharedPreferences prefs = getSharedPreferences(CallPollService.PREFS, Context.MODE_PRIVATE);
            String userId = prefs.getString(CallPollService.PREF_USER_ID, null);
            if (userId == null || callId == null) return true; // can't tell — don't wrongly dismiss

            URL url = new URL(POLL_URL + URLEncoder.encode(userId, "UTF-8"));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");
            try {
                if (conn.getResponseCode() != 200) return true; // network hiccup — don't dismiss over it
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
                JSONObject json = new JSONObject(sb.toString());
                if (json.isNull("incoming")) return false; // call ended/answered elsewhere/declined elsewhere
                JSONObject incoming = json.getJSONObject("incoming");
                return String.valueOf(incoming.getLong("id")).equals(callId);
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return true; // any error — never wrongly dismiss a call that might still be real
        }
    }

    private void finishIfUnresolved() {
        if (resolved || isFinishing()) return;
        resolved = true;
        clearNotification();
        finish();
    }

    private void accept() {
        if (resolved) return;
        resolved = true;
        clearNotification();

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(MainActivity.EXTRA_CALL_ACTION, "answer");
        intent.putExtra(MainActivity.EXTRA_CALL_ID, callId);
        startActivity(intent);
        finish();
    }

    private void decline() {
        if (resolved) return;
        resolved = true;
        clearNotification();

        SharedPreferences prefs = getSharedPreferences(CallPollService.PREFS, Context.MODE_PRIVATE);
        String userId = prefs.getString(CallPollService.PREF_USER_ID, null);
        String declineCallId = callId;
        if (userId != null && declineCallId != null) {
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("call_id", declineCallId);
                    payload.put("user_id", userId);

                    URL url = new URL(DECLINE_URL);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                    }
                    conn.getResponseCode(); // best-effort
                    conn.disconnect();
                } catch (Exception e) {
                    // Best-effort — same as CallActionReceiver's own Decline handling.
                }
            });
        }
        finish();
    }

    private void clearNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(CallPollService.CALL_NOTIF_ID);
    }

    @Override
    protected void onDestroy() {
        resolved = true;
        uiHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
