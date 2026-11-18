#pragma once
#include <stdio.h>
static inline void android_set_abort_message(const char * msg) { fprintf(stderr, "abort message: %s\n", msg); }
