#include <dlfcn.h>
#include <vulkan/vulkan.h>

namespace {

PFN_vkGetPhysicalDeviceFeatures2 resolve_vk_get_physical_device_features2() {
    void* handle = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        return nullptr;
    }

    auto fn = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
        dlsym(handle, "vkGetPhysicalDeviceFeatures2"));
    if (fn) {
        return fn;
    }

    auto fn_khr = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2KHR>(
        dlsym(handle, "vkGetPhysicalDeviceFeatures2KHR"));
    return reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(fn_khr);
}

}  // namespace

extern "C" VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceFeatures2(
    VkPhysicalDevice physicalDevice,
    VkPhysicalDeviceFeatures2* pFeatures) {
    static PFN_vkGetPhysicalDeviceFeatures2 fn = resolve_vk_get_physical_device_features2();
    if (fn) {
        fn(physicalDevice, pFeatures);
        return;
    }

    if (!pFeatures) {
        return;
    }

    pFeatures->pNext = nullptr;
    pFeatures->sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    vkGetPhysicalDeviceFeatures(physicalDevice, &pFeatures->features);
}
