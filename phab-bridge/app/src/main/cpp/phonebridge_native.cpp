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
 * This file intentionally does not depend on the device's private MTK audio
 * headers.  We only mirror the small public libhardware layouts needed to
 * inspect/open the primary audio HAL.
 */
struct hw_module_t_min;
struct hw_device_t_min;

struct hw_module_methods_t_min {
    int (*open)(const hw_module_t_min *module, const char *id,
                hw_device_t_min **device);
};

struct hw_module_t_min {
    uint32_t tag;
    uint16_t module_api_version;
    uint16_t hal_api_version;
    const char *id;
    const char *name;
    const char *author;
    hw_module_methods_t_min *methods;
    void *dso;
    uint32_t reserved[32];
};

struct hw_device_t_min {
    uint32_t tag;
    uint32_t version;
    hw_module_t_min *module;
    int (*close)(hw_device_t_min *device);
    uint32_t reserved[32];
};

typedef int (*hw_get_module_by_class_fn)(const char *, const char *,
                                          const hw_module_t_min **);

typedef int (*hw_get_module_fn)(const char *, const hw_module_t_min **);

/* Android audio HAL public constants/layouts, mirrored to avoid private MTK
 * headers.  We inspect the callback table only; no PCM stream is opened. */
struct audio_hw_device_min {
    hw_device_t_min common;
    int (*init_check)(audio_hw_device_min *dev);
    int (*set_voice_volume)(audio_hw_device_min *dev, float volume);
    int (*set_master_volume)(audio_hw_device_min *dev, float volume);
    int (*get_master_volume)(audio_hw_device_min *dev, float *volume);
    int (*set_mode)(audio_hw_device_min *dev, int mode);
    int (*set_mic_mute)(audio_hw_device_min *dev, bool state);
    int (*get_mic_mute)(const audio_hw_device_min *dev, bool *state);
    int (*set_parameters)(audio_hw_device_min *dev, const char *kv_pairs);
    char *(*get_parameters)(const audio_hw_device_min *dev, const char *keys);
    size_t (*get_input_buffer_size)(const void *config);
    void *open_output_stream;
    void *close_output_stream;
    void *open_input_stream;
    void *close_input_stream;
    int (*dump)(const audio_hw_device_min *dev, int fd);
    int (*set_master_mute)(audio_hw_device_min *dev, bool state);
    int (*get_master_mute)(audio_hw_device_min *dev, bool *state);
    int (*set_microphone_mute)(audio_hw_device_min *dev, bool state);
    int (*get_microphone_mute)(const audio_hw_device_min *dev, bool *state);
    void *reserved[8];
};

static std::string ptrString(const void *p) {
    std::ostringstream out;
    out << "0x" << std::hex << reinterpret_cast<uintptr_t>(p);
    return out.str();
}

static void appendCallback(std::ostringstream &out, const char *name,
                           const void *fn) {
    out << name << "=" << (fn ? "YES" : "NO") << "\n";
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
        out << "\nAUDIO_MODULE:OBTAINED=NO\n";
        dlclose(hardware);
        out << "NATIVE_AUDIO_HAL_PROBE:END";
        return env->NewStringUTF(out.str().c_str());
    }

    out << ",PTR=" << ptrString(module);
    if (module->id) out << ",ID=" << module->id;
    if (module->name) out << ",NAME=" << module->name;
    out << "\nAUDIO_MODULE:OBTAINED=YES\n";
    out << "AUDIO_MODULE_API=" << module->module_api_version << "\n";
    out << "AUDIO_HAL_API=" << module->hal_api_version << "\n";

    if (!module->methods || !module->methods->open) {
        out << "AUDIO_MODULE_OPEN:AVAILABLE=NO\n";
        dlclose(hardware);
        out << "NATIVE_AUDIO_HAL_PROBE:END";
        return env->NewStringUTF(out.str().c_str());
    }

    out << "AUDIO_MODULE_OPEN:AVAILABLE=YES\n";

    /* Open only the HAL device object.  This does NOT open an input/output
     * stream, does NOT start PCM, and does NOT alter routing. */
    hw_device_t_min *rawDevice = nullptr;
    rc = module->methods->open(module, "audio_hw_if", &rawDevice);
    out << "AUDIO_HW_DEVICE_OPEN:RC=" << rc;
    if (!rawDevice) {
        out << ",DEVICE=NULL\n";
        dlclose(hardware);
        out << "NATIVE_AUDIO_HAL_PROBE:END";
        return env->NewStringUTF(out.str().c_str());
    }
    out << ",DEVICE=" << ptrString(rawDevice) << "\n";

    audio_hw_device_min *audioDevice =
            reinterpret_cast<audio_hw_device_min *>(rawDevice);

    out << "AUDIO_HW_DEVICE_VERSION=" << audioDevice->common.version << "\n";
    appendCallback(out, "CALLBACK:init_check", reinterpret_cast<void *>(audioDevice->init_check));
    appendCallback(out, "CALLBACK:set_voice_volume", reinterpret_cast<void *>(audioDevice->set_voice_volume));
    appendCallback(out, "CALLBACK:set_mode", reinterpret_cast<void *>(audioDevice->set_mode));
    appendCallback(out, "CALLBACK:set_mic_mute", reinterpret_cast<void *>(audioDevice->set_mic_mute));
    appendCallback(out, "CALLBACK:set_parameters", reinterpret_cast<void *>(audioDevice->set_parameters));
    appendCallback(out, "CALLBACK:get_parameters", reinterpret_cast<void *>(audioDevice->get_parameters));
    appendCallback(out, "CALLBACK:get_input_buffer_size", reinterpret_cast<void *>(audioDevice->get_input_buffer_size));
    appendCallback(out, "CALLBACK:open_output_stream", audioDevice->open_output_stream);
    appendCallback(out, "CALLBACK:close_output_stream", audioDevice->close_output_stream);
    appendCallback(out, "CALLBACK:open_input_stream", audioDevice->open_input_stream);
    appendCallback(out, "CALLBACK:close_input_stream", audioDevice->close_input_stream);
    appendCallback(out, "CALLBACK:dump", reinterpret_cast<void *>(audioDevice->dump));

    /* Do not call set_parameters(), set_mode(), open_input_stream(), or
     * open_output_stream() here.  Those are the operations that could alter
     * the live call/audio route.  This probe only establishes whether the HAL
     * exposes the native control points that the bridge can use next. */
    out << "NATIVE_AUDIO_STREAMS_OPENED=NO\n";
    out << "NATIVE_AUDIO_ROUTING_CHANGED=NO\n";

    if (rawDevice->close) {
        int closeRc = rawDevice->close(rawDevice);
        out << "AUDIO_HW_DEVICE_CLOSE:RC=" << closeRc << "\n";
    } else {
        out << "AUDIO_HW_DEVICE_CLOSE:AVAILABLE=NO\n";
    }

    dlclose(hardware);
    out << "NATIVE_AUDIO_HAL_PROBE:END";
    ALOGD("HAL capability probe completed");
    return env->NewStringUTF(out.str().c_str());
}
