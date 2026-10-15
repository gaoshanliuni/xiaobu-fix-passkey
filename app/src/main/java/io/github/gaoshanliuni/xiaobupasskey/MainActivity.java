package io.github.gaoshanliuni.xiaobupasskey;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.method.LinkMovementMethod;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private LinearLayout content;
    private TextView status;
    private TextView result;
    private final List<Button> buttons = new ArrayList<>();
    private boolean busy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(28), dp(24), dp(32));
        scroll.addView(content);
        text(R.string.app_name, 28);
        text(R.string.developer, 15);
        TextView projectAddress = text(R.string.project_address, 14);
        projectAddress.setMovementMethod(LinkMovementMethod.getInstance());
        projectAddress.setMinHeight(dp(48));
        status = text(R.string.loading_status, 18);
        button(R.string.choose_default, () -> choose(false));
        button(R.string.clear_default, () -> rootAction("clear"));
        button(R.string.application_settings, () -> choose(true));
        button(R.string.refresh_status, this::refreshStatus);
        result = text(R.string.defaults_help, 15);
        text(R.string.usage_help, 16);
        setContentView(scroll);
    }

    @Override public void onResume() {
        super.onResume();
        if (!busy) refreshStatus();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(int resource, int size) {
        TextView view = new TextView(this);
        view.setText(resource);
        view.setTextSize(size);
        view.setPadding(0, 0, 0, dp(16));
        content.addView(view);
        return view;
    }
    private void button(int label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(52));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8);
        content.addView(button, params);
        button.setOnClickListener(view -> action.run());
        buttons.add(button);
    }

    private List<ResolveInfo> handlers() {
        return getPackageManager().queryIntentActivities(SystemDefaults.probe(), PackageManager.MATCH_DEFAULT_ONLY);
    }
    private static ComponentName component(ResolveInfo info) {
        return new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
    }
    private void refreshStatus() {
        List<ResolveInfo> candidates = handlers();
        ResolveInfo resolved = getPackageManager().resolveActivity(SystemDefaults.probe(), PackageManager.MATCH_DEFAULT_ONLY);
        if (candidates.isEmpty()) { status.setText(R.string.no_handler); return; }
        boolean selected = false;
        if (resolved != null) for (ResolveInfo info : candidates) {
            if (component(info).equals(component(resolved))) selected = true;
        }
        if (selected) {
            status.setText(getString(candidates.size() == 1 ? R.string.only_handler : R.string.current_default,
                    resolved.loadLabel(getPackageManager())));
        } else status.setText(R.string.ask_each_time);
    }

    private void choose(boolean settings) {
        List<ResolveInfo> candidates = handlers();
        if (candidates.isEmpty()) { result.setText(R.string.no_handler); return; }
        String[] labels = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            ResolveInfo info = candidates.get(i);
            labels[i] = info.loadLabel(getPackageManager()) + "\n" + info.activityInfo.packageName;
        }
        new AlertDialog.Builder(this)
                .setTitle(settings ? R.string.application_settings : R.string.choose_default)
                .setItems(labels, (dialog, which) -> {
                    ResolveInfo choice = candidates.get(which);
                    if (settings) {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + choice.activityInfo.packageName)));
                    } else rootAction("set", component(choice).flattenToString());
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    private void rootAction(String... args) {
        if (busy) return;
        if (android.os.Process.myUid() / 100000 != 0) { result.setText(R.string.owner_only); return; }
        busy = true;
        for (Button button : buttons) button.setEnabled(false);
        result.setText(R.string.root_progress);
        StringBuilder command = new StringBuilder("CLASSPATH=").append(quote(getApplicationInfo().sourceDir))
                .append(" /system/bin/app_process /system/bin ").append(SystemDefaults.class.getName());
        for (String arg : args) command.append(' ').append(quote(arg));
        new Thread(() -> {
            boolean success = false;
            Process process = null;
            try {
                process = new ProcessBuilder("su", "-c", command.toString()).redirectErrorStream(true).start();
                if (!process.waitFor(60, TimeUnit.SECONDS)) process.destroyForcibly();
                else {
                    StringBuilder output = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null && output.length() < 8192) output.append(line).append('\n');
                    }
                    success = process.exitValue() == 0 && output.toString().contains("OK:" + args[0]);
                    if (!success) android.util.Log.w("XiaobuPasskeyUI", "Defaults helper exit=" + process.exitValue() + " output=" + output);
                }
            } catch (Exception error) {
                android.util.Log.w("XiaobuPasskeyUI", "Defaults helper launch failed", error);
                if (process != null) process.destroy();
            }
            boolean done = success;
            runOnUiThread(() -> {
                busy = false;
                if (isFinishing() || isDestroyed()) return;
                for (Button button : buttons) button.setEnabled(true);
                result.setText(done ? ("clear".equals(args[0]) ? R.string.cleared_success : R.string.saved_success) : R.string.root_failed);
                refreshStatus();
            });
        }, "FidoDefaults").start();
    }
}
