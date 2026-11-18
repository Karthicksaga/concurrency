#pragma once
#include <stdio.h>
#include <stdarg.h>
enum { ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5, ANDROID_LOG_ERROR = 6, ANDROID_LOG_FATAL = 7 };
static inline int __android_log_print(int prio, const char * tag, const char * fmt, ...) {
    va_list ap; va_start(ap, fmt); fprintf(stderr, "[%d %s] ", prio, tag); vfprintf(stderr, fmt, ap); fprintf(stderr, "\n"); va_end(ap); return 0;
}
