#include "connector.h"
#include <opus.h>
#include <stdint.h>
#include <stdlib.h>

typedef struct opus_encoder_state_s {
	OpusEncoder* encoder;
	jint channels;
} opus_encoder_state_t;

typedef struct opus_decoder_state_s {
	OpusDecoder* decoder;
	jint channels;
} opus_decoder_state_t;

CONNECTOR_EXPORT jlong JNICALL Java_com_sedmelluq_discord_lavaplayer_natives_opus_OpusEncoderLibrary_create(JNIEnv *jni, jobject me, jint sample_rate, jint channels, jint application, jint quality) {
	opus_encoder_state_t* state = calloc(1, sizeof(*state));
	if (state == NULL) {
		return 0;
	}

	int error = OPUS_OK;
	OpusEncoder* encoder = opus_encoder_create(sample_rate, channels, application, &error);
	if (encoder == NULL || error != OPUS_OK) {
		if (encoder != NULL) {
			opus_encoder_destroy(encoder);
		}
		free(state);
		return 0;
	}

	opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY_REQUEST, quality);
	state->encoder = encoder;
	state->channels = channels;

	return (jlong) (intptr_t) state;
}

CONNECTOR_EXPORT jint JNICALL Java_com_sedmelluq_discord_lavaplayer_natives_opus_OpusEncoderLibrary_encode(JNIEnv *jni, jobject me, jlong instance, jobject direct_input, jint input_offset, jint frame_size,
		jobject direct_output, jint output_offset, jint output_length) {
	if (instance == 0) {
		return 0;
	}

	if (direct_input == NULL || direct_output == NULL) {
		return -1;
	}

	opus_encoder_state_t* state = (opus_encoder_state_t*) (intptr_t) instance;
	opus_int16* input = (*jni)->GetDirectBufferAddress(jni, direct_input);
	unsigned char* output = (*jni)->GetDirectBufferAddress(jni, direct_output);
	jlong input_capacity = (*jni)->GetDirectBufferCapacity(jni, direct_input);
	jlong output_capacity = (*jni)->GetDirectBufferCapacity(jni, direct_output);

	if (input == NULL || output == NULL || input_capacity < 0 || output_capacity < 0) {
		return -1;
	}

	if (input_offset < 0 || frame_size < 0 || (jlong) input_offset + (jlong) frame_size * state->channels > input_capacity ||
		output_offset < 0 || output_length < output_offset || (jlong) output_length > output_capacity) {
		return -1;
	}

	return opus_encode(state->encoder, input + input_offset, frame_size, output + output_offset, output_length - output_offset);
}

CONNECTOR_EXPORT void JNICALL Java_com_sedmelluq_discord_lavaplayer_natives_opus_OpusEncoderLibrary_destroy(JNIEnv *jni, jobject me, jlong instance) {
	opus_encoder_state_t* state = (opus_encoder_state_t*) (intptr_t) instance;

	if (state != NULL) {
		if (state->encoder != NULL) {
			opus_encoder_destroy(state->encoder);
		}
		free(state);
	}
}

CONNECTOR_EXPORT jlong JNICALL Java_com_sedmelluq_discord_lavaplayer_natives_opus_OpusDecoderLibrary_create(JNIEnv *jni, jobject me, jint sample_rate, jint channels) {
	opus_decoder_state_t* state = calloc(1, sizeof(*state));
	if (state == NULL) {
		return 0;
	}

	int error = OPUS_OK;
	OpusDecoder* decoder = opus_decoder_create(sample_rate, channels, &error);
	if (decoder == NULL || error != OPUS_OK) {
		if (decoder != NULL) {
			opus_decoder_destroy(decoder);
		}
		free(state);
		return 0;
	}

	state->decoder = decoder;
	state->channels = channels;

	return (jlong) (intptr_t) state;
}

CONNECTOR_EXPORT jint JNICALL Java_com_sedmelluq_discord_lavaplayer_natives_opus_OpusDecoderLibrary_decode(JNIEnv *jni, jobject me, jlong instance, jobject direct_input, jint input_offset, jint input_size,
	jobject direct_output, jint output_offset, jint frame_size) {

	if (instance == 0) {
		return 0;
	}

	if (direct_input == NULL || direct_output == NULL) {
		return -1;
	}

	opus_decoder_state_t* state = (opus_decoder_state_t*) (intptr_t) instance;
	unsigned char* input = (*jni)->GetDirectBufferAddress(jni, direct_input);
	opus_int16* output = (*jni)->GetDirectBufferAddress(jni, direct_output);
	jlong input_capacity = (*jni)->GetDirectBufferCapacity(jni, direct_input);
	jlong output_capacity = (*jni)->GetDirectBufferCapacity(jni, direct_output);

	if (input == NULL || output == NULL || input_capacity < 0 || output_capacity < 0) {
		return -1;
	}

	if (input_offset < 0 || input_size < 0 || (jlong) input_offset + (jlong) input_size > input_capacity ||
		output_offset < 0 || frame_size < 0 || (jlong) output_offset + (jlong) frame_size * state->channels > output_capacity) {
		return -1;
	}

	return opus_decode(state->decoder, input + input_offset, input_size, output + output_offset, frame_size, 0);
}

CONNECTOR_EXPORT void JNICALL Java_com_sedmelluq_discord_lavaplayer_natives_opus_OpusDecoderLibrary_destroy(JNIEnv *jni, jobject me, jlong instance) {
	opus_decoder_state_t* state = (opus_decoder_state_t*) (intptr_t) instance;

	if (state != NULL) {
		if (state->decoder != NULL) {
			opus_decoder_destroy(state->decoder);
		}
		free(state);
	}
}
