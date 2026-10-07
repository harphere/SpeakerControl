package dev.chet.miccontrol;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class MicHook implements IXposedHookLoadPackage {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final String[] COMMANDS = {"ENABLE", "DISABLE", "TOGGLE", "STATUS"};
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!"android".equals(p.packageName) || !"android".equals(p.processName)) return;
        try {
            Class<?> ams = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", p.classLoader);
            XposedBridge.hookAllMethods(ams, "systemReady", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Context c = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                        // Never create a Handler in a static initializer or on the hook thread.
                        new Handler(Looper.getMainLooper()).post(() -> register(c));
                    } catch (Throwable t) { log("startup", t); }
                }
            });
            XposedBridge.log("MicControl: framework hook installed");
        } catch (Throwable t) { log("hook installation", t); }
    }
    private static void register(Context c) {
        if (!REGISTERED.compareAndSet(false, true)) return;
        try {
            IntentFilter filter = new IntentFilter();
            for (String command : COMMANDS) filter.addAction(Protocol.PREFIX + command);
            c.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (!Protocol.TOKEN.equals(intent.getStringExtra(Protocol.KEY))) return;
                    final String action = intent.getAction();
                    if (action == null || !action.startsWith(Protocol.PREFIX)) return;
                    final String command = action.substring(Protocol.PREFIX.length());
                    final boolean privacy = intent.getBooleanExtra("privacy", false);
                    final String reply = Protocol.TASKER.equals(intent.getStringExtra("reply_package"))
                            ? Protocol.TASKER : Protocol.APP;
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        // Use the foreground user's context for AudioManager's user ID.
                        int user = (Integer) XposedHelpers.callStaticMethod(
                                XposedHelpers.findClass("android.app.ActivityManager", null), "getCurrentUser");
                        UserHandle handle = (UserHandle) XposedHelpers.newInstance(UserHandle.class, user);
                        Context current = (Context) XposedHelpers.callMethod(c, "createContextAsUser", handle, 0);
                        execute(current, command, privacy, reply, intent.getStringExtra("request_id"));
                    } catch (Throwable t) {
                        log("command " + command, t);
                        respond(c, reply, command, intent.getStringExtra("request_id"), false,
                                "ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    } finally { Binder.restoreCallingIdentity(identity); }
                }
            }, filter, null, new Handler(Looper.getMainLooper()), Context.RECEIVER_EXPORTED);
            XposedBridge.log("MicControl: receiver ready; audio-only default");
        } catch (Throwable t) { REGISTERED.set(false); log("receiver registration", t); }
    }
    private static void execute(Context c, String command, boolean privacy, String reply, String request) {
        AudioManager audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        Object sensor = getSensorPrivacyService(c);
        if (audio == null) throw new IllegalStateException("AudioManager unavailable");
        boolean desiredMuted = audio.isMicrophoneMute();
        if ("ENABLE".equals(command)) desiredMuted = false;
        else if ("DISABLE".equals(command)) desiredMuted = true;
        else if ("TOGGLE".equals(command)) desiredMuted = !desiredMuted;
        else if (!"STATUS".equals(command)) return;
        final boolean expectedMuted = desiredMuted;
        String error = "";
        if (!"STATUS".equals(command)) {
            if (privacy) {
                try {
                    if (sensor == null) throw new IllegalStateException("SensorPrivacyManager unavailable");
                    Class<?> sources = XposedHelpers.findClass("android.hardware.SensorPrivacyManager$Sources", null);
                    int source = XposedHelpers.getStaticIntField(sources, "OTHER");
                    XposedHelpers.callMethod(sensor, "setSensorPrivacy", source, 1, expectedMuted);
                } catch (Throwable t) { log("privacy control", t); error = "privacy failed: " + t; }
            }
            try { audio.setMicrophoneMute(expectedMuted); }
            catch (Throwable t) { log("audio control", t); error += " audio failed: " + t; }
        }
        final String operationError = error;
        // Sensor privacy propagates asynchronously into AudioService. Read after propagation.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            long identity = Binder.clearCallingIdentity();
            try {
                boolean muted = audio.isMicrophoneMute();
                Boolean software = readPrivacy(sensor, 1);
                Boolean hardware = readPrivacy(sensor, 2);
                boolean ok = operationError.isEmpty() && ("STATUS".equals(command)
                        || (muted == expectedMuted && (!privacy || (software != null && software == expectedMuted))));
                String text = "Audio muted: " + muted + "\nSoftware privacy blocked: " + software
                        + "\nHardware privacy blocked: " + hardware;
                if (!operationError.isEmpty()) text += "\n" + operationError;
                if (!ok && operationError.isEmpty()) text += "\nRequested state did not take effect. Check privacy, hardware or policy restrictions.";
                XposedBridge.log("MicControl: " + command + " success=" + ok + " " + text.replace('\n', ' '));
                Intent result = result(reply, command, request, ok, text);
                result.putExtra("audio_muted", muted);
                if (software != null) result.putExtra("privacy_blocked", software.booleanValue());
                if (hardware != null) result.putExtra("hardware_blocked", hardware.booleanValue());
                c.sendBroadcast(result);
            } catch (Throwable t) { log("status", t); respond(c, reply, command, request, false, "ERROR: " + t); }
            finally { Binder.restoreCallingIdentity(identity); }
        }, "STATUS".equals(command) ? 0 : 350);
    }
    // This hidden framework service is available in system_server, but excluded
    // from the public SDK's @ServiceName IntDef. Suppress only this lookup.
    @SuppressLint("WrongConstant")
    private static Object getSensorPrivacyService(Context c) {
        return c.getSystemService("sensor_privacy");
    }
    private static Boolean readPrivacy(Object manager, int toggle) {
        if (manager == null) return null;
        try { return (Boolean) XposedHelpers.callMethod(manager, "isSensorPrivacyEnabled", toggle, 1); }
        catch (Throwable t) { log("privacy status " + toggle, t); return null; }
    }
    private static Intent result(String reply, String command, String request, boolean ok, String text) {
        return new Intent(Protocol.PREFIX + "RESULT").setPackage(reply)
                .putExtra(Protocol.KEY, Protocol.TOKEN).putExtra("command", command)
                .putExtra("request_id", request).putExtra("success", ok).putExtra("status", text);
    }
    private static void respond(Context c, String reply, String command, String request, boolean ok, String text) {
        c.sendBroadcast(result(reply, command, request, ok, text));
    }
    private static void log(String operation, Throwable t) {
        XposedBridge.log("MicControl: " + operation + " failed: " + t);
        XposedBridge.log(t);
    }
}
