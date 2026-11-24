package com.localmusic.focusprobe;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Silent, foreground-only focus stimulus. Does not control or inspect USB. */
public final class MainActivity extends Activity {
    private static final String TAG = "M2FocusProbe";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder history = new StringBuilder();
    private AudioManager audio;
    private AudioFocusRequest request;
    private TextView result;
    private Button start;
    private final Runnable timeout = () -> release("Five seconds elapsed");

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        audio = getSystemService(AudioManager.class);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        TextView title = new TextView(this);
        title.setText("USB Focus Test");
        title.setTextSize(24);
        layout.addView(title);
        TextView instructions = new TextView(this);
        instructions.setText("Start the quiet Direct USB fixture in Music USB Probe, then return here. "
            + "Tap the button and wait five seconds. This app stays silent and does not access USB or music files. "
            + "Then return to Music USB Probe and check whether Direct stopped and stayed stopped.");
        layout.addView(instructions);
        start = new Button(this);
        start.setText("Request focus for 5 seconds");
        start.setOnClickListener(v -> acquire());
        layout.addView(start);
        result = new TextView(this);
        result.setTextIsSelectable(true);
        layout.addView(result);
        setContentView(layout);
        if (state != null) {
            String prior = state.getString("history", "");
            history.append(prior);
        }
        record("Ready. No sound will be played.");
    }

    private void acquire() {
        if (request != null) return;
        AudioFocusRequest candidate = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(change -> {
                record("Helper focus callback: " + change);
                if (change != AudioManager.AUDIOFOCUS_GAIN) release("Helper interrupted");
            }, handler).build();
        try {
            int status = audio.requestAudioFocus(candidate);
            record("Focus request result: " + status + (status == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? " (GRANTED)" : " (NOT GRANTED)"));
            if (status == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                request = candidate;
                start.setEnabled(false);
                if (!handler.postDelayed(timeout, 5000)) release("Could not schedule release");
            } else {
                // Defensive cleanup of a denied/non-granted request as well.
                audio.abandonAudioFocusRequest(candidate);
                record("No focus held. This attempt cannot verify Direct's response.");
            }
        } catch (RuntimeException error) {
            request = candidate;
            release("Request error: " + error.getClass().getSimpleName());
        }
    }

    private void release(String why) {
        handler.removeCallbacks(timeout);
        AudioFocusRequest held = request;
        request = null;
        if (held != null) {
            try {
                int status = audio.abandonAudioFocusRequest(held);
                record(why + "; abandon result: " + status + ". Return to Music USB Probe and save its report.");
            } catch (RuntimeException error) {
                record(why + "; abandon error: " + error.getClass().getSimpleName());
            }
        }
        if (start != null) start.setEnabled(true);
    }

    private void record(String text) {
        Log.i(TAG, text);
        if (history.length() > 6000) history.delete(0, history.length() - 3000);
        history.append(text).append('\n');
        if (result != null) result.setText(history.toString());
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("history", history.toString());
        super.onSaveInstanceState(state);
    }
    @Override protected void onStop() {
        release("Test app left the foreground");
        super.onStop();
    }
    @Override protected void onDestroy() {
        release("Test app closed");
        super.onDestroy();
    }
}
