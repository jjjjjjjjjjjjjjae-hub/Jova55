package kz.almas.netoptimizer;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.TelephonyManager;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 100;
    private TextView tvNetwork;
    private TextView tvScore;
    private TextView tvTest;
    private TextView tvApn;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private int lastSignalScore = 50;
    private int bestDbmSeen = -140;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(makeUi());
        requestNeededPermissions();
        showTransport();
        refreshSignal();
    }

    private View makeUi() {
        int pad = dp(18);
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(Color.rgb(11, 18, 32));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        sc.addView(root);

        TextView title = text("ALMAS NET OPTIMIZER", 24, Color.WHITE, true);
        root.addView(title);

        tvNetwork = card("Желі тексерілуде...");
        tvScore = card("Network Score: --/100");
        tvScore.setTextColor(Color.rgb(124, 255, 178));
        tvScore.setTextSize(21);
        tvTest = card("Ping: -- ms\nJitter: -- ms\nLoss: --%");
        tvApn = card("APN: Android қорғайды. Root арқылы оқуға болады.");

        root.addView(tvNetwork, lp(14));
        root.addView(tvScore, lp(10));
        root.addView(tvTest, lp(10));

        Button test = button("ИНТЕРНЕТТІ ТОЛЫҚ ТЕКСЕРУ");
        test.setOnClickListener(v -> runNetworkTest());
        root.addView(test, lp(12));

        Button signal = button("СИГНАЛДЫ ЖАҢАРТУ");
        signal.setOnClickListener(v -> {
            showTransport();
            refreshSignal();
        });
        root.addView(signal, lp(8));

        root.addView(tvApn, lp(12));

        Button apn = button("ROOT APN ТЕКСЕРУ");
        apn.setOnClickListener(v -> readApnWithRoot());
        root.addView(apn, lp(8));

        TextView note = text(
                "APN сигнал күшін көтермейді. Баға сигнал + кідіріс + jitter + loss бойынша есептеледі. Root тек APN тексергенде сұралады.",
                13, Color.rgb(148, 163, 184), false);
        root.addView(note, lp(14));
        return sc;
    }

    private LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        return p;
    }

    private TextView card(String s) {
        TextView v = text(s, 17, Color.rgb(229, 231, 235), false);
        v.setPadding(dp(14), dp(14), dp(14), dp(14));
        v.setBackgroundColor(Color.rgb(22, 32, 51));
        return v;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(null, android.graphics.Typeface.BOLD);
        return v;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        return b;
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= 23 &&
                (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED ||
                 checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQ_PERMS);
        }
    }

    private void showTransport() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Network n = cm.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        String type = "Белгісіз";
        boolean validated = false;
        if (caps != null) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) type = "Мобильді интернет";
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) type = "Wi‑Fi";
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) type = "Ethernet";
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        }
        tvNetwork.setText("Қосылу: " + type + "\nИнтернет: " + (validated ? "бар ✓" : "тексерілмеді / жоқ"));
    }

    private void refreshSignal() {
        pool.execute(() -> {
            StringBuilder out = new StringBuilder();
            int bestDbm = -140;
            String radio = "Белгісіз";
            try {
                TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
                String operator = tm.getNetworkOperatorName();
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread(() -> tvNetwork.setText("Сигнал үшін Location рұқсатын бер."));
                    return;
                }
                List<CellInfo> cells = tm.getAllCellInfo();
                if (cells != null) {
                    for (CellInfo c : cells) {
                        if (c instanceof CellInfoLte) {
                            CellSignalStrengthLte s = ((CellInfoLte) c).getCellSignalStrength();
                            bestDbm = Math.max(bestDbm, s.getDbm());
                            radio = "4G/LTE";
                        } else if (Build.VERSION.SDK_INT >= 29 && c instanceof CellInfoNr) {
                            CellSignalStrengthNr s = (CellSignalStrengthNr) ((CellInfoNr) c).getCellSignalStrength();
                            bestDbm = Math.max(bestDbm, s.getDbm());
                            radio = "5G/NR";
                        }
                    }
                }

                if (bestDbm <= -140) {
                    lastSignalScore = 50;
                    out.append("Оператор: ").append(empty(operator) ? "—" : operator)
                            .append("\nСигнал: дерек жоқ\nРұқсаттарды тексер.");
                } else {
                    if (bestDbm > bestDbmSeen) bestDbmSeen = bestDbm;
                    lastSignalScore = scoreSignal(bestDbm);
                    out.append("Оператор: ").append(empty(operator) ? "—" : operator)
                            .append("\nЖелі: ").append(radio)
                            .append("\nСигнал: ").append(bestDbm).append(" dBm")
                            .append("\nБаға: ").append(signalLabel(bestDbm))
                            .append("\nОсы сессиядағы ең жақсысы: ").append(bestDbmSeen).append(" dBm");
                }
            } catch (Exception e) {
                out.append("Сигналды оқу қатесі: ").append(e.getClass().getSimpleName());
            }
            String text = out.toString();
            runOnUiThread(() -> tvNetwork.setText(text));
        });
    }

    private boolean empty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private int scoreSignal(int dbm) {
        if (dbm >= -80) return 100;
        if (dbm <= -120) return 10;
        return Math.max(10, Math.min(100, 100 - ((-80 - dbm) * 90 / 40)));
    }

    private String signalLabel(int dbm) {
        if (dbm >= -85) return "Өте жақсы";
        if (dbm >= -95) return "Жақсы";
        if (dbm >= -105) return "Орташа";
        if (dbm >= -115) return "Нашар";
        return "Өте нашар";
    }

    private void runNetworkTest() {
        tvTest.setText("Тест жүріп жатыр...");
        pool.execute(() -> {
            List<Long> times = new ArrayList<>();
            int failures = 0;
            for (int i = 0; i < 8; i++) {
                long ms = probe("https://www.google.com/generate_204");
                if (ms >= 0) times.add(ms); else failures++;
            }

            if (times.isEmpty()) {
                runOnUiThread(() -> {
                    tvTest.setText("Интернетке тест қосыла алмады.");
                    tvScore.setText("Network Score: 0/100");
                });
                return;
            }

            Collections.sort(times);
            double avg = 0;
            for (long t : times) avg += t;
            avg /= times.size();

            double jitter = 0;
            for (int i = 1; i < times.size(); i++) {
                jitter += Math.abs(times.get(i) - times.get(i - 1));
            }
            if (times.size() > 1) jitter /= (times.size() - 1);

            int loss = (int) Math.round(failures * 100.0 / 8.0);
            int latencyScore = avg <= 40 ? 100 : avg >= 350 ? 10 : (int) (100 - ((avg - 40) * 90 / 310));
            int jitterScore = jitter <= 8 ? 100 : jitter >= 100 ? 10 : (int) (100 - ((jitter - 8) * 90 / 92));
            int lossScore = Math.max(0, 100 - loss * 4);
            int score = (int) Math.round(lastSignalScore * 0.30 + latencyScore * 0.35 + jitterScore * 0.20 + lossScore * 0.15);
            int finalScore = Math.max(0, Math.min(100, score));
            String details = String.format(Locale.US,
                    "Ping: %.0f ms\nJitter: %.0f ms\nLoss: %d%%",
                    avg, jitter, loss);

            runOnUiThread(() -> {
                tvTest.setText(details);
                tvScore.setText("Network Score: " + finalScore + "/100\n" + scoreLabel(finalScore));
            });
        });
    }

    private long probe(String urlStr) {
        HttpURLConnection c = null;
        try {
            long start = System.nanoTime();
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
            c.setRequestMethod("GET");
            c.setUseCaches(false);
            int code = c.getResponseCode();
            long ms = (System.nanoTime() - start) / 1_000_000L;
            return (code >= 200 && code < 500) ? ms : -1;
        } catch (Exception e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String scoreLabel(int s) {
        if (s >= 90) return "Өте тұрақты";
        if (s >= 75) return "Жақсы";
        if (s >= 55) return "Орташа";
        if (s >= 35) return "Нашар";
        return "Өте нашар";
    }

    private void readApnWithRoot() {
        tvApn.setText("Root рұқсаты сұралуы мүмкін...");
        pool.execute(() -> {
            String result;
            try {
                Process p = new ProcessBuilder(
                        "su", "-c",
                        "content query --uri content://telephony/carriers/preferapn --projection name:apn:type:protocol:roaming_protocol"
                ).start();

                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
                int code = p.waitFor();

                if (code == 0 && sb.length() > 0) {
                    result = "APN дерегі (root):\n" + sb.toString().trim()
                            + "\n\nAPN бағасы: интернет тестіндегі Network Score-пен салыстыр.";
                } else {
                    result = "Root берілмеді немесе APN провайдеріне қолжетім жоқ.";
                }
            } catch (Exception e) {
                result = "Root табылмады. Magisk болса, сұранысты рұқсат ет.";
            }
            String finalResult = result;
            runOnUiThread(() -> tvApn.setText(finalResult));
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }
}
