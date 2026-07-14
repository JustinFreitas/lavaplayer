#include "connector.h"
#include <samplerate.h>

CONNECTOR_EXPORT jlong JNICALL Java_com_sedmelluq_discord_lavaplayer_natives_samplerate_SampleRateLibrary_create(JNIEnv *jni, jobject me, jint type, jint channels) {
	int error;
	return (jlong)src_new(type, channels, &error);
}

CONNECTOR_EXPORT void JNICALL Java_com_sedmelluq_discord_lavaplayer_natives_samplerate_SampleRateLibrary_destroy(JNIEnv *jni, jobject me, jlong instance) {
	if (instance != 0) {
		src_delete((SRC_STATE*)instance);
	}
}

CONNECTOR_EXPORT void JNICALL Java_com_sedmelluq_discord_lavaplayer_natives_samplerate_SampleRateLibrary_reset(JNIEnv *jni, jobject me, jlong instance) {
	if (instance != 0) {
		src_reset((SRC_STATE*)instance);
	}
}

CONNECTOR_EXPORT jint JNICALL Java_com_sedmelluq_discord_lavaplayer_natives_samplerate_SampleRateLibrary_process(JNIEnv *jni, jobject me, jlong instance,
		jfloatArray in_array, jint in_offset, jint in_length, jfloatArray out_array, jint out_offset, jint out_length, jboolean end_of_input,
		jdouble source_ratio, jintArray progress_array) {

	if (instance == 0) {
		return -1;
	}

	jsize in_len = (*jni)->GetArrayLength(jni, in_array);
	jsize out_len = (*jni)->GetArrayLength(jni, out_array);

	if (in_offset < 0 || in_length < 0 || (in_offset + in_length) > in_len ||
		out_offset < 0 || out_length < 0 || (out_offset + out_length) > out_len) {
		jclass exClass = (*jni)->FindClass(jni, "java/lang/ArrayIndexOutOfBoundsException");
		if (exClass != NULL) {
			(*jni)->ThrowNew(jni, exClass, "JNI bounds violation");
		}
		return -1;
	}

	float* in = (*jni)->GetPrimitiveArrayCritical(jni, in_array, NULL);
	if (in == NULL) {
		// OutOfMemoryError is already pending
		return -1;
	}

	float* out = (*jni)->GetPrimitiveArrayCritical(jni, out_array, NULL);
	if (out == NULL) {
		(*jni)->ReleasePrimitiveArrayCritical(jni, in_array, in, JNI_ABORT);
		return -1;
	}

	SRC_DATA data;
	data.data_in = &in[in_offset];
	data.input_frames = in_length;
	data.input_frames_used = 0;
	data.end_of_input = end_of_input;
	data.data_out = &out[out_offset];
	data.output_frames = out_length;
	data.output_frames_gen = 0;
	data.src_ratio = source_ratio;

	int result = src_process((SRC_STATE*)instance, &data);

	(*jni)->ReleasePrimitiveArrayCritical(jni, in_array, in, JNI_ABORT);
	(*jni)->ReleasePrimitiveArrayCritical(jni, out_array, out, 0);

	int progress[2] = { data.input_frames_used, data.output_frames_gen };
	(*jni)->SetIntArrayRegion(jni, progress_array, 0, 2, progress);

	return result;
}