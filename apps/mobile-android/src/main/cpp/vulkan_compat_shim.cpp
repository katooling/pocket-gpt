#include <dlfcn.h>
#include <vulkan/vulkan.h>

namespace {

void* open_vulkan_loader() {
    return dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
}

PFN_vkGetPhysicalDeviceFeatures2 resolve_vk_get_physical_device_features2() {
    void* handle = open_vulkan_loader();
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

PFN_vkGetPhysicalDeviceFeatures resolve_vk_get_physical_device_features() {
    void* handle = open_vulkan_loader();
    if (!handle) {
        return nullptr;
    }
    return reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures>(
        dlsym(handle, "vkGetPhysicalDeviceFeatures"));
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
    static PFN_vkGetPhysicalDeviceFeatures base_fn = resolve_vk_get_physical_device_features();
    if (base_fn) {
        base_fn(physicalDevice, &pFeatures->features);
    }
}
