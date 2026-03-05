#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "llama.h"

namespace {
constexpr const char * TAG = "PocketLlamaJNI";
constexpr int DEFAULT_CONTEXT_SIZE = 512;
constexpr int DEFAULT_BATCH_SIZE = 512;
constexpr int CACHE_POLICY_OFF = 0;
constexpr int CACHE_POLICY_PREFIX_REUSE = 1;
constexpr int CACHE_POLICY_PREFIX_REUSE_STRICT = 2;

std::mutex g_mutex;
bool g_backend_initialized = false;
llama_model * g_model = nullptr;
llama_context * g_context = nullptr;
llama_sampler * g_sampler = nullptr;
std::vector<llama_token> g_cached_prompt_tokens;
std::string g_cached_cache_key;

void log_error(const std::string & message) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", message.c_str());
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
    for (size_t idx = offset; idx < tokens.size(); idx += static_cast<size_t>(DEFAULT_BATCH_SIZE)) {
        const size_t remaining = tokens.size() - idx;
        const int32_t chunk_size = static_cast<int32_t>(std::min(remaining, static_cast<size_t>(DEFAULT_BATCH_SIZE)));
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
    jstring modelPath) {
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
    model_params.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (g_model == nullptr) {
        log_error("nativeLoadModel failed: llama_model_load_from_file returned null");
        return JNI_FALSE;
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = DEFAULT_CONTEXT_SIZE;
    context_params.n_batch = DEFAULT_BATCH_SIZE;
    context_params.n_ubatch = DEFAULT_BATCH_SIZE;
    const auto hardware_threads = std::thread::hardware_concurrency();
    const int32_t runtime_threads = static_cast<int32_t>(
        std::max(2u, std::min(hardware_threads == 0 ? 4u : hardware_threads, 8u)));
    context_params.n_threads = runtime_threads;
    context_params.n_threads_batch = runtime_threads;
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
    const std::string cache_key = to_std_string(env, cacheKey);
    if (prompt_text.empty()) {
        return env->NewStringUTF("");
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model == nullptr || g_context == nullptr || g_sampler == nullptr) {
        log_error("nativeGenerate failed: runtime not initialized");
        return env->NewStringUTF("");
    }

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    if (vocab == nullptr) {
        log_error("nativeGenerate failed: model vocabulary is null");
        return env->NewStringUTF("");
    }

    llama_sampler_reset(g_sampler);

    const int safe_max_tokens = std::max(1, static_cast<int>(maxTokens));
    std::vector<llama_token> prompt_tokens = tokenize_prompt(vocab, prompt_text);
    if (prompt_tokens.empty()) {
        log_error("nativeGenerate failed: prompt tokenization returned zero tokens");
        return env->NewStringUTF("");
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
    bool use_prefix_cache = cachePolicy == CACHE_POLICY_PREFIX_REUSE || cachePolicy == CACHE_POLICY_PREFIX_REUSE_STRICT;
    bool strict_mode = cachePolicy == CACHE_POLICY_PREFIX_REUSE_STRICT;
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
        log_error("nativeGenerate failed: llama_decode failed on prompt batch");
        return env->NewStringUTF("");
    }

    g_cached_prompt_tokens = prompt_tokens;
    g_cached_cache_key = cache_key;
    log_prefix_cache_event(cachePolicy, cache_hit, reused_tokens, prompt_tokens.size(), strict_key_match);

    std::string output;
    output.reserve(static_cast<size_t>(safe_max_tokens) * 4);
    llama_batch batch{};
    for (int index = 0; index < safe_max_tokens; ++index) {
        const llama_token token = llama_sampler_sample(g_sampler, g_context, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }

        char piece[256];
        const int piece_size = llama_token_to_piece(vocab, token, piece, static_cast<int32_t>(sizeof(piece)), 0, true);
        if (piece_size < 0) {
            log_error("nativeGenerate warning: llama_token_to_piece failed");
            break;
        }
        output.append(piece, static_cast<size_t>(piece_size));
        llama_sampler_accept(g_sampler, token);

        llama_token next_token = token;
        batch = llama_batch_get_one(&next_token, 1);
        if (llama_decode(g_context, batch) != 0) {
            log_error("nativeGenerate warning: llama_decode failed on sampled token");
            break;
        }
    }

    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeUnloadModel(
    JNIEnv * /*env*/,
    jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    release_runtime_locked();
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
    jstring modelPath) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeLoadModel(
        env,
        thiz,
        modelId,
        modelPath);
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

extern "C" JNIEXPORT void JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeUnloadModel(
    JNIEnv * env,
    jobject thiz) {
    Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeUnloadModel(env, thiz);
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM * /*vm*/, void * /*reserved*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    release_runtime_locked();
    if (g_backend_initialized) {
        llama_backend_free();
        g_backend_initialized = false;
    }
}
