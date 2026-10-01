#pragma once

// Full producer chains are an unfinished experiment, not part of the default
// probe. Keep the initializer/lifecycle hooks out of stable candidate builds.
#ifndef CR_EXPERIMENTAL_PRODUCER_ORIGINS
#define CR_EXPERIMENTAL_PRODUCER_ORIGINS 0
#endif

#if CR_EXPERIMENTAL_PRODUCER_ORIGINS != 0 && CR_EXPERIMENTAL_PRODUCER_ORIGINS != 1
#error CR_EXPERIMENTAL_PRODUCER_ORIGINS must be 0 or 1
#endif

// Battle-log trace instrumentation (probe/battlelog_trace.inc). Development
// only, and off at runtime by default. Build with 0 to produce the baseline
// binary the trace build is measured against.
#ifndef CR_BATTLELOG_TRACE
#define CR_BATTLELOG_TRACE 1
#endif

#if CR_BATTLELOG_TRACE != 0 && CR_BATTLELOG_TRACE != 1
#error CR_BATTLELOG_TRACE must be 0 or 1
#endif
