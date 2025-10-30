package com.example.anticenter.services;

import android.util.Log;

import com.example.anticenter.BuildConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Dify.ai Voice Phishing Detector
 * Uploads audio files and analyzes them for voice phishing using LLM-based workflow
 */
public class DifyVoiceDetector implements Closeable {

    private static final String TAG = "DifyVoiceDetector";

    // ===== Configuration =====
    private static final int CORE = 2;
    private static final int MAX = 3;
    private static final int QUEUE_CAP = 200;
    private static final int MAX_RETRY = 3;
    private static final long BASE_BACKOFF_MS = 1500;
    private static final long TASK_TIMEOUT_MS = 5 * 60 * 1000; // 5 minutes

    // ===== Dify API Endpoints (Official Dify.ai API) =====
    private static final String UPLOAD_URL = "https://api.dify.ai/v1/files/upload";
    private static final String WORKFLOW_URL = "https://api.dify.ai/v1/workflows/run";
    private static final String USER_EMAIL = BuildConfig.DIFY_USER_EMAIL;
    
    // // ===== Hardcoded API Key for Auth-Voice workflow =====
    // private static final String VOICE_DETECTION_API_KEY = "app-j0J1Qt5SLk305PwR61Djy5fn";

    // ===== Local Backend Callback (NOT Dify API - for posting detection results) =====
    // This is your local server endpoint to receive Dify detection results
    private static final String BACKEND_URL = "http://10.0.2.2:8080/dify/result";

    private final String apiKey;
    private final LinkedBlockingDeque<Runnable> queue = new LinkedBlockingDeque<>(QUEUE_CAP);

    private final ThreadPoolExecutor exec = new ThreadPoolExecutor(
            CORE, MAX, 60, TimeUnit.SECONDS, queue,
            r -> {
                Thread t = new Thread(r, "Dify-Detector");
                t.setDaemon(true);
                return t;
            },
            (r, e) -> { queue.pollFirst(); e.execute(r); } // Drop oldest task when full
    );

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Dify-Retry");
                t.setDaemon(true);
                return t;
            });

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Detection result data class
     */
    public static class VoicePhishingResult {
        public final String fileName;
        public final String verdict;        // "PHISHING" or "SAFE"
        public final double confidence;     // 0.0 to 1.0
        public final List<String> reasons;
        public final List<String> evidence;
        public final String uploadId;
        public final long processingTimeMs;

        public VoicePhishingResult(String fileName, String verdict, double confidence,
                                  List<String> reasons, List<String> evidence,
                                  String uploadId, long processingTimeMs) {
            this.fileName = fileName;
            this.verdict = verdict;
            this.confidence = confidence;
            this.reasons = reasons;
            this.evidence = evidence;
            this.uploadId = uploadId;
            this.processingTimeMs = processingTimeMs;
        }

        @Override
        public String toString() {
            return "VoicePhishingResult{" +
                    "fileName='" + fileName + '\'' +
                    ", verdict='" + verdict + '\'' +
                    ", confidence=" + confidence +
                    ", reasons=" + reasons.size() +
                    ", evidence=" + evidence.size() +
                    '}';
        }
    }

     public DifyVoiceDetector(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("Dify API key is required");
        }
        this.apiKey = apiKey;

        try {
            String masked = apiKey.length() > 4 ? "****" + apiKey.substring(apiKey.length() - 4) : "****";
            Log.i(TAG, "Initialized with API key: " + masked);
        } catch (Exception ignore) {}
    }

    /**
     * Non-blocking voice phishing detection
     */
    public CompletableFuture<VoicePhishingResult> submit(File file) {
        Objects.requireNonNull(file, "file == null");
        final CompletableFuture<VoicePhishingResult> future = new CompletableFuture<>();

        if (!file.exists() || file.length() == 0L) {
            future.completeExceptionally(new IllegalArgumentException("File not found or empty: " + file));
            return future;
        }

        Runnable task = () -> detectWithRetry(file, 1, future);
        exec.execute(wrapWithTimeout(task, future, file.getName()));
        return future;
    }

    /**
     * Detection with retry logic
     */
    private void detectWithRetry(File file, int attempt, CompletableFuture<VoicePhishingResult> future) {
        if (Thread.currentThread().isInterrupted()) {
            future.completeExceptionally(new InterruptedException("Interrupted"));
            return;
        }

        try {
            long startTime = System.currentTimeMillis();

            // Step 1: Upload file to Dify
            Log.i(TAG, "[" + file.getName() + "] Step 1/2: Uploading file (attempt " + attempt + ")...");
            String uploadId = uploadFile(file);

            if (uploadId == null || uploadId.isEmpty()) {
                throw new IOException("Failed to upload file");
            }

            Log.i(TAG, "[" + file.getName() + "] Upload successful. ID: " + uploadId);

            // Step 2: Call workflow
            Log.i(TAG, "[" + file.getName() + "] Step 2/2: Running phishing detection workflow...");
            JSONObject response = callWorkflow(uploadId);

            if (response == null) {
                throw new IOException("Failed to call workflow");
            }

            // Step 3: Parse result
            VoicePhishingResult result = parseResult(file.getName(), uploadId, response,
                    System.currentTimeMillis() - startTime);

            Log.i(TAG, "[" + file.getName() + "] ✅ Detection complete: " + result);

            future.complete(result);
            // notifyBackend(result);  // Disabled: No local backend server, data flows through ZoomCollector pipeline

        } catch (Exception ex) {
            if (attempt < MAX_RETRY) {
                long backoff = (long) (BASE_BACKOFF_MS * Math.pow(2, attempt - 1));
                Log.w(TAG, "[" + file.getName() + "] Attempt " + attempt + " failed, retrying in " + backoff + "ms: " + ex.getMessage());
                scheduler.schedule(
                        () -> detectWithRetry(file, attempt + 1, future),
                        backoff, TimeUnit.MILLISECONDS);
            } else {
                Log.e(TAG, "[" + file.getName() + "] Detection failed after " + MAX_RETRY + " attempts", ex);
                future.completeExceptionally(ex);
            }
        }
    }

    /**
     * Upload file to Dify and return upload ID
     *
     * This is Step 1 of the two-step Dify workflow:
     * 1. Upload file → get upload_file_id
     * 2. Call workflow with upload_file_id → get detection result
     *
     * @param file Audio file to upload
     * @return Upload ID string, or null if upload failed
     * @throws IOException if network error occurs
     */
    private String uploadFile(File file) throws IOException {
        // Determine MIME type (expanded to match reference implementation)
        String mimeType = "audio/wav"; // Default
        String extension = getFileExtension(file.getName()).toLowerCase();
        switch (extension) {
            case "mp3":
                mimeType = "audio/mpeg";
                break;
            case "wav":
                mimeType = "audio/wav";
                break;
            case "m4a":
                mimeType = "audio/mp4";
                break;
            case "ogg":
                mimeType = "audio/ogg";
                break;
            case "txt":
                mimeType = "text/plain";
                break;
            case "eml":
                mimeType = "message/rfc822";
                break;
            default:
                mimeType = "application/octet-stream";
                break;
        }

        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("user", USER_EMAIL)
                .addFormDataPart("file", file.getName(),
                        RequestBody.create(file, MediaType.parse(mimeType)))
                .build();

        Request request = new Request.Builder()
                .url(UPLOAD_URL)
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(requestBody)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                throw new IOException("Upload failed: HTTP " + response.code() + " - " + errorBody);
            }

            String responseBody = response.body() != null ? response.body().string() : "";
            JSONObject jsonResponse = new JSONObject(responseBody);
            return jsonResponse.optString("id", null);
        } catch (JSONException e) {
            throw new IOException("Failed to parse upload response", e);
        }
    }

    /**
     * Call Dify workflow with uploaded file ID
     *
     * This is Step 2 of the two-step Dify workflow.
     * Calls the phishing detection workflow using the file ID from Step 1.
     *
     * Request format:
     * {
     *   "inputs": {
     *     "InputVoice": {
     *       "transfer_method": "local_file",
     *       "upload_file_id": "{uploadId}",
     *       "type": "audio"
     *     }
     *   },
     *   "response_mode": "blocking",
     *   "user": "{email}"
     * }
     *
     * @param uploadId The file ID returned from uploadFile()
     * @return JSONObject with workflow response
     * @throws IOException if network error or workflow fails
     */
    private JSONObject callWorkflow(String uploadId) throws IOException {
        try {
            JSONObject payload = new JSONObject();
            JSONObject inputs = new JSONObject();
            JSONObject inputVoice = new JSONObject();

            inputVoice.put("transfer_method", "local_file");
            inputVoice.put("upload_file_id", uploadId);
            inputVoice.put("type", "audio");

            inputs.put("InputVoice", inputVoice);
            payload.put("inputs", inputs);
            payload.put("response_mode", "blocking");
            payload.put("user", USER_EMAIL);

            RequestBody requestBody = RequestBody.create(
                    payload.toString(),
                    MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(WORKFLOW_URL)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(requestBody)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    throw new IOException("Workflow failed: HTTP " + response.code() + " - " + errorBody);
                }

                String responseBody = response.body() != null ? response.body().string() : "";
                return new JSONObject(responseBody);
            }
        } catch (JSONException e) {
            throw new IOException("Failed to build workflow request", e);
        }
    }

    /**
     * Parse Dify workflow response
     *
     * Expected response structure:
     * {
     *   "data": {
     *     "status": "succeeded",
     *     "outputs": {
     *       "LLM": "{\"verdict\":\"...\", \"confidence\":0.X, \"reasons\":[...], \"evidence\":[...]}"
     *     }
     *   }
     * }
     *
     * The LLM output may be wrapped in markdown code blocks (```json ... ```), which we strip.
     * We handle multiple field names for flexibility (verdict/decision, confidence/score/likelihood).
     *
     * @param fileName Original filename for logging
     * @param uploadId Upload ID from Dify
     * @param response Workflow response JSON
     * @param processingTimeMs Time taken to process
     * @return VoicePhishingResult with normalized verdict
     * @throws IOException if parsing fails
     */
    private VoicePhishingResult parseResult(String fileName, String uploadId,
                                           JSONObject response, long processingTimeMs) throws IOException {
        try {
            // Navigate to data.outputs.LLM
            if (!response.has("data")) {
                Log.e(TAG, "[" + fileName + "] Response missing 'data' field. Full response: " + response.toString());
                throw new IOException("No 'data' field in response");
            }

            JSONObject data = response.getJSONObject("data");
            String status = data.optString("status", "");

            if (!"succeeded".equals(status)) {
                String error = data.optString("error", "Unknown error");
                Log.e(TAG, "[" + fileName + "] Workflow status: " + status + ", error: " + error);
                throw new IOException("Workflow failed: " + error);
            }

            if (!data.has("outputs")) {
                Log.e(TAG, "[" + fileName + "] Data missing 'outputs' field. Data: " + data.toString());
                throw new IOException("No 'outputs' field in data");
            }

            JSONObject outputs = data.getJSONObject("outputs");
            String llmOutput = outputs.optString("LLM", "");

            if (llmOutput.isEmpty()) {
                Log.e(TAG, "[" + fileName + "] Empty LLM output. Outputs: " + outputs.toString());
                throw new IOException("No LLM output found");
            }

            Log.d(TAG, "[" + fileName + "] Raw LLM output (first 200 chars): " +
                    llmOutput.substring(0, Math.min(200, llmOutput.length())));

            // Remove markdown code blocks if present (handles: ```json\n{...}\n```)
            String jsonContent = llmOutput.trim();

            // Strategy 1: Remove ```json ... ```
            if (jsonContent.contains("```json")) {
                int startIdx = jsonContent.indexOf("```json") + 7;  // Skip "```json"
                jsonContent = jsonContent.substring(startIdx);

                int endIdx = jsonContent.indexOf("```");
                if (endIdx > 0) {
                    jsonContent = jsonContent.substring(0, endIdx);
                }
                jsonContent = jsonContent.trim();
                Log.d(TAG, "[" + fileName + "] Stripped markdown (```json format)");
            }
            // Strategy 2: Remove ``` ... ```
            else if (jsonContent.startsWith("```")) {
                jsonContent = jsonContent.substring(3);  // Skip first ```

                int endIdx = jsonContent.indexOf("```");
                if (endIdx > 0) {
                    jsonContent = jsonContent.substring(0, endIdx);
                }
                jsonContent = jsonContent.trim();
                Log.d(TAG, "[" + fileName + "] Stripped markdown (``` format)");
            }
            // Strategy 3: Already clean JSON
            else {
                Log.d(TAG, "[" + fileName + "] No markdown detected, parsing as-is");
            }

            Log.d(TAG, "[" + fileName + "] Cleaned JSON (first 200 chars): " +
                    jsonContent.substring(0, Math.min(200, jsonContent.length())));

            // Parse the JSON result
            JSONObject resultJson = new JSONObject(jsonContent);

            // Extract verdict (case-insensitive, with fallback to multiple field names)
            String verdict = resultJson.optString("verdict", "");
            if (verdict.isEmpty()) {
                verdict = resultJson.optString("decision", "");
            }
            if (verdict.isEmpty()) {
                // Try additional alternative field names from reference implementation
                for (String key : new String[]{"result", "output", "classification"}) {
                    verdict = resultJson.optString(key, "");
                    if (!verdict.isEmpty()) {
                        Log.d(TAG, "[" + fileName + "] Found verdict in alternative field: " + key);
                        break;
                    }
                }
            }
            if (verdict.isEmpty()) {
                Log.w(TAG, "[" + fileName + "] No verdict/decision field found, will use confidence fallback");
            }

            String verdictUpper = verdict.toUpperCase();
            Log.d(TAG, "[" + fileName + "] Extracted verdict: '" + verdict + "' -> '" + verdictUpper + "'");

            // Extract confidence (try multiple field names)
            double confidence = resultJson.optDouble("confidence", -1.0);
            if (confidence < 0) {
                confidence = resultJson.optDouble("score", -1.0);
            }
            if (confidence < 0) {
                // Check for likelihood field (0-10 scale, convert to 0-1)
                int likelihood = resultJson.optInt("likelihood", -1);
                if (likelihood >= 0) {
                    confidence = likelihood / 10.0;
                    Log.d(TAG, "[" + fileName + "] Using likelihood as confidence: " + likelihood + " -> " + confidence);
                } else {
                    confidence = 0.0; // Default if no confidence found
                }
            }
            Log.d(TAG, "[" + fileName + "] Extracted confidence: " + confidence);

            // Extract reasons
            List<String> reasons = new ArrayList<>();
            JSONArray reasonsArray = resultJson.optJSONArray("reasons");
            if (reasonsArray != null) {
                for (int i = 0; i < reasonsArray.length(); i++) {
                    String reason = reasonsArray.getString(i);
                    reasons.add(reason);
                    Log.d(TAG, "[" + fileName + "] Reason " + (i+1) + ": " + reason);
                }
            } else {
                Log.d(TAG, "[" + fileName + "] No reasons array found");
            }

            // Extract evidence (handles both object and string formats)
            List<String> evidence = new ArrayList<>();
            JSONArray evidenceArray = resultJson.optJSONArray("evidence");
            if (evidenceArray != null) {
                for (int i = 0; i < evidenceArray.length(); i++) {
                    Object item = evidenceArray.get(i);
                    if (item instanceof JSONObject) {
                        JSONObject evidenceObj = (JSONObject) item;
                        String quote = evidenceObj.optString("quote", "");
                        String tactic = evidenceObj.optString("tactic", "");

                        if (!quote.isEmpty() && !tactic.isEmpty()) {
                            String formatted = "'" + quote + "' (" + tactic + ")";
                            evidence.add(formatted);
                            Log.d(TAG, "[" + fileName + "] Evidence " + (i+1) + ": " + formatted);
                        } else if (!quote.isEmpty()) {
                            evidence.add(quote);
                            Log.d(TAG, "[" + fileName + "] Evidence " + (i+1) + " (quote only): " + quote);
                        } else if (!tactic.isEmpty()) {
                            evidence.add(tactic);
                            Log.d(TAG, "[" + fileName + "] Evidence " + (i+1) + " (tactic only): " + tactic);
                        }
                    } else if (item instanceof String) {
                        String strEvidence = (String) item;
                        evidence.add(strEvidence);
                        Log.d(TAG, "[" + fileName + "] Evidence " + (i+1) + " (string): " + strEvidence);
                    }
                }
            } else {
                Log.d(TAG, "[" + fileName + "] No evidence array found");
            }

            // Normalize verdict (case-insensitive matching, expanded from reference implementation)
            String normalizedVerdict;
            if (verdictUpper.contains("PHISHING") || verdictUpper.contains("MALICIOUS") ||
                    verdictUpper.contains("SUSPICIOUS") || verdictUpper.contains("FRAUD") ||
                    verdictUpper.contains("SCAM") || verdictUpper.contains("THREAT")) {
                normalizedVerdict = "PHISHING";
                Log.i(TAG, "[" + fileName + "] Normalized verdict: PHISHING (matched: " + verdictUpper + ")");
            } else if (verdictUpper.contains("SAFE") || verdictUpper.contains("LEGITIMATE") ||
                    verdictUpper.contains("BENIGN") || verdictUpper.contains("CLEAN") ||
                    verdictUpper.contains("NORMAL") || verdictUpper.contains("SPAM")) {
                // Note: SPAM is treated as SAFE (not phishing) per reference implementation
                normalizedVerdict = "SAFE";
                Log.i(TAG, "[" + fileName + "] Normalized verdict: SAFE (matched: " + verdictUpper + ")");
            } else {
                // Use confidence as fallback (threshold: 0.5)
                normalizedVerdict = confidence >= 0.5 ? "PHISHING" : "SAFE";
                Log.w(TAG, "[" + fileName + "] Could not match verdict '" + verdictUpper +
                        "', using confidence fallback: " + normalizedVerdict + " (confidence=" + confidence + ")");
            }

            VoicePhishingResult result = new VoicePhishingResult(
                    fileName, normalizedVerdict, confidence, reasons, evidence, uploadId, processingTimeMs);

            Log.i(TAG, "[" + fileName + "] Parse successful: verdict=" + normalizedVerdict +
                    ", confidence=" + confidence + ", reasons=" + reasons.size() + ", evidence=" + evidence.size());

            return result;

        } catch (JSONException e) {
            Log.e(TAG, "[" + fileName + "] JSON parsing failed", e);
            throw new IOException("Failed to parse result: " + e.getMessage(), e);
        }
    }

    /**
     * Timeout wrapper
     */
    private Runnable wrapWithTimeout(Runnable task, CompletableFuture<VoicePhishingResult> future, String tag) {
        return () -> {
            var single = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Dify-Single");
                t.setDaemon(true);
                return t;
            });
            try {
                var taskFuture = single.submit(task);
                try {
                    taskFuture.get(TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException te) {
                    taskFuture.cancel(true);
                    future.completeExceptionally(te);
                    Log.e(TAG, "Task timeout: " + tag, te);
                }
            } catch (Exception e) {
                future.completeExceptionally(e);
            } finally {
                single.shutdownNow();
            }
        };
    }

    /**
     * Notify backend (optional)
     */
    private void notifyBackend(VoicePhishingResult result) {
        try {
            var payload = new java.util.HashMap<String, Object>();
            payload.put("fileName", result.fileName);
            payload.put("verdict", result.verdict);
            payload.put("confidence", result.confidence);
            payload.put("reasons", result.reasons);
            payload.put("evidence", result.evidence);
            payload.put("uploadId", result.uploadId);
            payload.put("processingTimeMs", result.processingTimeMs);
            payload.put("createdAt", System.currentTimeMillis());

            String json = mapper.writeValueAsString(payload);

            Request req = new Request.Builder()
                    .url(BACKEND_URL)
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .build();

            Log.i(TAG, "↗️ Posting Dify result to backend for " + result.fileName);

            httpClient.newCall(req).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Backend notify failed for " + result.fileName, e);
                }

                @Override
                public void onResponse(Call call, Response resp) throws IOException {
                    try {
                        Log.i(TAG, "✅ Backend ack (" + resp.code() + ") for " + result.fileName);
                    } finally {
                        resp.close();
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Backend notification error: " + result.fileName, e);
        }
    }

    /**
     * Get file extension
     */
    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(lastDot + 1) : "";
    }

    public void shutdown() {
        try { close(); } catch (Exception ignored) {}
    }

    @Override
    public void close() {
        exec.shutdownNow();
        scheduler.shutdownNow();
    }
}
