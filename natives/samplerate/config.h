#pragma warning(disable: 4305)

#define CPU_CLIPS_NEGATIVE 0
#define CPU_CLIPS_POSITIVE 0

#define HAVE_LRINT 1
#define HAVE_LRINTF 1
#define HAVE_STDINT_H 1
/* libsamplerate 0.2.x guards <stdbool.h> behind this; required for `bool`. */
#define HAVE_STDBOOL_H 1

/* Enable all SINC converters so src_new() supports every quality setting. */
#define ENABLE_SINC_FAST_CONVERTER 1
#define ENABLE_SINC_MEDIUM_CONVERTER 1
#define ENABLE_SINC_BEST_CONVERTER 1

#define PACKAGE "libsamplerate"
#define VERSION "0.2.2"

#ifdef _MSC_VER
#define inline __inline
#endif
