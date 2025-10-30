package com.example.anticenter.services;

import android.util.Log;

import ai.realitydefender.RealityDefender;
import ai.realitydefender.exceptions.RealityDefenderException;
import ai.realitydefender.models.DetectionResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.anticenter.BuildConfig;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

public class RealityDefenderDetection implements Closeable {

    // ===== 可调参数 =====
    private static final int CORE = 2;
    private static final int MAX = 3;
    private static final int QUEUE_CAP = 200;
    private static final int MAX_RETRY = 3;
    private static final long BASE_BACKOFF_MS = 1200;
    private static final long TASK_TIMEOUT_MS = 4 * 60 * 1000;

    // ===== 回调你的后端（按需改）=====
    private static final String BACKEND_URL = "https://your.backend.example.com/rd/result";

    // 有界队列 + 最新优先（满了丢最旧）
    private final LinkedBlockingDeque<Runnable> queue = new LinkedBlockingDeque<>(QUEUE_CAP);

    public RealityDefenderDetection(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("API key is required");
        }
        // 可选：打个掩码日志
        try {
            String masked = apiKey.length() > 4 ? "****" + apiKey.substring(apiKey.length() - 4) : "****";
            System.out.println("[RD] Using API key: " + masked);
            android.util.Log.i("RD", "Using API key: " + masked);
        } catch (Exception ignore) {}

        this.rd = ai.realitydefender.RealityDefender.builder()
                .apiKey(apiKey)
                .build();
    }

    private final ThreadPoolExecutor exec = new ThreadPoolExecutor(
            CORE, MAX, 60, TimeUnit.SECONDS, queue,
            r -> {
                Thread t = new Thread(r, "RD-Detector");
                t.setDaemon(true);
                return t;
            },
            (r, e) -> { queue.pollFirst(); e.execute(r); } // 丢最旧再塞新任务
    );

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "RD-Retry");
                t.setDaemon(true);
                return t;
            });

    // RD 客户端（照官方样例）
    private final RealityDefender rd;

    // 回调 HTTP 客户端
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    public RealityDefenderDetection() {
        // 在 Gradle 里注入 BuildConfig.RD_API_KEY（见下）
        String apiKey = BuildConfig.RD_API_KEY;
        this.rd = RealityDefender.builder()
                .apiKey(apiKey)
                .build();
    }

    /** 非阻塞投递；立即返回 Future */
    public CompletableFuture<DetectionResult> submit(File file) {
        Objects.requireNonNull(file, "file == null");
        final CompletableFuture<DetectionResult> f = new CompletableFuture<>();

        if (!file.exists() || file.length() == 0L) {
            f.completeExceptionally(new IllegalArgumentException("File not found or empty: " + file));
            return f;
        }

        Runnable task = () -> runWithRetry(file, 1, f);
        exec.execute(wrapWithTimeout(task, f, file.getName()));
        return f;
    }

    /** 指数退避重试 */
    private void runWithRetry(File file, int attempt, CompletableFuture<DetectionResult> f) {
        if (Thread.currentThread().isInterrupted()) {
            f.completeExceptionally(new InterruptedException("Interrupted"));
            return;
        }
        try {
            // 官方样例的同步调用放后台线程执行
            DetectionResult result = rd.detectFile(file);

            System.out.println("[RD] ✅ Result received for " + file.getName()
                    + " | status=" + result.getStatus()
                    + " | requestId=" + result.getRequestId());
            android.util.Log.i("RD", "Result received: " + file.getName()
                    + " status=" + result.getStatus() + " requestId=" + result.getRequestId());

            f.complete(result);
            // notifyBackend(file.getName(), result);  // Disabled: using internal data pipeline instead
        } catch (RealityDefenderException ex) {
            if (attempt <= MAX_RETRY) {
                long backoff = (long) (BASE_BACKOFF_MS * Math.pow(2, attempt - 1));
                scheduler.schedule(
                        () -> runWithRetry(file, attempt + 1, f),
                        backoff, TimeUnit.MILLISECONDS);
            } else {
                f.completeExceptionally(ex);
                Log.e("RD", "Detect failed after retry: " + file.getName(), ex);
            }
        } catch (Exception ex) {
            f.completeExceptionally(ex);
            Log.e("RD", "Detect failed: " + file.getName(), ex);
        }
    }

    /** 超时包装（避免单个任务卡死） */
    private Runnable wrapWithTimeout(Runnable task, CompletableFuture<DetectionResult> f, String tag) {
        return () -> {
            var single = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "RD-Single");
                t.setDaemon(true);
                return t;
            });
            try {
                Future<?> future = single.submit(task);
                try {
                    future.get(TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException te) {
                    future.cancel(true);
                    f.completeExceptionally(te);
                    Log.e("RD", "Task timeout: " + tag, te);
                }
            } catch (Exception e) {
                f.completeExceptionally(e);
            } finally {
                single.shutdownNow();
            }
        };
    }

    /** 结果推送到你的后端（OkHttp 异步，不阻塞检测线程） */
    private void notifyBackend(String fileName, DetectionResult result) {
        try {
            var payload = new java.util.HashMap<String, Object>();
            payload.put("fileName", fileName);
            payload.put("status", result.getStatus());
            payload.put("score", result.getScore());
            payload.put("requestId", result.getRequestId());
            payload.put("createdAt", System.currentTimeMillis());
            payload.put("models", result.getModels());

            String json = mapper.writeValueAsString(payload);

            Request req = new Request.Builder()
                    .url(BACKEND_URL)
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .build();

            System.out.println("[RD] ↗️ Posting result to backend for " + fileName + ": " + json);
            android.util.Log.i("RD", "Posting result to backend for " + fileName);

            http.newCall(req).enqueue(new Callback() {
                // OkHttp 回调里：
                @Override public void onFailure(Call call, IOException e) {
                    System.out.println("[RD] ❌ Backend notify failed for " + fileName + " | " + e.getMessage());
                    android.util.Log.e("RD", "notifyBackend failed: " + fileName, e);
                }
                @Override public void onResponse(Call call, Response resp) throws IOException {
                    try {
                        System.out.println("[RD] ✅ Backend ack (" + resp.code() + ") for " + fileName);
                        android.util.Log.i("RD", "Backend ack " + resp.code() + " for " + fileName);
                        if (!resp.isSuccessful()) {
                            System.out.println("[RD] ⚠️ Backend non-2xx for " + fileName + ": " + resp.code());
                        }
                    } finally { resp.close(); }
                }
            });
        } catch (Exception e) {
            Log.e("RD", "notifyBackend serialization error: " + fileName, e);
        }
    }

    public void shutdown() {
        try { close(); } catch (Exception ignored) {}
    }

    @Override public void close() {
        exec.shutdownNow();
        scheduler.shutdownNow();
        try { rd.close(); } catch (Exception ignored) {}
    }
}
