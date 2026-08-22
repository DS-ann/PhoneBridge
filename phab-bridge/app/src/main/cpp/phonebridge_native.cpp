#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <sstream>

#define LOG_TAG "PhoneBridgeNative"
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Minimal declaration matching libhardware's public hw_module_t layout.
// We only inspect the metadata; no audio stream is opened and no audio data
// is routed by this diagnostic.
struct hw_module_t_min {
    uint32_t tag;
    uint16_t module_api_version;
    uint16_t hal_api_version;
    const char *id;
    const char *name;
    const char *author;
    uint32_t methods;
};

typedef int (*hw_get_module_by_class_fn)(const char *, const char *, const hw_module_t_min **);

typedef int (*hw_get_module_fn)(const char *, const hw_module_t_min **);

static std::string ptrString(const void *p) {
    std::ostringstream out;
    out << "0x" << std::hex << reinterpret_cast<uintptr_t>(p);
    return out.str();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dsann_phonebridge_NativeAudioProbe_probeHal(JNIEnv *env, jclass) {
    std::ostringstream out;
    out << "NATIVE_AUDIO_HAL_PROBE:BEGIN\n";

    void *hardware = dlopen("libhardware.so", RTLD_NOW | RTLD_LOCAL);
    if (!hardware) {
        const char *err = dlerror();
        out << "LIBHARDWARE:OPEN_FAILED:" << (err ? err : "unknown") << "\n";
        out << "NATIVE_AUDIO_HAL_PROBE:END";
        return env->NewStringUTF(out.str().c_str());
    }
    out << "LIBHARDWARE:OPEN_OK\n";

    void *sym = dlsym(hardware, "hw_get_module_by_class");
    if (!sym) {
        const char *err = dlerror();
        out << "HW_GET_MODULE_BY_CLASS:NOT_FOUND:" << (err ? err : "unknown") << "\n";
    } else {
        out << "HW_GET_MODULE_BY_CLASS:FOUND\n";
        hw_get_module_by_class_fn getModule = reinterpret_cast<hw_get_module_by_class_fn>(sym);
        const char *instances[] = {"primary", "", nullptr};
        bool obtained = false;
        for (int i = 0; instances[i] != nullptr; ++i) {
            const hw_module_t_min *module = nullptr;
            const char *instance = instances[i];
            int rc = getModule("audio", instance, &module);
            out << "AUDIO_MODULE[" << (instance[0] ? instance : "<empty>") << "]:RC=" << rc;
            if (module) {
                obtained = true;
                out << ",PTR=" << ptrString(module);
                if (module->id) out << ",ID=" << module->id;
                if (module->name) out << ",NAME=" << module->name;
            }
            out << "\n";
        }
        out << "AUDIO_MODULE:OBTAINED=" << (obtained ? "YES" : "NO") << "\n";
    }

    // Also test whether the process can directly load common MediaTek audio
    // HAL library names. This is diagnostic only; no symbols are invoked.
    const char *candidates[] = {
        "audio.primary.mt8783.so",
        "audio.primary.mt6735.so",
        "audio.primary.mt6753.so",
        "audio.primary.default.so",
        nullptr
    };
    for (int i = 0; candidates[i] != nullptr; ++i) {
        void *handle = dlopen(candidates[i], RTLD_NOW | RTLD_LOCAL);
        if (handle) {
            out << "DLOPEN:" << candidates[i] << ":OK\n";
            dlclose(handle);
        } else {
            const char *err = dlerror();
            out << "DLOPEN:" << candidates[i] << ":FAIL:" << (err ? err : "unknown") << "\n";
        }
    }

    dlclose(hardware);
    out << "NATIVE_AUDIO_HAL_PROBE:END";
    ALOGD("HAL probe completed");
    return env->NewStringUTF(out.str().c_str());
}
