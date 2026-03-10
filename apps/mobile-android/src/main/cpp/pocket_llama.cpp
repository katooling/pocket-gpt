#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <dlfcn.h>
#include <exception>
#include <fstream>
#include <functional>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>
#include <vulkan/vulkan.h>

#include "llama.h"
#if defined(GGML_USE_VULKAN)
#include "ggml-vulkan.h"
#endif

namespace {
constexpr const char * TAG = "PocketLlamaJNI";
constexpr int DEFAULT_CONTEXT_SIZE = 2048;
constexpr int DEFAULT_BATCH_SIZE = 512;
constexpr int DEFAULT_PROMPT_DECODE_BATCH_SIZE = 512;
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
enum class BackendInitMode {
    NONE,
    CPU_ONLY,
    GPU_ENABLED,
};
BackendInitMode g_backend_init_mode = BackendInitMode::NONE;
llama_model * g_model = nullptr;
llama_context * g_context = nullptr;
llama_sampler * g_sampler = nullptr;
llama_model * g_draft_model = nullptr;
llama_context * g_draft_context = nullptr;
llama_sampler * g_draft_sampler = nullptr;
std::atomic<bool> g_cancel_requested{false};
std::vector<llama_token> g_cached_prompt_tokens;
std::string g_cached_cache_key;
int32_t g_prompt_decode_batch_size = DEFAULT_PROMPT_DECODE_BATCH_SIZE;
int32_t g_runtime_context_size = DEFAULT_CONTEXT_SIZE;
bool g_speculative_enabled = false;
int32_t g_speculative_max_draft_tokens = 6;
int32_t g_speculative_min_draft_tokens = 2;

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

int32_t resolve_context_size(jint requested_ctx) {
    if (requested_ctx <= 0) {
        return DEFAULT_CONTEXT_SIZE;
    }
    return clamp_i32(static_cast<int32_t>(requested_ctx), 512, 32768);
}

std::string lowercase_copy(const std::string & value) {
    std::string lower(value.size(), '\0');
    std::transform(value.begin(), value.end(), lower.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    return lower;
}

bool gpu_offload_supported() {
    static std::atomic<int> cached_support{-1};
    const int cached = cached_support.load(std::memory_order_acquire);
    if (cached >= 0) {
        return cached == 1;
    }

    bool supported = llama_supports_gpu_offload();
#if defined(GGML_USE_VULKAN)
    if (supported) {
        try {
            // ggml Vulkan device enumeration applies backend-level capability filtering
            // (for example 16-bit storage support), so only expose GPU offload when at
            // least one usable Vulkan device is visible.
            const int device_count = ggml_backend_vk_get_device_count();
            supported = device_count > 0;
            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "GPU_OFFLOAD|vk_device_count=%d|supported=%s",
                device_count,
                supported ? "true" : "false");
        } catch (const std::exception & e) {
            supported = false;
            __android_log_print(
                ANDROID_LOG_WARN,
                TAG,
                "GPU_OFFLOAD|vk_probe_failed=%s",
                e.what());
        } catch (...) {
            supported = false;
            __android_log_print(
                ANDROID_LOG_WARN,
                TAG,
                "GPU_OFFLOAD|vk_probe_failed=unknown");
        }
    }
#endif
    cached_support.store(supported ? 1 : 0, std::memory_order_release);
    return supported;
}

void apply_android_vulkan_safety_env_once() {
    static std::atomic<bool> applied{false};
    bool expected = false;
    if (!applied.compare_exchange_strong(expected, true, std::memory_order_acq_rel)) {
        return;
    }
#if defined(GGML_USE_VULKAN)
    const std::string profile = lowercase_copy(std::getenv("POCKETGPT_VULKAN_PROFILE") != nullptr
        ? std::getenv("POCKETGPT_VULKAN_PROFILE")
        : "balanced");
    const bool safe_profile = profile == "safe";
    const bool aggressive_profile = profile == "aggressive";

    setenv("GGML_VK_ALLOW_SYSMEM_FALLBACK", "1", 1);
    setenv("GGML_VK_PREFER_HOST_MEMORY", "1", 1);
    if (safe_profile) {
        setenv("GGML_VK_DISABLE_COOPMAT", "1", 1);
        setenv("GGML_VK_DISABLE_COOPMAT2", "1", 1);
        setenv("GGML_VK_DISABLE_INTEGER_DOT_PRODUCT", "1", 1);
        setenv("GGML_VK_DISABLE_ASYNC", "1", 1);
        setenv("GGML_VK_DISABLE_BUFFER_DEVICE_ADDRESS", "1", 1);
        setenv("GGML_VK_DISABLE_F16", "1", 1);
        setenv("GGML_VK_DISABLE_FUSION", "1", 1);
        setenv("GGML_VK_DISABLE_MMVQ", "1", 1);
        setenv("GGML_VK_DISABLE_GRAPH_OPTIMIZE", "1", 1);
    } else if (aggressive_profile) {
        setenv("GGML_VK_DISABLE_COOPMAT2", "1", 1);
    } else {
        // Balanced mode keeps core safety rails but allows high-impact optimizations.
        setenv("GGML_VK_DISABLE_COOPMAT", "1", 1);
        setenv("GGML_VK_DISABLE_COOPMAT2", "1", 1);
        setenv("GGML_VK_DISABLE_BUFFER_DEVICE_ADDRESS", "1", 1);
    }
    __android_log_print(
        ANDROID_LOG_INFO,
        TAG,
        "GPU_OFFLOAD|vulkan_profile=%s|safe=%s|aggressive=%s",
        profile.c_str(),
        safe_profile ? "true" : "false",
        aggressive_profile ? "true" : "false");
#endif
}

const char * compiled_gpu_backends() {
#if defined(GGML_USE_VULKAN)
    return "vulkan";
#elif defined(GGML_USE_HEXAGON)
    return "hexagon";
#elif defined(GGML_USE_OPENCL)
    return "opencl";
#elif defined(GGML_USE_CUDA)
    return "cuda";
#elif defined(GGML_USE_METAL)
    return "metal";
#else
    return "cpu-only";
#endif
}

std::string json_escape(const std::string & value) {
    std::string escaped;
    escaped.reserve(value.size());
    for (const char ch : value) {
        switch (ch) {
            case '"':
                escaped += "\\\"";
                break;
            case '\\':
                escaped += "\\\\";
                break;
            case '\n':
                escaped += "\\n";
                break;
            case '\r':
                escaped += "\\r";
                break;
            case '\t':
                escaped += "\\t";
                break;
            default:
                escaped.push_back(ch);
                break;
        }
    }
    return escaped;
}

std::string format_api_version(uint32_t version) {
    if (version == 0) {
        return "0.0.0";
    }
    std::ostringstream out;
    out << VK_API_VERSION_MAJOR(version)
        << "."
        << VK_API_VERSION_MINOR(version)
        << "."
        << VK_API_VERSION_PATCH(version);
    return out.str();
}

struct VulkanDiagnostics {
    bool loader_available = false;
    uint32_t instance_api_version = 0;
    uint32_t selected_device_api_version = 0;
    uint32_t physical_device_count = 0;
    bool storage_buffer_16bit_access = false;
    bool shader_float16 = false;
    std::string driver_name;
    uint32_t driver_version = 0;
};

VulkanDiagnostics collect_vulkan_diagnostics() {
    VulkanDiagnostics info;
#if defined(GGML_USE_VULKAN)
    void * handle = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) {
        return info;
    }
    info.loader_available = true;

    auto vk_get_instance_proc_addr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
        dlsym(handle, "vkGetInstanceProcAddr"));
    auto vk_enumerate_instance_version = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
        dlsym(handle, "vkEnumerateInstanceVersion"));
    if (vk_get_instance_proc_addr == nullptr) {
        dlclose(handle);
        return info;
    }

    if (vk_enumerate_instance_version != nullptr) {
        vk_enumerate_instance_version(&info.instance_api_version);
    } else {
        info.instance_api_version = VK_API_VERSION_1_0;
    }

    auto vk_create_instance = reinterpret_cast<PFN_vkCreateInstance>(
        vk_get_instance_proc_addr(nullptr, "vkCreateInstance"));
    if (vk_create_instance == nullptr) {
        dlclose(handle);
        return info;
    }

    VkApplicationInfo app_info{};
    app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app_info.pApplicationName = "pocket_llama_vulkan_diag";
    app_info.applicationVersion = 1;
    app_info.pEngineName = "pocket_llama";
    app_info.engineVersion = 1;
    app_info.apiVersion = info.instance_api_version == 0 ? VK_API_VERSION_1_0 : info.instance_api_version;

    VkInstanceCreateInfo create_info{};
    create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create_info.pApplicationInfo = &app_info;

    VkInstance instance = VK_NULL_HANDLE;
    if (vk_create_instance(&create_info, nullptr, &instance) != VK_SUCCESS || instance == VK_NULL_HANDLE) {
        dlclose(handle);
        return info;
    }

    auto vk_destroy_instance = reinterpret_cast<PFN_vkDestroyInstance>(
        vk_get_instance_proc_addr(instance, "vkDestroyInstance"));
    auto vk_enumerate_physical_devices = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(
        vk_get_instance_proc_addr(instance, "vkEnumeratePhysicalDevices"));
    auto vk_get_physical_device_properties2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(
        vk_get_instance_proc_addr(instance, "vkGetPhysicalDeviceProperties2"));
    if (vk_get_physical_device_properties2 == nullptr) {
        vk_get_physical_device_properties2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(
            vk_get_instance_proc_addr(instance, "vkGetPhysicalDeviceProperties2KHR"));
    }
    auto vk_get_physical_device_features2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
        vk_get_instance_proc_addr(instance, "vkGetPhysicalDeviceFeatures2"));
    if (vk_get_physical_device_features2 == nullptr) {
        vk_get_physical_device_features2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
            vk_get_instance_proc_addr(instance, "vkGetPhysicalDeviceFeatures2KHR"));
    }

    if (vk_enumerate_physical_devices != nullptr) {
        uint32_t device_count = 0;
        if (vk_enumerate_physical_devices(instance, &device_count, nullptr) == VK_SUCCESS && device_count > 0) {
            info.physical_device_count = device_count;
            std::vector<VkPhysicalDevice> devices(device_count);
            if (vk_enumerate_physical_devices(instance, &device_count, devices.data()) == VK_SUCCESS &&
                !devices.empty()) {
                const VkPhysicalDevice selected = devices.front();
                if (vk_get_physical_device_properties2 != nullptr) {
                    VkPhysicalDeviceDriverProperties driver_props{};
                    driver_props.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES;
                    VkPhysicalDeviceProperties2 props2{};
                    props2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
                    props2.pNext = &driver_props;
                    vk_get_physical_device_properties2(selected, &props2);
                    info.selected_device_api_version = props2.properties.apiVersion;
                    info.driver_version = props2.properties.driverVersion;
                    if (driver_props.driverName[0] != '\0') {
                        info.driver_name = driver_props.driverName;
                    }
                }

                if (vk_get_physical_device_features2 != nullptr) {
                    VkPhysicalDeviceVulkan11Features features11{};
                    features11.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES;
                    VkPhysicalDeviceVulkan12Features features12{};
                    features12.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
                    features11.pNext = &features12;
                    VkPhysicalDeviceFeatures2 features2{};
                    features2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
                    features2.pNext = &features11;
                    vk_get_physical_device_features2(selected, &features2);
                    info.storage_buffer_16bit_access = features11.storageBuffer16BitAccess == VK_TRUE;
                    info.shader_float16 = features12.shaderFloat16 == VK_TRUE;
                }
            }
        }
    }

    if (vk_destroy_instance != nullptr) {
        vk_destroy_instance(instance, nullptr);
    }
    dlclose(handle);
#endif
    return info;
}

std::string vulkan_diagnostics_json() {
    const bool runtime_supported = gpu_offload_supported();
    int vk_device_count = -1;
#if defined(GGML_USE_VULKAN)
    try {
        vk_device_count = ggml_backend_vk_get_device_count();
    } catch (...) {
        vk_device_count = -1;
    }
#endif
    const VulkanDiagnostics diag = collect_vulkan_diagnostics();
    std::ostringstream out;
    out << "{"
        << "\"compiled_backend\":\"" << json_escape(compiled_gpu_backends()) << "\","
        << "\"runtime_supported\":" << (runtime_supported ? "true" : "false") << ","
        << "\"vk_device_count\":" << vk_device_count << ","
        << "\"loader_available\":" << (diag.loader_available ? "true" : "false") << ","
        << "\"instance_api_version\":" << diag.instance_api_version << ","
        << "\"instance_api_version_name\":\"" << format_api_version(diag.instance_api_version) << "\","
        << "\"selected_device_api_version\":" << diag.selected_device_api_version << ","
        << "\"selected_device_api_version_name\":\"" << format_api_version(diag.selected_device_api_version) << "\","
        << "\"physical_device_count\":" << diag.physical_device_count << ","
        << "\"storage_buffer_16bit_access\":" << (diag.storage_buffer_16bit_access ? "true" : "false") << ","
        << "\"shader_float16\":" << (diag.shader_float16 ? "true" : "false") << ","
        << "\"driver_name\":\"" << json_escape(diag.driver_name) << "\","
        << "\"driver_version\":" << diag.driver_version
        << "}";
    return out.str();
}

void log_gpu_support_once() {
    static bool logged = false;
    if (logged) {
        return;
    }
    logged = true;
    __android_log_print(
        ANDROID_LOG_INFO,
        TAG,
        "GPU_OFFLOAD|compiled_backend=%s|supported=%s",
        compiled_gpu_backends(),
        gpu_offload_supported() ? "true" : "false");
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
    if (g_draft_sampler != nullptr) {
        llama_sampler_free(g_draft_sampler);
        g_draft_sampler = nullptr;
    }
    if (g_context != nullptr) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_draft_context != nullptr) {
        llama_free(g_draft_context);
        g_draft_context = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    if (g_draft_model != nullptr) {
        llama_model_free(g_draft_model);
        g_draft_model = nullptr;
    }
    g_cached_prompt_tokens.clear();
    g_cached_cache_key.clear();
    g_prompt_decode_batch_size = DEFAULT_PROMPT_DECODE_BATCH_SIZE;
    g_runtime_context_size = DEFAULT_CONTEXT_SIZE;
    g_speculative_enabled = false;
    g_speculative_max_draft_tokens = 6;
    g_speculative_min_draft_tokens = 2;
    g_cancel_requested.store(false, std::memory_order_release);
}

bool ensure_backend_initialized_locked(bool require_gpu_backend) {
    if (require_gpu_backend) {
        apply_android_vulkan_safety_env_once();
    }
    if (g_backend_init_mode == BackendInitMode::GPU_ENABLED) {
        return true;
    }
    if (!require_gpu_backend && g_backend_init_mode == BackendInitMode::CPU_ONLY) {
        return true;
    }

    if (require_gpu_backend && g_backend_init_mode == BackendInitMode::CPU_ONLY) {
        // Promote CPU-only backend init to GPU-enabled without tearing down the backend.
        // Full backend free/re-init has been unstable on some Android Vulkan drivers.
        ggml_backend_load_all();
        g_backend_init_mode = BackendInitMode::GPU_ENABLED;
        __android_log_print(ANDROID_LOG_INFO, TAG, "GPU_OFFLOAD|backend_init_mode=gpu-enabled");
        return true;
    }

    if (require_gpu_backend) {
        ggml_backend_load_all();
    }
    llama_backend_init();
    g_backend_init_mode = require_gpu_backend ? BackendInitMode::GPU_ENABLED : BackendInitMode::CPU_ONLY;
    __android_log_print(
        ANDROID_LOG_INFO,
        TAG,
        "GPU_OFFLOAD|backend_init_mode=%s",
        require_gpu_backend ? "gpu-enabled" : "cpu-only");
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
    const int32_t prompt_decode_batch_size = std::max(1, g_prompt_decode_batch_size);
    for (size_t idx = offset; idx < tokens.size(); idx += static_cast<size_t>(prompt_decode_batch_size)) {
        if (g_cancel_requested.load(std::memory_order_acquire)) {
            log_error("nativeGenerate cancelled during prompt decode");
            return false;
        }
        const size_t remaining = tokens.size() - idx;
        const int32_t chunk_size = static_cast<int32_t>(
            std::min(remaining, static_cast<size_t>(prompt_decode_batch_size)));
        llama_batch prompt_batch = llama_batch_get_one(const_cast<llama_token *>(tokens.data() + idx), chunk_size);
        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "GPU_PROBE_DIAG|stage=prompt_decode_before|idx=%zu|chunk=%d",
            idx,
            static_cast<int>(chunk_size));
        if (llama_decode(ctx, prompt_batch) != 0) {
            return false;
        }
        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "GPU_PROBE_DIAG|stage=prompt_decode_after|idx=%zu|chunk=%d",
            idx,
            static_cast<int>(chunk_size));
    }
    return true;
}

llama_sampler * build_sampler_chain(float temperature, int32_t top_k, float top_p) {
    const auto sampler_params = llama_sampler_chain_default_params();
    llama_sampler * sampler = llama_sampler_chain_init(sampler_params);
    if (sampler == nullptr) {
        return nullptr;
    }
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
        return sampler;
    }
    if (top_k > 0) {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(top_k));
    }
    if (top_p > 0.0f && top_p < 1.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p, 1));
    }
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return sampler;
}

bool decode_single_token(llama_context * ctx, llama_token token) {
    llama_token next_token = token;
    llama_batch batch = llama_batch_get_one(&next_token, 1);
    return llama_decode(ctx, batch) == 0;
}

bool speculative_vocabs_compatible(const llama_model * target_model, const llama_model * draft_model) {
    const llama_vocab * target_vocab = llama_model_get_vocab(target_model);
    const llama_vocab * draft_vocab = llama_model_get_vocab(draft_model);
    if (target_vocab == nullptr || draft_vocab == nullptr) {
        return false;
    }
    if (llama_vocab_type(target_vocab) != llama_vocab_type(draft_vocab)) {
        return false;
    }
    if (llama_vocab_get_add_bos(target_vocab) != llama_vocab_get_add_bos(draft_vocab) ||
        llama_vocab_get_add_eos(target_vocab) != llama_vocab_get_add_eos(draft_vocab) ||
        llama_vocab_bos(target_vocab) != llama_vocab_bos(draft_vocab) ||
        llama_vocab_eos(target_vocab) != llama_vocab_eos(draft_vocab)) {
        return false;
    }
    const int target_n_vocab = llama_vocab_n_tokens(target_vocab);
    const int draft_n_vocab = llama_vocab_n_tokens(draft_vocab);
    if (std::abs(target_n_vocab - draft_n_vocab) > 128) {
        return false;
    }
    const int sample_vocab = std::min(target_n_vocab, draft_n_vocab);
    const int max_checks = std::min(sample_vocab, 2048);
    for (int id = 5; id < max_checks; ++id) {
        const char * target_piece = llama_vocab_get_text(target_vocab, id);
        const char * draft_piece = llama_vocab_get_text(draft_vocab, id);
        if (target_piece == nullptr || draft_piece == nullptr) {
            return false;
        }
        if (std::string(target_piece) != std::string(draft_piece)) {
            return false;
        }
    }
    return true;
}

struct SpeculativeStepResult {
    jint status = STREAM_STATUS_COMPLETED;
    bool reached_eog = false;
    std::vector<llama_token> emitted_tokens;
};

SpeculativeStepResult run_speculative_step(
    const llama_vocab * vocab,
    int remaining_tokens,
    llama_pos * target_context_tokens,
    llama_pos * draft_context_tokens) {
    SpeculativeStepResult result{};
    result.status = STREAM_STATUS_COMPLETED;
    if (remaining_tokens <= 0) {
        return result;
    }
    if (g_context == nullptr || g_sampler == nullptr || g_draft_context == nullptr || g_draft_sampler == nullptr) {
        result.status = STREAM_STATUS_RUNTIME_ERROR;
        return result;
    }

    const int max_draft = std::max(0, std::min(g_speculative_max_draft_tokens, remaining_tokens - 1));
    if (max_draft < g_speculative_min_draft_tokens) {
        const llama_token token = llama_sampler_sample(g_sampler, g_context, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            result.reached_eog = true;
            return result;
        }
        llama_sampler_accept(g_sampler, token);
        if (!decode_single_token(g_context, token)) {
            result.status = STREAM_STATUS_RUNTIME_ERROR;
            return result;
        }
        *target_context_tokens += 1;
        if (!decode_single_token(g_draft_context, token)) {
            result.status = STREAM_STATUS_RUNTIME_ERROR;
            return result;
        }
        *draft_context_tokens += 1;
        result.emitted_tokens.push_back(token);
        return result;
    }

    const llama_pos base_target_tokens = *target_context_tokens;
    const llama_pos base_draft_tokens = *draft_context_tokens;

    std::vector<llama_token> draft_tokens;
    draft_tokens.reserve(static_cast<size_t>(max_draft));
    for (int i = 0; i < max_draft; ++i) {
        if (g_cancel_requested.load(std::memory_order_acquire)) {
            result.status = STREAM_STATUS_CANCELLED;
            return result;
        }
        const llama_token draft_token = llama_sampler_sample(g_draft_sampler, g_draft_context, -1);
        if (llama_vocab_is_eog(vocab, draft_token)) {
            break;
        }
        llama_sampler_accept(g_draft_sampler, draft_token);
        draft_tokens.push_back(draft_token);
        if (!decode_single_token(g_draft_context, draft_token)) {
            result.status = STREAM_STATUS_RUNTIME_ERROR;
            return result;
        }
        *draft_context_tokens += 1;
    }

    if (static_cast<int>(draft_tokens.size()) < g_speculative_min_draft_tokens) {
        llama_memory_seq_rm(llama_get_memory(g_draft_context), -1, base_draft_tokens, -1);
        *draft_context_tokens = base_draft_tokens;
        llama_sampler_reset(g_draft_sampler);
        const llama_token token = llama_sampler_sample(g_sampler, g_context, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            result.reached_eog = true;
            return result;
        }
        llama_sampler_accept(g_sampler, token);
        if (!decode_single_token(g_context, token)) {
            result.status = STREAM_STATUS_RUNTIME_ERROR;
            return result;
        }
        *target_context_tokens += 1;
        if (!decode_single_token(g_draft_context, token)) {
            result.status = STREAM_STATUS_RUNTIME_ERROR;
            return result;
        }
        *draft_context_tokens += 1;
        result.emitted_tokens.push_back(token);
        return result;
    }

    const llama_token first_token = llama_sampler_sample(g_sampler, g_context, -1);
    if (llama_vocab_is_eog(vocab, first_token)) {
        result.reached_eog = true;
        return result;
    }
    llama_sampler_accept(g_sampler, first_token);

    if (first_token != draft_tokens[0]) {
        llama_memory_seq_rm(llama_get_memory(g_draft_context), -1, base_draft_tokens, -1);
        *draft_context_tokens = base_draft_tokens;
        llama_sampler_reset(g_draft_sampler);
        if (!decode_single_token(g_context, first_token)) {
            result.status = STREAM_STATUS_RUNTIME_ERROR;
            return result;
        }
        *target_context_tokens += 1;
        if (!decode_single_token(g_draft_context, first_token)) {
            result.status = STREAM_STATUS_RUNTIME_ERROR;
            return result;
        }
        *draft_context_tokens += 1;
        result.emitted_tokens.push_back(first_token);
        return result;
    }

    llama_batch target_batch = llama_batch_get_one(draft_tokens.data(), static_cast<int32_t>(draft_tokens.size()));
    if (llama_decode(g_context, target_batch) != 0) {
        result.status = STREAM_STATUS_RUNTIME_ERROR;
        return result;
    }
    *target_context_tokens += static_cast<llama_pos>(draft_tokens.size());

    size_t accepted_drafts = 1;
    llama_token next_token = 0;
    bool mismatch = false;
    for (size_t i = 1; i < draft_tokens.size(); ++i) {
        const llama_token sampled = llama_sampler_sample(g_sampler, g_context, static_cast<int32_t>(i - 1));
        if (llama_vocab_is_eog(vocab, sampled)) {
            result.reached_eog = true;
            next_token = sampled;
            mismatch = true;
            break;
        }
        llama_sampler_accept(g_sampler, sampled);
        if (sampled != draft_tokens[i]) {
            next_token = sampled;
            mismatch = true;
            break;
        }
        accepted_drafts += 1;
    }

    if (!mismatch) {
        next_token = llama_sampler_sample(
            g_sampler,
            g_context,
            static_cast<int32_t>(draft_tokens.size() - 1));
        if (llama_vocab_is_eog(vocab, next_token)) {
            result.reached_eog = true;
        } else {
            llama_sampler_accept(g_sampler, next_token);
        }
    }

    const llama_pos keep_target_tokens = base_target_tokens + static_cast<llama_pos>(accepted_drafts);
    llama_memory_seq_rm(llama_get_memory(g_context), -1, keep_target_tokens, -1);
    *target_context_tokens = keep_target_tokens;

    result.emitted_tokens.insert(
        result.emitted_tokens.end(),
        draft_tokens.begin(),
        draft_tokens.begin() + static_cast<std::ptrdiff_t>(accepted_drafts));

    if (!result.reached_eog) {
        result.emitted_tokens.push_back(next_token);
        if (!decode_single_token(g_context, next_token)) {
            result.status = STREAM_STATUS_RUNTIME_ERROR;
            return result;
        }
        *target_context_tokens += 1;
    }

    const llama_pos keep_draft_tokens = base_draft_tokens + static_cast<llama_pos>(accepted_drafts);
    llama_memory_seq_rm(llama_get_memory(g_draft_context), -1, keep_draft_tokens, -1);
    *draft_context_tokens = keep_draft_tokens;
    llama_sampler_reset(g_draft_sampler);
    if (!result.reached_eog) {
        if (!decode_single_token(g_draft_context, next_token)) {
            result.status = STREAM_STATUS_RUNTIME_ERROR;
            return result;
        }
        *draft_context_tokens += 1;
    }

    return result;
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
    __android_log_print(
        ANDROID_LOG_INFO,
        TAG,
        "GPU_PROBE_DIAG|stage=generate_enter|max_tokens=%d|prompt_bytes=%zu",
        max_tokens,
        prompt_text.size());
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
    if (g_speculative_enabled && g_draft_sampler != nullptr) {
        llama_sampler_reset(g_draft_sampler);
    }

    const int safe_max_tokens = std::max(1, max_tokens);
    std::vector<llama_token> prompt_tokens = tokenize_prompt(vocab, prompt_text);
    if (prompt_tokens.empty()) {
        log_error("nativeGenerate failed: prompt tokenization returned zero tokens");
        return STREAM_STATUS_RUNTIME_ERROR;
    }

    const int runtime_context_size = static_cast<int>(
        llama_n_ctx(g_context) > 0 ? llama_n_ctx(g_context) : static_cast<uint32_t>(g_runtime_context_size));
    const int max_prompt_tokens = std::max(1, runtime_context_size - safe_max_tokens - 1);
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

    const bool speculative_ready = g_speculative_enabled &&
        g_draft_model != nullptr &&
        g_draft_context != nullptr &&
        g_draft_sampler != nullptr;
    llama_pos target_context_tokens = static_cast<llama_pos>(prompt_tokens.size());
    llama_pos draft_context_tokens = static_cast<llama_pos>(prompt_tokens.size());
    if (speculative_ready) {
        llama_memory_clear(llama_get_memory(g_draft_context), false);
        if (!decode_tokens_from_offset(g_draft_context, prompt_tokens, 0)) {
            log_error("nativeGenerate failed: draft prompt decode failed");
            g_cancel_requested.store(false, std::memory_order_release);
            return STREAM_STATUS_RUNTIME_ERROR;
        }
    }

    llama_batch batch{};
    std::string utf8_pending;
    bool reached_max_tokens = true;
    int emitted_token_count = 0;
    while (emitted_token_count < safe_max_tokens) {
        if (g_cancel_requested.load(std::memory_order_acquire)) {
            log_error("nativeGenerate cancelled while sampling");
            g_cancel_requested.store(false, std::memory_order_release);
            return STREAM_STATUS_CANCELLED;
        }
        std::vector<llama_token> emitted_tokens;
        const int remaining_tokens = safe_max_tokens - emitted_token_count;
        if (speculative_ready && remaining_tokens > 1) {
            SpeculativeStepResult step = run_speculative_step(
                vocab,
                remaining_tokens,
                &target_context_tokens,
                &draft_context_tokens);
            if (step.status != STREAM_STATUS_COMPLETED) {
                g_cancel_requested.store(false, std::memory_order_release);
                return step.status;
            }
            if (step.reached_eog) {
                reached_max_tokens = false;
                break;
            }
            emitted_tokens = std::move(step.emitted_tokens);
        } else {
            const llama_token token = llama_sampler_sample(g_sampler, g_context, -1);
            if (llama_vocab_is_eog(vocab, token)) {
                reached_max_tokens = false;
                break;
            }
            emitted_tokens.push_back(token);
            llama_sampler_accept(g_sampler, token);

            llama_token next_token = token;
            batch = llama_batch_get_one(&next_token, 1);
            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "GPU_PROBE_DIAG|stage=sample_decode_before|index=%d",
                emitted_token_count);
            if (llama_decode(g_context, batch) != 0) {
                log_error("nativeGenerate failed: llama_decode failed on sampled token");
                g_cancel_requested.store(false, std::memory_order_release);
                return STREAM_STATUS_RUNTIME_ERROR;
            }
            target_context_tokens += 1;
            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "GPU_PROBE_DIAG|stage=sample_decode_after|index=%d",
                emitted_token_count);
            if (speculative_ready) {
                if (!decode_single_token(g_draft_context, token)) {
                    g_cancel_requested.store(false, std::memory_order_release);
                    return STREAM_STATUS_RUNTIME_ERROR;
                }
                draft_context_tokens += 1;
            }
            if (g_cancel_requested.load(std::memory_order_acquire)) {
                log_error("nativeGenerate cancelled after decode");
                g_cancel_requested.store(false, std::memory_order_release);
                return STREAM_STATUS_CANCELLED;
            }
        }

        for (const llama_token token : emitted_tokens) {
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
        }
        emitted_token_count += static_cast<int>(emitted_tokens.size());
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
    return ensure_backend_initialized_locked(false) ? JNI_TRUE : JNI_FALSE;
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
    jint nCtx,
    jint nGpuLayers,
    jboolean quantizedKvCache,
    jfloat temperature,
    jint topK,
    jfloat topP,
    jboolean speculativeEnabled,
    jstring speculativeDraftModelPath,
    jint speculativeMaxDraftTokens,
    jint speculativeMinDraftTokens) {
    const std::string model_path = to_std_string(env, modelPath);
    const std::string draft_model_path = to_std_string(env, speculativeDraftModelPath);
    if (model_path.empty()) {
        log_error("nativeLoadModel failed: empty model path");
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    release_runtime_locked();
    log_gpu_support_once();
    const bool supports_gpu_offload = gpu_offload_supported();
    const bool request_gpu_layers = static_cast<int32_t>(nGpuLayers) > 0;
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = supports_gpu_offload
        ? clamp_i32(static_cast<int32_t>(nGpuLayers), 0, 128)
        : 0;
    const bool use_gpu_ops = model_params.n_gpu_layers > 0;
    // Keep upstream defaults for split/device selection on GPU paths to avoid driver-specific
    // regressions from aggressive overrides.
    if (!use_gpu_ops) {
        // Force strict CPU-only placement for non-GPU runs.
        // On some Vulkan-capable devices, default split policy can still allocate Vulkan buffers
        // even with n_gpu_layers=0, which can trigger driver crashes.
        model_params.split_mode = LLAMA_SPLIT_MODE_NONE;
        model_params.main_gpu = -1;
    }
    if (!ensure_backend_initialized_locked(request_gpu_layers && supports_gpu_offload)) {
        log_error("nativeLoadModel failed: backend initialization failed");
        return JNI_FALSE;
    }
    __android_log_print(
        ANDROID_LOG_INFO,
        TAG,
        "GPU_OFFLOAD|requested_layers=%d|effective_layers=%d|split_mode=%d|main_gpu=%d",
        static_cast<int>(nGpuLayers),
        static_cast<int>(model_params.n_gpu_layers),
        static_cast<int>(model_params.split_mode),
        static_cast<int>(model_params.main_gpu));
    g_model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (g_model == nullptr) {
        log_error("nativeLoadModel failed: llama_model_load_from_file returned null");
        return JNI_FALSE;
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(resolve_context_size(nCtx));
    context_params.n_batch = resolve_batch(nBatch);
    context_params.n_ubatch = resolve_batch(nUbatch);
    context_params.n_threads = resolve_threads(nThreads);
    context_params.n_threads_batch = resolve_threads(nThreadsBatch);
    context_params.offload_kqv = use_gpu_ops;
    context_params.op_offload = use_gpu_ops;
    context_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    context_params.type_k = quantizedKvCache == JNI_TRUE ? GGML_TYPE_Q8_0 : context_params.type_k;
    context_params.type_v = quantizedKvCache == JNI_TRUE ? GGML_TYPE_Q8_0 : context_params.type_v;
    __android_log_print(
        ANDROID_LOG_INFO,
        TAG,
        "GPU_OFFLOAD|offload_kqv=%s|op_offload=%s|flash_attn_type=%d|quantized_kv=%s",
        context_params.offload_kqv ? "true" : "false",
        context_params.op_offload ? "true" : "false",
        static_cast<int>(context_params.flash_attn_type),
        quantizedKvCache == JNI_TRUE ? "true" : "false");
    g_context = llama_init_from_model(g_model, context_params);
    if (g_context == nullptr) {
        log_error("nativeLoadModel failed: llama_init_from_model returned null");
        release_runtime_locked();
        return JNI_FALSE;
    }

    const float resolved_temperature = std::max(0.0f, static_cast<float>(temperature));
    const float resolved_top_p = std::max(0.0f, std::min(1.0f, static_cast<float>(topP)));
    const int32_t resolved_top_k = clamp_i32(static_cast<int32_t>(topK), 0, 1024);
    g_sampler = build_sampler_chain(
        resolved_temperature,
        resolved_top_k,
        resolved_top_p);
    if (g_sampler == nullptr) {
        log_error("nativeLoadModel failed: sampler init returned null");
        release_runtime_locked();
        return JNI_FALSE;
    }
    llama_sampler_reset(g_sampler);
    g_prompt_decode_batch_size = resolve_batch(nBatch);
    g_runtime_context_size = static_cast<int32_t>(context_params.n_ctx);
    g_speculative_enabled = false;
    g_speculative_max_draft_tokens = clamp_i32(static_cast<int32_t>(speculativeMaxDraftTokens), 1, 16);
    g_speculative_min_draft_tokens = clamp_i32(static_cast<int32_t>(speculativeMinDraftTokens), 1, 16);
    if (g_speculative_min_draft_tokens > g_speculative_max_draft_tokens) {
        g_speculative_min_draft_tokens = g_speculative_max_draft_tokens;
    }

    if (speculativeEnabled == JNI_TRUE &&
        !draft_model_path.empty() &&
        draft_model_path != model_path) {
        llama_model_params draft_model_params = llama_model_default_params();
        draft_model_params.n_gpu_layers = 0;
        draft_model_params.split_mode = LLAMA_SPLIT_MODE_NONE;
        draft_model_params.main_gpu = -1;
        g_draft_model = llama_model_load_from_file(draft_model_path.c_str(), draft_model_params);
        if (g_draft_model != nullptr && speculative_vocabs_compatible(g_model, g_draft_model)) {
            llama_context_params draft_context_params = llama_context_default_params();
            draft_context_params.n_ctx = context_params.n_ctx;
            draft_context_params.n_batch = context_params.n_batch;
            draft_context_params.n_ubatch = context_params.n_ubatch;
            draft_context_params.n_threads = resolve_threads(nThreadsBatch);
            draft_context_params.n_threads_batch = resolve_threads(nThreadsBatch);
            draft_context_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
            draft_context_params.type_k = context_params.type_k;
            draft_context_params.type_v = context_params.type_v;
            g_draft_context = llama_init_from_model(g_draft_model, draft_context_params);
            if (g_draft_context != nullptr) {
                g_draft_sampler = build_sampler_chain(
                    std::max(0.0f, resolved_temperature),
                    std::max(1, resolved_top_k / 2),
                    std::min(0.98f, std::max(0.5f, resolved_top_p)));
                if (g_draft_sampler != nullptr) {
                    g_speculative_enabled = true;
                }
            }
        }
        if (!g_speculative_enabled) {
            if (g_draft_sampler != nullptr) {
                llama_sampler_free(g_draft_sampler);
                g_draft_sampler = nullptr;
            }
            if (g_draft_context != nullptr) {
                llama_free(g_draft_context);
                g_draft_context = nullptr;
            }
            if (g_draft_model != nullptr) {
                llama_model_free(g_draft_model);
                g_draft_model = nullptr;
            }
            __android_log_print(ANDROID_LOG_WARN, TAG, "SPECULATIVE|disabled|reason=draft_init_failed");
        } else {
            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "SPECULATIVE|enabled=true|max=%d|min=%d",
                static_cast<int>(g_speculative_max_draft_tokens),
                static_cast<int>(g_speculative_min_draft_tokens));
        }
    }
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

double read_peak_rss_mb() {
    std::ifstream status("/proc/self/status");
    if (!status.is_open()) {
        return -1.0;
    }
    std::string line;
    while (std::getline(status, line)) {
        const bool is_peak = line.rfind("VmHWM:", 0) == 0;
        const bool is_current = line.rfind("VmRSS:", 0) == 0;
        if (!is_peak && !is_current) {
            continue;
        }
        std::istringstream parser(line.substr(line.find(':') + 1));
        long value_kb = 0;
        parser >> value_kb;
        if (value_kb > 0) {
            return static_cast<double>(value_kb) / 1024.0;
        }
    }
    return -1.0;
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
    jint nCtx,
    jint nGpuLayers,
    jboolean quantizedKvCache,
    jfloat temperature,
    jint topK,
    jfloat topP,
    jboolean speculativeEnabled,
    jstring speculativeDraftModelPath,
    jint speculativeMaxDraftTokens,
    jint speculativeMinDraftTokens) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeLoadModel(
        env,
        thiz,
        modelId,
        modelPath,
        nThreads,
        nThreadsBatch,
        nBatch,
        nUbatch,
        nCtx,
        nGpuLayers,
        quantizedKvCache,
        temperature,
        topK,
        topP,
        speculativeEnabled,
        speculativeDraftModelPath,
        speculativeMaxDraftTokens,
        speculativeMinDraftTokens);
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
    log_gpu_support_once();
    return gpu_offload_supported() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeVulkanDiagnosticsJson(
    JNIEnv * env,
    jobject /*thiz*/) {
    const std::string payload = vulkan_diagnostics_json();
    return env->NewStringUTF(payload.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeSupportsGpuOffload(
    JNIEnv * env,
    jobject thiz) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeSupportsGpuOffload(
        env,
        thiz);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeVulkanDiagnosticsJson(
    JNIEnv * env,
    jobject thiz) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeVulkanDiagnosticsJson(
        env,
        thiz);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativePeakRssMb(
    JNIEnv * /*env*/,
    jobject /*thiz*/) {
    return static_cast<jdouble>(read_peak_rss_mb());
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativePeakRssMb(
    JNIEnv * env,
    jobject thiz) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativePeakRssMb(
        env,
        thiz);
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM * /*vm*/, void * /*reserved*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    release_runtime_locked();
    if (g_backend_init_mode != BackendInitMode::NONE) {
        llama_backend_free();
        g_backend_init_mode = BackendInitMode::NONE;
    }
}
