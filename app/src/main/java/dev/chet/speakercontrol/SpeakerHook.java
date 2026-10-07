package dev.chet.speakercontrol;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

@SuppressWarnings("deprecation")
public final class SpeakerHook implements IXposedHookLoadPackage {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static volatile InCallService service;
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if ("android".equals(p.packageName) || Protocol.APP.equals(p.packageName)) return;
        try {
            XposedHelpers.findAndHookMethod(InCallService.class, "onBind", Intent.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getResult() != null) {
                        service = (InCallService) param.thisObject;
                        XposedBridge.log("SpeakerControl: InCallService bound in " + p.packageName);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(InCallService.class, "onUnbind", Intent.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (service == param.thisObject) service = null;
                }
            });
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Context c = (Context) param.args[0];
                    new Handler(Looper.getMainLooper()).post(() -> register(c));
                }
            });
            XposedBridge.log("SpeakerControl: hooks installed in " + p.packageName + " / " + p.processName);
        } catch (Throwable t) { log("hook installation", t); }
    }
    private static void register(Context c) {
        if (!REGISTERED.compareAndSet(false, true)) return;
        try {
            IntentFilter f = new IntentFilter();
            for (String command : new String[]{"ENABLE", "DISABLE", "TOGGLE", "STATUS"}) f.addAction(Protocol.PREFIX + command);
            c.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (!Protocol.TOKEN.equals(intent.getStringExtra("TOKEN"))) return;
                    String action = intent.getAction();
                    if (action == null || !action.startsWith(Protocol.PREFIX)) return;
                    String command = action.substring(Protocol.PREFIX.length());
                    String reply = Protocol.TASKER.equals(intent.getStringExtra("reply_package")) ? Protocol.TASKER : Protocol.APP;
                    execute(context, command, reply, intent.getStringExtra("request_id"));
                }
            }, f, null, new Handler(Looper.getMainLooper()), Context.RECEIVER_EXPORTED);
            XposedBridge.log("SpeakerControl: receiver ready in " + c.getPackageName());
        } catch (Throwable t) { REGISTERED.set(false); log("receiver", t); }
    }
    private static boolean active(InCallService s) {
        if (s == null) return false;
        for (Call call : s.getCalls()) if (call.getState() == Call.STATE_ACTIVE) return true;
        return false;
    }
    private static void execute(Context c, String command, String reply, String request) {
        InCallService s = service;
        try {
            if (s == null) { respond(c,reply,command,request,"STATUS".equals(command),false,-1,"Dialer hook connected; no bound InCallService. Start/answer a phone call."); return; }
            CallAudioState before = s.getCallAudioState();
            boolean callActive = active(s);
            if (before == null) { respond(c,reply,command,request,false,callActive,-1,"Call audio state not ready."); return; }
            int current = before.getRoute();
            if ("STATUS".equals(command)) { respond(c,reply,command,request,true,callActive,current,"Current route: " + routeName(current)); return; }
            if (!callActive) { respond(c,reply,command,request,false,false,current,"No active phone call; commands are not queued."); return; }
            boolean speaker;
            if ("ENABLE".equals(command)) speaker = true;
            else if ("DISABLE".equals(command)) speaker = false;
            else if ("TOGGLE".equals(command)) speaker = current != CallAudioState.ROUTE_SPEAKER;
            else return;
            int desired = speaker ? CallAudioState.ROUTE_SPEAKER : CallAudioState.ROUTE_EARPIECE;
            if ((before.getSupportedRouteMask() & desired) == 0) {
                respond(c,reply,command,request,false,true,current,"Requested route unavailable: " + routeName(desired) + ". Disconnect headset and retry."); return;
            }
            s.setAudioRoute(desired);
            verify(c,s,reply,command,request,desired,0);
        } catch (Throwable t) { log("command",t); respond(c,reply,command,request,false,false,-1,"ERROR: " + t); }
    }
    private static void verify(Context c, InCallService s, String reply, String command, String request, int desired, int attempt) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (service != s || !active(s)) { respond(c,reply,command,request,false,false,-1,"Call ended or service changed before verification."); return; }
                CallAudioState observed = s.getCallAudioState();
                int route = observed == null ? -1 : observed.getRoute();
                if (route != desired && attempt < 3) { verify(c,s,reply,command,request,desired,attempt+1); return; }
                boolean ok = route == desired;
                respond(c,reply,command,request,ok,true,route,"Current route: " + routeName(route) + (ok ? "" : ". Requested route did not take effect."));
            } catch (Throwable t) { log("verification",t); respond(c,reply,command,request,false,false,-1,"ERROR: " + t); }
        },300);
    }
    private static String routeName(int route) {
        switch (route) {
            case CallAudioState.ROUTE_SPEAKER: return "speakerphone";
            case CallAudioState.ROUTE_EARPIECE: return "earpiece";
            case CallAudioState.ROUTE_BLUETOOTH: return "Bluetooth";
            case CallAudioState.ROUTE_WIRED_HEADSET: return "wired headset";
            default: return "unknown (" + route + ")";
        }
    }
    private static void respond(Context c, String reply, String command, String request, boolean ok, boolean callActive, int route, String message) {
        c.sendBroadcast(new Intent(Protocol.PREFIX + "RESULT").setPackage(reply)
                .putExtra("TOKEN",Protocol.TOKEN).putExtra("command",command).putExtra("request_id",request)
                .putExtra("success",ok).putExtra("call_active",callActive).putExtra("route",route)
                .putExtra("speaker_on",route == CallAudioState.ROUTE_SPEAKER).putExtra("status",message)
                .putExtra("backend_package",c.getPackageName()));
        XposedBridge.log("SpeakerControl: " + command + " success=" + ok + " " + message);
    }
    private static void log(String label, Throwable t) {
        XposedBridge.log("SpeakerControl: " + label + " failed: " + t); XposedBridge.log(t);
    }
}
