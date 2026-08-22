#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <string>
#include <sstream>

#define LOG_TAG "PhoneBridgeNative"
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/*
 * Deliberately non-invasive HAL probe.
 *
 * The Phab uses an old MediaTek audio HAL.  An APK must not call the private
 * audio HAL device's open()/stream callbacks just to test access: those
 * callbacks can execute vendor code with assumptions that only hold inside
 * mediaserver/audioflinger.  This probe therefore limits itself to loading
 * libhardware, resolving the public hw_get_module_by_class symbol, obtaining
 * the public module descriptor, and probing likely vendor HAL shared-library
 * names with dlopen().  It never opens an audio_hw_device, starts PCM, changes
 * routing, or calls set_mode()/set_parameters().
 */
struct hw_module_t_min;

typedef int (*hw_get_module_by_class_fn)(const char *, const char *,
                                          const hw_module_t_min **);

struct hw_module_t_min {
    uint32_t tag;
    uint16_t module_api_version;
    uint16_t hal_api_version;
    const char *id;
    const char *name;
    const char *author;
    void *methods;
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

    /* This is a public libhardware lookup. It does not open the audio device. */
    const hw_module_t_min *module = nullptr;
    int rc = getModule("audio", "primary", &module);
    out << "AUDIO_MODULE[primary]:RC=" << rc;
    if (module) {
        out << ",PTR=AVAILABLE";
        if (module->id) out << ",ID=" << module->id;
        if (module->name) out << ",NAME=" << module->name;
        out << "\nAUDIO_MODULE:OBTAINED=YES\n";
    } else {
        out << ",PTR=NULL\nAUDIO_MODULE:OBTAINED=NO\n";
    }

    /* Keep the vendor-library discovery from the earlier probe, but only use
     * dlopen/dlclose. No vendor audio_hw_device is instantiated. */
    appendDlopenResult(out, "audio.primary.mt8783.so");
    appendDlopenResult(out, "audio.primary.mt6735.so");
    appendDlopenResult(out, "audio.primary.mt6753.so");
    appendDlopenResult(out, "audio.primary.default.so");

    out << "NATIVE_AUDIO_HW_DEVICE_OPENED=NO\n";
    out << "NATIVE_AUDIO_STREAMS_OPENED=NO\n";
    out << "NATIVE_AUDIO_ROUTING_CHANGED=NO\n";
    out << "NATIVE_AUDIO_HAL_PROBE:END";

    dlclose(hardware);
    ALOGD("Safe HAL capability probe completed");
    return env->NewStringUTF(out.str().c_str());
}
