package in.publictalk.genz;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import androidx.core.app.ActivityCompat;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebChromeClient;

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
 */
public class MainActivity extends BridgeActivity {

    private static final int PERMISSION_REQUEST_CODE = 1001;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Ask for camera/mic/location up front so the in-call getUserMedia
        // prompt and the Nearby geolocation prompt (both auto-granted below)
        // have real OS-level permission to hand out.
        String[] neededPermissions = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
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
}
