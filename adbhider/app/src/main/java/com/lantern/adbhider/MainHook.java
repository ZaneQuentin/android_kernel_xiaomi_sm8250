package com.lantern.adbhider;

import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 全局隐藏 "USB 调试已开启" 状态。
 *
 * 思路（单点全局）：system_server 里的 SettingsProvider 是所有进程读取
 * Settings.Global / Settings.Secure / Settings.System 的唯一出口，
 * 在这里把 adb_enabled 的读取结果改成 "0"，任何应用（无论是否被勾进作用域）
 * 通过 Settings API 或直接 Binder call 读到的都是 0。
 *
 * 关键安全点：system_server 自身（含 UsbDeviceManager）与 system uid(1000)
 * 读到的是真实值，否则 UsbDeviceManager 会认为 USB 调试已关闭从而关掉 adbd，
 * 导致 adb 断连。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "AdbHider: ";
    private static final String KEY = "adb_enabled";
    private static final String FAKE = "0";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        final boolean isSystemServer = "android".equals(lp.packageName);

        if (isSystemServer) {
            hookProvider(lp);
        }

        // system uid 进程（system_server、系统设置）保持真实行为
        if (Process.myUid() != Process.SYSTEM_UID) {
            hookAppSide(lp);
        }
    }

    /* ---------------- system_server: SettingsProvider ---------------- */

    private void hookProvider(XC_LoadPackage.LoadPackageParam lp) {
        Class<?> sp;
        try {
            sp = XposedHelpers.findClass("com.android.providers.settings.SettingsProvider", lp.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "SettingsProvider not found: " + t);
            return;
        }

        XposedBridge.hookAllMethods(sp, "call", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object[] a = param.args;
                    if (a == null || a.length < 2) return;
                    if (!(a[1] instanceof String) || !KEY.equals(a[1])) return;
                    if (!(param.getResult() instanceof Bundle)) return;
                    if (!shouldMaskCaller()) return;

                    Bundle b = (Bundle) param.getResult();
                    String real = b.getString("value");
                    if (real == null) return;
                    b.putString("value", FAKE);
                    XposedBridge.log(TAG + "provider.call adb_enabled " + real + " -> 0, uid="
                            + Binder.getCallingUid() + " pid=" + Binder.getCallingPid());
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "call hook error: " + t);
                }
            }
        });

        XposedBridge.log(TAG + "provider hook installed");
    }

    /**
     * 只对外部读者伪装。system_server 本进程内部调用（UsbDeviceManager 等）
     * 以及 system uid 调用（系统设置界面）必须拿到真值。
     */
    private static boolean shouldMaskCaller() {
        try {
            if (Binder.getCallingPid() == Process.myPid()) return false;
            return Binder.getCallingUid() != Process.SYSTEM_UID;
        } catch (Throwable t) {
            return true;
        }
    }

    /* ---------------- 普通进程: Settings API 兜底 ---------------- */

    private void hookAppSide(XC_LoadPackage.LoadPackageParam lp) {
        XC_MethodHook intHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (hasKey(p.args)) p.setResult(0);
            }
        };
        XC_MethodHook strHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (hasKey(p.args)) p.setResult(FAKE);
            }
        };

        try {
            Class<?> global = XposedHelpers.findClass("android.provider.Settings$Global", lp.classLoader);
            XposedBridge.hookAllMethods(global, "getInt", intHook);
            XposedBridge.hookAllMethods(global, "getString", strHook);
            XposedBridge.hookAllMethods(global, "getStringForUser", strHook);
        } catch (Throwable ignored) {
        }

        try {
            Class<?> nvc = XposedHelpers.findClass("android.provider.Settings$NameValueCache", lp.classLoader);
            XposedBridge.hookAllMethods(nvc, "getIntForUser", intHook);
            XposedBridge.hookAllMethods(nvc, "getStringForUser", strHook);
        } catch (Throwable ignored) {
        }
    }

    private static boolean hasKey(Object[] args) {
        if (args == null) return false;
        for (Object o : args) {
            if (o instanceof String && KEY.equals(o)) return true;
        }
        return false;
    }
}
