#include "connector.h"
#include <opus.h>

CONNECTOR_EXPORT jlong JNICALL Java_com_sedmelluq_discord_lavaplayer_natives_opus_OpusEncoderLibrary_create(JNIEnv *jni, jobject me, jint sample_rate, jint channels, jint application, jint quality) {
	int error;
	OpusEncoder* encoder = opus_encoder_create(sample_rate, channels, application, &error);
	
	if (encoder != NULL) {
		opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY_REQUEST, quality);
	}
	
	return (jlong) encoder;
}

CONNECTOR_EXPORT jint JNICALL Java_com_sedmelluq_discord_lavaplayer_natives_opus_OpusEncoderLibrary_encode(JNIEnv *jni, jobject me, jlong instance, jobject direct_input, jint input_offset, jint frame_size,
		jobject direct_output, jint output_offset, jint output_length) {
	if (instance == 0) {
		return 0;
	}

	if (direct_input == NULL || direct_output == NULL) {
		return -1;
	}

	opus_int16* input = (*jni)->GetDirectBufferAddress(jni, direct_input);
	unsigned char* output = (*jni)->GetDirectBufferAddress(jni, direct_output);
	jlong input_capacity = (*jni)->GetDirectBufferCapacity(jni, direct_input);
	jlong output_capacity = (*jni)->GetDirectBufferCapacity(jni, direct_output);

	if (input == NULL || output == NULL || input_capacity < 0 || output_capacity < 0) {
		return -1;
	}

	opus_int32 channels = 1;
	opus_encoder_ctl((OpusEncoder*) instance, OPUS_GET_CHANNELS(&channels));

	if (input_offset < 0 || frame_size < 0 || (jlong) input_offset + (jlong) frame_size * channels > input_capacity ||
		output_offset < 0 || output_length < output_offset || (jlong) output_length > output_capacity) {
		return -1;
	}

	return opus_encode((OpusEncoder*) instance, input + input_offset, frame_size, output + output_offset, output_length - output_offset);
}

CONNECTOR_EXPORT void JNICALL Java_com_sedmelluq_discord_lavaplayer_natives_opus_OpusEncoderLibrary_destroy(JNIEnv *jni, jobject me, jlong instance) {
	OpusEncoder* encoder = (OpusEncoder*) instance;

	if (encoder != NULL) {
		opus_encoder_destroy(encoder);
	}
}

CONNECTOR_EXPORT jlong JNICALL Java_com_sedmelluq_discord_lavaplayer_natives_opus_OpusDecoderLibrary_create(JNIEnv *jni, jobject me, jint sample_rate, jint channels) {
	int error;
	return (jlong) opus_decoder_create(sample_rate, channels, &error);
}

CONNECTOR_EXPORT jint JNICALL Java_com_sedmelluq_discord_lavaplayer_natives_opus_OpusDecoderLibrary_decode(JNIEnv *jni, jobject me, jlong instance, jobject direct_input, jint input_offset, jint input_size,
	jobject direct_output, jint output_offset, jint frame_size) {

	if (instance == 0) {
		return 0;
	}

	if (direct_input == NULL || direct_output == NULL) {
		return -1;
	}

	unsigned char* input = (*jni)->GetDirectBufferAddress(jni, direct_input);
	opus_int16* output = (*jni)->GetDirectBufferAddress(jni, direct_output);
	jlong input_capacity = (*jni)->GetDirectBufferCapacity(jni, direct_input);
	jlong output_capacity = (*jni)->GetDirectBufferCapacity(jni, direct_output);

	if (input == NULL || output == NULL || input_capacity < 0 || output_capacity < 0) {
		return -1;
	}

	opus_int32 channels = 1;
	opus_decoder_ctl((OpusDecoder*) instance, OPUS_GET_CHANNELS(&channels));

	if (input_offset < 0 || input_size < 0 || (jlong) input_offset + (jlong) input_size > input_capacity ||
		output_offset < 0 || frame_size < 0 || (jlong) output_offset + (jlong) frame_size * channels > output_capacity) {
		return -1;
	}

	return opus_decode((OpusDecoder*) instance, input + input_offset, input_size, output + output_offset, frame_size, 0);
}

CONNECTOR_EXPORT void JNICALL Java_com_sedmelluq_discord_lavaplayer_natives_opus_OpusDecoderLibrary_destroy(JNIEnv *jni, jobject me, jlong instance) {
	OpusDecoder* decoder = (OpusDecoder*) instance;

	if (decoder != NULL) {
		opus_decoder_destroy(decoder);
	}
}
