package io.github.gaoshanliuni.xiaobupasskey;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;

final class FidoIntents {
    static final String TARGET = "com.coloros.ocrscanner";
    private FidoIntents() {}

    static boolean matches(String raw) {
        return raw != null && raw.startsWith("FIDO:/");
    }

    static Activity activity(Context context) {
        for (int depth = 0; depth < 12 && context != null; depth++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) return null;
            context = next;
        }
        return null;
    }

    static Intent view(String raw, Context context) {
        // Deliberately no trimming, decoding, scheme normalization, package or component.
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(raw));
        if (activity(context) == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    static String fallbackRaw(Intent intent) {
        if (intent == null) return null;
        String raw = null;
        if (Intent.ACTION_WEB_SEARCH.equals(intent.getAction())) {
            raw = intent.getStringExtra("query");
        } else if (intent.getComponent() != null
                && TARGET.equals(intent.getComponent().getPackageName())
                && ("com.oplus.scanengine.router.ui.DialogRouterActivity".equals(intent.getComponent().getClassName())
                || "com.oplus.scanengine.router.ui.DialogServerRouterActivity".equals(intent.getComponent().getClassName()))
                && "type_text".equals(intent.getStringExtra("router_type"))) {
            raw = intent.getStringExtra("router_content");
        }
        return matches(raw) ? raw : null;
    }
}
