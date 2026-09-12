package in.publictalk.genz;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.core.content.ContextCompat;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.firebase.messaging.FirebaseMessaging;

/**
 * v7.43: "when locked screen not coming" — the JS-side handshake for
 * CallPollService (see that file's docblock for why this exists at all:
 * app.js's own polling gets frozen by Android whenever the screen locks or
 * the WebView is backgrounded, since this app is a plain WebView wrapper,
 * not Chrome). app.js calls CallListener.start({userId}) right after
 * login/boot (see syncNativeCallListener() in app.js) and .stop() on
 * logout — this plugin just remembers which user to poll for and starts/
 * stops the native background service accordingly. Does nothing else; all
 * the actual polling/notification logic lives in CallPollService.
 *
 * v7.62, Part B (FCM): start() now also fetches this device's current FCM
 * token and hands it to FcmService.sendTokenToServer(), which registers
 * it against this same userId via the new fcm/register_token.php. This is
 * the one extra step Firebase's own docs call for beyond onNewToken()
 * alone — a token can already exist on this device install from before
 * this particular user logged in (e.g. a shared/re-installed device), and
 * onNewToken() normally only fires again if/when that token is rotated.
 * Best-effort and fully additive: if Firebase isn't ready yet or this
 * fails, login and calling proceed completely unaffected, exactly as in
 * v7.60.
 */
@CapacitorPlugin(name = "CallListener")
public class CallListenerPlugin extends Plugin {

    @PluginMethod
    public void start(PluginCall call) {
        String userId = call.getString("userId");
        if (userId == null || userId.isEmpty()) {
            call.reject("userId is required");
            return;
        }
        Context ctx = getContext();
        SharedPreferences prefs = ctx.getSharedPreferences(CallPollService.PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(CallPollService.PREF_USER_ID, userId).apply();

        Intent intent = new Intent(ctx, CallPollService.class);
        ContextCompat.startForegroundService(ctx, intent);

        try {
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                if (task.isSuccessful() && task.getResult() != null) {
                    FcmService.sendTokenToServer(ctx, task.getResult());
                }
                // Any failure here (Firebase not configured, no network right
                // now, etc.) is silent on purpose — CallPollService's ~3s
                // native poll above already works independently of FCM.
            });
        } catch (Exception e) {
            // Same reasoning — never let an FCM hiccup affect login/calling.
        }

        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Context ctx = getContext();
        ctx.stopService(new Intent(ctx, CallPollService.class));
        ctx.getSharedPreferences(CallPollService.PREFS, Context.MODE_PRIVATE)
                .edit().remove(CallPollService.PREF_USER_ID).apply();
        call.resolve();
    }
}
