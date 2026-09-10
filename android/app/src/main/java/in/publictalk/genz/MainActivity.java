package in.publictalk.genz;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import androidx.core.app.ActivityCompat;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebChromeClient;
import org.json.JSONObject;

/**
 * GenZ is a Capacitor wrapper around the existing GenZ web app (loaded live
 * from https://publictalk.in via capacitor.config.json's server.url) — no
 * backend or frontend code was rewritten for mobile, so calling, wallet,
 * groups, and Z-AI all keep working exactly as they do on the website.
 *
 * The only native code needed is here: Android's WebView blocks
 * getUserMedia() (camera/mic access) by default, which would silently break
 * the calling feature, so onPermissionRequest is overridden to auto-grant
 * camera/mic to the WebView once the user has granted the underlying OS
 * runtime permissions below. This does not touch backend/api/calls/* or any
 * calling JS — it only makes the browser API those already rely on
 * (getUserMedia) actually available inside the app's WebView.
 *
 * v7.16: same pattern extended to navigator.geolocation, for the Nearby
 * feature. A bare Android WebView auto-denies every geolocation request
 * with no visible prompt at all unless the host Activity explicitly
 * implements onGeolocationPermissionsShowPrompt — that's why Nearby worked
 * in a real mobile browser (Safari/Chrome) but failed inside the installed
 * APK specifically. Same two-part fix as camera/mic: request the real OS
 * runtime permission up front, then auto-grant the WebView's own prompt
 * once that OS permission is held.
 *
 * v7.43: "when locked screen not coming" — registers CallListenerPlugin
 * (so app.js can start/stop CallPollService, the native background call
 * poller — see that file's docblock for the full why) and delivers the
 * result of tapping that service's lock-screen "Answer" notification back
 * into the WebView. The notification launches this Activity with
 * EXTRA_CALL_ACTION/EXTRA_CALL_ID; deliverPendingCallAction() waits for
 * app.js to finish loading (it may still be cold-starting) and then calls
 * the exact same window.__genzNativeCallAction() hook app.js exposes for
 * this, which feeds into the same _pendingCallAction handling the web
 * push notification's Answer button already uses (see sw.js/app.js v7.42).
 */
public class MainActivity extends BridgeActivity {

    private static final int PERMISSION_REQUEST_CODE = 1001;
    static final String EXTRA_CALL_ACTION = "call_action";
    static final String EXTRA_CALL_ID = "call_id";

    private String pendingCallAction;
    private String pendingCallActionId;
    private int deliverAttempts;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Capacitor plugins must be registered before super.onCreate().
        registerPlugin(CallListenerPlugin.class);
        super.onCreate(savedInstanceState);

        capturePendingCallAction(getIntent());

        // Ask for camera/mic/location/notifications up front so the in-call
        // getUserMedia prompt, the Nearby geolocation prompt (both
        // auto-granted below), and the lock-screen call notification
        // (CallPollService) all have real OS-level permission to work with.
        String[] neededPermissions = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        };
        java.util.List<String> toRequest = new java.util.ArrayList<>();
        for (String p : neededPermissions) {
            if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(p);
            }
        }
        if (!toRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }

        // Auto-grant the WebView's own getUserMedia permission prompt so
        // audio/video calls can start without a broken/no-op native dialog.
        // BridgeWebChromeClient is extended (not replaced) so Capacitor's own
        // file-chooser / camera-picker behavior for other parts of the app
        // keeps working unchanged.
        this.bridge.getWebView().setWebChromeClient(new BridgeWebChromeClient(this.bridge) {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }

            // Same idea as onPermissionRequest above, for navigator.geolocation
            // instead of getUserMedia. If the OS-level runtime permission
            // above hasn't actually been granted by the user yet, Android's
            // location stack itself will still fail the fix at the source —
            // this only unblocks the WebView-level prompt, it doesn't bypass
            // the real permission.
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        capturePendingCallAction(intent);
        deliverAttempts = 0;
        tryDeliverPendingCallAction();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingCallAction != null) {
            deliverAttempts = 0;
            tryDeliverPendingCallAction();
        }
    }

    private void capturePendingCallAction(Intent intent) {
        if (intent == null) return;
        String action = intent.getStringExtra(EXTRA_CALL_ACTION);
        String callId = intent.getStringExtra(EXTRA_CALL_ID);
        if (action != null && callId != null) {
            pendingCallAction = action;
            pendingCallActionId = callId;
        }
    }

    // Retries for ~6s (15 tries, 400ms apart) since app.js may still be
    // cold-starting (fresh network load of the whole SPA) when this fires —
    // gives up silently past that; the app still opens normally either way,
    // the user just answers manually from the ringing screen instead.
    private void tryDeliverPendingCallAction() {
        if (pendingCallAction == null || pendingCallActionId == null || this.bridge == null || this.bridge.getWebView() == null) return;
        final String action = pendingCallAction;
        final String callId = pendingCallActionId;
        this.bridge.getWebView().evaluateJavascript(
            "(typeof window.__genzNativeCallAction === 'function')",
            (result) -> {
                if ("true".equals(result)) {
                    String js = "window.__genzNativeCallAction(" + JSONObject.quote(action) + "," + JSONObject.quote(callId) + ")";
                    bridge.getWebView().evaluateJavascript(js, null);
                    pendingCallAction = null;
                    pendingCallActionId = null;
                } else if (deliverAttempts < 15) {
                    deliverAttempts++;
                    uiHandler.postDelayed(this::tryDeliverPendingCallAction, 400);
                }
            }
        );
    }
}
