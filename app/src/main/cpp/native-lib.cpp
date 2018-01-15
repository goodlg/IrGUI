//#define LOG_NDEBUG 0
#define LOG_TAG "Iris-JNI"

#include <jni.h>
#include <string>
#include <android/log.h>
#include <dlfcn.h>

#define UNUSED(x) (void)(x)
#define NELEM(x) ((int) (sizeof(x) / sizeof((x)[0])))

#define  LOGI(...)  __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#define IRIS_LIB_PATH "/system/lib/libraw_interface.so"

enum{
    IRIS_MSG_RECEIVED_RAW_FRAME = 0x001,
    IRIS_MSG_ALL_MSGS = 0xFFFF
} ;

static void *s_handle = NULL;

typedef int (*CAC_FUNC)();
typedef int (*CAC_FUNC1)(int);
typedef int (*CAC_FUNC2)(int, int *);
typedef int (*CAC_FUNC3)(int, int);
typedef void (*PFN_FRAMEPOST_CB)(void *pFrameHeapBase, int frame_len);
typedef int (*CAC_FUNC4)(PFN_FRAMEPOST_CB);

static void dataCallback(void *dataPtr, int size);

/* API */
CAC_FUNC Open = NULL;
CAC_FUNC Close = NULL;
CAC_FUNC StartStream = NULL;
CAC_FUNC StopStream = NULL;
CAC_FUNC2 ReadRegister = NULL;
CAC_FUNC3 WriteRegister = NULL;
CAC_FUNC3 SetLed = NULL;
CAC_FUNC4 RegisterFrameCallback = NULL;

static JavaVM *m_JVM = NULL;
jclass gIrisClass = NULL;
jobject gIrisJObjectWeak = NULL;
jmethodID gPostEvent = NULL;

static void dataCallback(void *dataPtr, int size) {
    jbyteArray obj = NULL;
    JavaVMAttachArgs args = { JNI_VERSION_1_6, __FUNCTION__, NULL };
    JNIEnv* env = NULL;
    m_JVM->AttachCurrentThread(&env, &args);
    
    // allocate Java byte array and copy data
    if (dataPtr != NULL) {
        uint8_t *dataBase = (uint8_t *)dataPtr;
        LOGI("[IR_JNI]: dataCallback, size=%d", size);

        if (dataBase != NULL) {
            const jbyte *data = reinterpret_cast<const jbyte *>(dataBase);
            obj = env->NewByteArray(size >= 0 ? size : 0 );

            if (obj == NULL) {
                LOGE("[IR_JNI] Couldn't allocate byte array for raw data");
                env->ExceptionClear();
            } else {
                env->SetByteArrayRegion(obj, 0, size, data);
            }
        } else {
            LOGE("image heap is NULL");
        }
    }

    // post data to Java
    env->CallStaticVoidMethod(gIrisClass, gPostEvent,
            gIrisJObjectWeak, IRIS_MSG_RECEIVED_RAW_FRAME, 0, 0, obj);
    if (obj) {
        env->DeleteLocalRef(obj);
    }
    m_JVM->DetachCurrentThread();
}
static jint ApiInit(JNIEnv *, jobject) {
    char *error;
    LOGI("[IR_JNI]: ApiInit");

    s_handle = dlopen(IRIS_LIB_PATH, RTLD_NOW);
    if (NULL == s_handle) {
        LOGE("[IR_JNI]: Failed to get IRIS handle in %s()! (Reason=%s)\n", __FUNCTION__, dlerror());
        return JNI_FALSE;
    }

    //clear prev error
    dlerror();

    *(void **) (&Open) = dlsym(s_handle, "RawCam_Open");
    if ((error = dlerror()) != NULL)  {
        LOGE("[IR_JNI]: Failed to get RawCam_Open handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        return JNI_FALSE;
    }

    *(void **) (&Close) = dlsym(s_handle, "RawCam_Close");
    if ((error = dlerror()) != NULL)  {
        LOGE("[IR_JNI]: Failed to get RawCam_Close handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        return JNI_FALSE;
    }

    *(void **) (&StartStream) = dlsym(s_handle, "RawCam_StartStream");
    if ((error = dlerror()) != NULL)  {
        LOGE("[IR_JNI]: Failed to get RawCam_StartStream handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        return JNI_FALSE;
    }

    *(void **) (&StopStream) = dlsym(s_handle, "RawCam_StopStream");
    if ((error = dlerror()) != NULL)  {
        LOGE("[IR_JNI]: Failed to get RawCam_StopStream handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        return JNI_FALSE;
    }

    *(void **) (&ReadRegister) = dlsym(s_handle, "RawCam_ReadRegister");
    if ((error = dlerror()) != NULL)  {
        LOGE("[IR_JNI]: Failed to get RawCam_ReadRegister handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        return JNI_FALSE;
    }

    *(void **) (&WriteRegister) = dlsym(s_handle, "RawCam_WriteRegister");
    if ((error = dlerror()) != NULL)  {
        LOGE("[IR_JNI]: Failed to get RawCam_WriteRegister handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        return JNI_FALSE;
    }

    *(void **) (&SetLed) = dlsym(s_handle, "RawCam_SetLed");
    if ((error = dlerror()) != NULL)  {
        LOGE("[IR_JNI]: Failed to get RawCam_SetLed handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        return JNI_FALSE;
    }

    *(void **) (&RegisterFrameCallback) = dlsym(s_handle, "RawCam_RegisterFrameCallback");
    if ((error = dlerror()) != NULL)  {
        LOGE("[IR_JNI]: Failed to get RawCam_RegisterFrameCallback handle in %s()! (Reason=%s)\n", __FUNCTION__, error);
        return JNI_FALSE;
    }

    LOGI("[IR_JNI]: INIT done");
    return JNI_TRUE;
}

static jint ApiDeinit(JNIEnv *, jobject) {
    if (s_handle != NULL) {
        dlclose(s_handle);
        LOGI("[IR_JNI]: ApiDeinit done");
    }
    return JNI_OK;
}

static jint RawCam_Open(JNIEnv *env, jobject thiz, jobject weak_ir, jint camId) {
    LOGI("[IR_JNI]: RawCamOpen (id %d)", camId);

    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == NULL) {
        // This should never happen
        return JNI_FALSE;
    }

    gIrisJObjectWeak = env->NewGlobalRef(weak_ir);
    gIrisClass = (jclass)env->NewGlobalRef(clazz);

    return Open();
}

static jint RawCam_Close(JNIEnv *env, jobject) {
    LOGI("[IR_JNI]: RawCam_Close");

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
    LOGI("[IR_JNI]: RawCam_StartStream");
    return StartStream();
}

static jint RawCam_StopStream(JNIEnv *, jobject) {
    LOGI("[IR_JNI]: RawCam_StopStream");
    return StopStream();
}

static jint RawCam_ReadRegister(JNIEnv *, jobject, jint addr) {
    jint value = 0;
    LOGI("[IR_JNI]: ReadRegister, addr=%d", addr);
    ReadRegister(addr, &value);
    return value;
}

static jint RawCam_WriteRegister(JNIEnv *, jobject, jint addr, jint value) {
    LOGI("[IR_JNI]: WriteRegister, addr=%d, value=%d", addr, value);
    return WriteRegister(addr, value);
}

static jint RawCam_SetLed(JNIEnv *, jobject, jint led1, jint led2) {
    LOGI("[IR_JNI]: RawCamSetLed, led1=%d, led2=%d", led1, led2);
    return SetLed(led1, led2);
}

static jint RawCam_RegisterFrameCallback(JNIEnv *, jobject) {
    LOGI("[IR_JNI]: RawCam_RegisterFrameCallback...");
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
    {"RawCam_SetLed", "(II)I", (void*) RawCam_SetLed},
    {"RawCam_RegisterFrameCallback", "()I", (void*) RawCam_RegisterFrameCallback},
};

static int registerNativeMethods(JNIEnv* env, const char* className,
        const JNINativeMethod* gMethods, int numMethods) {
    jclass clazz = env->FindClass(className);
    if (clazz == NULL) {
        return JNI_FALSE;
    }
    gPostEvent = env->GetStaticMethodID(clazz, "postRawDataEvent",
                                           "(Ljava/lang/Object;IIILjava/lang/Object;)V");
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