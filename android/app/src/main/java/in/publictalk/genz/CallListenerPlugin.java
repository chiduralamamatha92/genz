package in.publictalk.genz;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.core.content.ContextCompat;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

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
