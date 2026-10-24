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
 * 单点全局：system_server 里的 SettingsProvider 是所有进程读取
 * Settings.Global 的唯一出口。把 adb_enabled 的读取结果改成 "0"，
 * 任何应用（无论是否在作用域里）通过 Settings API 读到的都是 0。
 *
 * 难点：SettingsProvider 的类不在 system_server 主 classloader 中
 * （它由 com.android.providers.settings APK 单独加载），所以改用
 * 框架层 ContentProvider.attachInfo 作为落点，在实例创建时挂 call。
 *
 * 安全点：system_server 自身（UsbDeviceManager）与 system uid(1000)
 * 读到真实值，否则 adbd 会被关掉导致 adb 断连。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "AdbHider: ";
    private static final String KEY = "adb_enabled";
    private static final String FAKE = "0";
    private static final String SP_CLASS = "com.android.providers.settings.SettingsProvider";

    private static boolean sProviderHooked = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if ("android".equals(lp.packageName) || "system".equals(lp.packageName)) {
            hookProvider(lp);
        }

        // system uid 进程保持真实行为（系统设置界面、系统服务）
        if (Process.myUid() != Process.SYSTEM_UID) {
            hookAppSide(lp);
        }
    }

    /* ---------------- system_server: SettingsProvider ---------------- */

    private void hookProvider(final XC_LoadPackage.LoadPackageParam lp) {
        // 1) 少数 ROM 把 provider 打进主 classloader，先直接试
        try {
            Class<?> sp = XposedHelpers.findClass(SP_CLASS, lp.classLoader);
            hookProviderClass(sp);
            XposedBridge.log(TAG + "provider class found directly");
            return;
        } catch (Throwable ignored) {
        }

        // 2) 通用路径：hook 框架层 ContentProvider.attachInfo，
        //    SettingsProvider 实例创建时拿到它真正的 Class 再挂 call
        try {
            Class<?> cp = XposedHelpers.findClass("android.content.ContentProvider", lp.classLoader);
            XposedBridge.hookAllMethods(cp, "attachInfo", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object self = param.thisObject;
                        if (self == null) return;
                        Class<?> cls = self.getClass();
                        if (!SP_CLASS.equals(cls.getName())) return;
                        hookProviderClass(cls);
                        XposedBridge.log(TAG + "provider instance attached: " + cls.getName());
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "attachInfo hook error: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + "attachInfo hook armed (waiting for SettingsProvider)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "ContentProvider not found: " + t);
        }
    }

    private synchronized void hookProviderClass(Class<?> cls) {
        if (sProviderHooked) return;
        sProviderHooked = true;

        XposedBridge.hookAllMethods(cls, "call", new XC_MethodHook() {
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
                    XposedBridge.log(TAG + "masked adb_enabled " + real + " -> 0, uid="
                            + Binder.getCallingUid() + " pid=" + Binder.getCallingPid());
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "call hook error: " + t);
                }
            }
        });
        XposedBridge.log(TAG + "SettingsProvider.call hooked");
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
