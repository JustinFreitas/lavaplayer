#include <jni.h>
#include <windows.h>

enum {
	SYSTEM_TOTAL,
	SYSTEM_USER,
	SYSTEM_KERNEL,
	PROCESS_USER,
	PROCESS_KERNEL
};

JNIEXPORT void JNICALL Java_com_sedmelluq_discord_lavaplayer_natives_statistics_CpuStatisticsLibrary_getSystemTimes(JNIEnv *jni, jobject me, jlongArray valueArray) {
	jlong values[5] = { 0, 0, 0, 0, 0 };
	jlong idle = 0;
	FILETIME unused;

	if (GetSystemTimes((FILETIME*) &idle, (FILETIME*) &values[SYSTEM_KERNEL], (FILETIME*) &values[SYSTEM_USER])) {
		values[SYSTEM_TOTAL] = values[SYSTEM_KERNEL] + values[SYSTEM_USER];
		values[SYSTEM_KERNEL] -= idle;
	} else {
		values[SYSTEM_KERNEL] = 0;
		values[SYSTEM_USER] = 0;
	}

	if (!GetProcessTimes(GetCurrentProcess(), &unused, &unused, (FILETIME*) &values[PROCESS_KERNEL], (FILETIME*) &values[PROCESS_USER])) {
		values[PROCESS_KERNEL] = 0;
		values[PROCESS_USER] = 0;
	}

	(*jni)->SetLongArrayRegion(jni, valueArray, 0, sizeof(values) / sizeof(*values), values);
}
