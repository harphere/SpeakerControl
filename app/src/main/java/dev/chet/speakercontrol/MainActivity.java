package dev.chet.speakercontrol;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.telecom.TelecomManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private TextView status;
    private String dialer;
    private boolean registered;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> status.setText("No dialer reply. Enable SpeakerControl for your default Phone app in LSPosed, reboot, open Phone and check SpeakerControl logs.");
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!Protocol.TOKEN.equals(intent.getStringExtra(Protocol.KEY))) return;
            handler.removeCallbacks(timeout);
            status.setText("Success: " + intent.getBooleanExtra("success", false) + "\n" + intent.getStringExtra("status"));
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this); title.setText("SpeakerControl 1.0.0"); title.setTextSize(26); layout.addView(title);
        TextView help = new TextView(this);
        help.setText("Phone calls only. Scope to your default Phone app in LSPosed."); layout.addView(help);
        for (String command : new String[]{"ENABLE", "DISABLE", "TOGGLE", "STATUS"}) {
            Button button = new Button(this); button.setText(command); button.setOnClickListener(v -> send(command)); layout.addView(button);
        }
        TextView key = new TextView(this); key.setText("Tasker TOKEN (keep private):\n" + Protocol.TOKEN); key.setTextIsSelectable(true); layout.addView(key);
        status = new TextView(this); status.setText("Press STATUS to check the framework connection."); status.setTextIsSelectable(true); layout.addView(status);
        ScrollView scroll = new ScrollView(this); scroll.addView(layout); setContentView(scroll);
        TelecomManager tm = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
        dialer = tm == null ? null : tm.getDefaultDialerPackage();
        help.setText("Default dialer: " + dialer + "\nEnable SpeakerControl in LSPosed for this Phone app, reboot, and start/answer a phone call before testing. ENABLE = speaker; DISABLE = earpiece.");
    }
    @Override protected void onStart() {
        super.onStart();
        registerReceiver(receiver, new IntentFilter(Protocol.PREFIX + "RESULT"), Context.RECEIVER_EXPORTED);
        registered = true;
    }
    @Override protected void onStop() {
        handler.removeCallbacks(timeout);
        if (registered) { unregisterReceiver(receiver); registered = false; }
        super.onStop();
    }
    private void send(String command) {
        if (dialer == null || dialer.isEmpty()) { status.setText("No default dialer detected."); return; }
        status.setText("Waiting for dialer…");
        handler.removeCallbacks(timeout); handler.postDelayed(timeout, 5000);
        sendBroadcast(new Intent(Protocol.PREFIX + command).setPackage(dialer)
                .putExtra(Protocol.KEY, Protocol.TOKEN));
    }
}
