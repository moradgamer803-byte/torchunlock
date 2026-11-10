package com.zyvo.torchunlock;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * TorchUnlock v2 — surgical.
 *
 * v1 hooked generic battery APIs whenever any "Flashlight"/"Torch" class was on
 * the stack. The hook framework's own frames contain this class name
 * (com.zyvo.torchunlock.TorchHook), so the stack test was true for EVERY call
 * inside SystemUI: BatteryControllerImpl read level=100 from BATTERY_CHANGED and
 * the status bar showed 100% forever. That is the bug v2 fixes.
 *
 * v2 never inspects stack traces and never lies to a generic API outside the
 * exact gate. The ROM has two low-battery gates, both in FlashlightTile:
 *
 *   1. handleClick(): mBatteryManager.getIntProperty(4) < 15 -> toast, return.
 *   2. FlashlightTile$1.onReceive(): torch on && level < 15 -> force torch off.
 *
 * Hook plan:
 *   - ThreadLocal flag set ONLY while FlashlightTile.handleClick runs.
 *   - BatteryManager.getIntProperty returns 100 for CAPACITY only when that flag
 *     is set. Every other caller (battery icon, QS, every app) is untouched.
 *   - FlashlightTile$1.onReceive is a no-op (it only implements gate 2).
 */
public class TorchHook implements IXposedHookLoadPackage {

    private static final String TAG = "TorchUnlock";
    private static final String TARGET_PKG = "com.android.systemui";
    private static final String STATUS_ACTION = "com.zyvo.torchunlock.STATUS";
    private static final int FAKE_LEVEL = 100;

    /** True only while the flashlight tile's click handler is on this thread. */
    private static final ThreadLocal<Boolean> IN_CLICK = new ThreadLocal<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !TARGET_PKG.equals(lpparam.packageName)) return;
        if (lpparam.classLoader == null) return;

        List<String> armed = new ArrayList<>();

        // ---- gate 1: mark the exact call window -----------------------------
        try {
            Class<?> tile = XposedHelpers.findClass(
                    "com.android.systemui.qs.tiles.FlashlightTile", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(tile, "handleClick", View.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    IN_CLICK.set(Boolean.TRUE);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    IN_CLICK.remove();
                }
            });
            armed.add("click window");
        } catch (Throwable t) {
            log("handleClick hook failed: " + t);
        }

        // ---- capacity lie, scoped to that window only -----------------------
        try {
            XposedBridge.hookAllMethods(BatteryManager.class, "getIntProperty", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!Boolean.TRUE.equals(IN_CLICK.get())) return;
                    Object[] a = param.args;
                    if (a == null || a.length != 1 || !(a[0] instanceof Integer)) return;
                    if ((Integer) a[0] == BatteryManager.BATTERY_PROPERTY_CAPACITY) {
                        param.setResult(FAKE_LEVEL);
                    }
                }
            });
            armed.add("capacity lie (click only)");
        } catch (Throwable t) {
            log("getIntProperty hook failed: " + t);
        }

        // ---- gate 2: kill the auto-off listener -----------------------------
        try {
            Class<?> listener = XposedHelpers.findClass(
                    "com.android.systemui.qs.tiles.FlashlightTile$1", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(listener, "onReceive",
                    Context.class, Intent.class, XC_MethodReplacement.DO_NOTHING);
            armed.add("no auto-off");
        } catch (Throwable t) {
            log("onReceive hook failed: " + t);
        }

        log("armed: " + armed);
        report(armed);
    }

    /** Tells the app that SystemUI really loaded us (and which hooks stuck). */
    private static void report(List<String> armed) {
        try {
            Context ctx = (Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication");
            if (ctx == null) return;
            Intent i = new Intent(STATUS_ACTION);
            i.setPackage("com.zyvo.torchunlock");
            i.putStringArrayListExtra("layers", new ArrayList<>(armed));
            i.putExtra("fakeLevel", FAKE_LEVEL);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {
            log("report failed: " + t);
        }
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }
}
