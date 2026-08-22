#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <stdint.h>
#include <string>
#include <sstream>
#include <errno.h>

#define LOG_TAG "PhoneBridgeNative"
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Minimal ABI-compatible definitions for the Android hardware module API. */
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
    uint32_t reserved[32];
};

/* Android 6-era hw_device_t layout. */
struct hw_device_t_min {
    uint32_t tag;
    uint32_t version;
    hw_module_t_min *module;
    uint32_t reserved[12];
    int (*close)(hw_device_t_min *device);
    uint32_t reserved2[4];
};

/* Only the beginning of audio_hw_device is inspected. */
struct audio_hw_device_probe_min {
    hw_device_t_min common;
    void *get_supported_devices;
    void *init_check;
    void *set_voice_volume;
    void *set_master_volume;
    void *get_master_volume;
    void *set_mode;
    void *set_mic_mute;
    void *get_mic_mute;
    void *set_parameters;
    void *get_parameters;
    void *get_input_buffer_size;
    void *open_output_stream;
    void *close_output_stream;
    void *open_input_stream;
    void *close_input_stream;
    void *dump;
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

    if (!module) {
        out << ",PTR=NULL\n";
        out << "AUDIO_MODULE:OBTAINED=NO\n";
        out << "AUDIO_MODULE_OPEN_CALLBACK=UNKNOWN\n";
        out << "AUDIO_MODULE_OPEN_INVOKED=NO\n";
        appendDlopenResult(out, "audio.primary.mt8783.so");
        appendDlopenResult(out, "audio.primary.mt6735.so");
        appendDlopenResult(out, "audio.primary.mt6753.so");
        appendDlopenResult(out, "audio.primary.default.so");
        out << "NATIVE_AUDIO_HW_DEVICE_OPENED=NO\n";
        out << "NATIVE_AUDIO_HW_DEVICE_CLOSE_CALLED=NO\n";
        out << "NATIVE_AUDIO_INIT_CHECK=NOT_RUN\n";
        out << "NATIVE_AUDIO_INPUT_STREAM_CALLBACK=UNKNOWN\n";
        out << "NATIVE_AUDIO_INPUT_STREAM_OPENED=NO\n";
        out << "NATIVE_AUDIO_ROUTING_CHANGED=NO\n";
        out << "NATIVE_AUDIO_HAL_PROBE:END";
        dlclose(hardware);
        return env->NewStringUTF(out.str().c_str());
    }

    out << ",PTR=AVAILABLE";
    if (module->id) out << ",ID=" << module->id;
    if (module->name) out << ",NAME=" << module->name;
    out << "\nAUDIO_MODULE:OBTAINED=YES\n";

    if (!module->methods || !module->methods->open) {
        out << "AUDIO_MODULE_OPEN_CALLBACK=UNAVAILABLE\n";
        out << "AUDIO_MODULE_OPEN_INVOKED=NO\n";
        out << "NATIVE_AUDIO_HW_DEVICE_OPENED=NO\n";
        out << "NATIVE_AUDIO_HW_DEVICE_CLOSE_CALLED=NO\n";
        out << "NATIVE_AUDIO_INIT_CHECK=NOT_RUN\n";
        out << "NATIVE_AUDIO_INPUT_STREAM_CALLBACK=UNKNOWN\n";
        out << "NATIVE_AUDIO_INPUT_STREAM_OPENED=NO\n";
        out << "NATIVE_AUDIO_ROUTING_CHANGED=NO\n";
        out << "NATIVE_AUDIO_HAL_PROBE:END";
        dlclose(hardware);
        return env->NewStringUTF(out.str().c_str());
    }

    out << "AUDIO_MODULE_OPEN_CALLBACK=AVAILABLE\n";

    /*
     * The hardware-module open callback does NOT receive the module class
     * name ("audio").  Android's audio HAL contract uses
     * AUDIO_HARDWARE_INTERFACE, which is "audio_hw_if" on the Android 6
     * interface used by this device.  Passing "audio" makes many vendor
     * implementations return -EINVAL (-22) before creating the device.
     *
     * This remains a non-invasive probe: no stream and no routing operation
     * is performed. If the device opens, it is immediately closed through
     * its own common.close callback.
     */
    static const char AUDIO_HARDWARE_INTERFACE[] = "audio_hw_if";

    hw_device_t_min *rawDevice = nullptr;
    out << "AUDIO_MODULE_OPEN_INVOKED=YES\n";
    out << "AUDIO_MODULE_OPEN_ID=" << AUDIO_HARDWARE_INTERFACE << "\n";
    int openRc = module->methods->open(module, AUDIO_HARDWARE_INTERFACE, &rawDevice);
    out << "AUDIO_HW_DEVICE_OPEN_RC=" << openRc << "\n";

    if (openRc != 0 || !rawDevice) {
        out << "NATIVE_AUDIO_HW_DEVICE_OPENED=NO\n";
        out << "NATIVE_AUDIO_HW_DEVICE_CLOSE_CALLED=NO\n";
        out << "NATIVE_AUDIO_INIT_CHECK=NOT_RUN\n";
        out << "NATIVE_AUDIO_INPUT_STREAM_CALLBACK=UNKNOWN\n";
        out << "NATIVE_AUDIO_INPUT_STREAM_OPENED=NO\n";
        out << "NATIVE_AUDIO_ROUTING_CHANGED=NO\n";
        out << "NATIVE_AUDIO_HAL_PROBE:END";
        dlclose(hardware);
        return env->NewStringUTF(out.str().c_str());
    }

    out << "NATIVE_AUDIO_HW_DEVICE_OPENED=YES\n";

    audio_hw_device_probe_min *audioDevice =
            reinterpret_cast<audio_hw_device_probe_min *>(rawDevice);

    out << "NATIVE_AUDIO_INIT_CHECK=NOT_RUN\n";
    out << "NATIVE_AUDIO_INPUT_STREAM_CALLBACK="
        << (audioDevice->open_input_stream ? "AVAILABLE" : "UNAVAILABLE") << "\n";
    out << "NATIVE_AUDIO_OUTPUT_STREAM_CALLBACK="
        << (audioDevice->open_output_stream ? "AVAILABLE" : "UNAVAILABLE") << "\n";
    out << "NATIVE_AUDIO_SET_MODE_CALLBACK="
        << (audioDevice->common.reserved[0] ? "UNKNOWN" : "UNKNOWN") << "\n";

    bool closeCalled = false;
    if (rawDevice->close) {
        int closeRc = rawDevice->close(rawDevice);
        out << "AUDIO_HW_DEVICE_CLOSE_RC=" << closeRc << "\n";
        closeCalled = true;
    } else {
        out << "AUDIO_HW_DEVICE_CLOSE_RC=UNAVAILABLE\n";
    }
    out << "NATIVE_AUDIO_HW_DEVICE_CLOSE_CALLED="
        << (closeCalled ? "YES" : "NO") << "\n";

    /* Deliberately do not open a real capture/playback stream here. That
     * must be done by AudioFlinger/AudioPolicy to avoid disturbing an active
     * phone call. We only establish whether the callbacks exist. */
    out << "NATIVE_AUDIO_INPUT_STREAM_OPENED=NO\n";
    out << "NATIVE_AUDIO_OUTPUT_STREAM_OPENED=NO\n";
    out << "NATIVE_AUDIO_ROUTING_CHANGED=NO\n";

    appendDlopenResult(out, "audio.primary.mt8783.so");
    appendDlopenResult(out, "audio.primary.mt6735.so");
    appendDlopenResult(out, "audio.primary.mt6753.so");
    appendDlopenResult(out, "audio.primary.default.so");

    out << "NATIVE_AUDIO_HAL_PROBE:END";
    dlclose(hardware);
    ALOGD("MTK audio HAL device-open probe completed");
    return env->NewStringUTF(out.str().c_str());
}
