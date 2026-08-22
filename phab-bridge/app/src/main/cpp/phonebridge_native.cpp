#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <stdint.h>
#include <string>
#include <sstream>

#define LOG_TAG "PhoneBridgeNative"
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/*
 * Non-invasive MTK audio HAL capability probe.
 *
 * IMPORTANT: do not call the vendor audio_hw_device open()/stream callbacks
 * from an ordinary APK process. On old MediaTek builds those callbacks may
 * assume AudioFlinger/mediaserver state and a bad call can abort the app.
 *
 * This probe therefore only resolves the public libhardware module and
 * inspects whether its public hw_module_methods_t contains an open callback.
 * It does NOT open a device, start PCM, change routing, or alter call audio.
 */
struct hw_module_t_min;
struct hw_device_t_min;

struct hw_module_methods_t_min {
    int (*open)(const hw_module_t_min *module, const char *id,
                hw_device_t_min **device);
};

typedef int (*hw_get_module_by_class_fn)(const char *, const char *,
                                          const hw_module_t_min **);

struct hw_module_t_min {
    uint32_t tag;
    uint16_t module_api_version;
    uint16_t hal_api_version;
    const char *id;
    const char *name;
    const char *author;
    const hw_module_methods_t_min *methods;
    void *dso;
};

static void appendDlopenResult(std::ostringstream &out, const char *name) {
    dlerror();
    void *handle = dlopen(name, RTLD_NOW | RTLD_LOCAL);
    if (handle) {
        out << "DLOPEN:" << name << ":OK\n";
        dlclose(handle);
    } else {
        const char *err = dlerror();
        out << "DLOPEN:" << name << ":FAIL:" << (err ? err : "unknown") << "\n";
    }
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

    dlerror();
    void *sym = dlsym(hardware, "hw_get_module_by_class");
    const char *symErr = dlerror();
    if (!sym || symErr) {
        out << "HW_GET_MODULE_BY_CLASS:NOT_FOUND:"
            << (symErr ? symErr : "unknown") << "\n";
        dlclose(hardware);
        out << "NATIVE_AUDIO_HAL_PROBE:END";
        return env->NewStringUTF(out.str().c_str());
    }

    out << "HW_GET_MODULE_BY_CLASS:FOUND\n";
    hw_get_module_by_class_fn getModule =
            reinterpret_cast<hw_get_module_by_class_fn>(sym);

    const hw_module_t_min *module = nullptr;
    int rc = getModule("audio", "primary", &module);
    out << "AUDIO_MODULE[primary]:RC=" << rc;
    if (module) {
        out << ",PTR=AVAILABLE";
        if (module->id) out << ",ID=" << module->id;
        if (module->name) out << ",NAME=" << module->name;
        out << "\nAUDIO_MODULE:OBTAINED=YES\n";

        /* Inspect only. Never invoke module->methods->open here. */
        if (module->methods && module->methods->open) {
            out << "AUDIO_MODULE_OPEN_CALLBACK=AVAILABLE\n";
            out << "AUDIO_MODULE_OPEN_INVOKED=NO\n";
        } else {
            out << "AUDIO_MODULE_OPEN_CALLBACK=UNAVAILABLE\n";
            out << "AUDIO_MODULE_OPEN_INVOKED=NO\n";
        }
    } else {
        out << ",PTR=NULL\nAUDIO_MODULE:OBTAINED=NO\n";
        out << "AUDIO_MODULE_OPEN_CALLBACK=UNKNOWN\n";
        out << "AUDIO_MODULE_OPEN_INVOKED=NO\n";
    }

    appendDlopenResult(out, "audio.primary.mt8783.so");
    appendDlopenResult(out, "audio.primary.mt6735.so");
    appendDlopenResult(out, "audio.primary.mt6753.so");
    appendDlopenResult(out, "audio.primary.default.so");

    out << "NATIVE_AUDIO_HW_DEVICE_OPENED=NO\n";
    out << "NATIVE_AUDIO_STREAMS_OPENED=NO\n";
    out << "NATIVE_AUDIO_ROUTING_CHANGED=NO\n";
    out << "NATIVE_AUDIO_HAL_PROBE:END";

    dlclose(hardware);
    ALOGD("Safe HAL callback capability probe completed");
    return env->NewStringUTF(out.str().c_str());
}
