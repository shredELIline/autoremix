package com.alexey.autoremix;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQUEST_AUDIO = 71;
    private TextView status;
    private TextView library;
    private TextView current;
    private TextView currentMeta;
    private TextView next;
    private TextView transition;
    private TextView chaosLabel;
    private TextView patienceLabel;
    private ProgressBar progress;
    private Button pause;
    private int chaos = 62;
    private int patience = 78;
    private boolean overlays = true;
    private boolean harmonic = true;
    private final android.os.Handler handler = new android.os.Handler();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(7, 8, 14));
        getWindow().setNavigationBarColor(Color.rgb(7, 8, 14));
        setContentView(buildUi());
        handler.post(poll);
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(7, 8, 14));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(44));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView badge = text("WOW PCM STEM DIRECTOR · 2.0", 12, Color.rgb(164, 137, 255));
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(badge);
        TextView title = text("AutoRemix Stem Director", 34, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        TextView subtitle = text("Единый PCM-аудиодвижок заранее строит сцену, разделяет обе песни на lead/drums/bass/backing и плавно меняет только музыкальные роли. Полного резкого A→B в автоматическом режиме нет.", 15, Color.rgb(190, 194, 214));
        subtitle.setPadding(0, dp(6), 0, dp(18));
        root.addView(subtitle);

        LinearLayout stateCard = card();
        status = text("Готов к запуску", 18, Color.WHITE);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        stateCard.addView(status);
        library = text("Медиатека: ещё не просканирована", 13, Color.rgb(157, 163, 187));
        library.setPadding(0, dp(6), 0, dp(10));
        stateCard.addView(library);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        stateCard.addView(progress, new LinearLayout.LayoutParams(-1, dp(7)));
        root.addView(stateCard);

        Button start = primaryButton("ЗАПУСТИТЬ БЕСКОНЕЧНЫЙ РЕМИКС");
        start.setOnClickListener(v -> ensurePermissionAndStart());
        root.addView(start, marginTop(16));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        pause = secondaryButton("Пауза");
        pause.setOnClickListener(v -> send(RemixEngineService.paused ? RemixEngineService.ACTION_RESUME : RemixEngineService.ACTION_PAUSE));
        controls.addView(pause, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button skip = secondaryButton("Перестроить следующую сцену");
        skip.setOnClickListener(v -> send(RemixEngineService.ACTION_SKIP));
        LinearLayout.LayoutParams skipParams = new LinearLayout.LayoutParams(0, dp(52), 1);
        skipParams.leftMargin = dp(9);
        controls.addView(skip, skipParams);
        root.addView(controls, marginTop(9));

        Button stop = tertiaryButton("Остановить");
        stop.setOnClickListener(v -> send(RemixEngineService.ACTION_STOP));
        root.addView(stop, marginTop(9));

        TextView playingHeader = section("СЕЙЧАС В МИКСЕ");
        root.addView(playingHeader);
        LinearLayout currentCard = card();
        current = text("—", 18, Color.WHITE);
        current.setTypeface(Typeface.DEFAULT_BOLD);
        currentCard.addView(current);
        currentMeta = text("—", 13, Color.rgb(154, 226, 213));
        currentMeta.setPadding(0, dp(7), 0, 0);
        currentCard.addView(currentMeta);
        root.addView(currentCard);

        TextView nextHeader = section("СЛЕДУЮЩАЯ МУЗЫКАЛЬНАЯ СЦЕНА");
        root.addView(nextHeader);
        LinearLayout nextCard = card();
        next = text("—", 16, Color.WHITE);
        next.setTypeface(Typeface.DEFAULT_BOLD);
        nextCard.addView(next);
        transition = text("—", 13, Color.rgb(180, 163, 255));
        transition.setPadding(0, dp(7), 0, 0);
        nextCard.addView(transition);
        root.addView(nextCard);

        TextView settingsHeader = section("ХАРАКТЕР РЕМИКСА");
        root.addView(settingsHeader);
        LinearLayout settings = card();
        chaosLabel = text("Смелость сценариев: " + chaos + "%", 14, Color.WHITE);
        settings.addView(chaosLabel);
        SeekBar chaosBar = new SeekBar(this);
        chaosBar.setMax(100);
        chaosBar.setProgress(chaos);
        chaosBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                chaos = Math.max(10, value);
                chaosLabel.setText("Смелость сценариев: " + chaos + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        settings.addView(chaosBar, new LinearLayout.LayoutParams(-1, -2));

        patienceLabel = text("Дать трекам звучать: " + patience + "%", 14, Color.WHITE);
        patienceLabel.setPadding(0, dp(12), 0, 0);
        settings.addView(patienceLabel);
        SeekBar patienceBar = new SeekBar(this);
        patienceBar.setMax(100);
        if (Build.VERSION.SDK_INT >= 26) patienceBar.setMin(20);
        patienceBar.setProgress(patience);
        patienceBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                patience = Math.max(20, value);
                patienceLabel.setText("Дать трекам звучать: " + patience + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        settings.addView(patienceBar, new LinearLayout.LayoutParams(-1, -2));

        settings.addView(toggle("Гостевые слои с возвратом к исходному треку", true, checked -> overlays = checked));
        settings.addView(toggle("Строгая гармоническая совместимость", true, checked -> harmonic = checked));
        root.addView(settings);

        LinearLayout info = card();
        TextView infoTitle = text("Что именно делает эта версия", 17, Color.WHITE);
        infoTitle.setTypeface(Typeface.DEFAULT_BOLD);
        info.addView(infoTitle);
        info.addView(text("• Трек получает длинный сольный участок; следующая сцена рассчитывается заранее в фоне.\n" +
                "• Кандидаты сравниваются по тембру, басу, ударным, динамике, энергии, тональности, beat-grid и роли вокала.\n" +
                "• Граф вайба проверяет ещё и будущие пути, чтобы хороший переход не завёл микс в музыкальный тупик.\n" +
                "• После WSOLA-подгонки темпа выполняется отдельное фазовое совмещение ударов по onset-корреляции.\n" +
                "• HPSS + mid/side разложение создаёт четыре комплементарных слоя: lead, drums, bass и backing.\n" +
                "• Если ведёт вокал A, меняется только фон B; если ведёт грув A, постепенно появляется только lead B.\n" +
                "• Два вокала разводятся последовательно: сначала приходит бэк B, затем уходит lead A, после чего раскрывается lead B.\n" +
                "• Один AudioTrack и sample-level cosine automation исключают команды mute/start/release между отдельными проигрывателями.\n" +
                "• DC blocker, единый limiter, мягкая сатурация и безопасные границы сцен защищают от щелчков и клиппинга.\n" +
                "• Если нет действительно совместимой пары, текущая композиция продолжается — плохой fallback запрещён.", 13, Color.rgb(188, 192, 211)));
        root.addView(info, marginTop(18));

        TextView boundary = text("Поддерживаются обычные MP3/M4A/FLAC/WAV-файлы, видимые Android в медиатеке. Офлайн-кэш Яндекс Музыки закрыт внутри приложения Яндекса и здесь не появится — его подключим отдельным источником позже.", 12, Color.rgb(132, 138, 160));
        boundary.setPadding(0, dp(16), 0, 0);
        root.addView(boundary);
        return scroll;
    }

    private void ensurePermissionAndStart() {
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{permission}, REQUEST_AUDIO);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 72);
        }
        Intent intent = new Intent(this, RemixEngineService.class).setAction(RemixEngineService.ACTION_START)
                .putExtra(RemixEngineService.EXTRA_CHAOS, chaos)
                .putExtra(RemixEngineService.EXTRA_OVERLAYS, overlays)
                .putExtra(RemixEngineService.EXTRA_HARMONIC, harmonic)
                .putExtra(RemixEngineService.EXTRA_PATIENCE, patience);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) ensurePermissionAndStart();
            else Toast.makeText(this, "Без доступа к аудиофайлам AutoDJ не увидит медиатеку", Toast.LENGTH_LONG).show();
        }
    }

    private void send(String action) {
        Intent intent = new Intent(this, RemixEngineService.class).setAction(action);
        try { startService(intent); } catch (Throwable ignored) {}
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            status.setText(RemixEngineService.status);
            library.setText("Медиатека: " + RemixEngineService.librarySize + " треков · проанализировано " + RemixEngineService.analyzedCount);
            current.setText(RemixEngineService.currentTrack);
            currentMeta.setText(RemixEngineService.currentMeta);
            next.setText(RemixEngineService.nextTrack);
            transition.setText(RemixEngineService.transition);
            progress.setProgress(RemixEngineService.progress);
            pause.setText(RemixEngineService.paused ? "Продолжить" : "Пауза");
            handler.postDelayed(this, 350);
        }
    };

    @Override protected void onDestroy() {
        handler.removeCallbacks(poll);
        super.onDestroy();
    }

    private TextView section(String title) {
        TextView view = text(title, 12, Color.rgb(151, 156, 180));
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(26), 0, dp(9));
        return view;
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(17), dp(17), dp(17), dp(17));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(19, 21, 33));
        background.setCornerRadius(dp(19));
        background.setStroke(dp(1), Color.rgb(35, 38, 57));
        layout.setBackground(background);
        return layout;
    }

    private Button primaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setAllCaps(false);
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(116, 77, 255), Color.rgb(74, 203, 193)});
        background.setCornerRadius(dp(16));
        button.setBackground(background);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setMinHeight(dp(56));
        return button;
    }

    private Button secondaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(13);
        button.setAllCaps(false);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(26, 29, 44));
        background.setCornerRadius(dp(15));
        background.setStroke(dp(1), Color.rgb(57, 60, 84));
        button.setBackground(background);
        return button;
    }

    private Button tertiaryButton(String label) {
        Button button = secondaryButton(label);
        button.setTextColor(Color.rgb(187, 191, 209));
        button.setMinHeight(dp(48));
        return button;
    }

    private Switch toggle(String label, boolean initial, BoolSetter setter) {
        Switch control = new Switch(this);
        control.setText(label);
        control.setTextColor(Color.WHITE);
        control.setTextSize(13);
        control.setChecked(initial);
        control.setPadding(0, dp(10), 0, dp(4));
        control.setOnCheckedChangeListener((buttonView, isChecked) -> setter.set(isChecked));
        return control;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.12f);
        return view;
    }

    private LinearLayout.LayoutParams marginTop(int value) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(value);
        return params;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private interface BoolSetter { void set(boolean value); }
}
