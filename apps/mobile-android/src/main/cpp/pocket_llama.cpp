#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "llama.h"

namespace {
constexpr const char * TAG = "PocketLlamaJNI";
constexpr int DEFAULT_CONTEXT_SIZE = 512;
constexpr int DEFAULT_BATCH_SIZE = 512;
constexpr int PROMPT_DECODE_BATCH_SIZE = 64;
constexpr int CACHE_POLICY_OFF = 0;
constexpr int CACHE_POLICY_PREFIX_REUSE = 1;
constexpr int CACHE_POLICY_PREFIX_REUSE_STRICT = 2;
constexpr jint STREAM_STATUS_COMPLETED = 0;
constexpr jint STREAM_STATUS_MAX_TOKENS = 1;
constexpr jint STREAM_STATUS_CANCELLED = 2;
constexpr jint STREAM_STATUS_CALLBACK_ERROR = 3;
constexpr jint STREAM_STATUS_UTF8_STREAM_ERROR = 4;
constexpr jint STREAM_STATUS_RUNTIME_ERROR = 5;

std::mutex g_mutex;
bool g_backend_initialized = false;
llama_model * g_model = nullptr;
llama_context * g_context = nullptr;
llama_sampler * g_sampler = nullptr;
std::atomic<bool> g_cancel_requested{false};
std::vector<llama_token> g_cached_prompt_tokens;
std::string g_cached_cache_key;

int32_t clamp_i32(int32_t value, int32_t min_value, int32_t max_value) {
    return std::max(min_value, std::min(value, max_value));
}

int32_t resolve_threads(jint requested_threads) {
    const auto hardware_threads = std::thread::hardware_concurrency();
    const int32_t fallback = static_cast<int32_t>(
        std::max(2u, std::min(hardware_threads == 0 ? 4u : hardware_threads, 8u)));
    if (requested_threads <= 0) {
        return fallback;
    }
    return clamp_i32(static_cast<int32_t>(requested_threads), 1, 16);
}

int32_t resolve_batch(jint requested_batch) {
    if (requested_batch <= 0) {
        return DEFAULT_BATCH_SIZE;
    }
    return clamp_i32(static_cast<int32_t>(requested_batch), 32, 2048);
}

bool gpu_offload_supported() {
#if defined(GGML_USE_VULKAN) || defined(GGML_USE_OPENCL) || defined(GGML_USE_CUDA) || defined(GGML_USE_METAL)
    return true;
#else
    return false;
#endif
}

void log_error(const std::string & message) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", message.c_str());
}

struct Utf8PrefixResult {
    size_t valid_prefix_len = 0;
    bool has_invalid = false;
    bool has_incomplete = false;
};

Utf8PrefixResult utf8_valid_prefix(const std::string & text) {
    Utf8PrefixResult result{};
    size_t index = 0;
    while (index < text.size()) {
        const uint8_t lead = static_cast<uint8_t>(text[index]);
        size_t length = 0;
        uint32_t codepoint = 0;
        if ((lead & 0x80u) == 0u) {
            length = 1;
            codepoint = lead;
        } else if ((lead & 0xE0u) == 0xC0u) {
            length = 2;
            codepoint = static_cast<uint32_t>(lead & 0x1Fu);
        } else if ((lead & 0xF0u) == 0xE0u) {
            length = 3;
            codepoint = static_cast<uint32_t>(lead & 0x0Fu);
        } else if ((lead & 0xF8u) == 0xF0u) {
            length = 4;
            codepoint = static_cast<uint32_t>(lead & 0x07u);
        } else {
            result.valid_prefix_len = index;
            result.has_invalid = true;
            return result;
        }

        if (index + length > text.size()) {
            result.valid_prefix_len = index;
            result.has_incomplete = true;
            return result;
        }

        for (size_t offset = 1; offset < length; ++offset) {
            const uint8_t next = static_cast<uint8_t>(text[index + offset]);
            if ((next & 0xC0u) != 0x80u) {
                result.valid_prefix_len = index;
                result.has_invalid = true;
                return result;
            }
            codepoint = (codepoint << 6u) | static_cast<uint32_t>(next & 0x3Fu);
        }

        if ((length == 2 && codepoint < 0x80u) ||
            (length == 3 && (codepoint < 0x800u || (codepoint >= 0xD800u && codepoint <= 0xDFFFu))) ||
            (length == 4 && (codepoint < 0x10000u || codepoint > 0x10FFFFu))) {
            result.valid_prefix_len = index;
            result.has_invalid = true;
            return result;
        }

        index += length;
    }
    result.valid_prefix_len = text.size();
    return result;
}

bool token_to_piece_dynamic(const llama_vocab * vocab, llama_token token, std::string * out_piece) {
    std::vector<char> buffer(256);
    for (int attempt = 0; attempt < 4; ++attempt) {
        const int piece_size = llama_token_to_piece(
            vocab,
            token,
            buffer.data(),
            static_cast<int32_t>(buffer.size()),
            0,
            true);
        if (piece_size >= 0) {
            out_piece->assign(buffer.data(), static_cast<size_t>(piece_size));
            return true;
        }
        const int required = -piece_size;
        if (required <= 0) {
            return false;
        }
        buffer.resize(static_cast<size_t>(required) + 8);
    }
    return false;
}

enum class EmitUtf8Status {
    OK,
    CALLBACK_REJECTED,
    UTF8_ERROR,
};

EmitUtf8Status emit_utf8_buffered(
    const std::string & piece,
    std::string * pending,
    const std::function<bool(const char *, size_t)> & emit_piece) {
    pending->append(piece);
    while (!pending->empty()) {
        const Utf8PrefixResult prefix = utf8_valid_prefix(*pending);
        if (prefix.has_invalid) {
            return EmitUtf8Status::UTF8_ERROR;
        }
        if (prefix.valid_prefix_len == 0) {
            return EmitUtf8Status::OK;
        }
        if (!emit_piece(pending->data(), prefix.valid_prefix_len)) {
            return EmitUtf8Status::CALLBACK_REJECTED;
        }
        pending->erase(0, prefix.valid_prefix_len);
        if (prefix.has_incomplete) {
            return EmitUtf8Status::OK;
        }
    }
    return EmitUtf8Status::OK;
}

std::string to_std_string(JNIEnv * env, jstring value) {
    if (value == nullptr) {
        return "";
    }
    const char * raw = env->GetStringUTFChars(value, nullptr);
    if (raw == nullptr) {
        return "";
    }
    std::string result(raw);
    env->ReleaseStringUTFChars(value, raw);
    return result;
}

void release_runtime_locked() {
    if (g_sampler != nullptr) {
        llama_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    if (g_context != nullptr) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_cached_prompt_tokens.clear();
    g_cached_cache_key.clear();
    g_cancel_requested.store(false, std::memory_order_release);
}

bool ensure_backend_initialized_locked() {
    if (g_backend_initialized) {
        return true;
    }
    ggml_backend_load_all();
    llama_backend_init();
    g_backend_initialized = true;
    return true;
}

std::vector<llama_token> tokenize_prompt(const llama_vocab * vocab, const std::string & prompt) {
    const int token_count = -llama_tokenize(
        vocab,
        prompt.c_str(),
        static_cast<int32_t>(prompt.size()),
        nullptr,
        0,
        true,
        true);
    if (token_count <= 0) {
        return {};
    }
    std::vector<llama_token> tokens(static_cast<size_t>(token_count));
    const int actual = llama_tokenize(
        vocab,
        prompt.c_str(),
        static_cast<int32_t>(prompt.size()),
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        true,
        true);
    if (actual < 0) {
        return {};
    }
    tokens.resize(static_cast<size_t>(actual));
    return tokens;
}

size_t longest_common_prefix(
    const std::vector<llama_token> & lhs,
    const std::vector<llama_token> & rhs) {
    const size_t limit = std::min(lhs.size(), rhs.size());
    size_t count = 0;
    while (count < limit && lhs[count] == rhs[count]) {
        ++count;
    }
    return count;
}

bool decode_tokens_from_offset(
    llama_context * ctx,
    const std::vector<llama_token> & tokens,
    size_t offset) {
    if (offset >= tokens.size()) {
        return true;
    }
    for (size_t idx = offset; idx < tokens.size(); idx += static_cast<size_t>(PROMPT_DECODE_BATCH_SIZE)) {
        if (g_cancel_requested.load(std::memory_order_acquire)) {
            log_error("nativeGenerate cancelled during prompt decode");
            return false;
        }
        const size_t remaining = tokens.size() - idx;
        const int32_t chunk_size = static_cast<int32_t>(
            std::min(remaining, static_cast<size_t>(PROMPT_DECODE_BATCH_SIZE)));
        llama_batch prompt_batch = llama_batch_get_one(const_cast<llama_token *>(tokens.data() + idx), chunk_size);
        if (llama_decode(ctx, prompt_batch) != 0) {
            return false;
        }
    }
    return true;
}

void log_prefix_cache_event(
    int policy,
    bool hit,
    size_t reused_tokens,
    size_t prompt_tokens,
    bool strict_key_match) {
    __android_log_print(
        ANDROID_LOG_INFO,
        TAG,
        "PREFIX_CACHE|policy=%d|hit=%s|reused_tokens=%zu|prompt_tokens=%zu|strict_key_match=%s",
        policy,
        hit ? "true" : "false",
        reused_tokens,
        prompt_tokens,
        strict_key_match ? "true" : "false");
}

jint generate_locked(
    JNIEnv * env,
    const std::string & prompt_text,
    int max_tokens,
    const std::string & cache_key,
    int cache_policy,
    const std::function<bool(const char *, size_t)> & emit_piece) {
    if (g_model == nullptr || g_context == nullptr || g_sampler == nullptr) {
        log_error("nativeGenerate failed: runtime not initialized");
        return STREAM_STATUS_RUNTIME_ERROR;
    }
    if (g_cancel_requested.exchange(false, std::memory_order_acq_rel)) {
        log_error("nativeGenerate cancelled before start");
        return STREAM_STATUS_CANCELLED;
    }

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    if (vocab == nullptr) {
        log_error("nativeGenerate failed: model vocabulary is null");
        return STREAM_STATUS_RUNTIME_ERROR;
    }

    llama_sampler_reset(g_sampler);

    const int safe_max_tokens = std::max(1, max_tokens);
    std::vector<llama_token> prompt_tokens = tokenize_prompt(vocab, prompt_text);
    if (prompt_tokens.empty()) {
        log_error("nativeGenerate failed: prompt tokenization returned zero tokens");
        return STREAM_STATUS_RUNTIME_ERROR;
    }

    const int max_prompt_tokens = std::max(1, DEFAULT_CONTEXT_SIZE - safe_max_tokens - 1);
    if (static_cast<int>(prompt_tokens.size()) > max_prompt_tokens) {
        const size_t trim_count = prompt_tokens.size() - static_cast<size_t>(max_prompt_tokens);
        prompt_tokens.erase(prompt_tokens.begin(), prompt_tokens.begin() + static_cast<std::ptrdiff_t>(trim_count));
    }

    bool strict_key_match = true;
    size_t reused_tokens = 0;
    size_t decode_offset = 0;
    bool cache_hit = false;
    bool use_prefix_cache = cache_policy == CACHE_POLICY_PREFIX_REUSE || cache_policy == CACHE_POLICY_PREFIX_REUSE_STRICT;
    bool strict_mode = cache_policy == CACHE_POLICY_PREFIX_REUSE_STRICT;
    if (use_prefix_cache) {
        strict_key_match = !strict_mode || (cache_key == g_cached_cache_key && !cache_key.empty());
        if (strict_key_match && !g_cached_prompt_tokens.empty()) {
            reused_tokens = longest_common_prefix(g_cached_prompt_tokens, prompt_tokens);
            if (reused_tokens > 0) {
                if (llama_memory_seq_rm(llama_get_memory(g_context), -1, static_cast<llama_pos>(reused_tokens), -1)) {
                    decode_offset = reused_tokens;
                    cache_hit = true;
                } else {
                    reused_tokens = 0;
                }
            }
        }
    }

    if (!cache_hit) {
        llama_memory_clear(llama_get_memory(g_context), false);
        decode_offset = 0;
    }

    if (!decode_tokens_from_offset(g_context, prompt_tokens, decode_offset)) {
        const bool cancelled = g_cancel_requested.load(std::memory_order_acquire);
        if (!g_cancel_requested.load(std::memory_order_acquire)) {
            log_error("nativeGenerate failed: llama_decode failed on prompt batch");
        }
        g_cancel_requested.store(false, std::memory_order_release);
        return cancelled ? STREAM_STATUS_CANCELLED : STREAM_STATUS_RUNTIME_ERROR;
    }

    g_cached_prompt_tokens = prompt_tokens;
    g_cached_cache_key = cache_key;
    log_prefix_cache_event(cache_policy, cache_hit, reused_tokens, prompt_tokens.size(), strict_key_match);

    llama_batch batch{};
    std::string utf8_pending;
    bool reached_max_tokens = true;
    for (int index = 0; index < safe_max_tokens; ++index) {
        if (g_cancel_requested.load(std::memory_order_acquire)) {
            log_error("nativeGenerate cancelled while sampling");
            g_cancel_requested.store(false, std::memory_order_release);
            return STREAM_STATUS_CANCELLED;
        }
        const llama_token token = llama_sampler_sample(g_sampler, g_context, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            reached_max_tokens = false;
            break;
        }

        std::string piece;
        if (!token_to_piece_dynamic(vocab, token, &piece)) {
            log_error("nativeGenerate failed: llama_token_to_piece dynamic sizing failed");
            g_cancel_requested.store(false, std::memory_order_release);
            return STREAM_STATUS_RUNTIME_ERROR;
        }
        const EmitUtf8Status emit_status = emit_utf8_buffered(piece, &utf8_pending, emit_piece);
        if (emit_status == EmitUtf8Status::CALLBACK_REJECTED) {
            log_error("nativeGenerate failed: token callback rejected piece");
            g_cancel_requested.store(false, std::memory_order_release);
            return STREAM_STATUS_CALLBACK_ERROR;
        }
        if (emit_status == EmitUtf8Status::UTF8_ERROR) {
            log_error("nativeGenerate failed: utf8 stream validation failed");
            g_cancel_requested.store(false, std::memory_order_release);
            return STREAM_STATUS_UTF8_STREAM_ERROR;
        }
        llama_sampler_accept(g_sampler, token);

        llama_token next_token = token;
        batch = llama_batch_get_one(&next_token, 1);
        if (llama_decode(g_context, batch) != 0) {
            log_error("nativeGenerate failed: llama_decode failed on sampled token");
            g_cancel_requested.store(false, std::memory_order_release);
            return STREAM_STATUS_RUNTIME_ERROR;
        }
        if (g_cancel_requested.load(std::memory_order_acquire)) {
            log_error("nativeGenerate cancelled after decode");
            g_cancel_requested.store(false, std::memory_order_release);
            return STREAM_STATUS_CANCELLED;
        }
    }

    if (!utf8_pending.empty()) {
        const Utf8PrefixResult final_prefix = utf8_valid_prefix(utf8_pending);
        if (final_prefix.has_invalid || final_prefix.has_incomplete) {
            log_error("nativeGenerate failed: trailing invalid/incomplete utf8 bytes");
            g_cancel_requested.store(false, std::memory_order_release);
            return STREAM_STATUS_UTF8_STREAM_ERROR;
        }
        if (final_prefix.valid_prefix_len > 0 &&
            !emit_piece(utf8_pending.data(), final_prefix.valid_prefix_len)) {
            log_error("nativeGenerate failed: callback rejected utf8 tail");
            g_cancel_requested.store(false, std::memory_order_release);
            return STREAM_STATUS_CALLBACK_ERROR;
        }
    }
    g_cancel_requested.store(false, std::memory_order_release);
    return reached_max_tokens ? STREAM_STATUS_MAX_TOKENS : STREAM_STATUS_COMPLETED;
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeInitialize(
    JNIEnv * /*env*/,
    jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return ensure_backend_initialized_locked() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeLoadModel(
    JNIEnv * env,
    jobject /*thiz*/,
    jstring /*modelId*/,
    jstring modelPath,
    jint nThreads,
    jint nThreadsBatch,
    jint nBatch,
    jint nUbatch,
    jint nGpuLayers) {
    const std::string model_path = to_std_string(env, modelPath);
    if (model_path.empty()) {
        log_error("nativeLoadModel failed: empty model path");
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    release_runtime_locked();
    if (!ensure_backend_initialized_locked()) {
        log_error("nativeLoadModel failed: backend initialization failed");
        return JNI_FALSE;
    }

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = gpu_offload_supported()
        ? clamp_i32(static_cast<int32_t>(nGpuLayers), 0, 128)
        : 0;
    g_model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (g_model == nullptr) {
        log_error("nativeLoadModel failed: llama_model_load_from_file returned null");
        return JNI_FALSE;
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = DEFAULT_CONTEXT_SIZE;
    context_params.n_batch = resolve_batch(nBatch);
    context_params.n_ubatch = resolve_batch(nUbatch);
    context_params.n_threads = resolve_threads(nThreads);
    context_params.n_threads_batch = resolve_threads(nThreadsBatch);
    g_context = llama_init_from_model(g_model, context_params);
    if (g_context == nullptr) {
        log_error("nativeLoadModel failed: llama_init_from_model returned null");
        release_runtime_locked();
        return JNI_FALSE;
    }

    const auto sampler_params = llama_sampler_chain_default_params();
    g_sampler = llama_sampler_chain_init(sampler_params);
    if (g_sampler == nullptr) {
        log_error("nativeLoadModel failed: llama_sampler_chain_init returned null");
        release_runtime_locked();
        return JNI_FALSE;
    }
    llama_sampler_chain_add(g_sampler, llama_sampler_init_greedy());
    llama_sampler_reset(g_sampler);
    g_cancel_requested.store(false, std::memory_order_release);

    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeGenerate(
    JNIEnv * env,
    jobject /*thiz*/,
    jstring prompt,
    jint maxTokens,
    jstring cacheKey,
    jint cachePolicy) {
    const std::string prompt_text = to_std_string(env, prompt);
    if (prompt_text.empty()) {
        return env->NewStringUTF("");
    }
    std::string output;
    output.reserve(static_cast<size_t>(std::max(1, static_cast<int>(maxTokens))) * 4);
    const std::string cache_key = to_std_string(env, cacheKey);

    std::lock_guard<std::mutex> lock(g_mutex);
    const jint status = generate_locked(
        env,
        prompt_text,
        static_cast<int>(maxTokens),
        cache_key,
        static_cast<int>(cachePolicy),
        [&output](const char * piece, size_t piece_size) {
            output.append(piece, piece_size);
            return true;
        });
    if (status != STREAM_STATUS_COMPLETED && status != STREAM_STATUS_MAX_TOKENS) {
        return env->NewStringUTF("");
    }

    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeGenerateStream(
    JNIEnv * env,
    jobject /*thiz*/,
    jstring requestId,
    jstring prompt,
    jint maxTokens,
    jstring cacheKey,
    jint cachePolicy,
    jobject callback) {
    const std::string request_id = to_std_string(env, requestId);
    (void)request_id;
    const std::string prompt_text = to_std_string(env, prompt);
    if (prompt_text.empty() || callback == nullptr) {
        return STREAM_STATUS_RUNTIME_ERROR;
    }
    const std::string cache_key = to_std_string(env, cacheKey);
    jclass callback_class = env->GetObjectClass(callback);
    if (callback_class == nullptr) {
        log_error("nativeGenerateStream failed: callback class missing");
        return STREAM_STATUS_RUNTIME_ERROR;
    }
    const jmethodID on_token = env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)V");
    if (on_token == nullptr) {
        env->DeleteLocalRef(callback_class);
        log_error("nativeGenerateStream failed: callback.onToken(String) missing");
        return STREAM_STATUS_CALLBACK_ERROR;
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    const jint status = generate_locked(
        env,
        prompt_text,
        static_cast<int>(maxTokens),
        cache_key,
        static_cast<int>(cachePolicy),
        [&](const char * piece, size_t piece_size) {
            std::string token(piece, piece_size);
            jstring token_j = env->NewStringUTF(token.c_str());
            if (token_j == nullptr) {
                return false;
            }
            env->CallVoidMethod(callback, on_token, token_j);
            env->DeleteLocalRef(token_j);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                return false;
            }
            return true;
        });
    env->DeleteLocalRef(callback_class);
    return status;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeUnloadModel(
    JNIEnv * /*env*/,
    jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    release_runtime_locked();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeCancelGeneration(
    JNIEnv * /*env*/,
    jobject /*thiz*/) {
    g_cancel_requested.store(true, std::memory_order_release);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeInitialize(
    JNIEnv * env,
    jobject thiz) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeInitialize(env, thiz);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeLoadModel(
    JNIEnv * env,
    jobject thiz,
    jstring modelId,
    jstring modelPath,
    jint nThreads,
    jint nThreadsBatch,
    jint nBatch,
    jint nUbatch,
    jint nGpuLayers) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeLoadModel(
        env,
        thiz,
        modelId,
        modelPath,
        nThreads,
        nThreadsBatch,
        nBatch,
        nUbatch,
        nGpuLayers);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeGenerate(
    JNIEnv * env,
    jobject thiz,
    jstring prompt,
    jint maxTokens,
    jstring cacheKey,
    jint cachePolicy) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeGenerate(
        env,
        thiz,
        prompt,
        maxTokens,
        cacheKey,
        cachePolicy);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeGenerateStream(
    JNIEnv * env,
    jobject thiz,
    jstring requestId,
    jstring prompt,
    jint maxTokens,
    jstring cacheKey,
    jint cachePolicy,
    jobject callback) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeGenerateStream(
        env,
        thiz,
        requestId,
        prompt,
        maxTokens,
        cacheKey,
        cachePolicy,
        callback);
}

extern "C" JNIEXPORT void JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeUnloadModel(
    JNIEnv * env,
    jobject thiz) {
    Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeUnloadModel(env, thiz);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeCancelGeneration(
    JNIEnv * env,
    jobject thiz) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeCancelGeneration(
        env,
        thiz);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeSupportsGpuOffload(
    JNIEnv * /*env*/,
    jobject /*thiz*/) {
    return gpu_offload_supported() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeSupportsGpuOffload(
    JNIEnv * env,
    jobject thiz) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeSupportsGpuOffload(
        env,
        thiz);
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM * /*vm*/, void * /*reserved*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    release_runtime_locked();
    if (g_backend_initialized) {
        llama_backend_free();
        g_backend_initialized = false;
    }
}
