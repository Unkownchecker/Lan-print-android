/* JNI bridge: Kotlin -> f2x_run() (the wrapped foo2xqx). GPL-2.0-or-later. */
#include <jni.h>
#include <stdlib.h>
#include <string.h>

int f2x_run(int argc, char **argv, const char *in_path, const char *out_path);

/*
 * Foo2xqx.nativeConvert(pbmPath, outPath, args) -> exit code (0 = success)
 * args are foo2xqx command-line options WITHOUT the program name.
 */
JNIEXPORT jint JNICALL
Java_com_lanprint_android_Foo2xqx_nativeConvert(JNIEnv *env, jclass clazz,
                                                jstring jin, jstring jout,
                                                jobjectArray jargs)
{
    (void) clazz;
    const char *in = (*env)->GetStringUTFChars(env, jin, NULL);
    const char *out = (*env)->GetStringUTFChars(env, jout, NULL);
    jsize n = jargs ? (*env)->GetArrayLength(env, jargs) : 0;

    char **argv = (char **) calloc((size_t) n + 2, sizeof(char *));
    jstring *refs = (jstring *) calloc((size_t) n + 1, sizeof(jstring));
    if (!argv || !refs || !in || !out) {
        free(argv); free(refs);
        if (in) (*env)->ReleaseStringUTFChars(env, jin, in);
        if (out) (*env)->ReleaseStringUTFChars(env, jout, out);
        return -100;
    }

    argv[0] = "foo2xqx";
    for (jsize i = 0; i < n; i++) {
        refs[i] = (jstring) (*env)->GetObjectArrayElement(env, jargs, i);
        const char *s = (*env)->GetStringUTFChars(env, refs[i], NULL);
        argv[i + 1] = strdup(s ? s : "");
        if (s) (*env)->ReleaseStringUTFChars(env, refs[i], s);
        (*env)->DeleteLocalRef(env, refs[i]);
    }
    argv[n + 1] = NULL;

    int rc = f2x_run((int) n + 1, argv, in, out);

    for (jsize i = 0; i < n; i++) free(argv[i + 1]);
    free(argv);
    free(refs);
    (*env)->ReleaseStringUTFChars(env, jin, in);
    (*env)->ReleaseStringUTFChars(env, jout, out);
    return rc;
}
