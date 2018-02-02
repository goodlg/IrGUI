//#define LOG_NDEBUG 0
#define LOG_TAG "Iris-JNI"

#include <jni.h>
#include <string>
#include <android/log.h>
#include <dlfcn.h>
#include <math.h>

#define UNUSED(x) (void)(x)
#define NELEM(x) ((int) (sizeof(x) / sizeof((x)[0])))

#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define IRIS_LIB_PATH "/system/lib/libraw_interface.so"

enum{
    IRIS_MSG_CONTINUE_RAW_FRAME = 0x001,
    IRIS_MSG_ONE_RAW_FRAME = 0x002,
    IRIS_MSG_ALL_MSGS = 0xFFFF
} ;

typedef int (*CAC_FUNC)();
typedef int (*CAC_FUNC1)(int);
typedef int (*CAC_FUNC2)(int, int *);
typedef int (*CAC_FUNC3)(int, int);
typedef void (*PFN_FRAMEPOST_CB)(void *pFrameHeapBase, int frame_len);
typedef int (*CAC_FUNC4)(PFN_FRAMEPOST_CB);
typedef int (*CAC_FUNC5)(int *);
typedef int (*CAC_FUNC6)(float);
typedef int (*CAC_FUNC7)(int, int, int);

static void dataCallback(void *dataPtr, int size);

/* API */
CAC_FUNC1 Open = NULL;
CAC_FUNC Close = NULL;
CAC_FUNC StartStream = NULL;
CAC_FUNC StopStream = NULL;
CAC_FUNC2 ReadRegister = NULL;
CAC_FUNC3 WriteRegister = NULL;
CAC_FUNC7 SetLed = NULL;
CAC_FUNC5 GetBuffer = NULL;
CAC_FUNC4 RegisterFrameCallback = NULL;
CAC_FUNC6 SetFps = NULL;
CAC_FUNC3 SetResolution = NULL;
CAC_FUNC1 SetFocus = NULL;
CAC_FUNC1 SetFormat = NULL;

jint gCurrentWidth = 1944, gCurrentHeight = 1944;

static void *s_handle = NULL;
static JavaVM *m_JVM = NULL;
jclass gIrisClass = NULL;
jobject gIrisJObjectWeak = NULL;
jmethodID gPostEvent = NULL;
jboolean gApiInited = JNI_FALSE;

//CAM_FORMAT_BAYER_MIPI_RAW_10BPP_BGGR to alpha8
static void raw10ToAlpha8(char* in, char* out, int width, int height) {
    int lineSize = (width /  4 * 5 - 1) / 4 * 4 + 4;
    char* inp = in;
    char* outp = out;
    int skip2line = lineSize - width /  4 * 5;
    int line;
    int pixelgroup;

    for (line = 0; line < height; line++){
        for (pixelgroup = 0; pixelgroup < width / 4; pixelgroup++) {
            *outp++ = -(*inp++);
            *outp++ = -(*inp++);
            *outp++ = -(*inp++);
            *outp++ = -(*inp++);
            inp++;
        }
        inp += skip2line;
    }
}

static void dataCallback(void *dataPtr, int size) {
    jbyteArray rawObj = NULL;
    jbyteArray alpha8Obj = NULL;
    uint8_t *out = NULL;
    JNIEnv* env = NULL;
    JavaVMAttachArgs args = { JNI_VERSION_1_6, __FUNCTION__, NULL };
    m_JVM->AttachCurrentThread(&env, &args);

    // allocate Java byte array and copy data
    if (dataPtr != NULL) {
        uint8_t *dataBase = (uint8_t *)dataPtr;
        LOGI("dataCallback, size=%d", size);

        if (dataBase != NULL) {
            out = new uint8_t[size];
            //get alpha8 data
            raw10ToAlpha8((char *)dataBase, (char *)out, gCurrentWidth, gCurrentHeight);

            const jbyte *rawData = reinterpret_cast<const jbyte *>(dataBase);
            rawObj = env->NewByteArray(size >= 0 ? size : 0 );

            const jbyte *alpha8Data = reinterpret_cast<const jbyte *>(out);
            alpha8Obj = env->NewByteArray(size >= 0 ? size : 0 );

            if (rawObj == NULL || alpha8Obj == NULL) {
                LOGE("Couldn't allocate byte array for raw data");
                env->ExceptionClear();
            } else {
                env->SetByteArrayRegion(rawObj, 0, size, rawData);
                env->SetByteArrayRegion(alpha8Obj, 0, size, alpha8Data);
            }
        } else {
            LOGE("image heap is NULL");
        }
    }

    // post data to Java
    env->CallStaticVoidMethod(gIrisClass, gPostEvent,
            gIrisJObjectWeak, IRIS_MSG_CONTINUE_RAW_FRAME, 0, 0, rawObj, alpha8Obj);

    if (rawObj) {
        env->DeleteLocalRef(rawObj);
    }

    if (alpha8Obj) {
        env->DeleteLocalRef(alpha8Obj);
    }

    if (out) {
        delete out;
    }

    m_JVM->DetachCurrentThread();
}

static jint ApiInit(JNIEnv *, jobject) {
    char *error;
    LOGI("ApiInit");

    s_handle = dlopen(IRIS_LIB_PATH, RTLD_NOW);
    if (NULL == s_handle) {
        LOGE("Failed to get IRIS handle in %s()! (Reason=%s)\n", __FUNCTION__, dlerror());
        return JNI_FALSE;
    }

    //clear prev error
    dlerror();

    *(void **) (&Open) = dlsym(s_handle, "RawCam_Open");
    if ((error = dlerror()) != NULL)  {
        LOGE("Failed to get RawCam_Open handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        return JNI_FALSE;
    }

    *(void **) (&Close) = dlsym(s_handle, "RawCam_Close");
    if ((error = dlerror()) != NULL)  {
        LOGE("Failed to get RawCam_Close handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        return JNI_FALSE;
    }

    *(void **) (&StartStream) = dlsym(s_handle, "RawCam_StartStream");
    if ((error = dlerror()) != NULL)  {
        LOGE("Failed to get RawCam_StartStream handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        return JNI_FALSE;
    }

    *(void **) (&StopStream) = dlsym(s_handle, "RawCam_StopStream");
    if ((error = dlerror()) != NULL)  {
        LOGE("Failed to get RawCam_StopStream handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        return JNI_FALSE;
    }

    *(void **) (&ReadRegister) = dlsym(s_handle, "RawCam_ReadRegister");
    if ((error = dlerror()) != NULL)  {
        LOGE("Failed to get RawCam_ReadRegister handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        return JNI_FALSE;
    }

    *(void **) (&WriteRegister) = dlsym(s_handle, "RawCam_WriteRegister");
    if ((error = dlerror()) != NULL)  {
        LOGE("Failed to get RawCam_WriteRegister handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        return JNI_FALSE;
    }

    *(void **) (&SetLed) = dlsym(s_handle, "RawCam_SetLed");
    if ((error = dlerror()) != NULL)  {
        LOGE("Failed to get RawCam_SetLed handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        return JNI_FALSE;
    }

    *(void **) (&GetBuffer) = dlsym(s_handle, "RawCam_GetBuffer");
    if ((error = dlerror()) != NULL)  {
        LOGE("Failed to get RawCam_GetBuffer handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        //return JNI_FALSE;
    }

    *(void **) (&RegisterFrameCallback) = dlsym(s_handle, "RawCam_RegisterFrameCallback");
    if ((error = dlerror()) != NULL)  {
        LOGE("Failed to get RawCam_RegisterFrameCallback handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        return JNI_FALSE;
    }

    *(void **) (&SetFps) = dlsym(s_handle, "RawCam_SetFps");
    if ((error = dlerror()) != NULL)  {
        LOGE("Failed to get RawCam_SetFps handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        return JNI_FALSE;
    }

    *(void **) (&SetResolution) = dlsym(s_handle, "RawCam_SetResolution");
    if ((error = dlerror()) != NULL)  {
        LOGE("Failed to get RawCam_SetResolution handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        return JNI_FALSE;
    }

    *(void **) (&SetFocus) = dlsym(s_handle, "RawCam_SetFocus");
    if ((error = dlerror()) != NULL)  {
        LOGE("Failed to get RawCam_SetFocus handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        return JNI_FALSE;
    }

    *(void **) (&SetFormat) = dlsym(s_handle, "RawCam_SetFormat");
    if ((error = dlerror()) != NULL)  {
        LOGE("Failed to get RawCam_SetFormat handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        //return JNI_FALSE;
    }

    LOGI("INIT done");
    gApiInited = JNI_TRUE;
    return JNI_TRUE;
}

static jint ApiDeinit(JNIEnv *, jobject) {
    if (s_handle != NULL) {
        dlclose(s_handle);
        LOGI("ApiDeinit done");
    }
    return JNI_OK;
}

static jint RawCam_Open(JNIEnv *env, jobject thiz, jobject weak_ir, jint camId) {
    LOGI("RawCamOpen (id %d)", camId);
    int ret = -1, rc = 0;
    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == NULL) {
        // This should never happen
        return JNI_FALSE;
    }

    gIrisJObjectWeak = env->NewGlobalRef(weak_ir);
    gIrisClass = (jclass)env->NewGlobalRef(clazz);

    ret = Open(camId);

    // set register for preview
    if ((rc = WriteRegister(0x3500, 0x00)) != 0
        && (rc = WriteRegister(0x3501, 0x40)) != 0
        && (rc = WriteRegister(0x3502, 0x00)) != 0
        && (rc = WriteRegister(0x3508, 0x00)) != 0
        && (rc = WriteRegister(0x3509, 0x80)) != 0) {
        LOGE("set register failed for preview");
    }

    return ret;
}

static jint RawCam_Close(JNIEnv *env, jobject) {
    LOGI("RawCam_Close");

    if (gIrisJObjectWeak != NULL) {
        env->DeleteGlobalRef(gIrisJObjectWeak);
        gIrisJObjectWeak = NULL;
    }
    if (gIrisClass != NULL) {
        env->DeleteGlobalRef(gIrisClass);
        gIrisClass = NULL;
    }

    return Close();
}

static jint RawCam_StartStream(JNIEnv *, jobject) {
    LOGI("RawCam_StartStream");
    return StartStream();
}

static jint RawCam_StopStream(JNIEnv *, jobject) {
    LOGI("RawCam_StopStream");
    return StopStream();
}

static jint RawCam_ReadRegister(JNIEnv *, jobject, jint addr) {
    jint value = 0;
    LOGI("ReadRegister, addr=%d", addr);
    ReadRegister(addr, &value);
    return value;
}

static jint RawCam_WriteRegister(JNIEnv *, jobject, jint addr, jint value) {
    LOGI("WriteRegister, addr=%d, value=%d", addr, value);
    return WriteRegister(addr, value);
}

static jint RawCam_SetLed(JNIEnv *, jobject, jint led1, jint led2, jint ledType) {
    LOGI("RawCamSetLed, led1=%d, led2=%d, ledType=%d", led1, led2, ledType);
    return SetLed(led1, led2, ledType);
}

static jint RawCam_SetFps(JNIEnv *, jobject, jfloat fps) {
    LOGI("RawCam_SetFps, fps=%f", fps);
    if (SetFps == NULL) return -1;
    return SetFps(fps);
}

static jint RawCam_SetResolution(JNIEnv *, jobject, jint width, jint height) {
    gCurrentWidth = width;
    gCurrentHeight = height;
    LOGI("SetResolution, width=%d, height=%d", gCurrentWidth, gCurrentHeight);
    if (SetResolution == NULL) return -1;
    return SetResolution(width, height);
}

static jint RawCam_SetFocus(JNIEnv *, jobject, jint mode) {
    LOGI("SetFocus, focus mode=%d", mode);
    return SetFocus(mode);
}

static jint RawCam_SetFormat(JNIEnv *, jobject, jint format) {
    LOGI("SetFormat, format=%d", format);
    if (SetFormat == NULL) return -1;
    return SetFormat(format);
}

static void RawCam_GetBuffer(JNIEnv *env, jobject) {
    LOGI("GetBuffer");
    if (GetBuffer == NULL) return;
    jbyteArray obj = NULL;
    int size = 0;
    uint8_t *database = (uint8_t *)GetBuffer(&size);

    // allocate Java byte array and copy data
    if (database != NULL) {
        const jbyte *data = reinterpret_cast<const jbyte *>(database);
        obj = env->NewByteArray(size >= 0 ? size : 0 );

        if (obj == NULL) {
            LOGE("Couldn't allocate byte array for raw data");
            env->ExceptionClear();
        } else {
            env->SetByteArrayRegion(obj, 0, size, data);
        }
    } else {
        LOGE("image heap is NULL");
    }

    // post data to Java
    env->CallStaticVoidMethod(gIrisClass, gPostEvent,
                              gIrisJObjectWeak, IRIS_MSG_ONE_RAW_FRAME, 0, 0, obj);
    if (obj) {
        env->DeleteLocalRef(obj);
    }
}

static jint RawCam_RegisterFrameCallback(JNIEnv *, jobject) {
    LOGI("RawCam_RegisterFrameCallback...");
    return RegisterFrameCallback(dataCallback);
}

static const JNINativeMethod sMethods[] = {
    {"ApiInit", "()I", (void*) ApiInit},
    {"ApiDeinit", "()I", (void*) ApiDeinit},
    {"RawCam_Open", "(Ljava/lang/Object;I)I", (void*) RawCam_Open},
    {"RawCam_Close", "()I", (void*) RawCam_Close},
    {"RawCam_StartStream", "()I", (void*) RawCam_StartStream},
    {"RawCam_StopStream", "()I", (void*) RawCam_StopStream},
    {"RawCam_ReadRegister", "(I)I", (void*) RawCam_ReadRegister},
    {"RawCam_WriteRegister", "(II)I", (void*) RawCam_WriteRegister},
    {"RawCam_SetLed", "(III)I", (void*) RawCam_SetLed},
    {"RawCam_GetBuffer", "()V", (void*) RawCam_GetBuffer},
    {"RawCam_RegisterFrameCallback", "()I", (void*) RawCam_RegisterFrameCallback},
    {"RawCam_SetFps", "(F)I", (void*) RawCam_SetFps},
    {"RawCam_SetResolution", "(II)I", (void*) RawCam_SetResolution},
    {"RawCam_SetFocus", "(I)I", (void*) RawCam_SetFocus},
    {"RawCam_SetFormat", "(I)I", (void*) RawCam_SetFormat},
};

static int registerNativeMethods(JNIEnv* env, const char* className,
        const JNINativeMethod* gMethods, int numMethods) {
    jclass clazz = env->FindClass(className);
    if (clazz == NULL) {
        return JNI_FALSE;
    }
    gPostEvent = env->GetStaticMethodID(clazz, "postRawDataEvent",
                                           "(Ljava/lang/Object;IIILjava/lang/Object;Ljava/lang/Object;)V");
    if (env->RegisterNatives(clazz, gMethods, numMethods) < 0) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

jint JNI_OnLoad(JavaVM* jvm, void*) {
    m_JVM = jvm;
    JNIEnv *env = NULL;
    if (jvm->GetEnv((void**) &env, JNI_VERSION_1_6)) {
        return JNI_ERR;
    }
    if (registerNativeMethods(env, "org/ftd/gyn/IrisguiActicity",
            sMethods, NELEM(sMethods)) == -1) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}