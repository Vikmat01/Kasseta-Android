/* JNI bridge for com.kasseta.player.Native */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "kcore.h"

#define FN(name) Java_com_kasseta_player_Native_##name

/* Paths are passed as UTF-8 byte arrays: JNI "modified UTF-8" would mangle emoji in file names. */
static char *jpath(JNIEnv *env, jbyteArray a) {
	if (!a) return NULL;
	jsize n = (*env)->GetArrayLength(env, a);
	char *p = malloc((size_t)n + 1);
	if (!p) return NULL;
	(*env)->GetByteArrayRegion(env, a, 0, n, (jbyte *)p);
	p[n] = 0;
	return p;
}

JNIEXPORT jlong JNICALL FN(open)(JNIEnv *env, jclass cls, jbyteArray path, jint maxRate, jboolean dsdFilter, jobjectArray errOut) {
	(void)cls;
	char *p = jpath(env, path);
	if (!p) return 0;
	char err[256] = "";
	KDec *d = kd_open(p, maxRate, dsdFilter ? 1 : 0, err, sizeof err);
	free(p);
	if (!d && errOut && (*env)->GetArrayLength(env, errOut) > 0) {
		jstring e = (*env)->NewStringUTF(env, err);
		(*env)->SetObjectArrayElement(env, errOut, 0, e);
	}
	return (jlong)(intptr_t)d;
}

/* out: [outRate, outCh, srcRate, srcCh, bits, isDsd, dsdRate, durMs low 31 bits, durMs high bits, kbps]; returns codec name */
JNIEXPORT jstring JNICALL FN(info)(JNIEnv *env, jclass cls, jlong h, jintArray out) {
	(void)cls;
	KDec *d = (KDec *)(intptr_t)h;
	if (!d) return NULL;
	KInfo in;
	kd_info(d, &in);
	jint v[10] = { in.out_rate, in.out_ch, in.src_rate, in.src_ch, in.bits, in.is_dsd, in.dsd_rate,
	               (jint)(in.dur_ms & 0x7fffffff), (jint)(in.dur_ms >> 31), in.bitrate };
	if (out && (*env)->GetArrayLength(env, out) >= 10) (*env)->SetIntArrayRegion(env, out, 0, 10, v);
	return (*env)->NewStringUTF(env, in.codec);
}

JNIEXPORT jint JNICALL FN(read)(JNIEnv *env, jclass cls, jlong h, jfloatArray buf, jint maxFrames) {
	(void)cls;
	KDec *d = (KDec *)(intptr_t)h;
	if (!d) return -1;
	jsize cap = (*env)->GetArrayLength(env, buf) / 2;
	if (maxFrames > cap) maxFrames = cap;
	/* decode into a native scratch buffer, then copy: decoding can take a while and must not
	   run inside a GetPrimitiveArrayCritical section */
	static __thread float *tmp;
	static __thread int tmp_cap;
	if (tmp_cap < maxFrames * 2) {
		free(tmp);
		tmp = malloc((size_t)maxFrames * 2 * sizeof(float));
		tmp_cap = tmp ? maxFrames * 2 : 0;
		if (!tmp) return -1;
	}
	int n = kd_read(d, tmp, maxFrames);
	if (n > 0) (*env)->SetFloatArrayRegion(env, buf, 0, n * 2, tmp);
	return n;
}

JNIEXPORT jint JNICALL FN(seek)(JNIEnv *env, jclass cls, jlong h, jlong ms) {
	(void)env; (void)cls;
	KDec *d = (KDec *)(intptr_t)h;
	return d ? kd_seek(d, ms) : -1;
}

JNIEXPORT void JNICALL FN(setGain)(JNIEnv *env, jclass cls, jlong h, jfloat g) {
	(void)env; (void)cls;
	KDec *d = (KDec *)(intptr_t)h;
	if (d) kd_set_gain(d, g);
}

JNIEXPORT void JNICALL FN(close)(JNIEnv *env, jclass cls, jlong h) {
	(void)env; (void)cls;
	kd_close((KDec *)(intptr_t)h);
}

static jbyteArray to_jbytes(JNIEnv *env, const void *p, int len) {
	jbyteArray a = (*env)->NewByteArray(env, len);
	if (a) (*env)->SetByteArrayRegion(env, a, 0, len, (const jbyte *)p);
	return a;
}

JNIEXPORT jbyteArray JNICALL FN(probe)(JNIEnv *env, jclass cls, jbyteArray path) {
	(void)cls;
	char *p = jpath(env, path);
	if (!p) return NULL;
	int len = 0;
	char *r = kp_probe(p, &len);
	free(p);
	if (!r) return NULL;
	jbyteArray a = to_jbytes(env, r, len);
	free(r);
	return a;
}

JNIEXPORT jbyteArray JNICALL FN(cover)(JNIEnv *env, jclass cls, jbyteArray path) {
	(void)cls;
	char *p = jpath(env, path);
	if (!p) return NULL;
	int len = 0;
	uint8_t *r = kp_cover(p, &len);
	free(p);
	if (!r) return NULL;
	jbyteArray a = to_jbytes(env, r, len);
	free(r);
	return a;
}
