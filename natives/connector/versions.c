#include "connector.h"
#include <aacdecoder_lib.h>
#include <opus.h>
#include <samplerate.h>
#include <vorbis/codec.h>
#include <stddef.h>
#include <string.h>

#ifndef FDK_AAC_VERSION
#define FDK_AAC_VERSION "3.0.0"
#endif
#ifndef MPG123_VERSION
#define MPG123_VERSION "1.33.7"
#endif
#ifndef OPUS_VERSION
#define OPUS_VERSION "1.6.1"
#endif
#ifndef VORBIS_VERSION
#define VORBIS_VERSION "1.3.7"
#endif
#ifndef OGG_VERSION
#define OGG_VERSION "1.3.6"
#endif
#ifndef SAMPLERATE_VERSION
#define SAMPLERATE_VERSION "0.2.2"
#endif

CONNECTOR_IMPORT const char* mpg123_distversion(unsigned int *major, unsigned int *minor, unsigned int *patch);

static const char CONNECTOR_CODEC_VERSIONS[] =
	"lavaplayer-natives: fdk-aac " FDK_AAC_VERSION
	", mpg123 " MPG123_VERSION
	", libopus " OPUS_VERSION
	", libVorbis " VORBIS_VERSION
	", ogg " OGG_VERSION
	", libsamplerate " SAMPLERATE_VERSION;

CONNECTOR_EXPORT const char* connector_codec_versions(int query_runtime) {
	if (query_runtime) {
		LIB_INFO aac_info[FDK_MODULE_LAST];
		memset(aac_info, 0, sizeof(aac_info));
		if (aacDecoder_GetLibInfo(aac_info) != AAC_DEC_OK) {
			return NULL;
		}
		if (opus_get_version_string() == NULL ||
			vorbis_version_string() == NULL ||
			src_get_version() == NULL ||
			mpg123_distversion(NULL, NULL, NULL) == NULL) {
			return NULL;
		}
	}
	return CONNECTOR_CODEC_VERSIONS;
}
