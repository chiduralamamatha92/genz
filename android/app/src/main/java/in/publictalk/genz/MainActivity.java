package in.publictalk.genz;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
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
 */
public class MainActivity extends BridgeActivity {

    private static final int PERMISSION_REQUEST_CODE = 1001;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Ask for camera/mic up front so the in-call getUserMedia prompt
        // (auto-granted below) has real OS-level permission to hand out.
        String[] neededPermissions = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
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
        });
    }
}
