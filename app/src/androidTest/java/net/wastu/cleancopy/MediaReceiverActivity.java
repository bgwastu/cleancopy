package net.wastu.cleancopy;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.os.Bundle;
import java.io.InputStream;

/** Uses only framework classes so it can run independently in the test APK's UID. */
public class MediaReceiverActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ClipData clip = getIntent().getClipData();
        int count = clip == null ? 0 : clip.getItemCount();
        boolean readable = count > 0;
        for (int index = 0; index < count; index++) {
            try (InputStream input = getContentResolver().openInputStream(clip.getItemAt(index).getUri())) {
                readable &= input != null && input.read() != -1;
            } catch (Exception failure) {
                readable = false;
            }
        }
        sendBroadcast(new Intent("net.wastu.cleancopy.TEST_MEDIA_READ")
            .setPackage(getIntent().getStringExtra("replyPackage"))
            .putExtra("readable", readable).putExtra("count", count));
        finish();
    }
}
