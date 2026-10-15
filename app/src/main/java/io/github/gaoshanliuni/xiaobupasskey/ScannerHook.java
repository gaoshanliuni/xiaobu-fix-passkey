package io.github.gaoshanliuni.xiaobupasskey;

import android.app.Instrumentation;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** All hooks are installed exclusively in the scanner package's processes. */
public final class ScannerHook implements IXposedHookLoadPackage {
    private static final String TAG = "XiaobuPasskey";
    private static final String ENGINE = "com.oplus.scanengine.";
    private static final String HANDLED = "xiaobupasskey.handled";

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!FidoIntents.TARGET.equals(lp.packageName)) return;
        ClassLoader cl = lp.classLoader;
        // Install routing first: never change the parser without a working route hook.
        int routing = 0;
        routing += hookRouter(cl, ENGINE + "router.Router", "router");
        routing += hookRouter(cl, ENGINE + "router.RouterManager", "route");
        routing += hookRouter(cl, ENGINE + "router.routers.BrowserRouter", "route");
        routing += hookRouter(cl, ENGINE + "router.routers.TextRouter", "route");
        int parsers = routing == 0 ? 0 : hookParser(cl);
        int fallback = hookIntentFallback();
        log("loaded 1.1.1 process=" + lp.processName + " parser=" + parsers
                + " routing=" + routing + " intentFallback=" + fallback);
    }

    private static int hookParser(ClassLoader cl) {
        int installed = 0;
        try {
            Class<?> qbar = XposedHelpers.findClass(ENGINE + "common.data.QBarScanResult", cl);
            Class<?> type = XposedHelpers.findClass(ENGINE + "common.data.ParsedResultType", cl);
            Class<?> simple = XposedHelpers.findClass(ENGINE + "parser.parsers.SimpleParsedResult", cl);
            Constructor<?> constructor = simple.getConstructor(type, String.class);
            Object uriType = type.getField("URI").get(null);
            Method getRaw = qbar.getMethod("getMShowResult");
            Method getBitmap = qbar.getMethod("getDecodeImg");
            XC_MethodHook callback = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String raw = (String) getRaw.invoke(param.args[1]);
                    if (!FidoIntents.matches(raw)) return;
                    // This is a scanner routing envelope, not FIDO parsing. Preserve the raw value.
                    Object result = constructor.newInstance(uriType, raw);
                    XposedHelpers.setObjectField(result, "parsedImg", getBitmap.invoke(param.args[1]));
                    param.setResult(result);
                    log("raw FIDO result preserved; parser bypassed");
                }
            };
            for (String name : new String[]{ENGINE + "parser.ParserManager$Companion", ENGINE + "parser.ParserManager"}) {
                try {
                    Class<?> owner = XposedHelpers.findClass(name, cl);
                    XposedBridge.hookMethod(owner.getDeclaredMethod("parse", Context.class, qbar), callback);
                    installed++;
                } catch (Throwable error) {
                    log("parser compatibility: " + name + " " + error.getClass().getSimpleName());
                }
            }
        } catch (Throwable error) {
            log("parser unavailable: " + error.getClass().getSimpleName());
        }
        return installed;
    }

    private static int hookRouter(ClassLoader cl, String name, String methodName) {
        int installed = 0;
        try {
            Class<?> owner = XposedHelpers.findClass(name, cl);
            for (Method method : owner.getDeclaredMethods()) {
                Class<?>[] p = method.getParameterTypes();
                if (!method.getName().equals(methodName) || method.getReturnType() != void.class
                        || p.length != 3 || p[0] != Context.class
                        || !p[2].getName().equals(ENGINE + "router.IResultRouterCallback")) continue;
                Method display = p[1].getMethod("getDisplayResult");
                Method success = p[2].getMethod("onSuccess", String.class, boolean.class);
                Method failure = p[2].getMethod("onFail", Throwable.class, String.class);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args[1] == null) return;
                        String raw = (String) display.invoke(param.args[1]);
                        if (!FidoIntents.matches(raw)) return;
                        Context context = (Context) param.args[0];
                        if (context == null || !FidoIntents.TARGET.equals(context.getPackageName())) return;
                        Object callback = param.args[2];
                        // Consume even if Android cannot launch: never leak a FIDO URI to web search.
                        param.setResult(null);
                        onMain(() -> dispatch(context, raw, callback, success, failure));
                    }
                });
                installed++;
            }
        } catch (Throwable error) {
            log("route compatibility: " + name + " " + error.getClass().getSimpleName());
        }
        return installed;
    }

    private static void dispatch(Context context, String raw, Object callback, Method success, Method failure) {
        try {
            context.startActivity(FidoIntents.view(raw, context));
        } catch (RuntimeException error) {
            log("system handoff failed: " + error.getClass().getSimpleName());
            showFailure(context);
            notifyCallback(callback, failure, new IllegalStateException("System passkey handler unavailable"), "");
            return;
        }
        log("FIDO handed to Android ACTION_VIEW");
        // Preserve scanner completion/reset behavior, without invoking its text/search router.
        notifyCallback(callback, success, "", false);
    }

    private static void notifyCallback(Object callback, Method method, Object... args) {
        if (callback == null) return;
        try {
            method.invoke(callback, args);
        } catch (Throwable error) {
            // Do not print exception messages: vendor exceptions may embed scan contents.
            log("scanner completion callback failed: " + error.getClass().getSimpleName());
        }
    }

    private static int hookIntentFallback() {
        int installed = 0;
        for (Method method : Instrumentation.class.getDeclaredMethods()) {
            if (!method.getName().equals("execStartActivity")) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 0 || types[0] != Context.class) continue;
            int found = -1;
            for (int i = 0; i < types.length; i++) if (types[i] == Intent.class) found = i;
            if (found < 0) continue;
            final int intentIndex = found;
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Context context = (Context) param.args[0];
                        if (context == null || !FidoIntents.TARGET.equals(context.getPackageName())) return;
                        String raw = FidoIntents.fallbackRaw((Intent) param.args[intentIndex]);
                        if (raw == null) return;
                        param.args[intentIndex] = FidoIntents.view(raw, context);
                        param.setObjectExtra(HANDLED, Boolean.TRUE);
                        log("compatibility fallback replaced scanner search/text intent");
                    }
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!Boolean.TRUE.equals(param.getObjectExtra(HANDLED)) || !param.hasThrowable()) return;
                        Throwable error = param.getThrowable();
                        if (!(error instanceof ActivityNotFoundException) && !(error instanceof SecurityException)) return;
                        param.setResult(null);
                        log("fallback handler unavailable: " + error.getClass().getSimpleName());
                        showFailure((Context) param.args[0]);
                    }
                });
                installed++;
            } catch (Throwable error) {
                log("intent fallback unavailable: " + error.getClass().getSimpleName());
            }
        }
        return installed;
    }

    private static void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else new Handler(Looper.getMainLooper()).post(action);
    }

    private static void showFailure(Context context) {
        onMain(() -> Toast.makeText(context,
                "无法打开系统通行密钥处理器；已阻止将二维码内容作为搜索文本发送。",
                Toast.LENGTH_LONG).show());
    }

    private static void log(String message) {
        Log.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }
}
