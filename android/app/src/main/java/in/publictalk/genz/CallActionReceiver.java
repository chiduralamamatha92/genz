package in.publictalk.genz;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.core.app.NotificationManagerCompat;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * v7.43: handles the native lock-screen call notification's "Decline"
 * button (see CallPollService) entirely outside the WebView/app.js —
 * posts the SAME request app.js's declineIncomingCall() and sw.js's push
 * notification Decline action already send, straight to the existing
 * backend/api/calls/decline.php. That endpoint itself is never touched;
 * this is just one more caller of it, same pattern as sw.js's Decline
 * action on the web/PWA side.
 */
public class CallActionReceiver extends BroadcastReceiver {
    static final String ACTION_DECLINE = "in.publictalk.genz.ACTION_DECLINE_CALL";
    private static final String DECLINE_URL = "https://publictalk.in/backend/api/calls/decline.php";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_DECLINE.equals(intent.getAction())) return;
        NotificationManagerCompat.from(context).cancel(CallPollService.CALL_NOTIF_ID);

        String callId = intent.getStringExtra(MainActivity.EXTRA_CALL_ID);
        if (callId == null) return;
        SharedPreferences prefs = context.getSharedPreferences(CallPollService.PREFS, Context.MODE_PRIVATE);
        String userId = prefs.getString(CallPollService.PREF_USER_ID, null);
        if (userId == null) return;

        // A BroadcastReceiver is normally killed the instant onReceive()
        // returns — goAsync() plus a background executor keeps it alive just
        // long enough to finish this one POST.
        final PendingResult pendingResult = goAsync();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("call_id", callId);
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
                conn.getResponseCode(); // best-effort — drain/complete the request, response body unused
                conn.disconnect();
            } catch (Exception e) {
                // Best-effort — if this fails, the call just rings out/times
                // out normally, exactly as it would have before this feature.
            } finally {
                pendingResult.finish();
            }
        });
    }
}
