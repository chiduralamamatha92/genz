package in.publictalk.genz;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.core.content.ContextCompat;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * v7.62, Part B of the plan explained to the user before starting (FCM):
 * "long distance city calls" + the popup were two separate asks. Step 1
 * (v7.60) fixed the popup's getUserMedia race. Step 2 (v7.61) added a
 * dedicated Metered.ca TURN relay for long-distance media. This is Step
 * 3 — FCM — and it is a THIRD, purely additive path alongside the other
 * two that already exist:
 *   1. app.js's own live polling while the WebView/tab is foregrounded.
 *   2. CallPollService's ~3s native poll loop (v7.43) — the fallback for
 *      when Android freezes the WebView's JS (screen locked/backgrounded).
 *
 * This file does NOT build or show any notification of its own, and does
 * NOT talk to backend/api/calls/* directly — both on purpose:
 *   - onNewToken(): whenever Firebase (re)issues this device a token,
 *     hand it to the new backend/api/fcm/register_token.php endpoint (own
 *     new folder — backend/api/calls/ untouched) so the server knows
 *     where to push to for whichever user is logged in on this device.
 *   - onMessageReceived(): the server's extended
 *     backend/api/push/notify_call.php sends a DATA-ONLY push (no
 *     "notification" key, precisely so THIS method runs instead of
 *     Android auto-displaying something we don't control) the instant a
 *     call starts. All this does with it is make sure CallPollService is
 *     running and then poke it to poll backend/api/calls/poll.php right
 *     now — CallPollService's own already-fixed (v7.60) notification and
 *     Answer/Decline handling is what actually shows the popup, reused
 *     as-is, never duplicated here.
 *
 * Net effect if this entire file failed outright (Firebase misconfigured
 * on this specific device, server never sends the push, no network at
 * that instant, etc.): calling is completely unaffected — CallPollService
 * still polls every ~3s exactly as it did in v7.60. This only removes
 * that worst-case ~3s wait and makes the popup appear fast even when the
 * phone is deep in Doze/battery-saver — the actual "long distance/locked
 * phone popup slow" gap this step exists to close.
 */
public class FcmService extends FirebaseMessagingService {

    private static final String REGISTER_URL = "https://publictalk.in/backend/api/fcm/register_token.php";

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        sendTokenToServer(this, token);
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        super.onMessageReceived(message);
        Map<String, String> data = message.getData();
        if (data == null || !"incoming_call".equals(data.get("type"))) return;

        SharedPreferences prefs = getSharedPreferences(CallPollService.PREFS, Context.MODE_PRIVATE);
        String userId = prefs.getString(CallPollService.PREF_USER_ID, null);
        if (userId != null && !userId.isEmpty()) {
            // Idempotent/cheap if it's already running (see CallListenerPlugin) —
            // covers the case where this process was cold-started fresh just
            // to deliver this one FCM message.
            ContextCompat.startForegroundService(this, new Intent(this, CallPollService.class));
        }
        CallPollService.pokeNow();
    }

    /**
     * Shared by onNewToken() above and CallListenerPlugin.start() (called
     * right after login, to also cover the case where Firebase already
     * issued this device a token before this specific user logged in —
     * onNewToken() itself typically fires only once per install unless
     * the token is later rotated).
     */
    static void sendTokenToServer(Context context, String token) {
        SharedPreferences prefs = context.getSharedPreferences(CallPollService.PREFS, Context.MODE_PRIVATE);
        String userId = prefs.getString(CallPollService.PREF_USER_ID, null);
        if (userId == null || userId.isEmpty() || token == null || token.isEmpty()) return; // nobody logged in yet — nothing to associate this token with
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("user_id", userId);
                payload.put("token", token);

                URL url = new URL(REGISTER_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                }
                conn.getResponseCode(); // best-effort — drain/complete the request, response body unused
                conn.disconnect();
            } catch (Exception e) {
                // Best-effort — if this fails, calling still works exactly as
                // it did in v7.60 (CallPollService's own ~3s poll); this
                // device just won't get the FCM "instant wake" speed-up.
            }
        });
    }
}
